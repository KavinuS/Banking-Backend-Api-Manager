package com.lhb.banking.api;

/** The only error shape the backend ever returns: no stack traces, no echoed input, no internal hosts. */
public record ApiError(String code, String message, String correlationId) {
}
