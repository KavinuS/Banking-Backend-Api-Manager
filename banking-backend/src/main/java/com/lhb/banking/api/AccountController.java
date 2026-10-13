package com.lhb.banking.api;

import com.lhb.banking.model.Account;
import com.lhb.banking.model.Transaction;
import com.lhb.banking.service.AccountService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    static final String ACCOUNT_ID_PATTERN = "^ACC-\\d{4}$";

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{accountId}")
    public AccountSummary getAccount(@PathVariable @Pattern(regexp = ACCOUNT_ID_PATTERN) String accountId) {
        return AccountSummary.from(accountService.getAccount(accountId));
    }

    @GetMapping("/{accountId}/transactions")
    public TransactionPage getTransactions(
            @PathVariable @Pattern(regexp = ACCOUNT_ID_PATTERN) String accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        List<TransactionView> items = accountService.getTransactions(accountId, fromDate, toDate, limit).stream()
                .map(TransactionView::from)
                .toList();
        return new TransactionPage(accountId, fromDate, toDate, items.size(), items);
    }

    /** v1 contract: masked number, ledger balance only (availableBalance is reserved for v2). */
    public record AccountSummary(String accountId, String maskedAccountNumber, Account.AccountType type,
                                 String currency, BigDecimal balance) {
        static AccountSummary from(Account a) {
            return new AccountSummary(a.accountId(), a.maskedAccountNumber(), a.type(), a.currency(), a.ledgerBalance());
        }
    }

    public record TransactionView(String transactionId, LocalDate bookingDate, String description,
                                  BigDecimal amount, Transaction.Direction direction, String currency) {
        static TransactionView from(Transaction t) {
            return new TransactionView(t.transactionId(), t.bookingDate(), t.description(), t.amount(),
                    t.direction(), t.currency());
        }
    }

    public record TransactionPage(String accountId, LocalDate fromDate, LocalDate toDate, int count,
                                  List<TransactionView> transactions) {
    }
}
