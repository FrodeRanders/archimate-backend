package io.archi.collab.endpoint;

import io.smallrye.jwt.build.Jwt;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

final class TestJwtTokens {
    private static final String ISSUER = "https://collab.test";
    private static final PrivateKey PRIVATE_KEY = loadPrivateKey();

    private TestJwtTokens() {
    }

    static String token(String user, String... roles) {
        return Jwt.claims()
                .issuer(ISSUER)
                .subject(user)
                .upn(user)
                .preferredUserName(user)
                .groups(Set.of(roles))
                .issuedAt(Instant.now())
                .expiresIn(Duration.ofMinutes(10))
                .sign(PRIVATE_KEY);
    }

    static String issuer() {
        return ISSUER;
    }

    private static PrivateKey loadPrivateKey() {
        try (InputStream in = TestJwtTokens.class.getResourceAsStream("/jwt/privateKey.pem")) {
            if (in == null) {
                throw new IllegalStateException("Missing test JWT private key");
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load test JWT private key", e);
        }
    }
}
