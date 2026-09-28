package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.PosStaffPin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PosStaffPinRepository extends JpaRepository<PosStaffPin, UUID> {
}
