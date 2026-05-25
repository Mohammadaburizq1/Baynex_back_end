package com.byonix.shoplink.service.security;

import com.byonix.shoplink.api.dto.AuthDtos;
import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.service.MapperService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthResponseFactory {
    private final AuthProperties authProperties;
    private final MapperService mapper;

    public AuthDtos.AuthResponse authenticated(User user, String accessToken, String refreshTokenBody,
                                               Integer riskScore, boolean extraVerification) {
        String refreshForBody = refreshTokenBody;
        if (authProperties.getRefreshTokenDelivery() == AuthProperties.RefreshTokenDelivery.COOKIE) {
            refreshForBody = null;
        }
        return new AuthDtos.AuthResponse(
                accessToken,
                refreshForBody,
                mapper.user(user),
                riskScore,
                extraVerification);
    }

    public AuthDtos.AuthActionResponse actionMessage(String message) {
        return new AuthDtos.AuthActionResponse(message);
    }
}
