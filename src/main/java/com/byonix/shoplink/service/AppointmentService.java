package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.AppointmentDtos;
import com.byonix.shoplink.domain.entity.Appointment;
import com.byonix.shoplink.domain.entity.AppointmentSlot;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.AppointmentStatus;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.repository.AppointmentRepository;
import com.byonix.shoplink.repository.AppointmentSlotRepository;
import com.byonix.shoplink.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppointmentService {
    private final AppointmentSlotRepository slotRepository;
    private final AppointmentRepository appointmentRepository;
    private final ProductRepository productRepository;
    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final MapperService mapper;

    @Transactional
    public AppointmentDtos.SlotResponse createSlot(AppointmentDtos.SlotRequest r) {
        Store store = storeService.accessibleStore(r.storeId());
        currentUser.ensureSectionAccess(store, DashboardSection.APPOINTMENTS, PermissionLevel.EDIT);
        if (!r.endsAt().isAfter(r.startsAt())) {
            throw new IllegalArgumentException("End time must be after start time");
        }
        AppointmentSlot slot = new AppointmentSlot();
        slot.setStore(store);
        slot.setStartsAt(r.startsAt());
        slot.setEndsAt(r.endsAt());
        slot.setCapacity(r.capacity() == null ? 1 : r.capacity());
        slot.setActive(r.active() == null || r.active());
        return mapper.appointmentSlot(slotRepository.save(slot));
    }

    // storeId is optional — same "scope to this store, or every store this merchant owns" pattern
    // as every other dashboard list in this app.
    public List<AppointmentDtos.SlotResponse> dashboardSlots(UUID storeId) {
        if (currentUser.isSuperAdmin()) {
            List<AppointmentSlot> slots = storeId != null
                    ? slotRepository.findByStore_IdOrderByStartsAtAsc(storeId)
                    : slotRepository.findAll();
            return slots.stream().map(mapper::appointmentSlot).toList();
        }
        currentUser.ensureListSectionAccess(DashboardSection.APPOINTMENTS, PermissionLevel.VIEW);
        return storeService.myStores().stream()
                .filter(s -> storeId == null || s.id().equals(storeId))
                .flatMap(s -> slotRepository.findByStore_IdOrderByStartsAtAsc(s.id()).stream())
                .map(mapper::appointmentSlot).toList();
    }

    @Transactional
    public void deleteSlot(UUID id) {
        AppointmentSlot slot = slotRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Slot not found"));
        currentUser.ensureSectionAccess(slot.getStore(), DashboardSection.APPOINTMENTS, PermissionLevel.EDIT);
        // Deleting would cascade-delete any appointments booked against it (see the FK in
        // V25__appointments.sql) — block that rather than silently losing booking history.
        if (slot.getBookedCount() > 0) {
            throw new IllegalArgumentException("Cannot delete a slot with existing bookings — cancel the appointments first");
        }
        slotRepository.delete(slot);
    }

    public List<AppointmentDtos.AppointmentResponse> dashboardAppointments(UUID storeId) {
        if (currentUser.isSuperAdmin()) {
            List<Appointment> appointments = storeId != null
                    ? appointmentRepository.findByStore_IdOrderByCreatedAtDesc(storeId)
                    : appointmentRepository.findAll();
            return appointments.stream().map(mapper::appointment).toList();
        }
        currentUser.ensureListSectionAccess(DashboardSection.APPOINTMENTS, PermissionLevel.VIEW);
        return storeService.myStores().stream()
                .filter(s -> storeId == null || s.id().equals(storeId))
                .flatMap(s -> appointmentRepository.findByStore_IdOrderByCreatedAtDesc(s.id()).stream())
                .map(mapper::appointment).toList();
    }

    @Transactional
    public AppointmentDtos.AppointmentResponse updateStatus(UUID id, AppointmentDtos.StatusUpdateRequest request) {
        Appointment appointment = appointmentRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Appointment not found"));
        currentUser.ensureSectionAccess(appointment.getStore(), DashboardSection.APPOINTMENTS, PermissionLevel.EDIT);
        AppointmentStatus previous = appointment.getStatus();
        appointment.setStatus(request.status());
        // Cancelling frees the seat back up — mirrors OrderService.updateStatus restoring stock
        // on order cancellation.
        if (request.status() == AppointmentStatus.CANCELLED && previous != AppointmentStatus.CANCELLED) {
            slotRepository.restoreBooking(appointment.getSlot().getId());
        }
        return mapper.appointment(appointment);
    }

    // Public — no auth required, same tier as public product listing.
    public List<AppointmentDtos.SlotResponse> publicUpcomingSlots(String storeSlug) {
        storeService.publicStore(storeSlug);
        return slotRepository.findAvailableUpcoming(storeSlug, OffsetDateTime.now()).stream()
                .map(mapper::appointmentSlot).toList();
    }

    @Transactional
    public AppointmentDtos.AppointmentResponse createPublicAppointment(String storeSlug, AppointmentDtos.CreateAppointmentRequest r) {
        Store store = storeService.publicStore(storeSlug);
        AppointmentSlot slot = slotRepository.findById(r.slotId())
                .orElseThrow(() -> new EntityNotFoundException("Slot not found"));
        if (!slot.getStore().getId().equals(store.getId())) {
            throw new EntityNotFoundException("Slot not found");
        }
        if (!slot.isActive() || slot.getStartsAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("This slot is no longer available");
        }

        Product product = null;
        if (r.productId() != null) {
            product = productRepository.findByIdAndStore_Id(r.productId(), store.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Product not found"));
        }

        // Atomic guarded UPDATE — same all-or-nothing race protection as stock/offer-usage. If
        // another concurrent booking just took the last open seat, this returns 0 rows affected
        // and the whole booking rolls back with it.
        if (slotRepository.incrementBookingIfAvailable(slot.getId()) == 0) {
            throw new IllegalArgumentException("This slot has just been fully booked");
        }

        Appointment appointment = new Appointment();
        appointment.setStore(store);
        appointment.setSlot(slot);
        appointment.setProduct(product);
        appointment.setCustomer(currentUser.user());
        appointment.setCustomerName(r.customerName());
        appointment.setCustomerEmail(r.customerEmail() == null ? null : r.customerEmail().toLowerCase());
        appointment.setCustomerPhone(r.customerPhone());
        appointment.setNotes(r.notes());
        return mapper.appointment(appointmentRepository.save(appointment));
    }
}
