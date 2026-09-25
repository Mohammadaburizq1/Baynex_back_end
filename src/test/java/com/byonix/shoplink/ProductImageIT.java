package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.support.ApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** C5: the product picture gallery, and uploading picture files. */
class ProductImageIT extends ApiIT {
    private static final byte[] PNG = withHeader(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 64);
    private static final byte[] JPEG = withHeader(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 64);
    private static final byte[] GIF = withHeader("GIF89a".getBytes(StandardCharsets.US_ASCII), 64);
    private static final byte[] WEBP = webp();

    private String ownerA;
    private String ownerB;
    private String customer;
    private String slugA;
    private String storeA;
    private String storeB;
    private String lamp;

    @BeforeEach
    void setUp() throws Exception {
        ownerA = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-a"));
        ownerB = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-b"));
        customer = tokenFor(saveUser(Role.CUSTOMER, "customer"));
        slugA = uniqueSlug("img-a");
        storeA = createStore(ownerA, slugA);
        storeB = createStore(ownerB, uniqueSlug("img-b"));
        lamp = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "lamp", null)).andExpect(status().isOk()));
        activateStore(ownerA, storeA, slugA);
    }

    // ── uploads ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void anUploadedImageIsStoredUnderARandomNameAndServedPubliclyWithItsRealType() throws Exception {
        ResultActions up = upload(ownerA, storeA, "photo.png", "image/png", PNG).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url", startsWith("http://localhost:8081/media/" + storeA + "/")));
        String url = read(up, "$.data.url");
        assertEndsWith(url, ".png");
        String path = url.substring("http://localhost:8081".length());

        // Anyone can fetch it — no token — with the type it really is and long-lived caching.
        byte[] served = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(PNG, served);

        // HEAD works too (link previews, monitors and some CDNs probe with it) and sends no body.
        mockMvc.perform(head(path)).andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));

        // The format comes from the bytes, not the client's name/content-type: a JPEG sent as ".png".
        String jpegUrl = read(upload(ownerA, storeA, "sneaky.png", "image/png", JPEG).andExpect(status().isOk()), "$.data.url");
        assertEndsWith(jpegUrl, ".jpg");
        mockMvc.perform(get(jpegUrl.substring("http://localhost:8081".length())))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg"));

        assertEndsWith(read(upload(ownerA, storeA, "a.gif", "image/gif", GIF).andExpect(status().isOk()), "$.data.url"), ".gif");
        assertEndsWith(read(upload(ownerA, storeA, "a.webp", "image/webp", WEBP).andExpect(status().isOk()), "$.data.url"), ".webp");

        // Two uploads of the same file never share a name.
        String again = read(upload(ownerA, storeA, "photo.png", "image/png", PNG).andExpect(status().isOk()), "$.data.url");
        org.junit.jupiter.api.Assertions.assertNotEquals(url, again);
    }

    @Test
    void uploadsThatAreNotGenuineImagesOrAreTooBigAreRefused() throws Exception {
        upload(ownerA, storeA, "notes.png", "image/png", "just some text, not an image".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("JPEG, PNG, WebP or GIF")));
        // SVG can carry script, so it is not accepted even when it claims to be a PNG.
        upload(ownerA, storeA, "logo.png", "image/png", "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest());
        upload(ownerA, storeA, "empty.png", "image/png", new byte[0]).andExpect(status().isBadRequest());
        byte[] big = withHeader(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 5 * 1024 * 1024 + 1);
        upload(ownerA, storeA, "huge.png", "image/png", big)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("5 MB")));
    }

    @Test
    void mediaUrlsOnlyServeFilesTheServerNamedItself() throws Exception {
        String url = read(upload(ownerA, storeA, "photo.png", "image/png", PNG).andExpect(status().isOk()), "$.data.url");
        String name = url.substring(url.lastIndexOf('/') + 1);

        mockMvc.perform(get("/media/" + storeA + "/" + name)).andExpect(status().isOk());
        // The same file name under another store's folder, made-up names, other extensions, odd ids: nothing.
        mockMvc.perform(get("/media/" + storeB + "/" + name)).andExpect(status().isNotFound());
        mockMvc.perform(get("/media/" + storeA + "/photo.png")).andExpect(status().isNotFound());
        mockMvc.perform(get("/media/" + storeA + "/" + name.replace(".png", ".svg"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/media/" + storeA + "/" + UUID.randomUUID() + ".png")).andExpect(status().isNotFound());
        mockMvc.perform(get("/media/not-a-uuid/" + name)).andExpect(status().isNotFound());
        mockMvc.perform(get("/media/" + storeA + "/..%2F..%2Fapplication.yml")).andExpect(status().is4xxClientError());
    }

    @Test
    void onlyThoseWhoMayEditTheStoresProductsCanUpload() throws Exception {
        upload(null, storeA, "a.png", "image/png", PNG).andExpect(status().isUnauthorized());
        upload(customer, storeA, "a.png", "image/png", PNG).andExpect(status().isForbidden());
        upload(ownerB, storeA, "a.png", "image/png", PNG).andExpect(status().isForbidden());

        Store storeAEntity = storeRepository.findById(UUID.fromString(storeA)).orElseThrow();
        User staff = saveUser(Role.MERCHANT_STAFF, "staff-a");
        staff.setStore(storeAEntity);
        staff = userRepository.save(staff);
        String staffToken = tokenFor(staff);
        upload(staffToken, storeA, "a.png", "image/png", PNG).andExpect(status().isOk());
        // Uploading into a store you don't belong to is refused for staff too.
        upload(staffToken, storeB, "a.png", "image/png", PNG).andExpect(status().isForbidden());

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"VIEW\"}]}").andExpect(status().isOk());
        upload(staffToken, storeA, "a.png", "image/png", PNG).andExpect(status().isForbidden());
    }

    // ── the gallery ─────────────────────────────────────────────────────────────────────────────

    @Test
    void theGalleryIsReplacedInOrderAndTheFirstImageBecomesThePrimaryOne() throws Exception {
        ResultActions first = putImages(lamp, ownerA, images(null, "https://cdn.example.com/a.jpg", "front",
                null, "https://cdn.example.com/b.jpg", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].url").value("https://cdn.example.com/a.jpg"))
                .andExpect(jsonPath("$.data[0].altText").value("front"));
        String aId = read(first, "$.data[0].id");
        String bId = read(first, "$.data[1].id");

        send(GET, "/api/dashboard/products/" + lamp, ownerA, null)
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/a.jpg"))
                .andExpect(jsonPath("$.data.images", hasSize(2)));
        send(GET, "/api/public/stores/" + slugA + "/products/lamp", null, null)
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/a.jpg"))
                .andExpect(jsonPath("$.data.images", hasSize(2)));

        // Reorder by id: B first → it becomes the primary image and A keeps its row.
        putImages(lamp, ownerA, images(bId, "https://cdn.example.com/b.jpg", null, aId, "https://cdn.example.com/a.jpg", "front"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(bId))
                .andExpect(jsonPath("$.data[1].id").value(aId));
        send(GET, "/api/dashboard/products/" + lamp, ownerA, null)
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/b.jpg"));

        // Drop B: A is primary again. Drop everything: no image.
        putImages(lamp, ownerA, "{\"images\":[{\"id\":\"" + aId + "\",\"url\":\"https://cdn.example.com/a.jpg\"}]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
        send(GET, "/api/dashboard/products/" + lamp, ownerA, null).andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/a.jpg"));
        putImages(lamp, ownerA, "{\"images\":[]}").andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        send(GET, "/api/dashboard/products/" + lamp, ownerA, null)
                .andExpect(jsonPath("$.data.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.data.images", hasSize(0)));
    }

    @Test
    void galleryValidation() throws Exception {
        putImages(lamp, ownerA, "{\"images\":[{\"url\":\"javascript:alert(1)\"}]}").andExpect(status().isBadRequest());
        putImages(lamp, ownerA, "{\"images\":[{\"url\":\"\"}]}").andExpect(status().isBadRequest());
        String thirteen = java.util.stream.IntStream.range(0, 13)
                .mapToObj(i -> "{\"url\":\"https://cdn.example.com/" + i + ".jpg\"}").collect(Collectors.joining(","));
        putImages(lamp, ownerA, "{\"images\":[" + thirteen + "]}").andExpect(status().isBadRequest());
        putImages(lamp, ownerA, "{\"images\":[{\"id\":\"" + UUID.randomUUID() + "\",\"url\":\"https://cdn.example.com/x.jpg\"}]}")
                .andExpect(status().isNotFound());
    }

    @Test
    void aNewProductWithAnImageStartsItsGalleryAndLaterEditsNeverWipeIt() throws Exception {
        String id = idOf(send(POST, "/api/dashboard/products", ownerA,
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Vase\",\"slug\":\"vase\",\"price\":9,\"sortOrder\":0,\"imageUrl\":\"https://cdn.example.com/vase.jpg\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/vase.jpg"))
                .andExpect(jsonPath("$.data.images", hasSize(1))));

        // An edit form that doesn't carry the image must not clear it.
        send(PUT, "/api/dashboard/products/" + id, ownerA,
                "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Vase v2\",\"slug\":\"vase\",\"price\":10,\"sortOrder\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nameEn").value("Vase v2"))
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/vase.jpg"))
                .andExpect(jsonPath("$.data.images", hasSize(1)));
    }

    // ── tenant isolation & permissions ──────────────────────────────────────────────────────────

    @Test
    void galleryEndpointsAreIsolatedPerStoreAndHonourTheStaffPermissionGrid() throws Exception {
        putImages(lamp, ownerA, images(null, "https://cdn.example.com/a.jpg", null, null, "https://cdn.example.com/b.jpg", null))
                .andExpect(status().isOk());

        send(GET, "/api/dashboard/products/" + lamp + "/images", ownerB, null).andExpect(status().isForbidden());
        putImages(lamp, ownerB, "{\"images\":[]}").andExpect(status().isForbidden());
        send(GET, "/api/dashboard/products/" + lamp + "/images", null, null).andExpect(status().isUnauthorized());
        send(GET, "/api/dashboard/products/" + lamp + "/images", customer, null).andExpect(status().isForbidden());

        Store storeAEntity = storeRepository.findById(UUID.fromString(storeA)).orElseThrow();
        User staff = saveUser(Role.MERCHANT_STAFF, "staff-a");
        staff.setStore(storeAEntity);
        staff = userRepository.save(staff);
        String staffToken = tokenFor(staff);
        send(GET, "/api/dashboard/products/" + lamp + "/images", staffToken, null).andExpect(status().isOk());
        putImages(lamp, staffToken, "{\"images\":[{\"url\":\"https://cdn.example.com/c.jpg\"}]}").andExpect(status().isOk());

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"VIEW\"}]}").andExpect(status().isOk());
        send(GET, "/api/dashboard/products/" + lamp + "/images", staffToken, null).andExpect(status().isOk());
        putImages(lamp, staffToken, "{\"images\":[]}").andExpect(status().isForbidden());

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"PRODUCTS\",\"level\":\"NONE\"}]}").andExpect(status().isOk());
        send(GET, "/api/dashboard/products/" + lamp + "/images", staffToken, null).andExpect(status().isForbidden());

        send(GET, "/api/dashboard/products/" + lamp + "/images", ownerA, null)
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].url").value("https://cdn.example.com/c.jpg"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private ResultActions putImages(String productId, String token, String body) throws Exception {
        return send(PUT, "/api/dashboard/products/" + productId + "/images", token, body);
    }

    /** Two entries: (id1, url1, alt1) and (id2, url2, alt2); null id = a new entry. */
    private static String images(String id1, String url1, String alt1, String id2, String url2, String alt2) {
        return "{\"images\":[" + image(id1, url1, alt1) + "," + image(id2, url2, alt2) + "]}";
    }

    private static String image(String id, String url, String alt) {
        return "{" + (id == null ? "" : "\"id\":\"" + id + "\",") + "\"url\":\"" + url + "\""
                + (alt == null ? "" : ",\"altText\":\"" + alt + "\"") + "}";
    }

    private static void assertEndsWith(String actual, String suffix) {
        org.junit.jupiter.api.Assertions.assertTrue(actual.endsWith(suffix), actual + " should end with " + suffix);
    }

    private static byte[] withHeader(byte[] header, int totalLength) {
        byte[] out = new byte[Math.max(totalLength, header.length)];
        System.arraycopy(header, 0, out, 0, header.length);
        Arrays.fill(out, header.length, out.length, (byte) 1);
        return out;
    }

    /** "RIFF" + 4-byte size + "WEBP" + payload. */
    private static byte[] webp() {
        byte[] out = new byte[64];
        Arrays.fill(out, (byte) 1);
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        return out;
    }
}
