package com.byonix.shoplink.security.pos;

import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.service.PosDeviceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates {@code Authorization: PosDevice <credential>} on {@code /api/pos/**} only. A device
 * credential is not a JWT and is never accepted anywhere else; the only authority it grants is
 * ROLE_POS_DEVICE, which no merchant, customer or admin route admits.
 *
 * A presented-but-unusable credential gets a 401 with a machine-readable code so the POS can tell
 * "revoked / re-activate" apart from a transient network failure. Constructed by SecurityConfig
 * (not a bean) so it runs exactly once, inside the security chain.
 */
public class PosDeviceAuthenticationFilter extends OncePerRequestFilter {
    public static final String SCHEME = "PosDevice ";
    public static final String ROLE = "ROLE_POS_DEVICE";

    private final PosDeviceService deviceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PosDeviceAuthenticationFilter(PosDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath() == null || request.getServletPath().isEmpty()
                ? request.getRequestURI() : request.getServletPath();
        return !path.startsWith("/api/pos/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(SCHEME)) {
            chain.doFilter(request, response);
            return;
        }
        PosDeviceService.CredentialCheck check = deviceService.authenticate(header.substring(SCHEME.length()).trim());
        if (check.principal() == null) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.error("Device is not authorized", "POS_DEVICE_" + check.problem().name()));
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                check.principal(), null, List.of(new SimpleGrantedAuthority(ROLE))));
        chain.doFilter(request, response);
    }
}
