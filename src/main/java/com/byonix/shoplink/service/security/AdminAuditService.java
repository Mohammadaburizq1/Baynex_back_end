package com.byonix.shoplink.service.security;

import com.byonix.shoplink.domain.entity.AdminAuditLog;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.AdminAuditAction;
import com.byonix.shoplink.repository.AdminAuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private final AdminAuditLogRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public void log(User admin, AdminAuditAction action, UUID targetUserId, String targetIp, Map<String, Object> details) {
        AdminAuditLog log = new AdminAuditLog();
        log.setAdminUserId(admin.getId());
        log.setAction(action);
        log.setTargetUserId(targetUserId);
        log.setTargetIp(targetIp);
        log.setDetailsJson(toJson(details));
        repository.save(log);
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
