package com.byonix.shoplink.service.productimport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The .xlsx/CSV reader on files shaped like Excel writes them, and on hostile ones. */
class SpreadsheetReaderTest {
    private static byte[] zip(Map<String, String> parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : parts.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, String> excelLike(String sheet) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("xl/workbook.xml", "<workbook xmlns:r=\"r\"><sheets><sheet name=\"Items\" sheetId=\"1\" r:id=\"rId7\"/></sheets></workbook>");
        p.put("xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId7\" Target=\"worksheets/sheet9.xml\"/></Relationships>");
        p.put("xl/sharedStrings.xml", "<sst><si><t>name_en</t></si><si><t>price</t></si><si><t>barcode</t></si>"
                + "<si><r><t>Coca </t></r><r><t>Cola</t></r><rPh><t>ignored</t></rPh></si><si><t>برجر</t></si></sst>");
        p.put("xl/worksheets/sheet9.xml", sheet);
        return p;
    }

    @Test
    void readsSharedStringsNumbersAndSparseCellsLikeExcelWritesThem() throws Exception {
        String sheet = "<worksheet><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>3</v></c><c r=\"B2\"><v>6.3749999999999991</v></c><c r=\"C2\"><v>6.25123456789E+12</v></c></row>"
                + "<row r=\"4\"><c r=\"A4\" t=\"s\"><v>4</v></c><c r=\"C4\" t=\"inlineStr\"><is><t>0012345</t></is></c></row>"
                + "<row r=\"5\"><c r=\"A5\" t=\"e\"><v>#N/A</v></c></row>"
                + "</sheetData></worksheet>";
        List<SpreadsheetReader.Row> rows = SpreadsheetReader.read("items.xlsx", zip(excelLike(sheet)), 100);
        assertEquals(3, rows.size(), "the #N/A-only row is blank");
        assertEquals(List.of("name_en", "price", "barcode"), rows.get(0).cells());
        assertEquals("Coca Cola", rows.get(1).cell(0), "rich text runs joined; phonetic hints ignored");
        assertEquals("6.375", rows.get(1).cell(1), "Excel's double noise rounded to 15 digits");
        assertEquals("6251234567890", rows.get(1).cell(2), "no exponent notation for long codes");
        assertEquals(4, rows.get(2).number(), "row numbers are the spreadsheet's own");
        assertEquals("برجر", rows.get(2).cell(0));
        assertNull(rows.get(2).cell(1), "missing cell");
        assertEquals("0012345", rows.get(2).cell(2), "text cells keep leading zeros");
    }

    @Test
    void externalEntitiesAreNeverResolved() throws Exception {
        Map<String, String> p = excelLike("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><worksheet><sheetData><row r=\"1\"><c t=\"inlineStr\"><is><t>&e;</t></is></c></row></sheetData></worksheet>");
        ImportFileException e = assertThrows(ImportFileException.class, () -> SpreadsheetReader.read("x.xlsx", zip(p), 10));
        assertEquals("MALFORMED_FILE", e.code());
    }

    @Test
    void aZipBombIsStoppedByTheInflatedSizeNotTheHeaders() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < 45; i++) z.write(chunk); // 45 MB of zeros compresses to ~45 KB
            z.closeEntry();
        }
        ImportFileException e = assertThrows(ImportFileException.class, () -> SpreadsheetReader.read("bomb.xlsx", out.toByteArray(), 10));
        assertEquals("FILE_TOO_LARGE", e.code());
    }

    @Test
    void tooManyRowsIsRefusedWithTheLimit() {
        StringBuilder csv = new StringBuilder("name_en,price\n");
        for (int i = 0; i < 6; i++) csv.append("p").append(i).append(",1\n");
        ImportFileException e = assertThrows(ImportFileException.class, () -> SpreadsheetReader.read("p.csv", csv.toString().getBytes(), 5));
        assertTrue(e.getMessage().contains("limit is 5"));
    }

    @Test
    void csvWithQuotesSemicolonsAndABom() {
        List<SpreadsheetReader.Row> rows = SpreadsheetReader.read("p.csv", "﻿name_en;price\r\n\"A \"\"quoted\"\" name\";1.5\r\n".getBytes(StandardCharsets.UTF_8), 10);
        assertEquals(List.of("name_en", "price"), rows.get(0).cells());
        assertEquals("A \"quoted\" name", rows.get(1).cell(0));
    }

    @Test
    void csvCellsCannotBecomeFormulas() {
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", ProductImportService.csvCell("=HYPERLINK(\"x\")"));
        assertEquals("\"plain\"", ProductImportService.csvCell("plain"));
    }

    @Test
    void theWriterRoundTripsThroughTheReader() {
        byte[] x = XlsxWriter.write(List.of(new XlsxWriter.Sheet("S", List.of("name_en", "barcode"),
                List.of(List.of("Tea & <Milk>", "0099")), java.util.Set.of(1), null)));
        List<SpreadsheetReader.Row> rows = SpreadsheetReader.read("r.xlsx", x, 10);
        assertEquals(List.of("Tea & <Milk>", "0099"), rows.get(1).cells());
    }
}
