package com.lhb.banking.api;

import com.lhb.banking.api.AccountController.TransactionPage;
import com.lhb.banking.api.AccountController.TransactionView;
import com.lhb.banking.config.GatewayIdentity;
import com.lhb.banking.model.Account;
import com.lhb.banking.service.AccountService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.lhb.banking.api.AccountController.ACCOUNT_ID_PATTERN;

/** v2 contract (published as LHB-Accounts v2): adds availableBalance. Same service, so same authorization and audit. */
@RestController
@RequestMapping("/v2/accounts")
public class AccountControllerV2 {

    private final AccountService accountService;

    public AccountControllerV2(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{accountId}")
    public AccountSummaryV2 getAccount(@RequestAttribute(GatewayIdentity.REQUEST_ATTRIBUTE) GatewayIdentity caller,
                                       @PathVariable @Pattern(regexp = ACCOUNT_ID_PATTERN) String accountId) {
        return AccountSummaryV2.from(accountService.getAccount(caller, accountId));
    }

    @GetMapping("/{accountId}/transactions")
    public TransactionPage getTransactions(
            @RequestAttribute(GatewayIdentity.REQUEST_ATTRIBUTE) GatewayIdentity caller,
            @PathVariable @Pattern(regexp = ACCOUNT_ID_PATTERN) String accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        List<TransactionView> items = accountService.getTransactions(caller, accountId, fromDate, toDate, limit).stream()
                .map(TransactionView::from)
                .toList();
        return new TransactionPage(accountId, fromDate, toDate, items.size(), items);
    }

    public record AccountSummaryV2(String accountId, String maskedAccountNumber, Account.AccountType type,
                                   String currency, BigDecimal balance, BigDecimal availableBalance) {
        static AccountSummaryV2 from(Account a) {
            return new AccountSummaryV2(a.accountId(), a.maskedAccountNumber(), a.type(), a.currency(),
                    a.ledgerBalance(), a.availableBalance());
        }
    }
}
