package com.lhb.banking.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

/**
 * Trust boundary: loopback binding + Basic endpoint credential (Stage A) + gateway-signed backend JWT that identifies
 * the calling application (Stage B). A request must pass all three to reach a controller.
 */
@Configuration
public class SecurityConfig {

    private static final int MIN_PASSWORD_LENGTH = 16;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, GatewayJwtVerifier jwtVerifier,
                                            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless API, no browser sessions or cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().hasRole("GATEWAY"))
                .httpBasic(basic -> basic.authenticationEntryPoint(
                        (req, res, ex) -> resolver.resolveException(req, res, null, ex)))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> resolver.resolveException(req, res, null, e))
                        .accessDeniedHandler((req, res, e) -> resolver.resolveException(req, res, null, e)))
                // Runs only once the Basic credential has been authorized.
                .addFilterAfter(new GatewayJwtFilter(jwtVerifier, resolver), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Requests the firewall rejects (e.g. encoded ';' or '..') would otherwise be forwarded to the secured /error page
     * and surface as a misleading 401 without a correlation ID. Route them to the standard 400 response instead.
     */
    @Bean
    WebSecurityCustomizer requestRejectedHandling(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return web -> web.requestRejectedHandler((req, res, ex) -> resolver.resolveException(req, res, null, ex));
    }

    @Bean
    GatewayJwtVerifier gatewayJwtVerifier(RSAPublicKey gatewaySigningKey) {
        return new GatewayJwtVerifier(gatewaySigningKey, Clock.systemUTC());
    }

    /** Public certificate only (exported from WSO2's wso2carbon keystore); the private key never leaves WSO2. */
    @Bean
    RSAPublicKey gatewaySigningKey(@Value("${lhb.gateway.jwt.certificate}") Resource certificate)
            throws IOException, CertificateException {
        try (InputStream in = certificate.getInputStream()) {
            return (RSAPublicKey) CertificateFactory.getInstance("X.509").generateCertificate(in).getPublicKey();
        }
    }

    @Bean
    UserDetailsService gatewayUser(@Value("${lhb.gateway.username}") String username,
                                   @Value("${lhb.gateway.password}") String password,
                                   PasswordEncoder encoder) {
        if (username.isBlank() || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "LHB_GW_USER / LHB_GW_PASS must be set (password >= " + MIN_PASSWORD_LENGTH + " chars)");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("GATEWAY").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Minimum cost: the secret is a long random value (not a human password) checked on every request,
        // so a high work factor would only add latency to the throttling demo.
        return new BCryptPasswordEncoder(4);
    }
}
