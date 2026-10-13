package com.lhb.banking.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stage A trust boundary: only the WSO2 gateway knows the endpoint credential, and the server listens on loopback.
 * Stage B adds verification of the gateway-signed backend JWT on top of this.
 */
@Configuration
public class SecurityConfig {

    private static final int MIN_PASSWORD_LENGTH = 16;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
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
                        .accessDeniedHandler((req, res, e) -> resolver.resolveException(req, res, null, e)));
        return http.build();
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
