package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {
    List<Appointment> findByStore_IdOrderByCreatedAtDesc(UUID storeId);
}
