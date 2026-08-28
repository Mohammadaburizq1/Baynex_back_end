package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.StoreCreationIdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StoreCreationIdempotencyKeyRepository extends JpaRepository<StoreCreationIdempotencyKey, UUID> {
}
