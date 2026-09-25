package com.byonix.shoplink.security;

import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.config.HstsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final HstsProperties hstsProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthenticatedEntryPoint()))
                .headers(headers -> {
                    headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; frame-ancestors 'none'"))
                            .contentTypeOptions(Customizer.withDefaults())
                            .frameOptions(frame -> frame.deny())
                            .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                            .permissionsPolicy(pp -> pp.policy("geolocation=(), microphone=(), camera=()"));
                    if (hstsProperties.isEnabled()) {
                        headers.httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(hstsProperties.getMaxAgeSeconds())
                                .includeSubDomains(hstsProperties.isIncludeSubdomains())
                                .preload(hstsProperties.isPreload()));
                    }
                })
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/me", "/api/auth/sessions", "/api/auth/sessions/**",
                                "/api/auth/logout-all", "/api/auth/security/**", "/api/auth/change-password")
                        .authenticated()
                        .requestMatchers("/api/admin/auth/me")
                        .hasAnyRole("SUPER_ADMIN", "SUPPORT_ADMIN", "FINANCE_ADMIN", "READ_ONLY_ADMIN")
                        .requestMatchers(
                                "/api/admin/auth/login",
                                "/api/admin/auth/mfa/verify",
                                "/api/admin/auth/mfa/setup",
                                "/api/admin/auth/refresh",
                                "/api/admin/auth/logout",
                                "/api/admin/auth/forgot-password",
                                "/api/admin/auth/reset-password")
                        .permitAll()
                        .requestMatchers("/api/public/auth/me", "/api/public/customers/**").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.POST, "/api/public/stores/*/orders").permitAll()
                        // Uploaded catalogue pictures are shown on public storefronts.
                        .requestMatchers(HttpMethod.GET, "/media/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/media/**").permitAll()
                        .requestMatchers("/api/admin/**").hasAnyRole("SUPER_ADMIN", "SUPPORT_ADMIN", "FINANCE_ADMIN", "READ_ONLY_ADMIN")
                        .requestMatchers("/api/auth/**", "/api/public/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/**", "/actuator/health").permitAll()
                        .requestMatchers("/api/dashboard/**").hasAnyRole("MERCHANT_OWNER", "MERCHANT_STAFF")
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Missing/expired/invalid JWTs land here as an anonymous principal denied by
     * authorizeHttpRequests. Without this, Spring Security's default
     * Http403ForbiddenEntryPoint returns 403 for that case — indistinguishable from a
     * genuine role/permission denial — which stops the frontend's refresh-on-401 retry
     * (lib/api/client.ts) from ever firing.
     */
    private AuthenticationEntryPoint unauthenticatedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), ApiResponse.error("Authentication required"));
        };
    }

    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String origins,
            @Value("${app.cors.allowed-origin-patterns:}") String originPatterns) {
        List<String> patterns = Arrays.stream(originPatterns.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        List<String> allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        if (allowed.contains("*")) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS must not contain '*'");
        }
        CorsConfiguration config = new CorsConfiguration();
        if (!patterns.isEmpty()) {
            config.setAllowedOriginPatterns(patterns);
        } else {
            config.setAllowedOrigins(allowed);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-Requested-With", "Idempotency-Key"));
        // Content-Disposition: lets the dashboard read the report CSV's server-chosen filename.
        config.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_DISPOSITION));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
