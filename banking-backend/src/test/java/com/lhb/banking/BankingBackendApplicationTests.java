package com.lhb.banking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
        "lhb.gateway.password=test-password-0123456789"
})
@AutoConfigureMockMvc
class BankingBackendApplicationTests {

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder asGateway(MockHttpServletRequestBuilder request) {
        return request.with(httpBasic("test-gateway", "test-password-0123456789"));
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
        mvc.perform(get("/accounts/ACC-1001").with(httpBasic("test-gateway", "wrong-password-000000")))
                .andExpect(status().isUnauthorized());
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
