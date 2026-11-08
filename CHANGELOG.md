# Changelog

## Unreleased — static hardening review

The initial review was static-only. Subsequent [GitHub Actions verification](https://github.com/SHOOP111/aurora-economy/actions/runs/42502782490) compiled the changes, passed all 153 tests on both Linux and Windows, passed coverage gates and verified packaged JAR contents. No local compilation/tests or live-server execution were completed.

- Make player registration metadata/grants atomic, freeze-aware and safe across unknown offline names.
- Restrict transaction handles to their live writer thread; reject nested writes; preserve rollback failures and confirmed commit receipts during cleanup faults.
- Add schema v3 order revisions, stale-scheduler guards and history/reference/ranking/ownership indexes.
- Validate exact YAML types, ASCII currency codes, printable symbols, full UUIDs, issuance source/direction and bounded zero amounts.
- Make unlimited daily counters saturating and capped comparisons overflow-safe; normalize interest-result overflow errors.
- Extend audits to historical ranges, orphan entries, missing triggers, custody creation terms, wallet-control events, order counters and domain storage/account topology.
- Group portfolio queries, reuse ledger SQL statements, batch projections and stream canonical hash encoding without changing version-1 digests.
- Defer GUI actions to the next tick; close protected views on disable; retry rejected registration; coalesce maintenance and drain accepted work despite interruption.
- Add reusable request IDs to savings/escrow/order/swap/admin financial commands and permission-aware deep tab completion.
- Preserve committed Vault success if a balance lookup fails; keep evidence snapshots available when a deep audit cannot complete.
- Upgrade SQLite JDBC to verified published 3.54.1.0; preserve multi-release packaging metadata; validate release version syntax and serialize releases.
- Improve missing/malformed CI-report summaries, backup-file ignore rules and upgrade/recovery documentation.

## 1.0.0

Initial implementation, built and tested through GitHub Actions.

### Financial core

- Exact multi-currency money with persisted precision and checked arithmetic.
- Atomic double-entry SQLite ledger, immutable journal/entries and replay-safe request receipts.
- Canonical chained SHA-256, projection/custody/supply audits and integrity writer tripwire.
- UTC spending budgets, transfer fees, explicit treasury mint/burn and persistent audited wallet controls.

### Economy systems

- Fully treasury-funded, fixed-term savings with maturity claims.
- Refundable payment escrow with acceptance, cancellation and offline expiry.
- Finite recurring transfers with atomic execution counters, snapshot fees and pause/resume behavior.
- Treasury-owned constant-product currency pools with protected minimum-output swaps.

### Paper integration

- Permission-gated command tree, aliases, paginated contracts, replay-key payments and clickable receipts.
- Session-owned read-only financial dashboard, wallet statements and liquid-wallet leaderboard.
- Bukkit-registered asynchronous integration API and optional honest Vault compatibility.
- Bounded worker queue, startup audit, accepted-work shutdown drain and schema version migration.

### Delivery and recovery

- Checksummed Gradle wrapper, pinned GitHub workflow actions and reproducible archive ordering.
- Real SQLite financial, persistence, concurrency, policy, API, command and Vault tests on Linux and Windows.
- Shaded plugin/native-driver packaging verification and JaCoCo/test artifacts.
- Consistent SQLite backups with snapshot SHA-256 and audit-head manifests.
- GitHub tag release workflow, operator documentation and explicit security/testing limitations.
