package com.byonix.shoplink.security.google;

import com.byonix.shoplink.config.AuthProperties;
import com.byonix.shoplink.security.login.GenericAuthException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies Google Sign-In ID tokens (signature, issuer, audience) via GoogleIdTokenVerifier.
 * Audience comes from app.auth.google-oauth-client-ids (GOOGLE_OAUTH_CLIENT_IDS), one entry per
 * platform client ID. An empty/unconfigured audience list fails every token closed, never open.
 */
@Component
public class GoogleTokenVerifier {
    private final GoogleIdTokenVerifier verifier;

    public record GoogleIdentity(String sub, String email, String name) {}

    public GoogleTokenVerifier(AuthProperties authProperties) {
        List<String> audience = Arrays.stream(authProperties.getGoogleOauthClientIds().split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        try {
            this.verifier = new GoogleIdTokenVerifier.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(audience)
                    .build();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Failed to initialize Google ID token verifier", e);
        }
    }

    public GoogleIdentity verify(String idTokenString) {
        if (idTokenString == null || idTokenString.isBlank()) {
            throw new GenericAuthException();
        }
        GoogleIdToken idToken;
        try {
            idToken = verifier.verify(idTokenString);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new GenericAuthException();
        }
        if (idToken == null) {
            throw new GenericAuthException();
        }
        GoogleIdToken.Payload payload = idToken.getPayload();
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new GenericAuthException();
        }
        String email = payload.getEmail();
        String sub = payload.getSubject();
        if (email == null || email.isBlank() || sub == null || sub.isBlank()) {
            throw new GenericAuthException();
        }
        Object nameClaim = payload.get("name");
        return new GoogleIdentity(sub, email, nameClaim != null ? nameClaim.toString() : null);
    }
}
