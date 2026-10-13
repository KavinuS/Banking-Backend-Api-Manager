# Lanka Horizon Bank — Secure Banking API Platform (POC)

**Product:** WSO2 API Manager 4.7.0 · **Backend:** Java 21 + Spring Boot · **Audience:** Sales Engineering evaluation panel
**Plan version:** v2 (2026-10-13), revised after external review. See §12 for the change log.
**Environment (verified 2026-10-13):** WSO2 at `~/Downloads/wso2am-4.7.0`, Temurin JDK 21.0.12 (arm64), Maven not installed (use `./mvnw`).

> All data in this POC is fictional. No real customer data, credentials or bank systems are used at any point.
> This is a plan. Nothing here has been implemented or tested yet. Items marked 🔎 **VERIFY** are assumptions about WSO2 4.7.0 that must be confirmed in the real installation before we rely on them.

---

## 1. Objectives & guiding principles

| Outcome the bank cares about | What the POC must prove |
|---|---|
| **Protect existing banking services** | Nothing reaches the core banking backend except through the gateway, with a valid token, the right scope, and (Stage B) authorization for that specific account |
| **Improve API operations** | Central publishing, versioning, lifecycle, throttling and logs from one control plane |
| **Enable fintech partnerships** | A partner can self-discover, subscribe, get credentials and make a call in minutes |

Principles:
1. **Working core first.** Stage A, the essential demo, must be complete and frozen before Stage B features are added. A small demo that works beats an ambitious one that is unfinished.
2. **Defence in depth.** The gateway is the first check, not the only one. The backend enforces its own authorization (no BOLA).
3. **Least privilege.** Narrow scopes, per-partner account access, and only the fields a partner needs.
4. **Evidence over claims.** Every capability shown has a test with an expected result.
5. **Honest scope.** We state clearly what the POC proves and what it deliberately does not (§4.3).
6. **Demo-safe.** Everything is scripted, resettable, and runs offline on one Mac.

---

## 2. Business value & success metrics

These are **proposed POC measurements**. They show capability, not savings. Claims about money or time saved need the bank's real baseline data, collected during discovery.

| Customer concern (from discovery) | POC measurement | How we capture it | Stage |
|---|---|---|---|
| Slow fintech onboarding | Elapsed time from "API published" to "partner's first successful call" | Stopwatch during rehearsal, then shown live | A |
| Security risk of exposing core banking | % of unauthorized / out-of-scope / cross-account requests rejected (target: 100% of the test matrix) | `security-tests.sh` PASS report | A + B |
| Uncontrolled partner traffic | Requests over quota are rejected with 429, and the backend sees no traffic past the limit | `throttle-test.sh` plus the backend request count | A |
| Maintenance burden of custom gateway code | List of the bank's custom functions (auth, rate limiting, key issuing, docs portal) that become **configuration** in WSO2, with the integration effort that remains | Capability mapping table in the panel deck | Deck |
| Limited API governance | One version change plus deprecation, visible to partners | Publisher / Dev Portal screenshots | B |
| Audit / regulator questions | Each partner call is traceable: who, which account, outcome, time | Backend audit log with correlation ID | B |

### 2.1 Discovery → demo mapping

The demo order changes depending on which concern is the top priority in discovery. All segments still fit in 28 minutes. Only the order and emphasis change.

| If discovery shows the top concern is… (persona) | Lead with | Then | Emphasise |
|---|---|---|---|
| Security / compliance (CISO) | No token, bad scope, cross-account (BOLA), gateway bypass | Throttling → onboarding | Defence in depth, audit trail, the honest consent roadmap |
| Partner growth (Head of Digital / Partnerships) | Dev Portal onboarding, timed | Security → governance | Self-service, speed to first call |
| Integration effort / operations (Integration Engineer) | Publishing from OpenAPI, endpoint config | Versioning → throttling | Configuration over custom code, lifecycle |
| Stability of core systems (Head of IT Ops) | Throttling (429 protects the core) | Security → observability | Rate limits per partner tier, logs |

---

## 3. System architecture

### 3.1 Logical architecture

```
                         ┌──────────────────────────────────────────────────────────┐
  Fintech Partner App    │              WSO2 API Manager 4.7.0 (all-in-one)         │
  (Postman / curl)       │                                                          │
        │                │  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐  │
        │ 1. get token   │  │ Key Manager  │  │  Publisher   │  │ Developer      │  │
        ├───────────────►│  │ /oauth2/token│  │  /publisher  │  │ Portal         │  │
        │  (client creds)│  │ issues JWT   │  │ design,      │  │ /devportal     │  │
        │                │  │ w/ scopes    │  │ lifecycle    │  │ discover, sub- │  │
        │                │  └──────────────┘  └──────────────┘  │ scribe, keys   │  │
        │                │                                      └────────────────┘  │
        │ 2. call API    │  ┌──────────────────────────────────┐ ┌───────────────┐  │
        │  Bearer <JWT>  │  │ API Gateway  :8243 (HTTPS)       │ │ Traffic Mgr   │  │
        ├───────────────►│  │ • token signature/expiry check   │◄┤ rate-limit    │  │
        │                │  │ • subscription check             │ │ decisions     │  │
        │                │  │ • scope check per resource  (B)  │ └───────────────┘  │
        │                │  │ • throttling (429)               │ ┌───────────────┐  │
        │                │  │ • threat protection / CORS  (B)  │ │ Admin Portal  │  │
        │                │  │ • backend JWT               (B)  │ │ /admin        │  │
        │                │  └───────────────┬──────────────────┘ └───────────────┘  │
        │                └──────────────────┼───────────────────────────────────────┘
        │                                   │ 3. Basic auth endpoint credential (A)
        │                                   │    + gateway-signed JWT (B)
        │                                   ▼
        │                ┌──────────────────────────────────────────────────────────┐
        │   ✗ direct     │   Mock Core Banking Backend (Spring Boot)  127.0.0.1:8080│
        └ ─ ─ ─ ─ ─ ─ ─ ►│  • requires gateway credential  → direct call = 401      │
          call → 401     │  • (B) verifies gateway JWT, checks account access       │
                         │  • input validation, safe errors, masked fields          │
                         │  • in-memory fictional accounts & transactions           │
                         └──────────────────────────────────────────────────────────┘
   (A) = Stage A essential demo     (B) = Stage B advanced banking proof
```

### 3.2 Component inventory & ports

| Component | URL | Notes |
|---|---|---|
| Publisher | `https://localhost:9443/publisher` | API design, lifecycle, versioning |
| Developer Portal | `https://localhost:9443/devportal` | Partner onboarding |
| Admin Portal | `https://localhost:9443/admin` | Rate-limit policies, (optional) approvals |
| Carbon console | `https://localhost:9443/carbon` | Users and roles |
| Token endpoint | `https://localhost:9443/oauth2/token` | Client-credentials grant |
| API Gateway | `https://localhost:8243` | All partner traffic. HTTPS only |
| Banking backend | `http://127.0.0.1:8080` | Bound to loopback only |

🔎 **VERIFY** the default ports and portal paths when WSO2 first starts. The startup log prints them.

### 3.3 Request flow (happy path, full Stage B)

1. The partner app calls `POST /oauth2/token` with its consumer key and secret, requesting `accounts:read transactions:read`.
2. The Key Manager issues a signed JWT access token (default expiry 1 h) with the permitted scopes.
3. The partner calls `GET https://localhost:8243/lhb/accounts/v1/accounts/ACC-1001` with `Authorization: Bearer <token>`.
4. The gateway validates the token, subscription, scope and quota, then applies threat protection.
5. The gateway forwards the request to the backend with the endpoint Basic-auth credential and a gateway-signed backend JWT that identifies the calling application.
6. The backend checks the credential, verifies the JWT, and checks that **this application may see ACC-1001**.
7. The backend returns minimised, masked data and writes an audit log entry.

### 3.4 Repository layout (to be created)

```
Api-Manager/
├── docs/        PROJECT_PLAN.md, architecture.md, demo-script.md, objection-handling.md, value-map.md
├── banking-backend/                 Spring Boot (./mvnw), openapi/lhb-accounts-v1.yaml
├── wso2/        deployment.toml.patch + README (every config change, with how to revert)
├── postman/     collection + environment template (no secrets committed)
├── snapshots/   (git-ignored) tarballs of known-good WSO2 states
└── scripts/     start-all.sh, stop-all.sh, get-token.sh, security-tests.sh,
                 throttle-test.sh, reset-demo.sh, snapshot.sh
```

---

## 4. Security architecture & guardrails

### 4.1 Security layers

| # | Layer | Control | Stage | Threat mitigated |
|---|---|---|---|---|
| L1 | Transport | HTTPS only on gateway (8243) | A | Eavesdropping, token theft |
| L2 | Identity | OAuth2 client credentials, JWT access tokens, short expiry | A | Anonymous access |
| L3 | Subscription | App must be subscribed to the API | A | Unregistered consumers |
| L4 | Traffic | Subscription tier `LHB-Partner-Demo` (low quota) | A | Abuse, scraping, overload of the core |
| L5 | Gateway → backend channel | Basic-auth endpoint security, backend bound to 127.0.0.1 | A | Direct backend access |
| L6 | Safe errors & input validation | ID pattern, `limit` cap, no stack traces | A | Injection, information leakage |
| L7 | Authorization (coarse) | Per-resource scopes `accounts:read`, `transactions:read` | B | Over-broad access |
| L8 | Gateway identity to backend | Gateway-signed backend JWT carrying the application identity | B 🔎 | Backend not knowing who is calling |
| L9 | Authorization (fine) | Application → allowed-accounts registry, checked per request | B | **BOLA (OWASP API1)** |
| L10 | Data minimisation | Masked account numbers, no unnecessary PII | A | Data leakage |
| L11 | Threat protection | JSON / regex threat-protection policies, CORS | B 🔎 | Payload attacks |
| L12 | Audit & observability | Backend audit log (app, account, outcome, correlation ID). Tokens never logged | B | Undetected misuse |
| L13 | Platform hardening | Non-default admin password, separate publisher and subscriber users, localhost only | A | Admin takeover |
| L14 | Partner approval | Subscription approval workflow | Optional 🔎 | Unvetted partners |
| L15 | Strong client auth | mTLS for partners / gateway → backend | Optional 🔎 | Credential theft |

### 4.2 Gateway → backend trust

**What actually provides the trust boundary.** A signed assertion on its own does **not** prove that a request came through the gateway. Anyone who captures a valid assertion could replay it directly to the backend until it expires. Trust comes from these layers together:

1. **Network control.** The backend listens on `127.0.0.1` only. In production this becomes a private subnet or security group that allows only gateway nodes, ideally with mTLS.
2. **Channel credential (Stage A).** Basic-auth endpoint security configured in the Publisher. The backend rejects any request without it.
3. **Signed identity (Stage B).** The backend JWT tells the backend *which application* is calling, so it can make per-account decisions. The backend verifies the signature, the expiry and the key type, and rejects anything unsigned or expired.

**Verification steps before writing any backend JWT code** 🔎:
- Enable backend JWT generation in `deployment.toml`. The `[apim.jwt]` section is the expected starting point, but confirm the key names against the 4.7.0 docs.
- Send one call through the gateway and capture the raw header with a temporary debug endpoint. Then record:
  - the header name
  - the encoding
  - the signing algorithm
  - how the signing key is published (certificate vs JWKS)
  - the exact claim names (application ID / name, key type, subscriber)
- Remove the debug endpoint and write the verifier against the **observed** format, not the documented one.

**Fallback if backend JWT can't be made to work within its time box (3 h):** have the gateway add an `X-LHB-App` header from the request context through a mediation policy, and strip any client-supplied copy. This is protected only by the Basic-auth channel and loopback binding. It's weaker, and we'll say so if asked.

### 4.3 Consent model: what this POC proves and what it doesn't

**Critical message for the panel.** OAuth2 *client credentials* authenticate the **fintech application**, not the **bank customer**. In this POC, "consent" is a static list in the backend of which accounts each partner app may see. That list stands in for consents that a real system would capture from customers.

| This POC proves | This POC does **not** prove |
|---|---|
| Only registered, subscribed partner apps can call the APIs | That a specific customer authorized a specific partner |
| A partner app cannot read accounts outside its allowed list (BOLA blocked) | Consent capture, consent expiry or revocation by the customer |
| Scopes limit which operations an app can perform | Strong customer authentication (SCA) |
| The backend independently enforces authorization | Compliance with any specific open-banking standard |

**Production path (roadmap slide, not built):** authorization-code + PKCE grant, where the customer logs in at the bank and approves a consent with its scope, accounts and expiry. The token then carries the end-user identity, and the backend checks it against a consent-management service. Customers can revoke consent, and partners use mTLS or private-key-JWT client authentication (FAPI-style).

### 4.4 Platform & operational guardrails

| Guardrail | Rule |
|---|---|
| **Secrets** | Consumer secrets, passwords and keystores are never committed. Scripts read `LHB_CLIENT_ID` / `LHB_CLIENT_SECRET` from the environment. `.gitignore` covers `*.jks`, `*.env`, `snapshots/` |
| **Default credentials** | Change `admin/admin` before any demo. Create separate users: `lhb_publisher` (Publisher only), `paylanka_dev`, `quickbudget_dev` (subscriber only) |
| **Network exposure** | Localhost only. No port forwarding and no public tunnels |
| **TLS in demo** | WSO2's self-signed certificate means `curl -k` locally only. Production uses CA-issued certificates |
| **Logging hygiene** | No wire or debug logs during the demo, because they can print bearer tokens. The backend masks account numbers and never logs auth headers |
| **macOS quarantine** | Don't clear quarantine by default. Only if macOS actually blocks execution, and only after confirming the archive came from WSO2's official site (check the checksum if one is published). Then clear it for that directory only |
| **Change control** | Back up the original `deployment.toml`. Record every change in `wso2/deployment.toml.patch`. **Snapshot WSO2 after each working milestone** (`snapshot.sh` stops the server and tars the install), so any failed Stage B experiment can be rolled back in minutes |
| **Feature freeze** | Stage A is frozen once it passes. Stage B work happens only on top of a snapshot. Nothing new goes in during the 48 h before the panel |
| **Backend hardening** | Only the `health` actuator endpoint is exposed. `server.error.include-stacktrace=never`. No-store cache headers |

---

## 5. API design

| Method | Resource | Scope (Stage B) | Description |
|---|---|---|---|
| GET | `/accounts/{accountId}` | `accounts:read` | Account summary (masked number, type, currency, balance) |
| GET | `/accounts/{accountId}/transactions` | `transactions:read` | Paged history (`?fromDate&toDate&limit≤50`) |
| GET | `/health` | none, not published | Liveness check for scripts |

- **Fictional data:** apps `PayLanka Wallet` (ACC-1001, ACC-1002) and `QuickBudget` (ACC-2001). About 6 accounts with about 10 deterministic transactions each.
- **Errors** use one shape, `{code, message, correlationId}`, with no stack traces or internal hosts.
- An account the app isn't allowed to see returns **404**, so partners can't enumerate which accounts exist.
- `v2.0.0` (Stage B) adds `availableBalance`, and v1 is then marked Deprecated.

---

## 6. Implementation plan: staged

### Stage A: Essential demonstration (target ≈ 12–18 h)

The goal is a complete, persuasive demo that would be enough to present on its own.

| Step | Work | Exit criteria |
|---|---|---|
| A1 | **Environment.** Back up `deployment.toml`. Set `JAVA_HOME` to JDK 21. Run `bin/api-manager.sh`. Open all three portals. Change the admin password and create users | All three portals open. Default password gone. **Snapshot #1** |
| A2 | **Backend.** Spring Boot project with the two endpoints, validation, safe errors, masking, and Basic-auth requirement. OpenAPI spec. Unit tests | `./mvnw test` passes. `curl` with the credential returns data, and without it returns 401 |
| A3 | **Publish.** Import the OpenAPI spec into the Publisher. Endpoint `http://127.0.0.1:8080` with Basic-auth endpoint security. Deploy a revision and publish | Call through `:8243` succeeds. Direct call without the credential returns 401 |
| A4 | **Onboard.** In the Dev Portal, as `paylanka_dev`: create the app, subscribe, and generate production keys | Subscription visible. Time-to-first-call measured |
| A5 | **Secure.** Get a token with `get-token.sh`, then test with no token, a tampered token, a valid token, and an unsubscribed app | T3, T4, T7a, T10, T12 pass |
| A6 | **Throttle.** In the Admin Portal, create the `LHB-Partner-Demo` tier with a low quota and burst control. Apply it to QuickBudget's subscription. Run `throttle-test.sh` | T11 passes. **Snapshot #2 (Stage A frozen)** |

### Stage B: Advanced banking proof (target ≈ 8–14 h)

Each item is time-boxed. If it fails its time box, drop it and say so honestly in the demo rather than presenting it half-built.

| Step | Work | Time box | Depends on |
|---|---|---|---|
| B1 | Resource-level scopes in the Publisher. Test insufficient scope | 2 h | A |
| B2 | 🔎 Verify backend JWT format and claims (§4.2), then implement the verifier | 3 h | A |
| B3 | Application → account authorization (BOLA protection) and audit log | 2 h | B2, or the §4.2 fallback |
| B4 | Version v2.0.0 and deprecate v1. Lifecycle walkthrough | 1.5 h | A |
| B5 | Threat-protection policies and CORS | 1.5 h | A |
| B6 | Observability: gateway logs plus backend audit log. Analytics only if verified | 1.5 h | B3 |
| B7 | *(Optional)* 🔎 Subscription approval workflow. Evaluate how 4.7.0 configures it; include only if it works reliably | 2 h | A |
| B8 | *(Optional)* 🔎 mTLS | 2 h | A |

B2 comes before B3 because the account-level check needs to know *which application* is calling, and the verified JWT (or the fallback header) supplies that.

### Stage C: Panel package (≈ 5–7 h)

- Discovery questions, value map (§2), the discovery → demo mapping (§2.1), architecture slide, and the "proves / doesn't prove" consent slide (§4.3).
- A 28-minute demo script with one variant per persona from §2.1. Objection handling: competitor comparison, on-prem vs SaaS, open-banking consent, scale/HA, and total cost.
- Proposed next step: a 2–4 week on-site POC against the bank's non-production endpoints, with agreed success metrics.
- Two timed rehearsals, plus a backup video and screenshots.

**Total estimate: about 25–39 h, plus a 20% contingency for installation and configuration troubleshooting.** This is a planning estimate, not a measurement. If time runs short, cut Stage B items from the bottom up. Stage A plus Stage C alone is still a complete submission.

---

## 7. Test matrix (success criteria)

| # | Scenario | Expected | Stage |
|---|---|---|---|
| T1 | API discoverable in Dev Portal | `LHB Accounts v1` visible | A |
| T2 | Partner subscribes | Subscription created (or approved, if B7 is done) | A |
| T3 | No token | **401** | A |
| T4 | Invalid / tampered token | **401** | A |
| T5 | Expired token | **401** | A |
| T6 | Token lacks the required scope | **403** | B |
| T7a | Valid token, subscribed app | **200**, masked data | A |
| T7b | Valid token, allowed account | **200** | B |
| T8 | **Cross-account (BOLA) attempt:** PayLanka → ACC-2001 | **404** plus audit entry | B |
| T9 | Invalid input (`/accounts/1;DROP`, `limit=10000`) | **400** | A |
| T10 | Direct backend call bypassing the gateway | **401** | A |
| T11 | **Throttling:** a scripted burst of requests well above the quota inside the policy window | **At least one 429** within the window, and recovery after the window | A |
| T12 | Unsubscribed application | Rejected (401/403) | A |
| T13 | Version / lifecycle change | v2 published, v1 shown as Deprecated | B |
| T14 | No leakage | No stack traces, internal hosts or tokens in responses or logs | A |

Notes:
- **T11 is deliberately non-deterministic.** WSO2's rate limiting is evaluated asynchronously, so the first 429 may arrive one or two calls after the exact quota. The test passes when the quota is exceeded and a 429 appears within the window. It does not require exactly N successes.
- Exact WSO2 error codes (for example 900901/900902/900910/900800) are recorded during testing and then used in the script. They are not assumed in advance 🔎.
- `security-tests.sh` prints PASS/FAIL per test. Run it after every config change and right before the demo.

---

## 8. Demo plan (28 min)

Default order (reorder per §2.1): **3 context · 7 onboarding · 8 security · 6 traffic · 3 governance · 1 close.**
Each segment ends by naming the business outcome it supports: *protect*, *operate*, or *enable*.

Demo-day guardrails:
- **The day before:** restore the latest snapshot, then run `security-tests.sh` and get all PASS. Refresh the screenshots.
- **One hour before:** start WSO2 and the backend, generate fresh tokens, and pre-open tabs in demo order.
- Never show a consumer secret on screen. Use 18pt or larger terminal fonts and Do Not Disturb.
- **Fallback ladder:** live → recorded video → screenshots.

---

## 9. Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Backend JWT format differs from docs | Medium–High | B2 verifies first. 3 h time box. Header fallback (§4.2) |
| Approval workflow config differs in 4.7.0 | Medium | Optional (B7). Not in the core demo |
| Throttle doesn't trip exactly at quota | High | Non-deterministic T11 criterion. Burst control |
| A Stage B change breaks the working demo | Medium | Snapshots, feature freeze, `reset-demo.sh` |
| Panel conflates app auth with customer consent | Medium | Explicit §4.3 slide. Raise it proactively, before they ask |
| Overrunning the time estimate | Medium–High | Staged plan. Cut Stage B from the bottom |
| Port conflicts / slow WSO2 start | Medium | Check ports in `start-all.sh`. Start early |
| Token expires mid-demo | Medium | Refresh before the security segment |

---

## 10. Milestone tracker

**Stage A: Essential demonstration**
- [ ] A1 WSO2 starts, all three portals open, admin password changed
- [ ] A2 Mock banking API works and tests pass
- [ ] A3 API published, and calls work through the gateway only
- [ ] A4 Partner subscribes through the Dev Portal (time measured)
- [ ] A5 Authorized vs unauthorized access demonstrated
- [ ] A6 Throttling (429) demonstrated, Stage A frozen

**Stage B: Advanced banking proof**
- [ ] B1 Resource scopes
- [ ] B2 Backend JWT verified and implemented (or the fallback adopted)
- [ ] B3 Account-level authorization and audit log
- [ ] B4 Versioning and lifecycle
- [ ] B5 Threat protection
- [ ] B6 Observability verified
- [ ] B7 / B8 Optional: approval workflow, mTLS

**Stage C: Panel**
- [ ] C1 Value map and discovery → demo variants
- [ ] C2 Demo script and objection handling
- [ ] C3 Two timed rehearsals, backup video recorded

---

## 11. Immediate next step

**A1:** back up `deployment.toml`, start WSO2 from its current location, and confirm that the Publisher, Developer Portal and Admin Portal open. Only then build the backend and connect one API end to end.

Step-by-step commands and checks for every phase: [EXECUTION_PLAN.md](EXECUTION_PLAN.md).

---

## 12. Change log

**v2 (2026-10-13):** revised after external review (scored 8.3/10, "proceed with revisions").
- Staged into A (essential), B (advanced) and C (panel), with a feature freeze and snapshots.
- Added §2 business value metrics and the discovery → demo mapping.
- Consent: added a "proves / doesn't prove" table and a production roadmap.
- Gateway trust: clarified that network control plus channel credential make the boundary, and the JWT supplies identity. Added a verification-first step and a fallback.
- Approval workflow and mTLS moved to optional. Unverified items marked 🔎.
- T11 throttling made non-deterministic. WSO2 error codes are recorded during testing, not assumed.
- Estimate revised from 11–20 h to about 25–39 h plus 20% contingency.
- macOS quarantine removal is conditional, not a default step.

**v1 (2026-10-13):** initial plan.
