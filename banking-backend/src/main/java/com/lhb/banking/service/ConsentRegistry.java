package com.lhb.banking.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;

/**
 * POC stand-in for a consent-management service: which accounts each partner application may read, keyed on the
 * gateway-asserted application UUID. In production this would be customer-granted, time-bound and revocable
 * (authorization-code + consent flow), not static configuration.
 */
@ConfigurationProperties(prefix = "lhb.consents")
public record ConsentRegistry(Map<String, Partner> partners) {

    public ConsentRegistry {
        partners = partners == null ? Map.of() : Map.copyOf(partners);
    }

    public boolean isPermitted(String applicationId, String accountId) {
        Partner partner = partners.get(applicationId);
        return partner != null && partner.accounts() != null && partner.accounts().contains(accountId);
    }

    public record Partner(String name, Set<String> accounts) {
    }
}
