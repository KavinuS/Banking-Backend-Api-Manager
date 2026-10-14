package com.lhb.banking.service;

import com.lhb.banking.config.GatewayIdentity;
import com.lhb.banking.model.Account;
import com.lhb.banking.model.Transaction;
import com.lhb.banking.repo.InMemoryBankRepository;
import com.lhb.banking.service.AuditLogger.Outcome;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class AccountService {

    static final String ACCOUNTS_READ = "accounts:read";
    static final String TRANSACTIONS_READ = "transactions:read";

    private final InMemoryBankRepository repository;
    private final ConsentRegistry consents;
    private final AuditLogger audit;

    public AccountService(InMemoryBankRepository repository, ConsentRegistry consents, AuditLogger audit) {
        this.repository = repository;
        this.consents = consents;
        this.audit = audit;
    }

    public Account getAccount(GatewayIdentity caller, String accountId) {
        return authorize(caller, "getAccount", ACCOUNTS_READ, accountId);
    }

    public List<Transaction> getTransactions(GatewayIdentity caller, String accountId, LocalDate from, LocalDate to,
                                             int limit) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("fromDate must not be after toDate");
        }
        authorize(caller, "getTransactions", TRANSACTIONS_READ, accountId);
        return repository.findTransactions(accountId, from, to, limit);
    }

    /**
     * Defence in depth behind the gateway: re-check the scope, then the partner's consent for this specific account
     * (OWASP API1 / BOLA). Non-consented and non-existent accounts are indistinguishable to the caller.
     */
    private Account authorize(GatewayIdentity caller, String operation, String scope, String accountId) {
        if (!caller.scopes().contains(scope)) {
            audit.record(caller, operation, accountId, Outcome.DENIED_SCOPE);
            throw new AccessDeniedException("missing scope");
        }
        if (!consents.isPermitted(caller.applicationId(), accountId)) {
            boolean exists = repository.findAccount(accountId).isPresent();
            audit.record(caller, operation, accountId, exists ? Outcome.DENIED_NO_CONSENT : Outcome.NOT_FOUND);
            throw new NotFoundException();
        }
        Account account = repository.findAccount(accountId).orElseThrow(() -> {
            audit.record(caller, operation, accountId, Outcome.NOT_FOUND);
            return new NotFoundException();
        });
        audit.record(caller, operation, accountId, Outcome.ALLOWED);
        return account;
    }

    /** Deliberately carries no detail: unknown and non-permitted accounts look identical to the caller. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException() {
            super(null, null, false, false);
        }
    }

    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message, null, false, false);
        }
    }
}
