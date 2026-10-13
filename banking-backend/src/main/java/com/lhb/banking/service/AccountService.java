package com.lhb.banking.service;

import com.lhb.banking.model.Account;
import com.lhb.banking.model.Transaction;
import com.lhb.banking.repo.InMemoryBankRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class AccountService {

    private final InMemoryBankRepository repository;

    public AccountService(InMemoryBankRepository repository) {
        this.repository = repository;
    }

    public Account getAccount(String accountId) {
        return repository.findAccount(accountId).orElseThrow(NotFoundException::new);
    }

    public List<Transaction> getTransactions(String accountId, LocalDate from, LocalDate to, int limit) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("fromDate must not be after toDate");
        }
        getAccount(accountId);
        return repository.findTransactions(accountId, from, to, limit);
    }

    /** Deliberately carries no detail: unknown and (later) non-permitted accounts look identical to the caller. */
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
