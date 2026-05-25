package com.byonix.shoplink.service.security;

import com.byonix.shoplink.domain.entity.IpBlocklistEntry;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.IpBlocklistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IpBlocklistService {
    private final IpBlocklistRepository repository;
    private final SecurityEventService securityEventService;

    public boolean isBlocked(String ip) {
        return repository.findActiveBlock(ip, Instant.now()).isPresent();
    }

    @Transactional
    public IpBlocklistEntry block(String ip, String reason, Instant blockedUntil, boolean permanent, User admin) {
        IpBlocklistEntry entry = new IpBlocklistEntry();
        entry.setIpAddress(ip);
        entry.setReason(reason);
        entry.setBlockedUntil(blockedUntil);
        entry.setPermanent(permanent);
        if (admin != null) {
            entry.setCreatedByAdminId(admin.getId());
        }
        repository.save(entry);
        securityEventService.log(SecurityEventType.IP_BLOCKED, SecurityEventSeverity.HIGH, admin, null,
                ip, null, Map.of("reason", reason, "permanent", permanent));
        return entry;
    }

    @Transactional
    public void unblock(String ip) {
        repository.findActiveBlock(ip, Instant.now()).ifPresent(e -> {
            e.setBlockedUntil(Instant.now());
            repository.save(e);
        });
    }

    public Optional<IpBlocklistEntry> active(String ip) {
        return repository.findActiveBlock(ip, Instant.now());
    }
}
