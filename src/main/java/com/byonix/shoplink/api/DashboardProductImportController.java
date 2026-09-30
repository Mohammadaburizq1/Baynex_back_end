package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.ProductImportDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.domain.entity.ProductImportSession.ExistingStrategy;
import com.byonix.shoplink.domain.entity.ProductImportSession.Mode;
import com.byonix.shoplink.service.productimport.ProductImportService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Bulk product import: template → preview (nothing saved) → confirm (the same file again). Store
 * access and the PRODUCTS grid (EDIT) are enforced in the service, like the product editor.
 */
@RestController
@RequestMapping("/api/dashboard/product-imports")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
public class DashboardProductImportController {
    private final ProductImportService importService;

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return download("khangates-products-template.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", importService.template());
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProductImportDtos.PreviewResponse> preview(@RequestParam UUID storeId,
                                                                   @RequestParam("file") MultipartFile file,
                                                                   @RequestParam(value = "images", required = false) MultipartFile images,
                                                                   @RequestParam(defaultValue = "SKIP") ExistingStrategy existing,
                                                                   @RequestParam(defaultValue = "VALID_ROWS_ONLY") Mode mode,
                                                                   @RequestParam(defaultValue = "false") boolean createCategories) {
        return ApiResponse.ok(importService.preview(storeId, file, images, existing, mode, createCategories));
    }

    @PostMapping(value = "/{sessionId}/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProductImportDtos.ResultResponse> confirm(@PathVariable UUID sessionId,
                                                                 @RequestParam("file") MultipartFile file,
                                                                 @RequestParam(value = "images", required = false) MultipartFile images) {
        return ApiResponse.ok(importService.confirm(sessionId, file, images));
    }

    @GetMapping("/{sessionId}/report")
    public ResponseEntity<byte[]> report(@PathVariable UUID sessionId, @RequestParam(defaultValue = "csv") String format) {
        ProductImportService.ReportFile f = importService.report(sessionId, format);
        return download(f.fileName(), f.contentType(), f.bytes());
    }

    private static ResponseEntity<byte[]> download(String name, String type, byte[] bytes) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(type))
                .body(bytes);
    }
}
