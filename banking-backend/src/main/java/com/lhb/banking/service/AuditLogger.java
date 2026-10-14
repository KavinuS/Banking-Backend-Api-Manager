package com.lhb.banking.service;

import com.lhb.banking.config.CorrelationIdFilter;
import com.lhb.banking.config.GatewayIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** One JSON line per data-access decision (logger "AUDIT", routed to logs/audit.log). Never logs tokens or PII. */
@Component
public class AuditLogger {

    public enum Outcome { ALLOWED, DENIED_NO_CONSENT, DENIED_SCOPE, NOT_FOUND }

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    public void record(GatewayIdentity caller, String operation, String accountId, Outcome outcome) {
        audit.info("{\"ts\":\"{}\",\"correlationId\":\"{}\",\"applicationId\":\"{}\",\"application\":\"{}\","
                        + "\"operation\":\"{}\",\"accountId\":\"{}\",\"outcome\":\"{}\"}",
                Instant.now(), json(MDC.get(CorrelationIdFilter.MDC_KEY)), json(caller.applicationId()),
                json(caller.applicationName()), operation, json(accountId), outcome);
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\x00-\\x1F]", " ");
    }
}
