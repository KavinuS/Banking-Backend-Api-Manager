package com.lhb.banking.repo;

import com.lhb.banking.model.Account;
import com.lhb.banking.model.Account.AccountType;
import com.lhb.banking.model.Transaction;
import com.lhb.banking.model.Transaction.Direction;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic, entirely fictional data so every demo run shows the same numbers.
 * Partner access (Stage B): PayLanka Wallet -> ACC-1001, ACC-1002; QuickBudget -> ACC-2001.
 */
@Repository
public class InMemoryBankRepository {

    private static final String CURRENCY = "LKR";
    private static final LocalDate FIRST_BOOKING_DATE = LocalDate.of(2026, 9, 1);
    private static final String[] DESCRIPTIONS = {
            "Salary credit - Demo Employer Ltd", "Supermarket - Demo Foods", "Utility bill - Demo Power",
            "Fuel - Demo Fuel Station", "Transfer from Demo Customer", "Mobile reload - Demo Telco",
            "Restaurant - Demo Cafe", "Pharmacy - Demo Health", "Online shopping - Demo Mart", "Interest credit"
    };

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, List<Transaction>> transactions = new LinkedHashMap<>();

    public InMemoryBankRepository() {
        add(new Account("ACC-1001", "LHB0000101001", AccountType.SAVINGS, CURRENCY, bd("254300.50"), bd("249300.50")));
        add(new Account("ACC-1002", "LHB0000101002", AccountType.CURRENT, CURRENCY, bd("1203450.00"), bd("1150000.00")));
        add(new Account("ACC-2001", "LHB0000202001", AccountType.SAVINGS, CURRENCY, bd("87600.25"), bd("87600.25")));
        add(new Account("ACC-2002", "LHB0000202002", AccountType.CURRENT, CURRENCY, bd("455000.00"), bd("430000.00")));
        add(new Account("ACC-3001", "LHB0000303001", AccountType.SAVINGS, CURRENCY, bd("12500.75"), bd("12500.75")));
        add(new Account("ACC-3002", "LHB0000303002", AccountType.SAVINGS, CURRENCY, bd("990000.00"), bd("985000.00")));
    }

    public Optional<Account> findAccount(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    /** Newest first. Bounds are inclusive and optional. */
    public List<Transaction> findTransactions(String accountId, LocalDate from, LocalDate to, int limit) {
        return transactions.getOrDefault(accountId, List.of()).stream()
                .filter(t -> from == null || !t.bookingDate().isBefore(from))
                .filter(t -> to == null || !t.bookingDate().isAfter(to))
                .sorted(Comparator.comparing(Transaction::bookingDate).reversed())
                .limit(limit)
                .toList();
    }

    private void add(Account account) {
        accounts.put(account.accountId(), account);
        int seed = Integer.parseInt(account.accountId().substring(4));
        List<Transaction> list = new ArrayList<>();
        for (int i = 0; i < DESCRIPTIONS.length; i++) {
            boolean credit = i == 0 || i == 4 || i == 9;
            BigDecimal amount = BigDecimal.valueOf(((seed * 37L + i * 1_913L) % 45_000) + 150, 0)
                    .add(BigDecimal.valueOf((seed + i * 7) % 100, 2));
            list.add(new Transaction(
                    "TXN-%d-%03d".formatted(seed, i + 1),
                    account.accountId(),
                    FIRST_BOOKING_DATE.plusDays(i * 3L),
                    DESCRIPTIONS[i],
                    amount,
                    credit ? Direction.CREDIT : Direction.DEBIT,
                    CURRENCY));
        }
        transactions.put(account.accountId(), List.copyOf(list));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
