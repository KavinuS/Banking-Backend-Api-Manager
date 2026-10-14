package com.lhb.banking;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Map;

/** Test stand-in for the WSO2 gateway: signs assertions shaped like the ones observed on APIM 4.7.0. */
@TestConfiguration
public class TestGateway {

    public static final String PAYLANKA_APP_ID = "f2e228cd-5c1e-4e16-a1a9-cd4d71a82447";
    public static final String QUICKBUDGET_APP_ID = "8c6a3c36-8bd9-4a73-91b2-83484eeb5731";
    private static final String WSO2 = "http://wso2.org/claims/";

    static final KeyPair GATEWAY_KEYS = generate();
    static final KeyPair ATTACKER_KEYS = generate();

    @Bean
    @Primary
    RSAPublicKey testGatewaySigningKey() {
        return (RSAPublicKey) GATEWAY_KEYS.getPublic();
    }

    public static String assertion(String appId, String appName) {
        return sign(GATEWAY_KEYS, claims(appId, appName, "PRODUCTION", Instant.now().plusSeconds(900), "wso2.org/products/am"));
    }

    static JWTClaimsSet.Builder claims(String appId, String appName, String keyType, Instant exp, String issuer) {
        Instant iat = exp.minusSeconds(900);
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(issuer)
                // WSO2 sends exp/iat as JSON strings
                .claim("exp", String.valueOf(exp.getEpochSecond()))
                .claim("iat", String.valueOf(iat.getEpochSecond()))
                .claim("scope", "accounts:read transactions:read");
        Map.of("applicationUUId", appId, "applicationname", appName, "keytype", keyType,
                        "subscriber", appName.toLowerCase().replace(' ', '_'), "apicontext", "/lhb/accounts/v1")
                .forEach((k, v) -> b.claim(WSO2 + k, v));
        return b;
    }

    static String sign(KeyPair keys, JWTClaimsSet.Builder claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner(keys.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
