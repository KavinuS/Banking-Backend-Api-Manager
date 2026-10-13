# WSO2 API Manager 4.7.0: verified facts & config changes

This file records what has actually been **observed** on this machine. It replaces the 🔎 assumptions in the plans as they get confirmed.

## Installation
- Location: `~/Downloads/wso2am-4.7.0` (`$WSO2_HOME`)
- JDK: Temurin 21.0.12 (arm64)
- Config backups (in `repository/conf/`):
  - `deployment.toml.orig`: pristine copy taken **before** the first start. ⚠️ **Do not restore it as is.** On first start, WSO2 4.7.0 appends a generated `[encryption] key = "…"` to `deployment.toml`. Losing that key makes encrypted data in the H2 databases unreadable.
  - `deployment.toml.after-first-start`: includes the encryption key (taken before the admin password change). To revert, use `scripts/restore.sh` with a snapshot instead of copying files.
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
| 2026-10-13 | `deployment.toml` (auto) | WSO2 appended `[encryption] key` on first start | Product behaviour | Never remove |
| 2026-10-13 | `deployment.toml` `[super_admin]` | `password` changed from `admin` to the generated `LHB_ADMIN_PASS` | Internal components (Key Manager, throttling, event hub, JMS broker) use `$ref{super_admin.*}`, so it must match the user-store password | Restore Snapshot #1 or earlier |
| 2026-10-13 | User store (via `RemoteUserStoreManagerService`) | Admin password changed. Created `lhb_publisher` (creator, publisher), `paylanka_dev`, `quickbudget_dev`, `rogue_dev` (subscriber) | Least privilege, no default credentials | Restore snapshot |

Passwords are only in `~/.lhb-poc.env` (chmod 600, outside git). Production would encrypt `deployment.toml` secrets with WSO2 Secure Vault (`ciphertool.sh`). That is a panel talking point.

Verified after restart: `admin/admin` → rejected, new admin password → OK, 0 ERROR lines. The only warnings are about missing commercial usage-reporting files (`subscription-*.json`, `USAGE_COUNT` table), which are irrelevant to the POC.
Note: subscriber users can log in to `/carbon` (the role includes the login permission) but have no admin menus.

## P3: API publishing (verified 2026-10-13)
- Published with `scripts/publish-api.sh` through the **Publisher REST API v4** (`/api/am/publisher/v4`) as `lhb_publisher`, using a DCR client (`/client-registration/v0.17/register`) and the password grant.
- Scopes requested vs granted to `lhb_publisher`: `apim:api_manage` and `apim:api_import_export` were **not** granted. Only `api_create`, `api_publish`, `api_view` and `subscription_view` were. Role-based least privilege works.
- API `LHB-Accounts` v1, id `8b618c61-2b3c-45fe-9d25-d5756ec282b2`, context `/lhb/accounts`, so the **gateway URL is `https://localhost:8243/lhb/accounts/v1/...`** (version appended).
- Both operations were imported with auth type "Application & Application User".
- Endpoint security: BASIC (production + sandbox). The Publisher API never returns the stored password.
- Revision 1 deployed to the `Default` gateway (vhost `localhost`), lifecycle → **Published**.
- ⚠️ Gateway deployment is **asynchronous** (~1–6 s after the deploy call). Calls made before that return 404 ("Message dispatched to the main sequence. Invalid URL."). The script waits for a 401 before reporting success.

## Error codes observed
| Situation | HTTP | WSO2 code | Message |
|---|---|---|---|
| No `Authorization` header | 401 | 900902 | Missing Credentials |
| HTTP (8280) on an HTTPS-only API | 403 | — | — |
| Unknown API path on the gateway | 404 | 404 | Not Found (Status report) |
*(rest filled in during P5)*

## Backend JWT format observed
*(filled in during P8)*
