package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.domain.entity.PosSyncConflict;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.PosSyncConflictStatus;
import com.byonix.shoplink.repository.PosDeviceRepository;
import com.byonix.shoplink.repository.PosSyncConflictRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * POS-11: what offline POS uploads disagreed with (oversold stock, removed or repriced products),
 * for the merchant to review. Same access rule as orders: owner, or this store's staff with ORDERS.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PosSyncConflictService {
    private final PosSyncConflictRepository conflictRepository;
    private final PosDeviceRepository deviceRepository;
    private final StoreService storeService;
    private final CurrentUserService currentUser;

    public PosDtos.ConflictSummary list(UUID storeId, PosSyncConflictStatus status) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.ORDERS, PermissionLevel.VIEW);
        List<PosSyncConflict> rows = status == null
                ? conflictRepository.findByStoreIdOrderByCreatedAtDesc(storeId)
                : conflictRepository.findByStoreIdAndStatusOrderByCreatedAtDesc(storeId, status);
        Map<UUID, String> names = deviceRepository.findAllById(rows.stream().map(PosSyncConflict::getDeviceId)
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PosDevice::getId, PosDevice::getName, (a, b) -> a));
        return new PosDtos.ConflictSummary(conflictRepository.countByStoreIdAndStatus(storeId, PosSyncConflictStatus.OPEN),
                rows.stream().map(c -> response(c, names::get)).toList());
    }

    @Transactional
    public PosDtos.ConflictResponse resolve(UUID id, PosDtos.ResolveConflictRequest request) {
        PosSyncConflict conflict = conflictRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Conflict not found"));
        Store store = storeService.accessibleStore(conflict.getStoreId());
        currentUser.ensureSectionAccess(store, DashboardSection.ORDERS, PermissionLevel.EDIT);
        if (conflict.getStatus() != PosSyncConflictStatus.RESOLVED) {
            conflict.setStatus(PosSyncConflictStatus.RESOLVED);
            conflict.setResolvedAt(Instant.now());
            conflict.setResolvedBy(currentUser.user());
            String note = request == null ? null : request.note();
            conflict.setResolutionNote(note == null || note.isBlank() ? null : note.trim());
        }
        String deviceName = conflict.getDeviceId() == null ? null
                : deviceRepository.findById(conflict.getDeviceId()).map(PosDevice::getName).orElse(null);
        return response(conflict, ignored -> deviceName);
    }

    private static PosDtos.ConflictResponse response(PosSyncConflict c, Function<UUID, String> deviceName) {
        return new PosDtos.ConflictResponse(c.getId(), c.getStoreId(), c.getDeviceId(),
                c.getDeviceId() == null ? null : deviceName.apply(c.getDeviceId()), c.getOrderId(), c.getOrderCode(),
                c.getReceiptNumber(), c.getType(), c.getProductId(), c.getVariantId(), c.getItemName(),
                c.getRequestedQuantity(), c.getAppliedQuantity(), c.getShortfall(), c.getStockBefore(), c.getStockAfter(),
                c.getSaleUnitPrice(), c.getCurrentUnitPrice(), c.getDetail(), c.getStatus(), c.getCreatedAt(),
                c.getResolvedAt(), c.getResolutionNote());
    }
}
