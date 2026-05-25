package com.byonix.shoplink.security;

import com.byonix.shoplink.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Claims claims = jwtService.parse(header.substring(7));
            String purpose = claims.get(JwtService.CLAIM_PURPOSE, String.class);
            if (purpose != null && !JwtService.PURPOSE_ACCESS.equals(purpose)) {
                SecurityContextHolder.clearContext();
                chain.doFilter(request, response);
                return;
            }
            userRepository.findById(jwtService.userId(claims)).ifPresent(user -> {
                Integer tokenVersion = claims.get("tokenVersion", Integer.class);
                if (user.isActive() && tokenVersion != null && tokenVersion == user.getTokenVersion()) {
                    AppUserDetails principal = new AppUserDetails(user);
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
                }
            });
        } catch (RuntimeException ignored) {
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
