package com.byonix.shoplink.service.productimport;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the first worksheet of an .xlsx file (or a UTF-8 CSV file) into rows of cell text.
 *
 * Deliberately small and dependency-free: .xlsx is a zip of XML parts and only three of them matter
 * (workbook, shared strings, the first sheet). Hardening, because the file comes from a user:
 *   * sizes are enforced on the bytes actually inflated, never on what the zip headers claim (zip bombs);
 *   * DTDs and external entities are off in the XML parser (XXE, entity expansion);
 *   * formulas are never evaluated — only the value Excel cached is read;
 *   * the legacy binary .xls format is rejected, not guessed at.
 */
public final class SpreadsheetReader {
    /** Largest single part (the sheet XML, shared strings) we will inflate. */
    static final long MAX_PART_BYTES = 40L * 1024 * 1024;
    /** Largest total inflated size of the parts we read. */
    static final long MAX_TOTAL_BYTES = 60L * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 500;
    static final int MAX_COLUMNS = 60;

    private SpreadsheetReader() {}

    /** One spreadsheet row: its 1-based row number in the file and its cells (index = column). */
    public record Row(int number, List<String> cells) {
        public String cell(int index) {
            return index < 0 || index >= cells.size() ? null : cells.get(index);
        }

        boolean blank() {
            return cells.stream().allMatch(c -> c == null || c.isBlank());
        }
    }

    public enum Format { XLSX, CSV }

    /** Decides the format from the file name and checks the bytes agree. */
    public static Format formatOf(String fileName, byte[] bytes) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT).trim();
        boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
        boolean ole = bytes.length >= 4 && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0;
        if (name.endsWith(".xls") || ole) {
            throw new ImportFileException("UNSUPPORTED_FORMAT", "Old Excel .xls files are not supported. In Excel choose File → Save As → Excel Workbook (.xlsx), then upload that.");
        }
        if (name.endsWith(".xlsx")) {
            if (!zip) throw new ImportFileException("MALFORMED_FILE", "This file is not a valid .xlsx workbook");
            return Format.XLSX;
        }
        if (name.endsWith(".csv")) {
            if (zip) throw new ImportFileException("MALFORMED_FILE", "This file is named .csv but is not a text file");
            return Format.CSV;
        }
        throw new ImportFileException("UNSUPPORTED_FORMAT", "Upload an Excel workbook (.xlsx) or a CSV file (.csv)");
    }

    /** All non-blank rows of the first sheet, header first. At most {@code maxRows} + 1 (header) rows. */
    public static List<Row> read(String fileName, byte[] bytes, int maxRows) {
        List<Row> rows = formatOf(fileName, bytes) == Format.XLSX ? readXlsx(bytes) : readCsv(bytes);
        List<Row> nonBlank = rows.stream().filter(r -> !r.blank()).toList();
        if (nonBlank.size() - 1 > maxRows) {
            throw new ImportFileException("TOO_MANY_ROWS", "This file has " + (nonBlank.size() - 1) + " product rows; the limit is " + maxRows
                    + " per import. Split it into smaller files.");
        }
        return nonBlank;
    }

    // ── XLSX ───────────────────────────────────────────────────────────────────────────────────

    private static List<Row> readXlsx(byte[] bytes) {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int entries = 0;
            long total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new ImportFileException("MALFORMED_FILE", "This workbook has too many parts to be a product list");
                String name = entry.getName();
                if (entry.isDirectory() || !(name.equals("xl/workbook.xml") || name.equals("xl/_rels/workbook.xml.rels")
                        || name.equals("xl/sharedStrings.xml") || name.startsWith("xl/worksheets/sheet"))) {
                    // Not needed, but skipping still inflates it: count it against the total (zip bombs).
                    total += readBounded(zip, MAX_PART_BYTES).length;
                    if (total > MAX_TOTAL_BYTES) throw new ImportFileException("FILE_TOO_LARGE", "This workbook is too large once unpacked");
                    continue;
                }
                byte[] part = readBounded(zip, MAX_PART_BYTES);
                total += part.length;
                if (total > MAX_TOTAL_BYTES) throw new ImportFileException("FILE_TOO_LARGE", "This workbook is too large once unpacked");
                parts.put(name, part);
            }
        } catch (ImportFileException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new ImportFileException("MALFORMED_FILE", "This .xlsx file could not be read. Open it in Excel, save it again and retry.");
        }
        String sheetPath = firstSheetPath(parts);
        byte[] sheet = parts.get(sheetPath);
        if (sheet == null) {
            throw new ImportFileException("MALFORMED_FILE", parts.containsKey("xl/workbook.xml") ? "This workbook has no worksheet"
                    : "This .xlsx file could not be read. Open it in Excel, save it again and retry.");
        }
        try {
            List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
            return sheetRows(sheet, shared);
        } catch (XMLStreamException | RuntimeException e) {
            if (e instanceof ImportFileException ife) throw ife;
            throw new ImportFileException("MALFORMED_FILE", "This .xlsx file could not be read. Open it in Excel, save it again and retry.");
        }
    }

    private static byte[] readBounded(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            n += r;
            if (n > max) throw new ImportFileException("FILE_TOO_LARGE", "This workbook is too large once unpacked");
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }


    /** Excel never writes a DOCTYPE; a file that has one is refused rather than parsed around it. */
    private static int next(XMLStreamReader r) throws XMLStreamException {
        int ev = r.next();
        if (ev == XMLStreamConstants.DTD || ev == XMLStreamConstants.ENTITY_REFERENCE) {
            throw new ImportFileException("MALFORMED_FILE", "This .xlsx file could not be read. Open it in Excel, save it again and retry.");
        }
        return ev;
    }
    private static XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    /** The first sheet listed in the workbook (not necessarily sheet1.xml), via its relationship. */
    private static String firstSheetPath(Map<String, byte[]> parts) {
        try {
            String rid = null;
            if (parts.containsKey("xl/workbook.xml")) {
                XMLStreamReader r = xml(parts.get("xl/workbook.xml"));
                while (r.hasNext() && rid == null) {
                    if (next(r) == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            if (r.getAttributeLocalName(i).equals("id")) rid = r.getAttributeValue(i);
                        }
                    }
                }
            }
            if (rid != null && parts.containsKey("xl/_rels/workbook.xml.rels")) {
                XMLStreamReader r = xml(parts.get("xl/_rels/workbook.xml.rels"));
                while (r.hasNext()) {
                    if (next(r) == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")
                            && rid.equals(r.getAttributeValue(null, "Id"))) {
                        String target = r.getAttributeValue(null, "Target");
                        if (target == null) break;
                        String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                        return path.replace("\\", "/");
                    }
                }
            }
        } catch (XMLStreamException ignored) {
            // fall through to the conventional name
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static List<String> sharedStrings(byte[] bytes) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = xml(bytes);
        StringBuilder current = null;
        int phonetic = 0;
        boolean inText = false;
        while (r.hasNext()) {
            int ev = next(r);
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "si" -> current = new StringBuilder();
                    case "rPh" -> phonetic++;
                    case "t" -> inText = phonetic == 0;
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "si" -> {
                        out.add(current == null ? "" : current.toString());
                        current = null;
                    }
                    case "rPh" -> phonetic--;
                    case "t" -> inText = false;
                    default -> { }
                }
            } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) && inText && current != null) {
                current.append(r.getText());
            }
        }
        return out;
    }

    private static List<Row> sheetRows(byte[] bytes, List<String> shared) throws XMLStreamException {
        List<Row> rows = new ArrayList<>();
        XMLStreamReader r = xml(bytes);
        int rowNumber = 0;
        List<String> cells = null;
        int col = -1;
        String type = null;
        StringBuilder value = null;
        boolean capture = false;
        int nextCol = 0;
        while (r.hasNext()) {
            int ev = next(r);
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> {
                        String rn = r.getAttributeValue(null, "r");
                        rowNumber = rn == null ? rowNumber + 1 : Integer.parseInt(rn);
                        cells = new ArrayList<>();
                        nextCol = 0;
                    }
                    case "c" -> {
                        String ref = r.getAttributeValue(null, "r");
                        col = ref == null ? nextCol : columnIndex(ref);
                        nextCol = col + 1;
                        type = r.getAttributeValue(null, "t");
                        value = new StringBuilder();
                    }
                    case "v", "t" -> capture = value != null;
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "v", "t" -> capture = false;
                    case "c" -> {
                        if (cells != null && col >= 0 && col < MAX_COLUMNS) {
                            while (cells.size() <= col) cells.add(null);
                            cells.set(col, cellText(type, value.toString(), shared));
                        }
                        value = null;
                    }
                    case "row" -> {
                        if (cells != null) rows.add(new Row(rowNumber, cells));
                        cells = null;
                    }
                    default -> { }
                }
            } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) && capture) {
                value.append(r.getText());
            }
        }
        return rows;
    }

    static int columnIndex(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch < 'A' || ch > 'Z') break;
            n = n * 26 + (ch - 'A' + 1);
        }
        return n - 1;
    }

    private static String cellText(String type, String raw, List<String> shared) {
        if (type == null || type.equals("n")) return number(raw);
        return switch (type) {
            case "s" -> {
                int i = Integer.parseInt(raw.trim());
                if (i < 0 || i >= shared.size()) throw new ImportFileException("MALFORMED_FILE", "This .xlsx file refers to text that is not in it");
                yield shared.get(i);
            }
            case "b" -> "1".equals(raw.trim()) ? "TRUE" : "FALSE";
            case "e" -> null; // #N/A, #REF!, … — treated as an empty cell
            default -> raw; // str (formula result), inlineStr
        };
    }

    /**
     * A numeric cell as exact decimal text. Excel keeps doubles, so 6.375 may be stored as
     * 6.3749999999999991: rounding to 15 significant digits (Excel's own display precision) gives
     * back what the merchant typed. Long codes typed as numbers come back without exponent notation.
     */
    static String number(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal d = new BigDecimal(raw.trim()).round(new MathContext(15)).stripTrailingZeros();
            if (d.scale() < 0) d = d.setScale(0);
            return d.toPlainString();
        } catch (NumberFormatException e) {
            return raw.trim();
        }
    }

    // ── CSV ────────────────────────────────────────────────────────────────────────────────────

    /** RFC 4180 CSV, UTF-8 (a BOM is ignored). A header with semicolons and no commas means ';' separators. */
    private static List<Row> readCsv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        if (text.indexOf('\0') >= 0) throw new ImportFileException("MALFORMED_FILE", "This file is not a text CSV file");
        int firstLineEnd = text.indexOf('\n');
        String first = firstLineEnd < 0 ? text : text.substring(0, firstLineEnd);
        char sep = first.indexOf(',') < 0 && first.indexOf(';') >= 0 ? ';' : ',';
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int record = 1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (ch == sep) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                cells.add(cell.toString());
                cell.setLength(0);
                rows.add(new Row(record++, trimColumns(cells)));
                cells = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (quoted) throw new ImportFileException("MALFORMED_FILE", "This CSV file has an unclosed quote");
        if (!cell.isEmpty() || !cells.isEmpty()) {
            cells.add(cell.toString());
            rows.add(new Row(record, trimColumns(cells)));
        }
        return rows;
    }

    private static List<String> trimColumns(List<String> cells) {
        return cells.size() > MAX_COLUMNS ? new ArrayList<>(cells.subList(0, MAX_COLUMNS)) : cells;
    }
}
