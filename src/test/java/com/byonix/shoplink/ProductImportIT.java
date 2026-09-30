package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.ProductImage;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.ProductImageRepository;
import com.byonix.shoplink.service.productimport.XlsxWriter;
import com.byonix.shoplink.support.ApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bulk product import: template, preview, validation, matching, create/update/skip, images, isolation, idempotency. */
class ProductImportIT extends ApiIT {
    private static final List<String> HEADER = List.of("name_en", "name_ar", "category", "sku", "barcode", "price", "sale_price", "stock",
            "image_url", "image_2_url", "description");
    private static final byte[] PNG = withHeader(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 64);
    private static final byte[] JPEG = withHeader(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 64);

    @Autowired ProductImageRepository imageRepository;

    private String ownerA;
    private String ownerB;
    private String storeA;
    private String storeB;
    private String drinksA;

    @BeforeEach
    void setUp() throws Exception {
        ownerA = tokenFor(saveUser(Role.MERCHANT_OWNER, "imp-owner-a"));
        ownerB = tokenFor(saveUser(Role.MERCHANT_OWNER, "imp-owner-b"));
        storeA = createStore(ownerA, uniqueSlug("imp-a"));
        storeB = createStore(ownerB, uniqueSlug("imp-b"));
        drinksA = idOf(send(POST, "/api/dashboard/categories", ownerA, category(storeA, "Drinks", "drinks")).andExpect(status().isOk()));
    }

    // ── template ────────────────────────────────────────────────────────────────────────────────

    @Test
    void theTemplateIsARealWorkbookWhoseFirstSheetHasTheImportColumns() throws Exception {
        byte[] xlsx = mockMvc.perform(get("/api/dashboard/product-imports/template").header("Authorization", "Bearer " + ownerA))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("khangates-products-template.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        List<com.byonix.shoplink.service.productimport.SpreadsheetReader.Row> rows =
                com.byonix.shoplink.service.productimport.SpreadsheetReader.read("t.xlsx", xlsx, 10);
        assertEquals(1, rows.size(), "the Products sheet is headers only (examples are on another sheet)");
        assertEquals(List.of("name_en", "name_ar", "description", "category", "sku", "barcode", "price", "sale_price", "stock",
                "low_stock_threshold", "available", "featured", "product_type", "currency", "image_url", "image_2_url", "image_3_url"), rows.get(0).cells());
        mockMvc.perform(get("/api/dashboard/product-imports/template")).andExpect(status().isUnauthorized());
    }

    // ── the golden path: preview (nothing saved), confirm, real products ──────────────────────────

    @Test
    void aValidSpreadsheetIsPreviewedWithoutSavingThenImportedAsNormalProducts() throws Exception {
        byte[] file = xlsx(List.of(
                row("Coca Cola", "كوكا كولا", "drinks", "COKE-330", "5449000000996", "0.750", "", "48", "https://cdn.example.com/coke.jpg", "https://cdn.example.com/coke-2.jpg", ""),
                row("Water", "مياه", "Drinks", "WATER-500", "6251234567890", "0.35", "0.300", "120", "", "", "Still"),
                row("Burger", "برجر", "", "BURGER-1", "", "5.25", "", "", "", "", "")));
        ResultActions preview = preview(ownerA, storeA, "products.xlsx", file, null, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalRows").value(3))
                .andExpect(jsonPath("$.data.summary.toCreate").value(3))
                .andExpect(jsonPath("$.data.summary.errors").value(0))
                .andExpect(jsonPath("$.data.canImport").value(true))
                .andExpect(jsonPath("$.data.currency").value("JOD"))
                .andExpect(jsonPath("$.data.rows[0].row").value(2))
                .andExpect(jsonPath("$.data.rows[0].action").value("CREATE"))
                .andExpect(jsonPath("$.data.rows[0].categoryResolved").value("Drinks"))
                .andExpect(jsonPath("$.data.rows[0].imageStatus").value("2 links"))
                .andExpect(jsonPath("$.data.rows[2].categoryResolved").doesNotExist());
        assertEquals(0, productsOf(storeA).size(), "a preview saves nothing");

        String session = read(preview, "$.data.sessionId");
        confirm(ownerA, session, "products.xlsx", file, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(3))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.imagesAttached").value(2))
                .andExpect(jsonPath("$.data.replayed").value(false));

        send(GET, "/api/dashboard/products?storeId=" + storeA, ownerA, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[?(@.sku=='COKE-330')].nameAr").value("كوكا كولا"))
                .andExpect(jsonPath("$.data[?(@.sku=='COKE-330')].barcode").value("5449000000996"))
                .andExpect(jsonPath("$.data[?(@.sku=='COKE-330')].stock").value(48))
                .andExpect(jsonPath("$.data[?(@.sku=='COKE-330')].categoryId").value(drinksA))
                .andExpect(jsonPath("$.data[?(@.sku=='COKE-330')].currency").value("JOD"))
                .andExpect(jsonPath("$.data[?(@.sku=='COKE-330')].imageUrl").value("https://cdn.example.com/coke.jpg"))
                .andExpect(jsonPath("$.data[?(@.sku=='COKE-330')].slug").value("coca-cola"))
                .andExpect(jsonPath("$.data[?(@.sku=='WATER-500')].salePrice").value(0.3))
                .andExpect(jsonPath("$.data[?(@.sku=='BURGER-1')].stock", hasSize(0)))          // not tracked: null, omitted
                .andExpect(jsonPath("$.data[?(@.sku=='BURGER-1')].categoryId", hasSize(0)));   // uncategorized
        UUID coke = UUID.fromString(firstOf(send(GET, "/api/dashboard/products?storeId=" + storeA, ownerA, null), "$.data[?(@.sku=='COKE-330')].id"));
        List<ProductImage> images = imageRepository.findByProduct_IdInOrderBySortOrderAsc(List.of(coke));
        assertEquals(List.of("https://cdn.example.com/coke.jpg", "https://cdn.example.com/coke-2.jpg"), images.stream().map(ProductImage::getUrl).toList());
        assertEquals(List.of(0, 1), images.stream().map(ProductImage::getSortOrder).toList());
        // The opening count went through the ledger like any new product.
        send(GET, "/api/dashboard/inventory/history?storeId=" + storeA + "&productId=" + coke, ownerA, null)
                .andExpect(jsonPath("$.data[0].reason").value("INITIAL"))
                .andExpect(jsonPath("$.data[0].delta").value(48));
        // The error report is downloadable.
        String csv = mockMvc.perform(get("/api/dashboard/product-imports/" + session + "/report?format=csv").header("Authorization", "Bearer " + ownerA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿row,sku,barcode,name,status,error_code,error_message"), csv);
        assertTrue(csv.contains("\"COKE-330\""), csv);
    }

    // ── row validation ────────────────────────────────────────────────────────────────────────────

    @Test
    void rowErrorsAreReportedBeforeAnythingIsSavedAndValidRowsStillImport() throws Exception {
        byte[] file = xlsx(List.of(
                row("No Price", "", "", "NP-1", "", "", "", "", "", "", ""),
                row("", "", "", "NN-1", "", "1.000", "", "", "", "", ""),
                row("Comma", "", "", "C-1", "", "5,25", "", "", "", "", ""),
                row("Too Precise", "", "", "TP-1", "", "1.2345", "", "", "", "", ""),
                row("Negative", "", "", "NEG-1", "", "-1", "", "", "", "", ""),
                row("Neg Stock", "", "", "NS-1", "", "1", "", "-3", "", "", ""),
                row("Bad Url", "", "", "BU-1", "", "1", "", "", "javascript:alert(1)", "ftp://x.example.com/a.png", ""),
                row("Bad Barcode", "", "", "BB-1", "has space", "1", "", "", "", "", ""),
                row("Good One", "", "Unknown Cat", "GOOD-1", "", "2.500", "", "3", "", "", "")));
        ResultActions preview = preview(ownerA, storeA, "p.xlsx", file, null, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.errors").value(8))
                .andExpect(jsonPath("$.data.summary.warnings").value(1))
                .andExpect(jsonPath("$.data.summary.toCreate").value(1))
                .andExpect(jsonPath("$.data.rows[0].issues[0].code").value("MISSING_PRICE"))
                .andExpect(jsonPath("$.data.rows[1].issues[0].code").value("MISSING_NAME"))
                .andExpect(jsonPath("$.data.rows[2].issues[0].code").value("INVALID_DECIMAL"))
                .andExpect(jsonPath("$.data.rows[3].issues[0].code").value("TOO_MANY_DECIMALS"))
                .andExpect(jsonPath("$.data.rows[4].issues[0].code").value("NEGATIVE_PRICE"))
                .andExpect(jsonPath("$.data.rows[5].issues[0].code").value("NEGATIVE_STOCK"))
                .andExpect(jsonPath("$.data.rows[6].issues[*].code", contains("INVALID_IMAGE_URL", "INVALID_IMAGE_URL")))
                .andExpect(jsonPath("$.data.rows[7].issues[0].code").value("INVALID_BARCODE"))
                .andExpect(jsonPath("$.data.rows[8].status").value("WARNING"))
                .andExpect(jsonPath("$.data.rows[8].issues[0].code").value("CATEGORY_NOT_FOUND"));
        confirm(ownerA, read(preview, "$.data.sessionId"), "p.xlsx", file, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.failed").value(8));
        send(GET, "/api/dashboard/products?storeId=" + storeA, ownerA, null)
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].sku").value("GOOD-1"))
                .andExpect(jsonPath("$.data[0].categoryId").doesNotExist());
    }

    @Test
    void duplicateSkusAndBarcodesInsideTheFileRejectBothRowsAndNameBoth() throws Exception {
        byte[] file = xlsx(List.of(
                row("One", "", "", "DUP-SKU", "111", "1", "", "", "", "", ""),
                row("Two", "", "", "dup-sku", "222", "1", "", "", "", "", ""),
                row("Three", "", "", "S3", "333", "1", "", "", "", "", ""),
                row("Four", "", "", "S4", "333", "1", "", "", "", "", ""),
                row("Five", "", "", "S5", "555", "1", "", "", "", "", "")));
        ResultActions p = preview(ownerA, storeA, "d.xlsx", file, null, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.errors").value(4))
                .andExpect(jsonPath("$.data.rows[0].issues[0].code").value("DUPLICATE_SKU_IN_FILE"))
                .andExpect(jsonPath("$.data.rows[0].issues[0].message", containsString("rows 2, 3")))
                .andExpect(jsonPath("$.data.rows[1].issues[0].code").value("DUPLICATE_SKU_IN_FILE"))
                .andExpect(jsonPath("$.data.rows[2].issues[0].code").value("DUPLICATE_BARCODE_IN_FILE"))
                .andExpect(jsonPath("$.data.rows[3].issues[0].message", containsString("rows 4, 5")));
        confirm(ownerA, read(p, "$.data.sessionId"), "d.xlsx", file, null).andExpect(jsonPath("$.data.created").value(1));
        assertEquals(1, productsOf(storeA).size());
    }

    // ── existing products: match by barcode, then SKU; skip / update / fail ───────────────────────

    @Test
    void existingProductsAreMatchedByBarcodeOrSkuAndSkippedByDefault() throws Exception {
        String cola = createProduct(storeA, "Cola", "cola", "COLA-1", "1111111111111", 1.0, 10);
        byte[] file = xlsx(List.of(
                row("Cola renamed", "", "", "", "1111111111111", "9", "", "", "", "", ""),
                row("Cola by sku", "", "", "cola-1", "", "9", "", "", "", "", ""),
                row("Cola", "", "", "", "", "9", "", "", "", "", "")));
        preview(ownerA, storeA, "e.xlsx", file, null, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].action").value("SKIP"))
                .andExpect(jsonPath("$.data.rows[0].matchedProductId").value(cola))
                .andExpect(jsonPath("$.data.rows[1].action").value("SKIP"))
                .andExpect(jsonPath("$.data.rows[1].issues[0].message", containsString("by SKU")))
                .andExpect(jsonPath("$.data.rows[2].action").value("CREATE"))       // same NAME is not a match
                .andExpect(jsonPath("$.data.summary.existingMatched").value(2));
        preview(ownerA, storeA, "e.xlsx", file, null, Map.of("existing", "FAIL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].issues[0].code").value("EXISTING_PRODUCT"))
                .andExpect(jsonPath("$.data.rows[1].issues[0].code").value("EXISTING_PRODUCT"));
    }

    @Test
    void updateChangesOnlyTheFilledCellsAndSetsStockThroughTheLedger() throws Exception {
        String cola = createProduct(storeA, "Cola", "cola", "COLA-1", "1111111111111", 1.0, 10);
        send(PUT, "/api/dashboard/products/" + cola + "/images", ownerA,
                "{\"images\":[{\"url\":\"https://cdn.example.com/old.jpg\"}]}").andExpect(status().isOk());
        byte[] file = xlsx(List.of(row("", "", "Drinks", "COLA-1", "", "1.250", "", "25", "", "", "")));
        ResultActions p = preview(ownerA, storeA, "u.xlsx", file, null, Map.of("existing", "UPDATE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.data.rows[0].nameEn").value("Cola"));
        confirm(ownerA, read(p, "$.data.sessionId"), "u.xlsx", file, null).andExpect(jsonPath("$.data.updated").value(1));
        send(GET, "/api/dashboard/products/" + cola, ownerA, null)
                .andExpect(jsonPath("$.data.nameEn").value("Cola"))              // empty cell: kept
                .andExpect(jsonPath("$.data.barcode").value("1111111111111"))   // empty cell: kept
                .andExpect(jsonPath("$.data.slug").value("cola"))               // never changed by an update
                .andExpect(jsonPath("$.data.price").value(1.25))
                .andExpect(jsonPath("$.data.stock").value(25))
                .andExpect(jsonPath("$.data.categoryId").value(drinksA))
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/old.jpg")); // no picture cells: gallery kept
        send(GET, "/api/dashboard/inventory/history?storeId=" + storeA + "&productId=" + cola, ownerA, null)
                .andExpect(jsonPath("$.data[0].reason").value("CORRECTION"))
                .andExpect(jsonPath("$.data[0].delta").value(15))
                .andExpect(jsonPath("$.data[0].note", containsString("Bulk import u.xlsx, row 2")));
        assertEquals(1, productsOf(storeA).size(), "no duplicate");
    }

    @Test
    void reImportingTheSameFileNeverDuplicatesProductsOrPictures() throws Exception {
        byte[] file = xlsx(List.of(row("Tea", "", "", "TEA-1", "2222222222222", "1", "", "5", "https://cdn.example.com/tea.jpg", "", "")));
        confirm(ownerA, read(preview(ownerA, storeA, "t.xlsx", file, null, Map.of()), "$.data.sessionId"), "t.xlsx", file, null)
                .andExpect(jsonPath("$.data.created").value(1));
        confirm(ownerA, read(preview(ownerA, storeA, "t.xlsx", file, null, Map.of()), "$.data.sessionId"), "t.xlsx", file, null)
                .andExpect(jsonPath("$.data.skipped").value(1)).andExpect(jsonPath("$.data.created").value(0));
        confirm(ownerA, read(preview(ownerA, storeA, "t.xlsx", file, null, Map.of("existing", "UPDATE")), "$.data.sessionId"), "t.xlsx", file, null)
                .andExpect(jsonPath("$.data.updated").value(1)).andExpect(jsonPath("$.data.imagesAttached").value(0));
        assertEquals(1, productsOf(storeA).size());
        UUID tea = productsOf(storeA).get(0);
        assertEquals(1, imageRepository.findByProduct_IdInOrderBySortOrderAsc(List.of(tea)).size());
    }

    // ── categories ────────────────────────────────────────────────────────────────────────────────

    @Test
    void categoriesResolveOnlyWithinTheStoreAndAreCreatedOnlyWhenAsked() throws Exception {
        send(POST, "/api/dashboard/categories", ownerB, category(storeB, "Snacks", "snacks")).andExpect(status().isOk());
        byte[] file = xlsx(List.of(row("Chips", "", "Snacks", "CH-1", "", "1", "", "", "", "", "")));
        preview(ownerA, storeA, "c.xlsx", file, null, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].issues[0].code").value("CATEGORY_NOT_FOUND"));   // store B's "Snacks" is not A's
        ResultActions p = preview(ownerA, storeA, "c.xlsx", file, null, Map.of("createCategories", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].issues[0].code").value("CATEGORY_WILL_BE_CREATED"));
        confirm(ownerA, read(p, "$.data.sessionId"), "c.xlsx", file, null).andExpect(jsonPath("$.data.created").value(1));
        String snacksA = firstOf(send(GET, "/api/dashboard/categories", ownerA, null), "$.data[?(@.nameEn=='Snacks' && @.storeId=='" + storeA + "')].id");
        send(GET, "/api/dashboard/products?storeId=" + storeA, ownerA, null).andExpect(jsonPath("$.data[0].categoryId").value(snacksA));
    }

    // ── tenant isolation and permissions ──────────────────────────────────────────────────────────

    @Test
    void storesAreIsolatedAndReadOnlyStaffCannotImport() throws Exception {
        String bProduct = createProductFor(ownerB, storeB, "B Secret", "b-secret", "B-SKU", "3333333333333", 1.0, 1);
        byte[] file = xlsx(List.of(row("Mine", "", "", "B-SKU", "3333333333333", "7", "", "", "", "", "")));
        // Store B's barcode/SKU are not store A's: A's row creates A's own product.
        ResultActions p = preview(ownerA, storeA, "i.xlsx", file, null, Map.of("existing", "UPDATE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].action").value("CREATE"))
                .andExpect(jsonPath("$.data.rows[0].matchedProductId").doesNotExist());
        String session = read(p, "$.data.sessionId");
        // B cannot preview into A, nor confirm or read A's import.
        preview(ownerB, storeA, "i.xlsx", file, null, Map.of()).andExpect(status().isForbidden());
        confirm(ownerB, session, "i.xlsx", file, null).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/dashboard/product-imports/" + session + "/report").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isForbidden());
        confirm(ownerA, session, "i.xlsx", file, null).andExpect(jsonPath("$.data.created").value(1));
        send(GET, "/api/dashboard/products/" + bProduct, ownerB, null).andExpect(jsonPath("$.data.price").value(1.0)).andExpect(jsonPath("$.data.nameEn").value("B Secret"));

        // Staff with only VIEW on products cannot import.
        Store a = storeRepository.findById(UUID.fromString(storeA)).orElseThrow();
        User staff = saveUser(Role.MERCHANT_STAFF, "imp-staff");
        staff.setStore(a);
        staff = userRepository.save(staff);
        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"VIEW\"}]}").andExpect(status().isOk());
        preview(jwtService.createAccessToken(staff), storeA, "i.xlsx", file, null, Map.of()).andExpect(status().isForbidden());
    }

    // ── files ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void unsupportedMalformedAndOversizedFilesAreRejectedClearly() throws Exception {
        preview(ownerA, storeA, "old.xls", new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0}, null, Map.of())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(".xlsx")));
        preview(ownerA, storeA, "notes.txt", "hello".getBytes(), null, Map.of()).andExpect(status().isBadRequest());
        preview(ownerA, storeA, "broken.xlsx", "PK\u0003\u0004garbage-not-a-zip".getBytes(StandardCharsets.ISO_8859_1), null, Map.of())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("could not be read")));
        preview(ownerA, storeA, "fake.xlsx", "name_en,price\nx,1".getBytes(), null, Map.of()).andExpect(status().isBadRequest());
        byte[] big = new byte[(int) (5L * 1024 * 1024) + 10];
        big[0] = 'P'; big[1] = 'K'; big[2] = 3; big[3] = 4;
        preview(ownerA, storeA, "big.xlsx", big, null, Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("up to 5 MB")));
        byte[] noName = xlsxWith(List.of("colour", "size"), List.of(List.of("red", "L")));
        preview(ownerA, storeA, "x.xlsx", noName, null, Map.of()).andExpect(status().isBadRequest());
        byte[] twoNames = xlsxWith(List.of("name", "name_en", "price"), List.of(List.of("a", "b", "1")));
        preview(ownerA, storeA, "x.xlsx", twoNames, null, Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("both mean name_en")));
    }

    @Test
    void csvFilesWorkTheSameWayAndAliasesAreAccepted() throws Exception {
        String csv = "product_name,arabic_name,item_code,ean,regular_price,qty,unknown_col\r\n\"Juice, Orange\",عصير,JU-1,4444444444444,1.100,7,x\r\n";
        preview(ownerA, storeA, "p.csv", csv.getBytes(StandardCharsets.UTF_8), null, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].nameEn").value("Juice, Orange"))
                .andExpect(jsonPath("$.data.rows[0].nameAr").value("عصير"))
                .andExpect(jsonPath("$.data.rows[0].sku").value("JU-1"))
                .andExpect(jsonPath("$.data.rows[0].price").value(1.1))
                .andExpect(jsonPath("$.data.rows[0].stock").value(7))
                .andExpect(jsonPath("$.data.fileIssues[0].code").value("COLUMN_IGNORED"));
    }

    // ── confirm: idempotency, same file, all-or-nothing ───────────────────────────────────────────

    @Test
    void confirmingTwiceImportsOnceAndADifferentFileIsRefused() throws Exception {
        byte[] file = xlsx(List.of(row("Once", "", "", "ONCE-1", "", "1", "", "", "", "", "")));
        String session = read(preview(ownerA, storeA, "o.xlsx", file, null, Map.of()), "$.data.sessionId");
        byte[] other = xlsx(List.of(row("Other", "", "", "OTHER-1", "", "1", "", "", "", "", "")));
        confirm(ownerA, session, "o.xlsx", other, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("not the files you previewed")));
        confirm(ownerA, session, "o.xlsx", file, null).andExpect(jsonPath("$.data.created").value(1)).andExpect(jsonPath("$.data.replayed").value(false));
        confirm(ownerA, session, "o.xlsx", file, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true)).andExpect(jsonPath("$.data.created").value(1));
        assertEquals(1, productsOf(storeA).size());
    }

    @Test
    void allOrNothingSavesNothingWhenAnyRowHasAnError() throws Exception {
        byte[] file = xlsx(List.of(row("Fine", "", "", "F-1", "", "1", "", "", "", "", ""), row("Broken", "", "", "B-1", "", "x", "", "", "", "", "")));
        ResultActions p = preview(ownerA, storeA, "a.xlsx", file, null, Map.of("mode", "ALL_OR_NOTHING")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canImport").value(false));
        confirm(ownerA, read(p, "$.data.sessionId"), "a.xlsx", file, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.rows[0].issues[0].code").value("NOT_IMPORTED"));
        assertEquals(0, productsOf(storeA).size());
    }

    // ── pictures from a ZIP ──────────────────────────────────────────────────────────────────────

    @Test
    void zipPicturesMatchBySkuThenBarcodeInOrderAndOthersAreReported() throws Exception {
        Map<String, byte[]> zip = new LinkedHashMap<>();
        zip.put("photos/SOAP-1.png", PNG);
        zip.put("photos/soap-1_2.jpg", JPEG);
        zip.put("7777777777777.png", PNG);        // no SKU on that row: matched by barcode
        zip.put("stray.png", PNG);                // matches nothing
        zip.put("FAKE-1.jpg", "not an image at all, just text".getBytes());
        zip.put("readme.txt", "ignored".getBytes());
        byte[] images = zip(zip);
        byte[] file = xlsx(List.of(
                row("Soap", "", "", "SOAP-1", "", "1", "", "", "https://cdn.example.com/soap-link.jpg", "", ""),
                row("Brush", "", "", "", "7777777777777", "1", "", "", "", "", ""),
                row("Fake", "", "", "FAKE-1", "", "1", "", "", "", "", "")));
        ResultActions p = preview(ownerA, storeA, "z.xlsx", file, images, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].imageStatus").value("1 link + 2 from ZIP"))
                .andExpect(jsonPath("$.data.rows[1].imageStatus").value("1 from ZIP"))
                .andExpect(jsonPath("$.data.rows[2].issues[0].code").value("IMAGE_NOT_AN_IMAGE"))
                .andExpect(jsonPath("$.data.unmatchedImages", containsInAnyOrder("stray.png", "readme.txt")));
        confirm(ownerA, read(p, "$.data.sessionId"), "z.xlsx", file, images).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(3))
                .andExpect(jsonPath("$.data.imagesAttached").value(4))
                .andExpect(jsonPath("$.data.imageFailures").value(1));
        UUID soap = UUID.fromString(firstOf(send(GET, "/api/dashboard/products?storeId=" + storeA, ownerA, null), "$.data[?(@.sku=='SOAP-1')].id"));
        List<String> urls = imageRepository.findByProduct_IdInOrderBySortOrderAsc(List.of(soap)).stream().map(ProductImage::getUrl).toList();
        assertEquals(3, urls.size());
        assertEquals("https://cdn.example.com/soap-link.jpg", urls.get(0), "the link column is the primary picture");
        assertTrue(urls.get(1).matches("http://localhost:8081/media/" + storeA + "/[0-9a-f-]{36}\\.png"), urls.get(1));
        assertTrue(urls.get(2).endsWith(".jpg"), urls.get(2));
        // Served like any uploaded picture, with the type from its bytes.
        mockMvc.perform(get(urls.get(1).substring("http://localhost:8081".length()))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
        preview(ownerA, storeA, "z.xlsx", file, "not a zip".getBytes(), Map.of()).andExpect(status().isBadRequest());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static List<String> row(String... cells) {
        return Arrays.asList(cells);
    }

    private static byte[] xlsx(List<List<String>> rows) {
        return xlsxWith(HEADER, rows);
    }

    private static byte[] xlsxWith(List<String> header, List<List<String>> rows) {
        List<List<String>> cleaned = new ArrayList<>();
        for (List<String> r : rows) cleaned.add(r.stream().map(c -> c == null || c.isEmpty() ? null : c).toList());
        return XlsxWriter.write(List.of(new XlsxWriter.Sheet("Products", header, cleaned, Set.of(), null)));
    }

    private static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private ResultActions preview(String token, String storeId, String name, byte[] file, byte[] images, Map<String, String> params) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/dashboard/product-imports/preview");
        req.file(new MockMultipartFile("file", name, "application/octet-stream", file));
        if (images != null) req.file(new MockMultipartFile("images", "pictures.zip", "application/zip", images));
        req.param("storeId", storeId);
        params.forEach(req::param);
        return perform(req, token);
    }

    private ResultActions confirm(String token, String session, String name, byte[] file, byte[] images) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/dashboard/product-imports/" + session + "/confirm");
        req.file(new MockMultipartFile("file", name, "application/octet-stream", file));
        if (images != null) req.file(new MockMultipartFile("images", "pictures.zip", "application/zip", images));
        return perform(req, token);
    }

    private ResultActions perform(MockMultipartHttpServletRequestBuilder req, String token) throws Exception {
        entityManager.flush();
        entityManager.clear();
        req.with(r -> {
            r.setRemoteAddr("10.77.0." + (1 + Math.abs(token.hashCode() % 200)));
            return r;
        });
        req.header("Authorization", "Bearer " + token);
        // Failures print the server's message, so a red test says why.
        return mockMvc.perform(req).andDo(r -> {
            if (r.getResponse().getStatus() >= 400) System.out.println("IMPORT-RESPONSE " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        });
    }

    private List<UUID> productsOf(String storeId) {
        entityManager.flush();
        entityManager.clear();
        return productRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(UUID.fromString(storeId)).stream().map(p -> p.getId()).toList();
    }

    private String createProduct(String storeId, String name, String slug, String sku, String barcode, double price, Integer stock) throws Exception {
        return createProductFor(ownerA, storeId, name, slug, sku, barcode, price, stock);
    }

    private String createProductFor(String token, String storeId, String name, String slug, String sku, String barcode, double price, Integer stock) throws Exception {
        return idOf(send(POST, "/api/dashboard/products", token, String.format(
                "{\"storeId\":\"%s\",\"nameEn\":\"%s\",\"slug\":\"%s\",\"sku\":\"%s\",\"barcode\":\"%s\",\"price\":%s,\"stock\":%d,\"sortOrder\":0}",
                storeId, name, slug, sku, barcode, price, stock)).andExpect(status().isOk()));
    }

    private static String category(String storeId, String name, String slug) {
        return String.format("{\"storeId\":\"%s\",\"nameEn\":\"%s\",\"slug\":\"%s\",\"categoryType\":\"PRODUCT\",\"sortOrder\":0,\"active\":true}", storeId, name, slug);
    }

    private static byte[] withHeader(byte[] magic, int size) {
        byte[] b = new byte[size];
        System.arraycopy(magic, 0, b, 0, magic.length);
        return b;
    }
}
