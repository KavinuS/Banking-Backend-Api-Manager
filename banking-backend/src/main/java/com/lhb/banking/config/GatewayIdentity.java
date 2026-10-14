package com.lhb.banking.config;

import java.util.Set;

/** The calling partner application, as asserted by the WSO2 gateway's signed backend JWT. */
public record GatewayIdentity(String applicationId, String applicationName, String subscriber, Set<String> scopes) {

    public static final String REQUEST_ATTRIBUTE = GatewayIdentity.class.getName();
}
