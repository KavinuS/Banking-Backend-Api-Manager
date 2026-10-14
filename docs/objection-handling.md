# Objection Handling

Answers stay within what the POC showed or what is a stated roadmap item. Where the POC did not test something, say so.

### "Why WSO2 rather than Kong, Apigee or Azure API Management?"
- Full lifecycle in one product: gateway, key manager, traffic manager, publisher, developer portal, governance — all shown today on one node.
- Open source core with deployment freedom (on-prem, private cloud, SaaS) — relevant for a bank with data-residency requirements.
- Don't disparage competitors; offer a criteria-based comparison (deployment model, security features, total cost) as part of the on-site POC.

### "We need customer consent, not app access." (expected from security / compliance)
- Agree — the POC deliberately uses client credentials + a static consent registry as a stand-in (slide: *proves / does not prove*).
- Production path: authorization code + PKCE, a consent page at the bank, consent stored with scope/accounts/expiry and revocable; the backend already authorizes on a verified identity, so swapping the source of consent does not change the enforcement point.

### "What if someone gets inside the network and calls the core directly?"
- Shown: direct calls without the gateway credential, with forged assertions, or with partner tokens are rejected (T10a–g).
- Stated limitation: credential + a captured assertion can be replayed ≤ 15 min → production uses network isolation and/or mTLS between gateway and backend.

### "Throttling didn't stop the very first excess request in a burst."
- Quota enforcement is cluster-wide and asynchronous (decisions in seconds). At steady rates it was exact (6th call rejected); a one-second burst completes before the decision arrives.
- Instant spike protection is **burst control** on the gateway — a configuration option, listed for the on-site POC.

### "How does this perform / scale? Is it highly available?"
- Not measured in this POC (single laptop). Answer with deployment patterns (multiple gateway nodes, separate traffic manager / key manager) and propose a load test in the on-site POC with LHB's target volumes.

### "Who maintains this? Do we need WSO2 specialists?"
- Everything in the demo was configured through the product's REST APIs and captured as scripts (`scripts/`), so changes are repeatable and reviewable.
- Training and support options are commercial discussions — note the question for the account team.

### "What does it cost?"
- Don't quote. Capture volumes, environments and support expectations in discovery and hand over to the account team.

### "What if the gateway goes down?"
- Single node in the POC. In production: multiple gateway nodes behind a load balancer; gateways validate JWT tokens locally, so token validation does not depend on a single key manager call per request.

### "We already have custom code for auth and rate limiting."
- Show the value map row *Maintenance*: auth, subscriptions, scopes, throttling, versioning and the developer portal were configuration here. Agree on which custom functions to retire first during the on-site POC.

### "Your security regex blocks legitimate input."
- Correct for broad patterns (e.g. `alter` matches "alternative"). This API's inputs are narrow; free-text APIs need tuned rules or schema validation (`jsonValidator` policy) instead.
