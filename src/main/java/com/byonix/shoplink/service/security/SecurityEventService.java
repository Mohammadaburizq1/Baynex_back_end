package com.byonix.shoplink.service.security;

import com.byonix.shoplink.domain.entity.SecurityEvent;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import com.byonix.shoplink.repository.SecurityEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SecurityEventService {
    private final SecurityEventRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public void log(SecurityEventType type, SecurityEventSeverity severity, User user, String email,
                    String ip, String userAgent, Map<String, Object> details) {
        SecurityEvent event = new SecurityEvent();
        event.setEventType(type);
        event.setSeverity(severity);
        if (user != null) {
            event.setUserId(user.getId());
            event.setEmail(user.getEmail());
        } else {
            event.setEmail(email);
        }
        event.setIpAddress(ip);
        event.setUserAgent(userAgent);
        event.setDetailsJson(toJson(details));
        repository.save(event);
    }

    public Page<SecurityEvent> forUser(UUID userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<SecurityEvent> suspicious(Pageable pageable) {
        return repository.findBySeverityInOrderByCreatedAtDesc(
                java.util.List.of(SecurityEventSeverity.HIGH, SecurityEventSeverity.CRITICAL), pageable);
    }

    public Page<SecurityEvent> all(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }

    private String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return details.toString();
        }
    }
}
