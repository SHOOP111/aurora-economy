# Security policy and threat model

## Report a vulnerability

Please do not publish reproduction steps for an exploitable duplication or account-theft flaw in a public issue. Use GitHub's private vulnerability reporting for this repository when available; otherwise privately contact the repository owner before disclosing details.

Include plugin version/commit, Paper and Java versions, minimal configuration, sanitized logs, the affected transaction IDs, expected behavior and a reproducible sequence. Do not send player databases, tokens or private server addresses in public reports.

## Intended protections

- Exact integer money and checked arithmetic; no float-based ledger mutations.
- Per-currency balanced postings and nonnegative spendable/custodial accounts.
- Serialized atomic writes and unique actor-scoped operation keys.
- Domain ownership/state/maturity enforcement inside transactions, not merely command permissions.
- Immutable journal/entry SQL triggers, deterministic chained SHA-256 and deep projection/custody/supply audits.
- Startup integrity refusal and a runtime audit tripwire that freezes financial writes.
- Persisted, audited wallet controls for incidents.
- Required AMM minimum output and actual reserve funding.
- Treasury reservation of all promised savings yield at opening.
- Main-thread Bukkit UI work and fully cancelled read-only inventory interactions, using owned holders rather than matching titles.
- User strings rendered as text components, not parsed MiniMessage markup or command execution payloads.
- Prepared SQL values; no arbitrary chat-derived database path or raw SQL command.
- Bounded queued work and active-contract quotas.
- Checksummed Gradle distribution, validated wrapper, pinned workflow action commits and verified packaged artifact contents.
- No telemetry, remote command endpoint, credential collection, hidden reward issuer or external trading integration.

## Trust boundaries

**Trusted:** server operators, all installed plugins, the JVM, local filesystem and clock, dependency publishers, GitHub build infrastructure and independently stored backup anchors.

**Untrusted:** ordinary player input, repeated/competing requests, client inventory interactions, missing responses and ordinary restart timing.

Bukkit permissions are a player authorization boundary, not a plugin sandbox. A malicious installed plugin can invoke trusted services, reflect into the engine, alter files or execute arbitrary Java. A compromised administrator can intentionally mint, burn or reprioritize liquidity; those actions are audited rather than magically prevented.

The ledger hash chain is unkeyed. SQL triggers can be removed by host/database administrators. An attacker controlling the database and every stored hash anchor can rewrite a self-consistent history. Off-host manifests provide independent evidence; they do not eliminate the need for server access control.

## Known limitations

- New implementation: no independent security audit or long-running production certification.
- No real Minecraft server/GUI acceptance test has been executed by this project; unit/integration tests use the core and mocked adapters.
- No network-wide consistency, network-filesystem safety or Folia support.
- Deep audits and snapshots can delay synchronous Vault calls; there is no demonstrated server-load capacity guarantee.
- Mutable nonfinancial metadata and every scheduling field are not fully event-rebuilt from journal intents. Host-level table edits remain outside the ordinary player threat boundary.
- Reliable host time is required for UTC budgets, escrow expiry and savings maturity.
- Legacy Vault doubles lose precision at very large values and cannot express client idempotency; only safe, exact mutation amounts are accepted.
- Starter grants can be farmed through legitimate alternate UUIDs unless server identity/anti-alt policy prevents it. Changing offline/online mode requires a migration plan.
- Refundable escrow does not ensure item/work delivery or protect a beneficiary who delivers goods before acceptance.
- SQLite/full synchronization still depends on functioning storage. Disk failure or ambiguous physical commit may require recovery and request replay.
- There is no automatic immutable-log archival, rate limiting by network identity, cross-plugin item-delivery transaction or external economy importer.

## Safe deployment

Use only verified shaded artifacts, least-privilege operator permissions, authenticated player UUIDs, local durable storage and off-server backups with head manifests. Exercise the staging checklist in `docs/TESTING.md`, monitor storage/queue/tick behavior and restart for policy changes. Do not hot-reload a live financial plugin or "repair" corruption by editing balances.
