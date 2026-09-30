package com.byonix.shoplink.service.productimport;

import com.byonix.shoplink.media.ImageType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The optional images ZIP. Pictures are matched to rows by file name only:
 * {@code <SKU>.jpg}, {@code <SKU>_2.jpg}, {@code <SKU>_3.jpg} … (then the same with the barcode),
 * case-insensitive, any folder inside the ZIP. Folder names are discarded — nothing is ever written
 * using a name from the ZIP (the media storage generates its own), so path tricks have no effect.
 * The picture's format is decided from its bytes (JPEG, PNG, WebP, GIF), as for normal uploads.
 */
public final class ImportImages {
    public static final long MAX_ZIP_BYTES = 50L * 1024 * 1024;
    public static final int MAX_ENTRIES = 2000;
    public static final int MAX_IMAGES = 1000;
    /** Same limit as a picture uploaded in the product editor (MediaService). */
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 250L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /** A picture from the ZIP. type null = the bytes are not a supported picture; tooLarge = over the limit (bytes not kept). */
    public record ZipImage(String fileName, byte[] bytes, ImageType type, boolean tooLarge) {}

    /** base name (lower case, without extension) → pictures with that name. */
    private final Map<String, List<ZipImage>> byBase;
    private final Set<String> used = new LinkedHashSet<>();
    private final List<String> ignored;

    private ImportImages(Map<String, List<ZipImage>> byBase, List<String> ignored) {
        this.byBase = byBase;
        this.ignored = ignored;
    }

    public static ImportImages none() {
        return new ImportImages(new TreeMap<>(), List.of());
    }

    public static ImportImages read(String fileName, byte[] bytes) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
        if (!name.endsWith(".zip") || !zip) {
            throw new ImportFileException("UNSUPPORTED_IMAGES_FILE", "Pictures must be uploaded as one .zip file");
        }
        if (bytes.length > MAX_ZIP_BYTES) {
            throw new ImportFileException("IMAGES_TOO_LARGE", "The pictures ZIP can be up to " + (MAX_ZIP_BYTES / 1024 / 1024) + " MB");
        }
        Map<String, List<ZipImage>> byBase = new TreeMap<>();
        List<String> ignored = new ArrayList<>();
        int entries = 0;
        int images = 0;
        long[] budget = {MAX_TOTAL_BYTES};
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new ImportFileException("IMAGES_TOO_MANY", "The pictures ZIP has more than " + MAX_ENTRIES + " files");
                if (e.isDirectory()) continue;
                String path = e.getName().replace('\\', '/');
                String file = path.substring(path.lastIndexOf('/') + 1);
                if (file.isEmpty() || file.startsWith(".") || path.startsWith("__MACOSX/") || path.contains("/__MACOSX/")) {
                    readBounded(in, 0, budget); // skipped, but its inflated size still counts
                    continue;
                }
                int dot = file.lastIndexOf('.');
                String ext = dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (dot <= 0 || !EXTENSIONS.contains(ext)) {
                    ignored.add(file);
                    readBounded(in, 0, budget);
                    continue;
                }
                if (++images > MAX_IMAGES) throw new ImportFileException("IMAGES_TOO_MANY", "The pictures ZIP can hold up to " + MAX_IMAGES + " pictures");
                byte[] content = readBounded(in, MAX_IMAGE_BYTES, budget);
                ZipImage img;
                if (content == null) {
                    img = new ZipImage(file, null, null, true);
                } else {
                    img = new ZipImage(file, content, ImageType.detect(content).orElse(null), false);
                }
                byBase.computeIfAbsent(file.substring(0, dot).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(img);
            }
        } catch (ImportFileException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw new ImportFileException("MALFORMED_IMAGES_FILE", "The pictures ZIP could not be read");
        }
        byBase.values().forEach(l -> l.sort(Comparator.comparing(ZipImage::fileName)));
        return new ImportImages(byBase, ignored);
    }

    /**
     * Reads at most max bytes; null when the entry is bigger. The rest of an oversized entry is still
     * inflated (to reach the next entry) but not kept, and every inflated byte counts against
     * {@code budget} (the total), so a ZIP bomb stops early instead of spinning.
     */
    private static byte[] readBounded(ZipInputStream in, long max, long[] budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            n += r;
            budget[0] -= r;
            if (budget[0] < 0) throw new ImportFileException("IMAGES_TOO_LARGE", "The pictures ZIP is too large once unpacked");
            if (n <= max) out.write(buf, 0, r);
        }
        return n > max ? null : out.toByteArray();
    }

    public boolean isEmpty() {
        return byBase.isEmpty();
    }

    public int count() {
        return byBase.values().stream().mapToInt(List::size).sum();
    }

    /**
     * The pictures for one product: those named after its SKU if any, otherwise after its barcode.
     * Order: {@code key}, {@code key_2}, {@code key_3}, … Several files with the same base name
     * (SKU.jpg and SKU.png) → the first by file name, and the rest are reported as duplicates.
     */
    public Match match(String sku, String barcode, int max) {
        for (String key : new String[]{sku, barcode}) {
            if (key == null || key.isBlank()) continue;
            String k = key.trim().toLowerCase(Locale.ROOT);
            List<ZipImage> found = new ArrayList<>();
            List<String> duplicates = new ArrayList<>();
            for (int i = 1; i <= max; i++) {
                List<ZipImage> same = byBase.get(i == 1 ? k : k + "_" + i);
                if (same == null) continue;
                found.add(same.get(0));
                same.forEach(z -> used.add(z.fileName()));
                same.stream().skip(1).forEach(z -> duplicates.add(z.fileName()));
            }
            if (!found.isEmpty()) return new Match(found, duplicates, key.trim());
        }
        return new Match(List.of(), List.of(), null);
    }

    public record Match(List<ZipImage> images, List<String> duplicates, String matchedBy) {}

    /** Pictures in the ZIP that no row used (reported, never guessed onto a product). */
    public List<String> unmatched() {
        List<String> out = new ArrayList<>();
        byBase.values().forEach(l -> l.forEach(z -> {
            if (!used.contains(z.fileName())) out.add(z.fileName());
        }));
        out.addAll(ignored);
        return out;
    }
}
