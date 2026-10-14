# Value Map — concern → capability → evidence

Measurements are from this POC (fictional data, single laptop). They show **capability**, not savings; business-case numbers require LHB's real baseline from discovery.

| Customer concern | WSO2 capability shown | Evidence (test / script) | Measured result |
|---|---|---|---|
| Slow fintech onboarding | Developer Portal self-service: discover, subscribe, keys | P4 `onboard-partner.sh`; live UI run | API-driven: ~2 s to first 200. **UI (human) time: ____ (measure in rehearsal)** |
| Exposing core banking is risky | OAuth2 at the gateway; subscription validation | T3, T4a–c, T5, T12 | 100 % of unauthenticated / forged / expired / unsubscribed calls rejected |
| Partners seeing too much | Resource scopes bound to bank-granted roles | T6a–d | Non-entitled partner **cannot obtain** `transactions:read` even when requesting it |
| One partner reading another's customers (BOLA) | Gateway-signed identity + backend consent check | T8a–e, `trace.sh` | Cross-partner reads → 404 identical to unknown account; every attempt audited |
| Bypassing the gateway | Loopback binding + endpoint credential + signed assertion | T10a–g | Direct calls, forged assertions, partner tokens replayed at backend → all 401 |
| Injection / malicious input | Gateway regex threat protection + backend validation | T9a–h, T15a–c | Blocked at gateway (400) before reaching the core; backend validates again |
| Uncontrolled partner traffic | Per-partner subscription tiers (Traffic Manager) | T11 `throttle-test.sh` | 5/min tier: exactly 5 calls reached the core, then 429 (3/3 runs) |
| Breaking partners on change | Versioning + lifecycle | T13a–d | v2 released, v1 deprecated & hidden, existing partners unaffected |
| Audit / regulator questions | Correlation IDs, audit trail, lifecycle history | `trace.sh`, `audit.log`, Publisher lifecycle history | Each call traceable: who, which account, decision, when |
| Maintenance of custom gateway code | Configuration instead of code | `scripts/` (publish, onboard, throttle, scopes, release) | Every platform change is a repeatable script; demo state rebuilds in ~45 s |

## What this POC proves / does not prove

| Proves | Does not prove |
|---|---|
| Only registered, subscribed, entitled partner apps get data | That a specific **customer** authorised a partner (needs auth-code + consent) |
| Cross-partner access is blocked and audited | Consent capture, expiry or revocation by customers |
| The backend independently enforces authorization | Strong customer authentication (SCA) / FAPI compliance |
| Quota limits protect the core | Performance at production scale / HA (single node here) |
| Threat protection at the edge | Fine-tuned rules for free-text APIs (regex is broad) |

Known limitation stated openly: with the gateway credential **and** a captured backend assertion, a replay is possible for ≤ 15 min — which is why production places the backend in a private network (or mTLS) reachable only by gateway nodes.
