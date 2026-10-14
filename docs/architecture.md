# Architecture — as built (2026-10-14)

Fictional bank, partners and data. Single macOS laptop, WSO2 API Manager 4.7.0 all-in-one, Spring Boot 4.1.1 backend.

```
 Fintech partner apps (fictional)                 WSO2 API Manager 4.7.0 (all-in-one, localhost)
 ┌──────────────────────────┐   1 token     ┌──────────────────────────────────────────────────────┐
 │ PayLanka Wallet          │──────────────►│ Key Manager  /oauth2/token  (client credentials, JWT) │
 │  accounts + transactions │               │   scopes issued only if owner holds the bound role   │
 │ QuickBudget              │               ├──────────────────────────────────────────────────────┤
 │  accounts only, 5/min    │   2 API call  │ Gateway :8243 (HTTPS only)                           │
 │ RogueApp (no subscription)│─────────────►│  ① token signature/expiry   ② subscription          │
 └──────────────────────────┘ Bearer JWT    │  ③ resource scope           ④ quota (LHBPartnerDemo) │
                                            │  ⑤ SQL-injection regex      ⑥ CORS (partner origin) │
         Developer Portal  /devportal ◄─────│  mints X-JWT-Assertion (RS256, 900 s)                │
         Publisher         /publisher       ├──────────────────────────────────────────────────────┤
         Admin Portal      /admin           │ Traffic Manager (Siddhi, 1-min windows) ◄─ localhost │
                                            └───────────────────────┬──────────────────────────────┘
                                                                    │ 3 Basic credential + X-JWT-Assertion
                                                                    ▼
                                            ┌──────────────────────────────────────────────────────┐
  direct call (no gateway) ─ ─ ─ ─ ─ ─ ─ ─ ►│ Mock core banking — Spring Boot  127.0.0.1:8080      │
                         401                │  ⑦ Basic credential   ⑧ verify gateway JWT (iss, exp,│
                                            │     keytype, signature)  ⑨ re-check scope            │
                                            │  ⑩ consent: application → accounts (BOLA) → 404      │
                                            │  ⑪ validation, masked data, safe errors              │
                                            │  ⑫ audit.log (JSON) + access log, correlation IDs    │
                                            └──────────────────────────────────────────────────────┘
```

## APIs

| API | Gateway URL | Backend | State |
|---|---|---|---|
| LHB-Accounts v1 | `https://localhost:8243/lhb/accounts/v1/...` | `http://127.0.0.1:8080/accounts/...` | **Deprecated** (existing subscribers only) |
| LHB-Accounts v2 | `https://localhost:8243/lhb/accounts/v2/...` | `http://127.0.0.1:8080/v2/accounts/...` | **Published** (adds `availableBalance`) |

Resources: `GET /accounts/{accountId}` (`accounts:read`), `GET /accounts/{accountId}/transactions` (`transactions:read`).

## Partners (all fictional)

| App | Developer | Subscription / tier | Scopes it can obtain | Consented accounts |
|---|---|---|---|---|
| PayLanka Wallet | paylanka_dev | v1+v2, Unlimited | accounts:read, transactions:read | ACC-1001, ACC-1002 |
| QuickBudget | quickbudget_dev | v1+v2, LHBPartnerDemo (5/min) | accounts:read | ACC-2001 |
| RogueApp | rogue_dev | none | accounts:read | none |

## Request flow (happy path)

1. Partner gets a JWT access token (client credentials). WSO2 drops scopes the owner is not entitled to.
2. Gateway checks ①–⑥, then forwards with the endpoint Basic credential and a signed `X-JWT-Assertion` naming the application.
3. Backend checks ⑦–⑪, writes an audit line, returns masked data. The partner's `X-Correlation-ID` is preserved end to end (`scripts/trace.sh`).

## Configuration as code

| Script | What it configures |
|---|---|
| `publish-api.sh` | v1 import from OpenAPI, HTTPS only, Basic endpoint security, deploy, publish |
| `onboard-partner.sh` | Developer Portal app, subscription, production keys (secrets to `~/.lhb-poc.env`) |
| `setup-throttling.sh` | Admin policy LHBPartnerDemo, business plan, QuickBudget subscription |
| `setup-scopes.sh` | Entitlement role, scope bindings, per-operation scopes |
| `release-v2.sh` | v2 copy, definition, scope re-apply, publish, deprecate v1 |
| `setup-threat-protection.sh` | regexPolicy on v2, API-level CORS |
| `reset-demo.sh` | Restore snapshot → start → warm up → verify (READY) |
| `security-tests.sh`, `throttle-test.sh` | 44 security checks (with `--slow`), quota test |

WSO2 `deployment.toml` changes (secrets omitted): `wso2/deployment.toml.patch`. Verified product behaviour and findings: `wso2/README.md`.

## Production differences (roadmap, not built)

- Customer consent via authorization code + PKCE and a consent service, instead of the static registry.
- Backend in a private network reachable only from gateway nodes; mTLS gateway → backend; secrets in WSO2 Secure Vault.
- Distributed deployment: multiple gateways, separate key manager / traffic manager, HA databases instead of embedded H2.
- Burst control for instant spike protection; analytics (e.g. ELK/Moesif/Choreo) for dashboards.
- CA-issued certificates (the POC uses WSO2's self-signed `localhost` certificate).
