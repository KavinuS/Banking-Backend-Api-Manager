# Execution Runbook: Lanka Horizon Bank API POC

This document carries out [PROJECT_PLAN.md](PROJECT_PLAN.md) (v2) step by step.
Each phase lists **goal → prerequisites → steps → verify → close-out**. Do not start a phase until the previous phase's *close-out* is done.

> 🔎 = WSO2 4.7.0 detail not yet confirmed on this machine. When you hit one, check the real UI/log, then write what you found in `wso2/README.md`.
> All commands assume macOS zsh. Secrets go in environment variables only, never in files that are committed.

---

## Overview

| Phase | Plan step | Goal | Est. | Gate (must be true to continue) |
|---|---|---|---|---|
| P0 | — | Workspace, git, tooling | 0.5–1 h | Repo initialised, tools present |
| P1 | A1 | WSO2 running, portals open, hardened | 1.5–2.5 h | 3 portals + new admin password · **Snapshot #1** |
| P2 | A2 | Mock banking backend | 3–4 h | `./mvnw test` green, direct call needs credential |
| P3 | A3 | API published through gateway | 1.5–2.5 h | Gateway call reaches backend |
| P4 | A4 | Partner onboarding | 1–1.5 h | Apps, subscriptions, keys exist; time measured |
| P5 | A5 | Core security tests | 2–3 h | T3, T4, T5, T7a, T9, T10, T12, T14 pass |
| P6 | A6 | Throttling + **Stage A freeze** | 1.5–2 h | T11 passes · **Snapshot #2** · git tag `stage-a` |
| P7 | B1 | Resource scopes | ≤ 2 h | T6 passes |
| P8 | B2 | Backend JWT (verify → implement) | ≤ 3 h | Backend identifies calling app (or fallback adopted) |
| P9 | B3 | Account-level authorization + audit | ≤ 2 h | T7b, T8 pass · **Snapshot #3** |
| P10 | B4 | Versioning & lifecycle | ≤ 1.5 h | T13 passes |
| P11 | B5–B6 | Threat protection, observability | ≤ 3 h | Policies active, audit trail demonstrable · **Snapshot #4** |
| P12 | B7–B8 | *Optional:* approval workflow, mTLS | ≤ 4 h | Only kept if 100 % reliable |
| P13 | C1–C2 | Panel package | 3–4 h | Deck, script, objections done |
| P14 | C3 | Rehearse & freeze | 2–3 h | 2 timed runs, backup video |

**Suggested sessions:** S1 = P0–P1 · S2 = P2 · S3 = P3–P5 · S4 = P6 (+ start P7) · S5 = P7–P9 · S6 = P10–P12 · S7 = P13 · S8 = P14 (≥ 48 h before panel).
If you are behind schedule after S4, skip from P12 upward and go straight to P13. Stage A + C is a complete submission.

---

## P0: Workspace preparation

**Goal:** a clean, version-controlled project with the tools in place.

**Steps**
1. Create the structure:
   ```bash
   cd ~/Documents/Amma/Api-Manager
   mkdir -p banking-backend wso2 postman scripts snapshots
   git init
   ```
2. Create `.gitignore`:
   ```
   snapshots/
   *.jks
   *.p12
   *.env
   .env*
   postman/*environment*.json
   banking-backend/target/
   .idea/
   .DS_Store
   ```
3. Tooling check (install only what's missing):
   ```bash
   java -version            # expect 21
   command -v jq || brew install jq
   command -v curl
   ```
   Postman is optional. Every test also exists as a curl script.
4. Create `~/.lhb-poc.env` (**outside** the repo, `chmod 600`). It will hold `LHB_*` secrets later. Load it with `source ~/.lhb-poc.env`.

**Verify:** `git status` shows only intended files.
**Close-out:** first commit "P0: project skeleton".

---

## P1 (A1): WSO2 environment

**Goal:** WSO2 4.7.0 running locally with the defaults hardened.
**Prereq:** P0.

**Steps**
1. Set variables (add the first two to `~/.zshrc` for convenience):
   ```bash
   export WSO2_HOME=~/Downloads/wso2am-4.7.0
   export JAVA_HOME=$(/usr/libexec/java_home -v 21)
   ```
2. Back up the config **before any edit**:
   ```bash
   cp $WSO2_HOME/repository/conf/deployment.toml $WSO2_HOME/repository/conf/deployment.toml.orig
   ```
3. Check that ports are free (no output = good):
   ```bash
   lsof -nP -iTCP:9443 -iTCP:8243 -iTCP:8280 -iTCP:9763 -sTCP:LISTEN
   ```
4. Start in the foreground the first time so you see errors:
   ```bash
   $WSO2_HOME/bin/api-manager.sh
   ```
   Wait for the startup-complete lines that print the console URLs. 🔎 Note the printed URLs and ports in `wso2/README.md`.
   *If macOS blocks execution:* only then, after confirming the archive came from wso2.com, run `xattr -dr com.apple.quarantine $WSO2_HOME`.
5. Open the portals (accept the self-signed certificate warning once): `https://localhost:9443/publisher`, `/devportal`, `/admin`, `/carbon`. Log in with `admin/admin`.
6. **Harden:**
   - Change the admin password. 🔎 Use the Carbon console user-management screen (Users and Roles → Users → admin → Change Password). Changing `[super_admin]` in `deployment.toml` after first start may not update an existing user store, so prefer the UI.
   - Create users:
     | User | Roles |
     |---|---|
     | `lhb_publisher` | `Internal/creator`, `Internal/publisher` |
     | `paylanka_dev` | `Internal/subscriber` |
     | `quickbudget_dev` | `Internal/subscriber` |
     | `rogue_dev` | `Internal/subscriber` (used for negative tests) |
   - Store the passwords in your password manager, not in the repo.
7. Write `scripts/start-all.sh` / `stop-all.sh` (WSO2: `api-manager.sh start|stop`, log at `$WSO2_HOME/repository/logs/wso2carbon.log`; backend added in P2).
8. Write `scripts/snapshot.sh <label>`:
   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   "$WSO2_HOME/bin/api-manager.sh" stop || true
   sleep 15
   tar -czf "snapshots/wso2-$1-$(date +%Y%m%d-%H%M).tgz" -C "$(dirname "$WSO2_HOME")" "$(basename "$WSO2_HOME")"
   ```
   and `scripts/restore.sh <file>` (stop, move the current install aside, untar).

**Verify**
- [ ] All four URLs load.
- [ ] `admin/admin` no longer works, and the new password does.
- [ ] `lhb_publisher` can log in to the Publisher but **not** the Admin Portal.
- [ ] `paylanka_dev` can log in to the Dev Portal only.

**Close-out:** `scripts/snapshot.sh A1` (**Snapshot #1**). Commit "P1: WSO2 baseline".
**Troubleshooting:** port in use → `lsof` shows the PID. Startup errors about Java → check `echo $JAVA_HOME`. Slow first start is normal (a few minutes).

---

## P2 (A2): Mock core banking backend

**Goal:** Spring Boot API on `127.0.0.1:8080` that requires a gateway credential, validates input, masks data, and hides internals.
**Prereq:** P0. Can run in parallel with P1.

**Steps**
1. Generate the project:
   ```bash
   cd ~/Documents/Amma/Api-Manager
   curl -s https://start.spring.io/starter.zip \
     -d type=maven-project -d language=java -d javaVersion=21 \
     -d groupId=com.lhb -d artifactId=banking-backend -d name=banking-backend \
     -d packageName=com.lhb.banking \
     -d dependencies=web,validation,security,actuator \
     -o /tmp/banking-backend.zip
   unzip -q /tmp/banking-backend.zip -d . && rm /tmp/banking-backend.zip
   ```
2. Implement (package `com.lhb.banking`):
   | Class | Responsibility |
   |---|---|
   | `model/Account`, `model/Transaction` (records) | Fictional data shape |
   | `repo/InMemoryBankRepository` | ~6 accounts (`ACC-1001`…), ~10 deterministic transactions each |
   | `api/AccountController` | `GET /accounts/{accountId}`, `GET /accounts/{accountId}/transactions?fromDate&toDate&limit` |
   | `api/dto/*` | Response DTOs: masked number `****1001`, no names on transactions |
   | `api/ApiExceptionHandler` | `{code, message, correlationId}` for 400/401/404/500, no stack traces |
   | `config/SecurityConfig` | HTTP Basic, single user from env `LHB_GW_USER` / `LHB_GW_PASS`, stateless, CSRF off (API only), `/actuator/health` open |
   | `config/CorrelationIdFilter` | Reuse an incoming correlation header or generate a UUID. Put it in the MDC and echo it in the response |
   Validation: `@Pattern(regexp="^ACC-\\d{4}$")` on `accountId`. `limit` is `@Min(1) @Max(50)`, and `fromDate ≤ toDate`.
3. `src/main/resources/application.yml`:
   ```yaml
   server:
     address: 127.0.0.1
     port: 8080
     error:
       include-stacktrace: never
       include-message: never
   management:
     endpoints.web.exposure.include: health
   spring.application.name: lhb-core-banking-mock
   ```
4. Write `src/main/resources/openapi/lhb-accounts-v1.yaml` (OpenAPI 3.0): two GET operations, path parameter pattern, query parameter limits, response schemas, and the error schema. No `securitySchemes` needed (WSO2 adds OAuth2).
5. Tests (`@WebMvcTest` or `@SpringBootTest` + MockMvc):
   - no credential → 401
   - valid credential + `ACC-1001` → 200, masked number
   - `ACC-9999` → 404
   - `abc` → 400
   - `limit=1000` → 400
   - error body contains no `trace` or `exception`
6. Add the backend start and stop commands to `start-all.sh` / `stop-all.sh` (`./mvnw spring-boot:run` in the background, logging to `banking-backend/backend.log`).

**Verify**
```bash
cd banking-backend && ./mvnw test
source ~/.lhb-poc.env && ./mvnw spring-boot:run &
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/accounts/ACC-1001                         # 401
curl -s -u "$LHB_GW_USER:$LHB_GW_PASS" http://127.0.0.1:8080/accounts/ACC-1001 | jq                       # 200, masked
curl -s -u "$LHB_GW_USER:$LHB_GW_PASS" -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/accounts/x  # 400
```
**Close-out:** commit "P2: mock banking backend".

---

## P3 (A3): Publish the API through WSO2

**Goal:** partner traffic goes `:8243` → backend. No other path works.
**Prereq:** P1, P2.

**Steps** (logged in to the Publisher as `lhb_publisher`):
1. **Create API → Import OpenAPI → Upload** `lhb-accounts-v1.yaml`.
   Name `LHB-Accounts`, context `/lhb/accounts`, version `v1`, endpoint `http://127.0.0.1:8080`.
2. **Endpoints → Endpoint Security** (Production *and* Sandbox): Basic, with `LHB_GW_USER` / `LHB_GW_PASS`. 🔎 Confirm the menu location in 4.7.0.
3. **Runtime / Transports:** HTTPS only. 🔎 Confirm the option exists, then untick HTTP.
4. **Business Plans / Subscriptions:** select `Unlimited` for now (the demo tier comes in P6).
5. **Resources:** confirm both GET operations were imported. Leave security as "OAuth2", with no scopes yet.
6. Add a description, a tag `banking`, and documentation ("Getting started for partners"). This content is shown during onboarding.
7. **Deployments → Deploy New Revision** → default gateway.
8. **Lifecycle → Publish.**

**Verify**
- [ ] The API shows as *Published* in the Publisher.
- [ ] The gateway log shows the API deployed with no errors.
- [ ] `curl -sk -o /dev/null -w '%{http_code}\n' https://localhost:8243/lhb/accounts/v1/accounts/ACC-1001` returns **401** (gateway is up, token required). A 404 here means a context or version mistake.

**Close-out:** export the API definition from the Publisher (Download or 🔎 `apictl export`) into `wso2/` for the record. Commit "P3: API published".

---

## P4 (A4): Partner onboarding

**Goal:** a partner self-onboards. Time-to-first-call is measured, because it's a business metric (§2 of the plan).
**Prereq:** P3.

**Steps**
1. **Start a stopwatch.**
2. Dev Portal as `paylanka_dev`: find **LHB-Accounts** by browsing or searching for the tag `banking`. Read the docs.
3. **Applications → Add New Application** `PayLanka Wallet` (app quota: Unlimited).
4. **Production Keys:** enable the *Client Credentials* grant, then **Generate Keys**. Copy the key and secret straight into `~/.lhb-poc.env` as `LHB_PAYLANKA_ID` / `LHB_PAYLANKA_SECRET`.
5. **Subscribe** `PayLanka Wallet` → `LHB-Accounts v1` (Unlimited).
6. Get a token and call the API (commands in P5). **Stop the stopwatch at the first 200.** Record the time in `docs/value-map.md`.
7. Repeat for `quickbudget_dev` → app `QuickBudget` (keys only for now; it gets subscribed in P6 on the demo tier).
8. As `rogue_dev`: app `RogueApp` with keys, **no subscription** (used for T12).

**Verify:** T1 and T2 pass. Three apps exist, and only PayLanka is subscribed.
**Close-out:** commit "P4: onboarding" (no secrets).

---

## P5 (A5): Core security tests

**Goal:** an automated, repeatable proof of security enforcement.
**Prereq:** P4.

**Steps**
1. `scripts/get-token.sh <APP>` (reads `LHB_<APP>_ID` / `LHB_<APP>_SECRET`, takes optional scopes):
   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   source ~/.lhb-poc.env
   app=$(echo "$1" | tr a-z A-Z); scope=${2:-}
   id_var="LHB_${app}_ID"; sec_var="LHB_${app}_SECRET"
   curl -sk -u "${!id_var}:${!sec_var}" \
     -d grant_type=client_credentials ${scope:+-d "scope=$scope"} \
     https://localhost:9443/oauth2/token | jq -r .access_token
   ```
   🔎 If `/oauth2/token` on 9443 doesn't respond, check the token endpoint shown in the Dev Portal's key screen.
2. `scripts/security-tests.sh`: a helper `expect <name> <expected-code> <curl args…>` that prints `PASS`/`FAIL` with the actual code, and exits non-zero on any failure. Cases:
   | Test | Call | Expect |
   |---|---|---|
   | T3 | no `Authorization` header | 401 |
   | T4 | `Bearer abc.def.ghi` and a real token with one changed character | 401 |
   | T5 | token from an app whose token expiry is set to 60 s (🔎 Dev Portal key settings), after `sleep 70` | 401 |
   | T7a | PayLanka token → `ACC-1001` | 200 |
   | T9 | `/accounts/1%3BDROP`, `?limit=10000` | 400 |
   | T10 | direct `http://127.0.0.1:8080/accounts/ACC-1001`, no credential | 401 |
   | T12 | RogueApp token → `ACC-1001` | 401/403 (record which) |
   | T14 | grep every error body for `trace`, `Exception`, `127.0.0.1`, `localhost` | none found |
   T5 is slow, so put it behind a `--slow` flag.
3. Run the suite. For every rejection, **record the real WSO2 error code and message** in `wso2/README.md`, then tighten the script to assert them.
4. Build the matching Postman collection (optional, for a visual demo). Keep the environment template free of values.

**Verify:** `scripts/security-tests.sh` is all PASS, twice in a row.
**Close-out:** commit "P5: security test suite".

---

## P6 (A6): Throttling + Stage A freeze

**Goal:** a reproducible 429 that protects the core, then lock in a working demo.
**Prereq:** P5.

**Steps**
1. Admin Portal (as admin) → **Rate Limiting Policies → Subscription Policies → Add**:
   `LHB-Partner-Demo`, request count **5 per 1 minute**. Leave burst control off for the first test (it gives a different 429 and makes the result harder to explain). Try it separately afterwards.
2. Publisher → LHB-Accounts → **Business Plans**: tick `LHB-Partner-Demo` → Save → **deploy a new revision**.
3. Dev Portal as `quickbudget_dev`: subscribe `QuickBudget` → LHB-Accounts with `LHB-Partner-Demo`.
4. `scripts/throttle-test.sh`:
   - Get a QuickBudget token, fire 15 requests quickly, and print each status code.
   - **PASS if at least one 429 appears.** Print how many 200s came before the first 429.
   - `sleep 65`, call once more, and expect 200 (recovery).
   - Also count the backend log lines for the run, to show the backend was protected from the excess requests.
5. Run it three times and note the variance (for example "first 429 at call 6–8"). That's your honest answer if the panel asks.

**Verify:** T11 passes three runs out of three.

**Close-out: Stage A freeze**
- [ ] `security-tests.sh` + `throttle-test.sh` all PASS
- [ ] `scripts/snapshot.sh stage-a` (**Snapshot #2**)
- [ ] `git commit` + `git tag stage-a`
- [ ] Write a 1-page `docs/demo-script.md` draft for Stage A only. **You now have a presentable demo.**

---

## P7 (B1): Resource-level scopes (time box 2 h)

**Prereq:** Stage A frozen.
1. Publisher → LHB-Accounts → **Local Scopes**: create `accounts:read` and `transactions:read`. Bind them to role `Internal/subscriber` (🔎 how role binding affects client-credentials tokens: scopes are issued based on the app owner's roles).
2. **Resources:** `GET /accounts/{accountId}` → `accounts:read`. `GET …/transactions` → `transactions:read`.
3. Deploy a new revision.
4. Update `get-token.sh` callers to request scopes explicitly. Tokens without a requested scope get only `default` and will now be rejected. **Update T7a accordingly.**
5. Add **T6**: token requested with only `accounts:read` → `/transactions` → expect **403**.

**Verify:** the full suite passes, including T6. **Close-out:** commit "P7: scopes".

---

## P8 (B2): Backend JWT, verify first (time box 3 h)

1. **Enable** backend JWT in `deployment.toml` (record the diff in `wso2/deployment.toml.patch`):
   ```toml
   [apim.jwt]
   enable = true
   ```
   🔎 Check the 4.7.0 docs for additional keys (encoding, header name, signing algorithm) before adding them. Restart WSO2.
2. **Observe:** temporarily log the names of incoming headers in the backend (`local-dev` profile only). Make one gateway call. Capture the JWT-looking header and decode it:
   ```bash
   echo "$JWT" | cut -d. -f1 | base64 -d 2>/dev/null | jq   # header: alg, kid, x5t
   echo "$JWT" | cut -d. -f2 | base64 -d 2>/dev/null | jq   # claims
   ```
   (Base64url padding may need fixing. Use `jq -R 'split(".")[1] | @base64d'` if `base64 -d` complains.)
3. **Record** in `wso2/README.md`:
   - the header name
   - `alg`
   - the key ID / thumbprint
   - the exact claim names for application ID or name, key type and subscriber
   - the expiry
4. **Get the verification key:** export the gateway's public certificate (`keytool -exportcert -rfc -alias wso2carbon -keystore $WSO2_HOME/repository/resources/security/wso2carbon.jks -file banking-backend/src/main/resources/wso2-gateway.pem` 🔎 alias and password), or use JWKS if the header carries a `kid` matching `https://localhost:9443/oauth2/jwks`.
5. **Remove the header logging.** Implement `GatewayJwtFilter`:
   - Verify the signature with Nimbus (`com.nimbusds:nimbus-jose-jwt`).
   - Check `exp`, and require the key type `PRODUCTION`.
   - Put the application identity in the request context.
   - Any missing or invalid assertion → 401.
6. Unit-test with a locally generated RSA key pair: valid → passes, tampered → 401, expired → 401.

**If the 3 h box runs out:** take the fallback in PROJECT_PLAN §4.2 (a gateway mediation policy sets `X-LHB-App` and strips any client-supplied copy). Note the decision in the change log.
**Verify:** gateway calls pass. A direct call with Basic auth but no assertion → 401 (add as **T10b**).
**Close-out:** commit "P8: gateway identity".

---

## P9 (B3): Account-level authorization + audit (time box 2 h)

1. `ConsentRegistry`: `PayLanka Wallet → {ACC-1001, ACC-1002}`, `QuickBudget → {ACC-2001}`. Key it on the application ID observed in P8; the display name is only a readable label.
2. Before data access in the controller or service layer: if the account isn't allowed for this app → **404** (same body as a non-existent account).
3. `AuditLogger` (separate logger `AUDIT`, JSON lines):
   `{ts, correlationId, app, accountMasked, endpoint, outcome: ALLOWED|DENIED_NO_CONSENT|NOT_FOUND|INVALID}`. Never log tokens or full account numbers.
4. Tests: T7b (PayLanka → ACC-1001 → 200) and **T8** (PayLanka → ACC-2001 → 404 + `DENIED_NO_CONSENT` audit line).

**Verify:** full suite green. **Close-out:** `snapshot.sh B3` (**Snapshot #3**), commit.

---

## P10 (B4): Versioning & lifecycle (time box 1.5 h)

1. Backend: add `GET /v2/accounts/{accountId}` returning the v1 fields plus `availableBalance`. Keep v1 unchanged.
2. Publisher → LHB-Accounts v1 → **Create New Version** `v2`, endpoint `http://127.0.0.1:8080/v2`. Update the OpenAPI response schema. Deploy a revision, then Publish.
3. Lifecycle of v1 → **Deprecate**. Confirm that the Dev Portal shows v1 as deprecated, existing PayLanka subscriptions still work, and new subscriptions to v1 are blocked (🔎 confirm behaviour).
4. Screenshot the lifecycle history.

**Verify:** T13. **Close-out:** commit.

---

## P11 (B5–B6): Threat protection & observability (time box 3 h)

**B5: Threat protection**
1. Publisher → API → **Policies**: add the 🔎 regex threat-protection policy on the request flow (for example reject SQL/script patterns in path or query). JSON threat protection matters less for GET-only APIs, so add it only if you add a POST later.
2. **CORS:** allow only `https://app.paylanka.example`, methods `GET`, headers `Authorization, Content-Type`.
3. New tests: a malicious query string (`?limit=1%20OR%201=1`, `<script>`) → rejected at the **gateway** (record the code). A disallowed Origin's preflight gets no CORS allow header.

**B6: Observability**
1. Gateway: identify a per-API or access log that is useful without debug or wire logs (🔎 4.7.0 options). Document the `tail` command.
2. Backend: the audit log from P9 and the correlation ID returned in responses.
3. Demo trick: make a call, copy the correlation ID from the response, then `grep` it in the audit log to show end-to-end traceability.
4. Analytics: only if it can be configured offline and verified within this time box. Otherwise, it's a roadmap item.

**Close-out:** full suite green, `snapshot.sh B6` (**Snapshot #4**), commit + `git tag stage-b`.

---

## P12 (B7–B8, optional): Approval workflow, mTLS (time box 2 h each)

Only start if you're ahead of schedule. Work from Snapshot #4 and restore if anything breaks.
- **B7:** find out how 4.7.0 configures the subscription approval workflow (🔎 Admin Portal settings vs registry `workflow-extensions.xml`). Success = the new subscription shows as *ON_HOLD*, an admin approves it in Admin Portal → Tasks, and then the subscription works. Run it five times without failure before adding it to the demo.
- **B8:** mTLS gateway→backend, or client mTLS on the API. Present it as a roadmap slide unless it's fully reliable.

---

## P13 (C1–C2): Panel package

1. `docs/value-map.md`: concern → capability → evidence (test ID) → measured result (time-to-first-call, % rejected, 429 behaviour).
2. Deck (about 10–12 slides):
   1. Bank context and discovery questions
   2. Three outcomes (protect / operate / enable)
   3. Architecture (§3.1)
   4. Security layers
   5. **What this POC proves / doesn't prove**
   6. Demo agenda
   7. Production roadmap (consent, mTLS, HA, analytics)
   8. Proposed 2–4 week on-site POC with success criteria
3. `docs/demo-script.md`: the 28-minute default order plus one variant per persona (PROJECT_PLAN §2.1). Give every step a command or click path, its expected output, and the one sentence about the business outcome to say.
4. `docs/objection-handling.md`:
   - vs Kong/Apigee/Azure APIM
   - on-prem vs SaaS
   - open-banking consent
   - performance and HA
   - licensing and total cost
   - "what if the gateway goes down?"
   - "who maintains this?"
5. `scripts/reset-demo.sh`: restore the latest snapshot, start everything, wait for health, run `security-tests.sh`, and print READY.

**Close-out:** commit "P13: panel package".

---

## P14 (C3): Rehearse & freeze

1. Rehearsal 1 with a timer. Note every stumble and overrun, then fix the script (not the system).
2. Record a **backup video** (macOS `Cmd+Shift+5`) of a clean full run. Capture screenshots of every success state.
3. Rehearsal 2 using a *different* persona order.
4. **Freeze 48 h before the panel:** no config or code changes. Bug fixes only if a test fails, and only from a snapshot.
5. **Demo day:**
   - Restart the Mac. Prevent sleep for the whole session: `caffeinate -di` in a spare terminal.
   - `reset-demo.sh` → READY.
   - Generate fresh tokens just before the security segment.
   - Do Not Disturb, 18pt fonts, and tabs pre-opened in order.
   - Fallback ladder: live → video → screenshots.

---

## Phase checklist (copy into your tracker)

- [x] P0 Workspace
- [x] P1 WSO2 baseline · Snapshot #1
- [x] P2 Backend
- [x] P3 Published
- [x] P4 Onboarding (API-driven: ~2 s; UI time to be measured in rehearsal: ____)
- [x] P5 Security suite green (23 checks incl. slow expiry test)
- [x] P6 Throttling · Snapshot #2 · `stage-a` tag ← **minimum viable demo**
- [x] P7 Scopes
- [x] P8 Backend JWT (verified; no fallback needed)
- [x] P9 Account authorization + audit · Snapshot #3
- [x] P10 Versioning
- [ ] P11 Threat protection + observability · Snapshot #4 · `stage-b` tag
- [ ] P12 Optional extras
- [ ] P13 Panel package
- [ ] P14 Rehearsed, video recorded, frozen
