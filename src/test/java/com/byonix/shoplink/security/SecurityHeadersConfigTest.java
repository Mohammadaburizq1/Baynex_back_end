package com.byonix.shoplink.security;

import com.byonix.shoplink.config.HstsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHeadersConfigTest {
    @Test
    void localProfileDisablesHstsByDefault() {
        HstsProperties props = new HstsProperties();
        assertFalse(props.isEnabled());
    }

    @Test
    void productionProfileEnablesHsts() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application-prod.yml"));
        factory.afterPropertiesSet();
        var yaml = factory.getObject();
        assertEquals("true", yaml.getProperty("app.security.hsts.enabled"));
        assertEquals("31536000", yaml.getProperty("app.security.hsts.max-age-seconds"));
    }

    @Test
    void refreshCookiesUseDistinctNames() {
        assertTrue(RefreshTokenCookieService.REFRESH_COOKIE.startsWith("shoplink_"));
        assertTrue(RefreshTokenCookieService.ADMIN_REFRESH_COOKIE.startsWith("shoplink_"));
        assertFalse(RefreshTokenCookieService.REFRESH_COOKIE.equals(RefreshTokenCookieService.ADMIN_REFRESH_COOKIE));
    }
}
