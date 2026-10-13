# WSO2 API Manager 4.7.0: verified facts & config changes

This file records what has actually been **observed** on this machine. It replaces the 🔎 assumptions in the plans as they get confirmed.

## Installation
- Location: `~/Downloads/wso2am-4.7.0` (`$WSO2_HOME`)
- JDK: Temurin 21.0.12 (arm64)
- Original config backed up: `repository/conf/deployment.toml.orig` (2026-10-13)
- macOS quarantine attribute is present on the files, but it did **not** block startup. It was left untouched.

## First start (2026-10-13)
- `bin/api-manager.sh start` → "WSO2 Carbon started in 17 sec", 0 ERROR lines
- Log: `repository/logs/wso2carbon.log`

| URL | HTTP status |
|---|---|
| https://localhost:9443/publisher/ | 200 |
| https://localhost:9443/devportal/ | 200 |
| https://localhost:9443/admin/ | 200 |
| https://localhost:9443/carbon/ | 302 (login redirect) |
| https://localhost:8243/ (gateway HTTPS) | 200 |

Listening ports (java): 8243, 8280, 9443, 9763, 5672, 8672, 9611, 9711, 9099, 9021, 8099, 8021, 9999, 11111, plus 10711 on the LAN IP.

### ⚠️ Exposure finding
WSO2 binds to **all interfaces** (`*:9443`, `*:8243`, …), not just localhost, and the macOS firewall is **disabled**. Anyone on the same Wi-Fi/LAN can reach the portals.

Mitigation:
1. Change the default `admin/admin` password **immediately** after first start.
2. Enable the macOS firewall (System Settings → Network → Firewall), or only run WSO2 on a trusted network.
3. Production uses a reverse proxy/load balancer and network segmentation. That is a talking point for the panel.

## Built-in operation policies (from the startup log)
These **exist in 4.7.0** and change parts of the plan:

| Policy | Relevance to plan |
|---|---|
| `backEndJWT_v1` | **Per-API backend JWT policy.** P8 may not need a `deployment.toml` change; try this first |
| `regexPolicy_v1` | Regex threat protection (P11 / B5) |
| `jsonValidator_v1` | Request/response schema validation |
| `jwtClaimBasedAccessValidator_v1` | Gateway-side claim-based access checks |
| `opaPolicy_v1` | Open Policy Agent authorization. A possible stretch item for account-level authorization at the gateway |
| `addHeader_v3` / `removeHeader_v2` | P8 fallback (`X-LHB-App` header, strip client-supplied copy) |
| `addLogMessage_v2` | Observability (P11 / B6) |
| `jsonFault_v1` | Uniform error responses |
| `PIIMaskingRegex_v1`, `RegexGuardrail_v1.0`, `JSONSchemaGuardrail_v1.0`, `ContentLengthGuardrail_v1.0`, `URLGuardrail_v1.0`, `SemanticPromptGuardrail_v1.0`, `AWSBedrockGuardrail_v1.1`, `AzureContentSafetyContentModeration_v1.0`, … | **AI gateway guardrails** (confirmed present). Optional talking point only |

## Config changes log
| Date | File | Change | Reason | Revert |
|---|---|---|---|---|
| — | — | none yet | — | — |

## Error codes observed
*(filled in during P5)*

## Backend JWT format observed
*(filled in during P8)*
