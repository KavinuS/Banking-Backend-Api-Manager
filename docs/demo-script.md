# Demo Script — Lanka Horizon Bank × WSO2 API Manager (28 min)

All names, partners and data are fictional. Every step below was run on this machine; the expected output is what was observed.

## Before the panel (T–60 min)

```bash
caffeinate -di &                      # the Mac must not sleep (it did once mid-test)
./scripts/reset-demo.sh               # ~45 s → "READY: demo state restored and all security checks pass."
```

Open in this order (browser, logged out): Developer Portal → Publisher → Admin Portal, then two terminals at the repo root, font ≥ 18 pt.
Have `docs/value-map.md` and the backup video ready. Never show `~/.lhb-poc.env`.

Handy shell helpers for terminal 1:

```bash
G=https://localhost:8243/lhb/accounts/v2
PAY=$(./scripts/get-token.sh PAYLANKA); QB=$(./scripts/get-token.sh QUICKBUDGET)
call() { curl -sk -w '\n→ HTTP %{http_code}\n' "$@"; }
```

---

## 1. Context — 3 min (slides)

- Lanka Horizon Bank wants to open account data to fintech partners **without exposing its core banking system**.
- Three outcomes we will prove, not claim: **protect** existing services, **operate** APIs centrally, **enable** partnerships.
- Show the architecture slide (`docs/architecture.md`): partner → WSO2 gateway → backend; nothing reaches the core except through the gateway.

> Say: "Everything you'll see is running live on this laptop, and every check you see has an automated test behind it."

## 2. Onboarding — 7 min (**enable**)

1. **Developer Portal** (logged out): LHB-Accounts **v2** is listed; v1 is not (deprecated). Open docs and the try-out page.
2. Log in as `paylanka_dev` → *Applications* → show **PayLanka Wallet** → *Subscriptions* (v2, Unlimited) → *Production Keys* (client credentials).
   - Live option (if time): create a new app **"PayLanka Demo"**, subscribe, generate keys — **time it** (record in `value-map.md`).
3. Terminal: first call.
   ```bash
   call -H "Authorization: Bearer $PAY" $G/accounts/ACC-1001
   ```
   → `200`, masked `****1001`, `balance` and `availableBalance`.

> Say: "Self-service onboarding to first successful call in minutes — no tickets, no custom integration code."

## 3. Security — 8 min (**protect**)

Run each, pausing on the business meaning.

| # | Command | Expected | Business message |
|---|---|---|---|
| a | `call $G/accounts/ACC-1001` | 401 · 900902 Missing Credentials | No anonymous access |
| b | `call -H "Authorization: Bearer abc.def.ghi" $G/accounts/ACC-1001` | 401 · 900901 | Forged tokens rejected |
| c | `call -H "Authorization: Bearer $(./scripts/get-token.sh ROGUEAPP)" $G/accounts/ACC-1001` | 403 · 900908 subscription validation failed | Registered ≠ approved |
| d | `call -H "Authorization: Bearer $QB" $G/accounts/ACC-2001/transactions` | 403 · 900910 | Bank-granted entitlement: QuickBudget asked for `transactions:read` and **was not granted it** |
| e | `call -H "Authorization: Bearer $QB" -H "X-Correlation-ID: panel-bola-1" $G/accounts/ACC-1001` | **404 NOT_FOUND** | **BOLA blocked**: a valid partner cannot read another partner's customer; looks identical to a non-existent account |
| f | `./scripts/trace.sh panel-bola-1` | audit: `QuickBudget getAccount ACC-1001 DENIED_NO_CONSENT` | Every decision is attributable and auditable |
| g | `call -H "Authorization: Bearer $PAY" "$G/accounts/ACC-1001/transactions?limit=1%27%20OR%20%271%27=%271"` | 400 · SQL-Injection Threat detected | Gateway threat protection |
| h | `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/accounts/ACC-1001` | 401 | **No bypass**: backend refuses anything not from the gateway |

Optional proof: `./scripts/security-tests.sh` → `42 passed, 0 failed`.

> Say: "Five layers had to agree before data left the bank: transport, token, subscription, scope, and the backend's own per-account check."

## 4. Traffic — 6 min (**protect / operate**)

1. Admin Portal → *Rate Limiting Policies* → **LHB Partner Demo** (5 requests/min).
2. Terminal 2:
   ```bash
   ./scripts/throttle-test.sh 20 --no-recovery
   ```
   (waits for the minute boundary, then 1 request/s) → `200 200 200 200 200 429 429 …`, **"only the 5 allowed calls reached the core system"**, 429 body `900804 Message throttled out` with retry-after time.

> Say: "A misbehaving partner is contained at the edge — the core banking system never sees the excess."

If asked about bursts: quota enforcement is cluster-wide and asynchronous (seconds); instant spike protection is burst control (roadmap item in this POC).

## 5. Governance — 3 min (**operate**)

1. Publisher → LHB-Accounts **v1**: lifecycle **Deprecated**; *Lifecycle history* shows CREATED → PUBLISHED → DEPRECATED with user and time.
2. v2 published; existing partners were migrated automatically (option: force re-subscription).
3. Show a deprecated v1 still serving an existing partner: `call -H "Authorization: Bearer $PAY" https://localhost:8243/lhb/accounts/v1/accounts/ACC-1001` → 200 without `availableBalance`.

> Say: "Change without breaking partners — and an audit trail of who changed what."

## 6. Close — 1 min

- Recap the three outcomes with the evidence just shown (`value-map.md`).
- Be explicit about scope: app-level consent stand-in, not customer consent (slide: *What this POC proves / does not prove*).
- Proposed next step: 2–4 week on-site POC against LHB's non-production core endpoints with agreed success metrics.

---

## Persona variants (reorder, same content)

| Lead persona | Order | Spend extra time on |
|---|---|---|
| CISO / security | 3 → 4 → 2 → 5 | BOLA (3e–f), no-bypass (3h), honest consent limits |
| Head of Digital / partnerships | 2 → 3 → 5 → 4 | Live onboarding with stopwatch |
| Integration engineer | 2 (Publisher first) → 5 → 4 → 3 | OpenAPI import, versioning, scripts-as-code |
| IT operations | 4 → 3 → 5 → 2 | Throttling protects core, tracing (`trace.sh`) |

## If something goes wrong

| Symptom | Likely cause | Fix (≤ 1 min) |
|---|---|---|
| 401 on a call that worked before | Token expired (1 h) | Re-run the `PAY=… QB=…` line |
| No 429 in the throttle test | Publisher not warmed up after restart / network change | Run one call, re-run the test; `reset-demo.sh` warms up automatically |
| 404 "Invalid URL" right after a change | Gateway redeploy still in progress (1–6 s) | Wait 5 s |
| Anything else | — | Switch to backup video; fix later with `reset-demo.sh` |
