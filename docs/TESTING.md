# Testing and verification

## GitHub-only execution

The project's compilation, tests, code coverage and packaging execute in `.github/workflows/ci.yml` on GitHub-hosted Linux and Windows runners with Temurin Java 21. No local Gradle build or test execution was used during development.

Push or open a pull request, then inspect [Actions](https://github.com/SHOOP111/aurora-economy/actions/workflows/ci.yml). `workflow_dispatch` can run the same checks manually. Each successful run publishes:

- shaded plugin, thin integration JAR and sources;
- JUnit XML and HTML test reports;
- JaCoCo XML and HTML coverage;
- packaging verification results in the build log.

Tag releases run the same Gradle `clean build`, generate SHA-256 checksums and publish only after success. The wrapper checksum and workflow wrapper validation protect against accidental wrapper substitution. Tests are not skipped to make release publishing faster.

## Hardening review verification

[GitHub Actions run 42502782490](https://github.com/SHOOP111/aurora-economy/actions/runs/42502782490) verified hardening commit `bebf4fb41226beb5f69fef6900f5c4456bb47ac4` on Linux and Windows: **153 tests per OS, zero failures/errors/skips**, successful compilation, coverage gates and JAR verification. Core coverage was 95.4% line / 69.0% branch; storage was 94.9% line / 66.5% branch. This is remote CI evidence, not live Minecraft acceptance. See [review notes](REVIEW.md).

## Automated coverage scope

Tests use **real temporary SQLite databases**, the actual transaction engine and reproducible seeded traffic. Paper/Vault boundary tests mock Bukkit objects; they do not pretend to be a live server.

| Area | Checks |
|---|---|
| Money | Exact/min/max parsing, dangerous representations, scale rejection, round trips, checked overflow, fees and yield rounding, large intermediate products |
| Ledger | Taxed transfers, zero/self rejection, matching replay, conflicting replay, actor scoping, rolled-back spending counters, retry after funding, issuance/burn supply |
| Persistence | Restart and replay, stable identity/name update, unknown-name behavior, exclusive process lock, precision/removal refusal, version-1 control migration |
| Integrity | Immutable SQL triggers, altered balance detection, altered journal intent detection, custody-state corruption, whole-ledger freeze after failed audit |
| Custody | Escrow reserve/accept/refund/expiry, boundary time, owner permissions, repeated close rejection, savings reservation/claim/maturity/ownership |
| Scheduling | Execution/counter atomicity, no repeated due run, no downtime catch-up burst, pause/resume/re-pause, cancellation, restart behavior |
| Markets | Exact reserve-based output, stale minimum-output failure without charge, same pool both directions, empty reserves, atomic funding, seeded constant-product properties |
| Controls | Frozen spending, allowed incoming/refund, bank/order admission, persisted freezes, audited replay-safe releases, Vault freeze enforcement |
| Concurrency | Concurrent same-key replay, concurrent overspend, accept/cancel race, randomized supply conservation |
| Async API | Real worker requests, queue rejection before mutation, domain-error futures, persistent references, immutable snapshots |
| Commands | Permission denial before queueing, amount validation, aliases, authorized real-ledger transfers, console restrictions, hidden admin tab suggestions |
| Vault | External issuance/burn, invalid doubles, no-op zero, insufficient response balances, names/worlds, starter replay, honest unsupported banks |
| Config | Shipped defaults, missing currency, worker/rate/precision and negative amount rejection |
| Backup | Consistent snapshot reopen, exact database SHA-256 and ledger-head manifest |
| Packaging | Expanded plugin version, main class, config, SQLite driver/services and Linux/Windows native libraries; Paper/Vault APIs not bundled |

The money property test evaluates 10000 reproducible reserve/input combinations. The traffic test applies 1000 reproducible transfers and deep-audits the resulting supply. These are invariant tests, not performance benchmarks or exhaustive proofs.

## Still required: staging acceptance

A successful CI build is **not** a live Minecraft certification. On a disposable Paper 1.21.4 staging server, complete this checklist before production:

- [ ] Startup with and without Vault; expected default provider and no linkage/native-driver errors.
- [ ] Fresh player registration, name change, reconnect and restart preserve the correct balance.
- [ ] `/eco` shows the right wallets and separates locked savings/escrow from liquid money.
- [ ] GUI shift-click, drag, number-key swap, collect-to-cursor and bottom-inventory actions cannot move or duplicate UI items.
- [ ] Closing/reopening dashboards cannot apply a late snapshot to the wrong session.
- [ ] Unprivileged players cannot inspect others, mint, burn, fund pools, freeze accounts, back up or audit through administrative commands.
- [ ] Explicit `/pay` replay key charges once; changed payload with that key is refused.
- [ ] Treasury funding and pool seeding produce the intended supply and market ratio.
- [ ] Savings and escrow IDs prepare, rather than auto-execute, clickable actions.
- [ ] Escrow acceptance/cancellation and offline expiry refund behave as documented.
- [ ] Due orders pause on insufficiency/freeze and resume with a fresh interval.
- [ ] Exchange minimum-output protection rejects a deliberately stale quote without balance changes.
- [ ] Actual shop/reward plugins handle Vault failures, exact precision and default global currency correctly.
- [ ] Account incident controls and read-only audit tripwire are visible to operators.
- [ ] A consistent snapshot + manifest can be restored offline into a clean staging instance.
- [ ] Server tick time, queue saturation, disk latency, maintenance delay and large-history audit/backup behavior meet your workload budget.

Do not run destructive fault injection on a valuable production economy. Process-kill-at-commit testing, power-loss simulation, full-server acceptance and long-duration load testing are not claimed as completed by this suite.
