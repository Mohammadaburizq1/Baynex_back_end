package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.DeliveryDtos;
import com.byonix.shoplink.domain.entity.DeliveryZone;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.repository.DeliveryZoneRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryZoneService {
    private final DeliveryZoneRepository deliveryZoneRepository;
    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final MapperService mapper;

    public List<DeliveryDtos.DeliveryZoneResponse> dashboardZones() {
        return storeService.myStores().stream()
                .flatMap(s -> deliveryZoneRepository.findByStore_IdOrderBySortOrderAsc(s.id()).stream())
                .map(mapper::deliveryZone).toList();
    }

    @Transactional
    public DeliveryDtos.DeliveryZoneResponse create(DeliveryDtos.DeliveryZoneRequest r) {
        Store store = storeService.accessibleStore(r.storeId());
        DeliveryZone zone = new DeliveryZone();
        zone.setStore(store);
        apply(zone, r);
        return mapper.deliveryZone(deliveryZoneRepository.save(zone));
    }

    @Transactional
    public DeliveryDtos.DeliveryZoneResponse update(UUID id, DeliveryDtos.DeliveryZoneRequest r) {
        DeliveryZone zone = ownedZone(id);
        apply(zone, r);
        return mapper.deliveryZone(zone);
    }

    @Transactional
    public void delete(UUID id) {
        deliveryZoneRepository.delete(ownedZone(id));
    }

    private DeliveryZone ownedZone(UUID id) {
        DeliveryZone zone = deliveryZoneRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Delivery zone not found"));
        currentUser.ensureStoreAccess(zone.getStore());
        return zone;
    }

    private void apply(DeliveryZone zone, DeliveryDtos.DeliveryZoneRequest r) {
        zone.setName(r.name().trim());
        zone.setAreas(MapperService.joinAreas(r.areas()));
        if (r.minOrder() != null) zone.setMinOrder(r.minOrder());
        if (r.deliveryFee() != null) zone.setDeliveryFee(r.deliveryFee());
        zone.setEstimatedTime(r.estimatedTime());
        if (r.isActive() != null) zone.setActive(r.isActive());
        if (r.sortOrder() != null) zone.setSortOrder(r.sortOrder());
    }
}
