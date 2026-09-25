package com.byonix.shoplink;

import com.byonix.shoplink.support.ApiIT;
import com.byonix.shoplink.domain.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFailurePersistenceIT extends ApiIT {
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.security.crypto.password.PasswordEncoder encoder;
    @org.springframework.beans.factory.annotation.Autowired
    com.byonix.shoplink.repository.RefreshTokenRepository refreshTokens;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void refreshReplayCommitsRevocationOfAllSessions() throws Exception {
        var user = saveUser(Role.MERCHANT_OWNER, "replay-persist");
        user.setPasswordHash(encoder.encode("Test-password!42"));
        userRepository.save(user);
        var login = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"Test-password!42\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(login, "$.data.refreshToken");
        var request = "{\"refreshToken\":\"" + token + "\"}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/refresh")
                .contentType("application/json").content(request)).andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/refresh")
                .contentType("application/json").content(request)).andExpect(status().isUnauthorized());
        assertEquals(0, refreshTokens.findByUser_IdAndRevokedAtIsNull(user.getId()).size());
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedLoginCommitsCounterDespiteUnauthorizedResponse() throws Exception {
        var user = saveUser(Role.MERCHANT_OWNER, "lock-persist");
        // This test intentionally crosses real transaction boundaries; bypass ApiIT.send's flush.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(1, userRepository.findById(user.getId()).orElseThrow().getFailedLoginCount());
    }
}
