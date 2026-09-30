package com.byonix.shoplink.service.productimport;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The import template's columns and the header aliases each accepts. Mapping is deterministic: a
 * header is normalised (lower case; spaces and dashes become "_") and must equal an alias exactly —
 * nothing is guessed from partial words. Two headers that mean the same column are an error, never
 * a silent choice.
 */
public enum ImportColumns {
    NAME_EN("name_en", "name", "product_name", "english_name"),
    NAME_AR("name_ar", "arabic_name"),
    DESCRIPTION("description"),
    CATEGORY("category", "category_name"),
    SKU("sku", "item_code"),
    BARCODE("barcode", "ean", "upc"),
    PRICE("price", "regular_price"),
    SALE_PRICE("sale_price", "discount_price"),
    STOCK("stock", "quantity", "qty"),
    LOW_STOCK_THRESHOLD("low_stock_threshold"),
    AVAILABLE("available"),
    FEATURED("featured"),
    PRODUCT_TYPE("product_type"),
    CURRENCY("currency"),
    IMAGE_1("image_url", "image_1_url"),
    IMAGE_2("image_2_url"),
    IMAGE_3("image_3_url");

    private final String template;
    private final List<String> aliases;

    ImportColumns(String template, String... aliases) {
        this.template = template;
        List<String> all = new ArrayList<>(List.of(template));
        all.addAll(List.of(aliases));
        this.aliases = List.copyOf(all);
    }

    /** The name used in the template and in messages. */
    public String template() {
        return template;
    }

    public List<String> aliases() {
        return aliases;
    }

    static String normalize(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-]+", "_");
    }

    /** Header row → column index per recognised column. */
    public record Mapping(Map<ImportColumns, Integer> index, List<String> recognised, List<String> ignored) {
        public Integer of(ImportColumns c) {
            return index.get(c);
        }

        public boolean has(ImportColumns c) {
            return index.containsKey(c);
        }
    }

    public static Mapping map(List<String> header) {
        Map<ImportColumns, Integer> index = new EnumMap<>(ImportColumns.class);
        Map<ImportColumns, String> seenAs = new LinkedHashMap<>();
        List<String> recognised = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String raw = header.get(i);
            if (raw == null || raw.isBlank()) continue;
            String norm = normalize(raw);
            ImportColumns match = null;
            for (ImportColumns c : values()) {
                if (c.aliases.contains(norm)) {
                    match = c;
                    break;
                }
            }
            if (match == null) {
                ignored.add(raw.trim());
                continue;
            }
            if (index.containsKey(match)) {
                throw new ImportFileException("DUPLICATE_COLUMN", "The columns \"" + seenAs.get(match) + "\" and \"" + raw.trim()
                        + "\" both mean " + match.template + ". Keep only one of them.");
            }
            index.put(match, i);
            seenAs.put(match, raw.trim());
            recognised.add(match.template);
        }
        if (index.isEmpty()) {
            throw new ImportFileException("NO_COLUMNS", "The first row must be the column headers (name_en, price, …). Download the template to see them.");
        }
        if (!index.containsKey(NAME_EN) && !index.containsKey(SKU) && !index.containsKey(BARCODE)) {
            throw new ImportFileException("NO_IDENTIFIER", "The file needs a name_en column (or sku / barcode to update existing products).");
        }
        return new Mapping(index, recognised, ignored);
    }
}
