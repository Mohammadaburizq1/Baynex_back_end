package com.byonix.shoplink.security.google;

import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.security.login.GenericAuthException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleTokenVerifierTest {
    @Test
    void rejectsMalformedToken() {
        AuthProperties props = new AuthProperties();
        props.setGoogleOauthClientIds("some-client-id.apps.googleusercontent.com");
        GoogleTokenVerifier verifier = new GoogleTokenVerifier(props);

        assertThrows(GenericAuthException.class, () -> verifier.verify("not-a-real-jwt"));
    }

    @Test
    void rejectsBlankToken() {
        AuthProperties props = new AuthProperties();
        props.setGoogleOauthClientIds("some-client-id.apps.googleusercontent.com");
        GoogleTokenVerifier verifier = new GoogleTokenVerifier(props);

        assertThrows(GenericAuthException.class, () -> verifier.verify(""));
        assertThrows(GenericAuthException.class, () -> verifier.verify(null));
    }

    @Test
    void rejectsEverythingWhenUnconfigured() {
        AuthProperties props = new AuthProperties();
        props.setGoogleOauthClientIds("");
        GoogleTokenVerifier verifier = new GoogleTokenVerifier(props);

        assertThrows(GenericAuthException.class, () -> verifier.verify("anything"));
    }
}
