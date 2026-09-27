package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.ModifierDtos;
import com.byonix.shoplink.api.dto.ProductDtos;
import com.byonix.shoplink.api.dto.VariantDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * POS-09 price snapshot policy: which prices a device was actually given.
 *
 * Every catalog response the server sends a device (full or "unchanged") records the catalog version
 * against that device, pointing at an immutable, content-addressed price book: product and variant
 * prices, add-on groups/options and their deltas, names and SKUs. An offline sale names the catalog
 * version it was rung up on; the server prices it from that recorded book, not from today's catalog.
 * A version this device was never given cannot be used, so a device cannot invent prices.
 *
 * Price books are deduplicated by content (stock is not part of them), so the frequent version
 * changes caused by stock movements add only a small delivery row, not a new book.
 */
@Service
@RequiredArgsConstructor
public class PosPriceBookService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record PriceBook(String currency, List<BookProduct> products) {
        Optional<BookProduct> product(UUID id) {
            return products.stream().filter(p -> p.id().equals(id)).findFirst();
        }
    }

    /** price is the effective unit price (sale price if set) for a product without variants. */
    public record BookProduct(UUID id, String name, String sku, boolean hasVariants, BigDecimal price,
                              List<BookVariant> variants, List<BookGroup> groups) {}

    public record BookVariant(UUID id, String label, String sku, BigDecimal price, boolean available) {}

    public record BookGroup(UUID id, String name, int minSelect, int maxSelect, List<BookOption> options) {}

    public record BookOption(UUID id, String name, BigDecimal priceDelta, boolean available) {}

    /** Called for every catalog response, inside the catalog request's transaction. Idempotent. */
    @Transactional
    public void recordDelivery(UUID storeId, UUID deviceId, String catalogVersion, String currency,
                               List<ProductDtos.ProductResponse> products) {
        PriceBook book = new PriceBook(currency, products.stream().map(PosPriceBookService::bookProduct).toList());
        String content = objectMapper.writeValueAsString(book);
        String hash = sha256(content);
        jdbc.update("""
                INSERT INTO pos_price_books (store_id, price_book_hash, currency, content)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, storeId, hash, currency, content);
        jdbc.update("""
                INSERT INTO pos_catalog_deliveries (device_id, catalog_version, store_id, price_book_hash)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, deviceId, catalogVersion, storeId, hash);
    }

    /** The price book behind a catalog version this device was given, if it was given it. */
    public Optional<PriceBook> deliveredPriceBook(UUID deviceId, UUID storeId, String catalogVersion) {
        List<String> rows = jdbc.queryForList("""
                SELECT b.content FROM pos_catalog_deliveries d
                JOIN pos_price_books b ON b.store_id = d.store_id AND b.price_book_hash = d.price_book_hash
                WHERE d.device_id = ? AND d.store_id = ? AND d.catalog_version = ?
                """, String.class, deviceId, storeId, catalogVersion);
        return rows.stream().findFirst().map(content -> objectMapper.readValue(content, PriceBook.class));
    }

    private static BookProduct bookProduct(ProductDtos.ProductResponse p) {
        List<BookVariant> variants = p.variants() == null ? List.of() : p.variants().stream()
                .map(PosPriceBookService::bookVariant).toList();
        List<BookGroup> groups = p.modifierGroups() == null ? List.of() : p.modifierGroups().stream()
                .map(PosPriceBookService::bookGroup).toList();
        return new BookProduct(p.id(), p.nameEn(), p.sku(), p.hasVariants(),
                p.salePrice() != null ? p.salePrice() : p.price(), variants, groups);
    }

    private static BookVariant bookVariant(VariantDtos.VariantResponse v) {
        return new BookVariant(v.id(), v.label(), v.sku(), v.salePrice() != null ? v.salePrice() : v.price(), v.available());
    }

    private static BookGroup bookGroup(ModifierDtos.ModifierGroupResponse g) {
        return new BookGroup(g.id(), g.name(), g.minSelect(), g.maxSelect(), g.options().stream()
                .map(o -> new BookOption(o.id(), o.name(), o.priceDelta(), o.available())).toList());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
