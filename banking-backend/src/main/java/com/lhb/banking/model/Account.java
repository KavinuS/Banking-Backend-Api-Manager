package com.lhb.banking.model;

import java.math.BigDecimal;

/** Fictional bank account. Full account number never leaves the backend unmasked. */
public record Account(
        String accountId,
        String accountNumber,
        AccountType type,
        String currency,
        BigDecimal ledgerBalance,
        BigDecimal availableBalance) {

    public enum AccountType { SAVINGS, CURRENT }

    public String maskedAccountNumber() {
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}
