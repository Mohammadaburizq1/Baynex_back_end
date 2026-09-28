package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.repository.CustomerSummaryProjection;
import com.byonix.shoplink.repository.OrderRepository;
import com.byonix.shoplink.repository.PosDeviceRepository;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * POS-12: the store's customers for offline lookup at the till.
 *
 * khanGates has no separate customer table: a store's customers are the accounts and guest contacts
 * derived from its orders — exactly what the dashboard Customers page lists
 * (OrderRepository.queryCustomerSummaries). The POS gets that same list for its own store only, with
 * name, phone, email and order count; no account security data exists in it to leak.
 *
 * A customer typed in at the till becomes part of this list through the sale that carries them (a
 * guest contact, grouped by phone, then email), so there is no second customer system to keep in step.
 */
@Service
@RequiredArgsConstructor
public class PosCustomerService {
    /** Most recent customers first; a till needs the active ones, not a decade of history. */
    static final int MAX_CUSTOMERS = 5000;

    private final PosDeviceRepository deviceRepository;
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public PosDtos.CustomersResponse customers(PosDevicePrincipal principal, String knownVersion) {
        PosDevice device = deviceRepository.findById(principal.deviceId()).orElseThrow();
        UUID storeId = device.getStore().getId();
        if (!storeId.equals(principal.storeId())) {
            throw new AccessDeniedException("Access denied");
        }
        List<PosDtos.PosCustomer> customers = orderRepository.queryCustomerSummaries(storeId).stream()
                .limit(MAX_CUSTOMERS)
                .map(PosCustomerService::customer)
                .toList();
        StringBuilder content = new StringBuilder();
        for (PosDtos.PosCustomer c : customers) {
            content.append(c.key()).append('|').append(c.name()).append('|').append(c.phone()).append('|')
                    .append(c.email()).append('|').append(c.orderCount()).append('|').append(c.lastOrderAt()).append('\n');
        }
        String version = PosPriceBookService.sha256(content.toString()).substring(0, 32);
        Instant now = Instant.now();
        if (version.equals(knownVersion)) {
            return new PosDtos.CustomersResponse(version, true, now, null);
        }
        return new PosDtos.CustomersResponse(version, false, now, customers);
    }

    private static PosDtos.PosCustomer customer(CustomerSummaryProjection p) {
        return new PosDtos.PosCustomer(key(p.getCustomerId(), p.getPhone(), p.getEmail()), p.getCustomerId(), p.getName(),
                blankToNull(p.getPhone()), blankToNull(p.getEmail()), p.getOrderCount() == null ? 0 : p.getOrderCount(),
                p.getLastOrderAt());
    }

    /** Same grouping as the summary query: the account, else the guest's phone, else their email. */
    static String key(UUID customerId, String phone, String email) {
        if (customerId != null) return "acct:" + customerId;
        String p = blankToNull(phone);
        if (p != null) return "guest:" + p;
        String e = blankToNull(email);
        return "guest:" + (e == null ? "" : e.toLowerCase(Locale.ROOT));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
