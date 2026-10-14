package com.lhb.banking.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies the X-JWT-Assertion minted by the WSO2 gateway. Format observed on WSO2 APIM 4.7.0 (wso2/README.md):
 * RS256, iss "wso2.org/products/am", WSO2 claims under http://wso2.org/claims/, and exp/iat sent as JSON strings.
 * Nimbus' JWTClaimsSet rejects string-typed exp, so the (signature-verified) payload is read as a plain JSON map.
 */
public class GatewayJwtVerifier {

    static final String ISSUER = "wso2.org/products/am";
    private static final String WSO2 = "http://wso2.org/claims/";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final RSASSAVerifier verifier;
    private final Clock clock;

    public GatewayJwtVerifier(RSAPublicKey gatewaySigningKey, Clock clock) {
        this.verifier = new RSASSAVerifier(gatewaySigningKey);
        this.clock = clock;
    }

    public GatewayIdentity verify(String assertion) {
        try {
            SignedJWT jwt = SignedJWT.parse(assertion);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                throw new InvalidAssertionException("unexpected algorithm");
            }
            if (!jwt.verify(verifier)) {
                throw new InvalidAssertionException("bad signature");
            }
            Map<String, Object> claims = jwt.getPayload().toJSONObject();
            if (claims == null || !ISSUER.equals(claims.get("iss"))) {
                throw new InvalidAssertionException("unexpected issuer");
            }
            long exp = epochSeconds(claims.get("exp"));
            if (clock.instant().minus(CLOCK_SKEW).getEpochSecond() >= exp) {
                throw new InvalidAssertionException("expired");
            }
            if (!"PRODUCTION".equals(claims.get(WSO2 + "keytype"))) {
                throw new InvalidAssertionException("non-production key type");
            }
            String appId = requiredString(claims, WSO2 + "applicationUUId");
            String scope = claims.get("scope") instanceof String s ? s : "";
            Set<String> scopes = Arrays.stream(scope.split(" ")).filter(x -> !x.isBlank()).collect(Collectors.toUnmodifiableSet());
            return new GatewayIdentity(appId,
                    String.valueOf(claims.get(WSO2 + "applicationname")),
                    String.valueOf(claims.get(WSO2 + "subscriber")),
                    scopes);
        } catch (ParseException | JOSEException | NumberFormatException e) {
            throw new InvalidAssertionException("malformed assertion");
        }
    }

    private static long epochSeconds(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            return Long.parseLong(s);
        }
        throw new InvalidAssertionException("missing exp");
    }

    private static String requiredString(Map<String, Object> claims, String name) {
        if (claims.get(name) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new InvalidAssertionException("missing " + name);
    }

    public static class InvalidAssertionException extends RuntimeException {
        public InvalidAssertionException(String reason) {
            super(reason, null, false, false);
        }
    }
}
