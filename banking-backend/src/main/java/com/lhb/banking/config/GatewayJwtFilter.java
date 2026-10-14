package com.lhb.banking.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Stage B: after the Basic gateway credential is accepted, also require a valid gateway-signed assertion and expose
 * the calling application as {@link GatewayIdentity}. Requests without one are rejected with the standard 401.
 */
public class GatewayJwtFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-JWT-Assertion";
    private static final Logger log = LoggerFactory.getLogger(GatewayJwtFilter.class);

    private final GatewayJwtVerifier verifier;
    private final HandlerExceptionResolver resolver;

    public GatewayJwtFilter(GatewayJwtVerifier verifier, HandlerExceptionResolver resolver) {
        this.verifier = verifier;
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String assertion = request.getHeader(HEADER);
        GatewayIdentity identity;
        try {
            if (assertion == null || assertion.isBlank()) {
                throw new GatewayJwtVerifier.InvalidAssertionException("missing assertion");
            }
            identity = verifier.verify(assertion);
        } catch (GatewayJwtVerifier.InvalidAssertionException e) {
            log.warn("Rejected request without valid gateway assertion: {}", e.getMessage());
            resolver.resolveException(request, response, null, new BadCredentialsException("invalid gateway assertion"));
            return;
        }
        request.setAttribute(GatewayIdentity.REQUEST_ATTRIBUTE, identity);
        chain.doFilter(request, response);
    }
}
