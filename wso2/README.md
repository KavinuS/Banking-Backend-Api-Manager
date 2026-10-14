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

## P4: Onboarding (verified 2026-10-14)
- Done with `scripts/onboard-partner.sh` through the **Developer Portal REST API v3** as each developer user (scopes `apim:subscribe apim:app_manage apim:sub_manage`).
- Apps (all fictional): `PayLanka Wallet` (paylanka_dev, subscribed, Unlimited), `QuickBudget` (quickbudget_dev, keys only, subscribes in P6), `RogueApp` (rogue_dev, keys only, never subscribed), `TokenExpiryTest` (rogue_dev, 10 s tokens, test only).
- Keys: PRODUCTION, client_credentials only, JWT tokens. Written directly to `~/.lhb-poc.env` and never printed.
- First authorised call through the gateway → 200 with masked data. API-driven onboarding took about 2 s end to end. The human UI path is timed separately during rehearsal.
- Token endpoint ignores `validity_period`. Set a short lifetime via the key-generation property `application_access_token_expiry_time`.
- Client-credentials token claims: `sub` = owner UUID, `scope` = `default` when no scope is requested.

## Error codes observed (P5, verified 2026-10-14)
| Situation | HTTP | Code | Source |
|---|---|---|---|
| No `Authorization` header | 401 | 900902 Missing Credentials | Gateway |
| Garbage / forged-claims / corrupted-signature / expired token | 401 | 900901 Invalid Credentials | Gateway |
| Valid token, app not subscribed | 403 | 900908 Resource forbidden (subscription validation failed) | Gateway |
| Method not declared in API (POST) | 405 | 405 Method not allowed for given API resource | Gateway |
| Resource not declared in API | 404 | 404 No matching resource found | Gateway |
| HTTP 8280 on HTTPS-only API | 403 | 403 | Gateway |
| Unknown API context | 404 | 404 Not Found (Status report) | Gateway |
| Bad account id, `limit` > 50, inverted dates, encoded `;` | 400 | INVALID_REQUEST | Backend |
| Unknown account | 404 | NOT_FOUND | Backend |
| Direct backend call without the gateway credential | 401 | UNAUTHORIZED | Backend |

Ordering note: an **expired** token from an unsubscribed app returns 401 (900901), not 403. Token validation runs before the subscription check.

### Test-design lesson: base64url padding bits
An RS256 signature is 342 base64url characters, and the low 4 bits of the last character are padding. Changing that character can decode to an **identical** signature (e.g. `A`→`B`), leaving the token genuinely valid. The first tamper test therefore "failed" intermittently with 200. This was verified: 200 only when the decoded bytes were identical, 401 in every other case. Fixed by forging claims (T4b) and corrupting a middle byte of the signature (T4c). After the fix: 10 consecutive green runs.

### Backend fix found by P5
`/accounts/1%3BDROP` was rejected by Spring Security's firewall and forwarded to the secured `/error` page, which surfaced as a misleading 401 without a correlation ID. A `requestRejectedHandler` now returns 400 INVALID_REQUEST with a correlation ID (unit-tested).

## P6: Throttling (verified 2026-10-14)
- `scripts/setup-throttling.sh`: subscription policy **`LHBPartnerDemo`** (display name "LHB Partner Demo"), 5 requests/min, `stopOnQuotaReach`, burst control off. WSO2 **rejects `-` in policy names** (code 900706). Added to the API's business plans, then a new revision was deployed. QuickBudget is subscribed on it.
- The Traffic Manager compiles it to `repository/deployment/server/executionplans/carbon.super_sub_LHBPartnerDemo.siddhiql` with `timeBatch(1 min, 0)` and `count >= 5`. **Windows reset on wall-clock minute boundaries.**
- Throttled response: **429, code 900804 "Message throttled out"**, with the retry-after time in the description.
- `scripts/throttle-test.sh` (1 req/s, 20 requests): `200×5 → 429×15` in 3/3 runs. The backend access log shows **exactly 5** requests reached the core. Access recovers in the next window.

### ⚠️ Finding: throttling silently broke when the Mac changed network
By default the gateway → Traffic Manager (9611/9711) and throttle-decision JMS (5672) connections use the **LAN IP detected at startup**. The Mac moved from `10.136.178.220` to `10.57.64.220` overnight. Every connection failed ("Cannot borrow client", JMS back-off up to 2560 s), and **no request was ever throttled**, although the API kept serving 200s.
Fix: pinned these endpoints to `localhost` in `deployment.toml` (see `wso2/deployment.toml.patch`). **Demo-day relevance:** venue Wi-Fi would have caused the same silent failure.

### Enforcement is asynchronous
- The publisher connects lazily on the first request after a restart.
- A 1-second burst of 15 requests was **never** throttled. The decision arrives after the burst has finished.
- At 2 req/s, right after a sleep/wake, about 10 calls passed before the first 429 (~5 s latency).
- At 1 req/s the 6th call was rejected every time.

Message for the panel: the quota is enforced cluster-wide, eventually and within seconds. **Burst control** (enforced locally on the gateway) is the instant spike guard.

### Other operational notes
- Tomcat access log on the backend (`banking-backend/logs/access.*.log`, unbuffered, `%{ms}T`). In Tomcat 11, `%D` is microseconds.
- The Mac went to sleep during a test: a 15-minute time jump, then 401s. **Demo day: run under `caffeinate -di`.**

## P7: Resource scopes & entitlements (verified 2026-10-14)
- `scripts/setup-scopes.sh` creates two local scopes on LHB-Accounts v1:
  - `accounts:read` → bound to `Internal/subscriber` → `GET /accounts/{accountId}`
  - `transactions:read` → bound to new role **`Internal/lhb_txn_partner`** (member: `paylanka_dev` only) → `GET /accounts/{accountId}/transactions`
- With client_credentials, WSO2 grants a requested scope **only if the app owner holds a bound role**. QuickBudget asked for both scopes and got `accounts:read` only. The bank controls entitlements, not the partner.
- No scope requested → token scope `default` → 403 on every resource.
- Insufficient scope → **403, code 900910**.
- `get-token.sh` now requests both scopes by default (`none` for no scope).
- Open gap (closed in P9): QuickBudget can still read PayLanka's account ACC-1001. Scopes limit *operations*, not *which customer's data* (BOLA).

## P8: Backend JWT (verified 2026-10-14)
- The per-API `backEndJWT_v1` policy lists `supportedGateways: ["ChoreoConnect"]`, so it **cannot** be used on the regular (Synapse) gateway. Backend JWT was enabled globally with `[apim.jwt]` in `deployment.toml` (see `deployment.toml.patch`) and a restart.
- Captured once from a real gateway call (a temporary access-log pattern, then deleted):
  - Header `X-JWT-Assertion`, base64url, **RS256**, `x5t` = SHA-1 thumbprint of the `wso2carbon` certificate (CN=localhost, valid until 2028-06-06), plus `kid`.
  - `iss` = `wso2.org/products/am`, **lifetime 900 s**, and **`exp`/`iat` are JSON strings**, not numbers.
  - Claims: `http://wso2.org/claims/applicationUUId`, `applicationname`, `subscriber`, `keytype` (PRODUCTION), `apicontext`, `version`, `tier`, `enduser`, plus `scope` and `client_id`.
- Signature independently verified with `openssl dgst -sha256 -verify` against the exported **public** certificate `banking-backend/src/main/resources/wso2-gateway.pem`.
- The backend `GatewayJwtVerifier` (Nimbus 10.10) checks RS256, the signature, `iss`, `exp` (30 s skew), `keytype == PRODUCTION` and `applicationUUId`.
  - ⚠️ Nimbus `getJWTClaimsSet()` rejects string-typed `exp`, so the verified payload is read as a JSON map. This was caught by unit tests that mirror the real format.
- **Same signing key** for partner access tokens and backend assertions. An unmodified partner token passes the signature check and is rejected only by the `iss` check (T10g). Claim validation is essential.
- Known limitation (stated to the panel): anyone holding the Basic credential **and** a captured assertion can replay it for up to 15 min. The real boundary is network isolation (loopback here; private subnet / mTLS in production).

## P9: Account-level authorization & audit (verified 2026-10-14)
- Backend consent registry (`lhb.consents.partners` in `application.yml`) keyed on the **verified** `applicationUUId`:
  - PayLanka Wallet `f2e228cd-…` → ACC-1001, ACC-1002
  - QuickBudget `8c6a3c36-…` → ACC-2001
  - ⚠️ These UUIDs change if the Developer Portal apps are recreated. Restore from a snapshot instead.
- The backend also re-checks the scope from the assertion (403 FORBIDDEN), defence in depth behind the gateway.
- A cross-partner request returns **404 NOT_FOUND, byte-identical (apart from correlationId) to a non-existent account**, so partners can't enumerate accounts.
- Audit trail `banking-backend/logs/audit.log`, one JSON line per decision: ts, correlationId, applicationId, application, operation, accountId, outcome (`ALLOWED` / `DENIED_NO_CONSENT` / `DENIED_SCOPE` / `NOT_FOUND`). No tokens, no full account numbers. Unit tests write to `target/audit-test.log`.
- Live suite: T7b, T8a–T8e. The BOLA attempt is found in the audit log by the correlation ID the caller sent.

## P10: Versioning & lifecycle (verified 2026-10-14)
- `scripts/release-v2.sh`: Publisher `copy-api` v1 → **v2** (context `/lhb/accounts`, gateway URL `/lhb/accounts/v2/...`), definition replaced with `lhb-accounts-v2.yaml`, backend endpoint `http://127.0.0.1:8080/v2` (new `AccountControllerV2`, same service: authorization, consent and audit are identical).
- ⚠️ **Replacing the OpenAPI definition wiped all per-operation scopes** (and the API's local scopes). v2 would have shipped with no scope enforcement. The script re-applies scopes after every definition update, and test T6/T13d cover it.
- **Publishing v2 auto-subscribed existing v1 subscribers**, with tiers preserved (QuickBudget stays on LHBPartnerDemo). Repeat subscribe → 409. The bank can force re-subscription with the "Requires re-subscription" option at publish time.
- v1 → **Deprecated**: hidden from the Developer Portal, existing subscribers keep working (200, v1 contract unchanged), a new subscription to v1 is refused. WSO2 answers that with a generic **HTTP 500 / 900967**, not a 4xx; the portal UI hides deprecated APIs anyway.
- Lifecycle history (Publisher API `/apis/{id}/lifecycle-history`): CREATED → PUBLISHED → DEPRECATED with user and timestamp, i.e. a governance audit trail.
- Test suites now target v2 (`security-tests.sh`: 40 checks incl. T13a–d; `throttle-test.sh` on v2: 5×200 then 429).

## P11: Threat protection & observability (verified 2026-10-14)
- Operation policies usable on our **Synapse** gateway: addHeader, addLogMessage, jsonValidator, regexPolicy, opaPolicy, jwtClaimBasedAccessValidator, removeHeader, jsonFault, … (`backEndJWT_v1` is Choreo-Connect-only).
- `scripts/setup-threat-protection.sh` attaches **`regexPolicy_v1`** (built-in SQL-injection regex over body, headers and path/query, no parameters) to both v2 operations, and sets API-level **CORS** to `https://app.paylanka.example`, GET/OPTIONS only (overrides the global `allow_origins = "*"`).
  - Blocked at the gateway with **400 "SQL-Injection Threat detected in Query Parameters / HTTP Headers"**: quotes, `OR '1'='1`, `alter`, `drop table`, `select * from`, and a quote in any header. My hypothesis that the multi-line regex in the template breaks the later alternatives was **disproven**: they match.
  - Trade-off: very broad patterns (`.*alter.*` also matches "alternative"; any apostrophe in a header is rejected). Fine for this narrow API, needs tuning for free-text APIs.
  - CORS: allowed origin echoed; unknown origin gets an empty `Access-Control-Allow-Origin` (the browser refuses).
  - Scopes stayed intact after this API update.
- Observability:
  - The partner's `X-Correlation-ID` is forwarded by the gateway and returned in the response; the gateway adds its own `activityid` header.
  - `scripts/trace.sh <cid>`: backend access log → audit decision → recent gateway security events (WSO2 log).
  - WSO2 logs worth showing: `wso2carbon.log` (APIAuthenticationHandler failures with appName, threat detections), `audit.log` (management actions, user names masked), `http_access_*.log`.
  - No bearer tokens or JWTs found in any WSO2 log (grep check).
  - Analytics dashboards (Choreo/ELK/Moesif) are **not** configured. Roadmap item, as planned.
