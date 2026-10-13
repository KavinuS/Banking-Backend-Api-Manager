package com.lhb.banking.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Transaction(
        String transactionId,
        String accountId,
        LocalDate bookingDate,
        String description,
        BigDecimal amount,
        Direction direction,
        String currency) {

    public enum Direction { CREDIT, DEBIT }
}
