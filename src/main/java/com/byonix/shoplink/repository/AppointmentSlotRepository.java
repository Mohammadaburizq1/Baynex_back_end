package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.AppointmentSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentSlotRepository extends JpaRepository<AppointmentSlot, UUID> {
    List<AppointmentSlot> findByStore_IdOrderByStartsAtAsc(UUID storeId);
    Optional<AppointmentSlot> findByIdAndStore_Id(UUID id, UUID storeId);

    // bookedCount < capacity is a same-entity two-field comparison — Spring Data's derived query
    // method names can only compare a property against a passed-in parameter, not another
    // property, so this needs an explicit JPQL query.
    @Query("SELECT s FROM AppointmentSlot s WHERE s.store.slug = :storeSlug AND s.active = true " +
           "AND s.startsAt > :after AND s.bookedCount < s.capacity ORDER BY s.startsAt ASC")
    List<AppointmentSlot> findAvailableUpcoming(@Param("storeSlug") String storeSlug, @Param("after") OffsetDateTime after);

    // Same atomic-UPDATE-with-guard discipline as OfferRepository.incrementUsageIfAvailable: two
    // concurrent bookings for the last open seat in a slot must not both succeed.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AppointmentSlot s SET s.bookedCount = s.bookedCount + 1 WHERE s.id = :id AND s.bookedCount < s.capacity")
    int incrementBookingIfAvailable(@Param("id") UUID id);

    // Symmetric restore on cancellation — mirrors ProductRepository.restoreStock.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AppointmentSlot s SET s.bookedCount = s.bookedCount - 1 WHERE s.id = :id AND s.bookedCount > 0")
    int restoreBooking(@Param("id") UUID id);
}
