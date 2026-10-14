package com.lhb.banking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "lhb.gateway.username=test-gateway",
        "lhb.gateway.password=test-password-0123456789",
        "lhb.audit.file=target/audit-test.log"
})
@AutoConfigureMockMvc
@Import(TestGateway.class)
class BankingBackendApplicationTests {

    @Autowired
    MockMvc mvc;

    private static final String PAYLANKA = TestGateway.assertion(TestGateway.PAYLANKA_APP_ID, "PayLanka Wallet");

    /** Basic credential + valid gateway assertion for PayLanka Wallet. */
    private static MockHttpServletRequestBuilder asGateway(MockHttpServletRequestBuilder request) {
        return withAssertion(request, PAYLANKA);
    }

    private static MockHttpServletRequestBuilder withAssertion(MockHttpServletRequestBuilder request, String assertion) {
        return request.with(httpBasic("test-gateway", "test-password-0123456789")).header("X-JWT-Assertion", assertion);
    }

    @Test
    void directCallWithoutCredentialIsRejected() throws Exception {
        mvc.perform(get("/accounts/ACC-1001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.correlationId", notNullValue()));
    }

    @Test
    void wrongCredentialIsRejected() throws Exception {
        mvc.perform(get("/accounts/ACC-1001").with(httpBasic("test-gateway", "wrong-password-000000"))
                        .header("X-JWT-Assertion", PAYLANKA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void basicCredentialWithoutAssertionIsRejected() throws Exception {
        mvc.perform(get("/accounts/ACC-1001").with(httpBasic("test-gateway", "test-password-0123456789")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void assertionWithoutBasicCredentialIsRejected() throws Exception {
        mvc.perform(get("/accounts/ACC-1001").header("X-JWT-Assertion", PAYLANKA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assertionSignedByAnotherKeyIsRejected() throws Exception {
        String forged = TestGateway.sign(TestGateway.ATTACKER_KEYS, TestGateway.claims(TestGateway.PAYLANKA_APP_ID,
                "PayLanka Wallet", "PRODUCTION", Instant.now().plusSeconds(900), "wso2.org/products/am"));
        mvc.perform(withAssertion(get("/accounts/ACC-1001"), forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void assertionWithAlteredClaimsIsRejected() throws Exception {
        String[] parts = PAYLANKA.split("\\.");
        String otherClaims = TestGateway.assertion(TestGateway.QUICKBUDGET_APP_ID, "QuickBudget").split("\\.")[1];
        mvc.perform(withAssertion(get("/accounts/ACC-1001"), parts[0] + "." + otherClaims + "." + parts[2]))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAssertionIsRejected() throws Exception {
        String expired = TestGateway.sign(TestGateway.GATEWAY_KEYS, TestGateway.claims(TestGateway.PAYLANKA_APP_ID,
                "PayLanka Wallet", "PRODUCTION", Instant.now().minusSeconds(120), "wso2.org/products/am"));
        mvc.perform(withAssertion(get("/accounts/ACC-1001"), expired)).andExpect(status().isUnauthorized());
    }

    @Test
    void sandboxAssertionIsRejected() throws Exception {
        String sandbox = TestGateway.sign(TestGateway.GATEWAY_KEYS, TestGateway.claims(TestGateway.PAYLANKA_APP_ID,
                "PayLanka Wallet", "SANDBOX", Instant.now().plusSeconds(900), "wso2.org/products/am"));
        mvc.perform(withAssertion(get("/accounts/ACC-1001"), sandbox)).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        String other = TestGateway.sign(TestGateway.GATEWAY_KEYS, TestGateway.claims(TestGateway.PAYLANKA_APP_ID,
                "PayLanka Wallet", "PRODUCTION", Instant.now().plusSeconds(900), "someone-else"));
        mvc.perform(withAssertion(get("/accounts/ACC-1001"), other)).andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedAssertionIsRejected() throws Exception {
        String[] parts = PAYLANKA.split("\\.");
        String noneHeader = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes());
        mvc.perform(withAssertion(get("/accounts/ACC-1001"), noneHeader + "." + parts[1] + "."))
                .andExpect(status().isUnauthorized());
    }

    private static final String QUICKBUDGET = TestGateway.assertion(TestGateway.QUICKBUDGET_APP_ID, "QuickBudget");

    @Test
    void partnerCanReadConsentedAccount() throws Exception {
        mvc.perform(withAssertion(get("/accounts/ACC-2001"), QUICKBUDGET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedAccountNumber").value("****2001"));
    }

    @Test
    void crossPartnerAccessIsIndistinguishableFromUnknownAccount() throws Exception {
        String bola = mvc.perform(withAssertion(get("/accounts/ACC-1001"), QUICKBUDGET))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(withAssertion(get("/accounts/ACC-9999"), QUICKBUDGET))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        // Same body apart from the correlation ID: a partner cannot probe which accounts exist
        org.junit.jupiter.api.Assertions.assertEquals(
                bola.replaceAll("\"correlationId\":\"[^\"]*\"", ""), unknown.replaceAll("\"correlationId\":\"[^\"]*\"", ""));
    }

    @Test
    void crossPartnerTransactionAccessIsDenied() throws Exception {
        mvc.perform(withAssertion(get("/accounts/ACC-2001/transactions"), PAYLANKA))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownApplicationHasNoConsents() throws Exception {
        String stranger = TestGateway.assertion("00000000-0000-0000-0000-000000000000", "Unregistered App");
        mvc.perform(withAssertion(get("/accounts/ACC-1001"), stranger)).andExpect(status().isNotFound());
    }

    @Test
    void backendReChecksScopeBehindTheGateway() throws Exception {
        String accountsOnly = TestGateway.assertionWithScope(TestGateway.PAYLANKA_APP_ID, "PayLanka Wallet", "accounts:read");
        mvc.perform(withAssertion(get("/accounts/ACC-1001"), accountsOnly)).andExpect(status().isOk());
        mvc.perform(withAssertion(get("/accounts/ACC-1001/transactions"), accountsOnly))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void v2AddsAvailableBalanceAndKeepsAuthorization() throws Exception {
        mvc.perform(asGateway(get("/v2/accounts/ACC-1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedAccountNumber").value("****1001"))
                .andExpect(jsonPath("$.availableBalance").value(249300.50));
        mvc.perform(withAssertion(get("/v2/accounts/ACC-1001"), QUICKBUDGET)).andExpect(status().isNotFound());
        mvc.perform(asGateway(get("/v2/accounts/ACC-1001/transactions").param("limit", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void accountIsReturnedMasked() throws Exception {
        mvc.perform(asGateway(get("/accounts/ACC-1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("ACC-1001"))
                .andExpect(jsonPath("$.maskedAccountNumber").value("****1001"))
                .andExpect(jsonPath("$.balance").value(254300.50))
                .andExpect(jsonPath("$.accountNumber").doesNotExist())
                .andExpect(jsonPath("$.availableBalance").doesNotExist())
                .andExpect(content().string(not(containsString("LHB0000101001"))));
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        mvc.perform(asGateway(get("/accounts/ACC-9999")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void malformedAccountIdIsRejected() throws Exception {
        mvc.perform(asGateway(get("/accounts/1;DROP")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("DROP"))));
    }

    @Test
    void firewallRejectedPathIsBadRequestWithCorrelationId() throws Exception {
        mvc.perform(asGateway(get(URI.create("/accounts/1%3BDROP"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId", notNullValue()));
    }

    @Test
    void transactionsAreLimitedAndNewestFirst() throws Exception {
        mvc.perform(asGateway(get("/accounts/ACC-1001/transactions").param("limit", "3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.transactions", hasSize(3)))
                .andExpect(jsonPath("$.transactions[0].bookingDate").value("2026-09-28"));
    }

    @Test
    void transactionsFilterByDateRange() throws Exception {
        mvc.perform(asGateway(get("/accounts/ACC-1001/transactions")
                        .param("fromDate", "2026-09-04").param("toDate", "2026-09-10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void excessiveLimitIsRejected() throws Exception {
        mvc.perform(asGateway(get("/accounts/ACC-1001/transactions").param("limit", "10000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void invertedDateRangeIsRejected() throws Exception {
        mvc.perform(asGateway(get("/accounts/ACC-1001/transactions")
                        .param("fromDate", "2026-09-30").param("toDate", "2026-09-01")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMethodIsRejected() throws Exception {
        mvc.perform(asGateway(post("/accounts/ACC-1001")))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void errorsNeverLeakInternals() throws Exception {
        mvc.perform(asGateway(get("/accounts/ACC-1001/transactions").param("limit", "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("trace"))))
                .andExpect(content().string(not(containsString("java."))));
    }

    @Test
    void wellFormedCorrelationIdIsEchoedAndUnsafeOneReplaced() throws Exception {
        mvc.perform(asGateway(get("/accounts/ACC-1001")).header("X-Correlation-ID", "demo-1234-abcd"))
                .andExpect(header().string("X-Correlation-ID", "demo-1234-abcd"));
        mvc.perform(asGateway(get("/accounts/ACC-1001")).header("X-Correlation-ID", "bad\nINJECTED log line"))
                .andExpect(header().string("X-Correlation-ID", matchesPattern("^[0-9a-f-]{36}$")));
    }

    @Test
    void healthIsOpenButOtherActuatorEndpointsAreNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(asGateway(get("/actuator/env"))).andExpect(status().isNotFound());
    }
}
