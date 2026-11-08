# Static review and hardening notes

## Scope and verification boundary

This pass inspected the tracked application and test Java sources, resource YAML, Gradle configuration, wrapper scripts, workflow/reporting code, documentation and SVG asset. Existing tests were read as specifications during the initial review and subsequently executed unchanged on GitHub-hosted runners. Generated caches, Git internals and the binary Gradle wrapper are not application source.

The initial review used source/control-flow analysis, searches, diff inspection and Git whitespace checks. The published SQLite JDBC POM and release notes were read to confirm the dependency update. A local wrapper download was interrupted before compilation/test tasks ran; no local build or tests completed. All completed compilation/test verification was performed through GitHub Actions after authorization.

### GitHub verification evidence

[Run 42502782490](https://github.com/SHOOP111/aurora-economy/actions/runs/42502782490), commit `bebf4fb41226beb5f69fef6900f5c4456bb47ac4`, passed on both `ubuntu-latest` and `windows-latest`:

- Java compilation, `clean build`, coverage verification and plugin-JAR verification succeeded.
- **153 tests per OS; 0 failures, 0 errors, 0 skipped.**
- Core coverage: **95.4% lines / 69.0% branches**.
- Storage coverage: **94.9% lines / 66.5% branches**.
- Both jobs published shaded/thin/source JARs, test reports and coverage reports.
- Native SQLite loading was exercised by the real-SQLite tests; packaged driver/service/Linux/Windows resources were checked.

These changes address issues found through the review. Successful CI is not a proof that every possible bug has been eliminated and does not constitute a live-server or production-readiness certification.

## Findings addressed

| Area | Previously vulnerable/error-prone behavior | Change |
|---|---|---|
| Registration | Player metadata was committed before starter grants. Grant failure left a partial account, and metadata could change while the financial writer was frozen. | Registration metadata and initial grants share one transaction. Matching replays refresh metadata within the same protected transaction. |
| Offline identity | An unknown/null offline name replaced an existing real name with a UUID; abbreviated UUID strings were accepted by Java's permissive parser. | Preserve known names on fallback registration; require full UUID grammar for identity/contract IDs; use deterministic name-lookup tie breaking. |
| Transaction nesting | A reentrant nested write could commit an outer operation. Escaped transaction handles could be reused outside their callback. | Reject nested writes and require every transaction-handle operation to occur on its active writer thread. |
| Transaction faults | Rollback/reset errors could replace the original failure, and switching to autocommit after failed rollback could commit partial work. | Preserve suppressed cleanup errors; disable broken connections; never reset after failed rollback. Apply the same rule to schema/metadata initialization. |
| Confirmed commits | A cleanup failure after a successful physical commit could be reported as if the operation had failed. | Preserve the confirmed receipt, disable the connection and log the cleanup fault. Ambiguous `commit()` failures still require recovery. |
| Issuance attribution | Arbitrary source labels/directions could accidentally bypass the intended Vault withdrawal controls. | Allow only the four supported issuance sources, enforce mint/burn direction and require Vault actor/target identity agreement. |
| Recurring scheduling | A failure snapshot could pause a manually resumed order; no-op/replayed pause receipts could inflate maintenance counts. | Persist a control revision, compare revision/execution/due snapshots before running or pausing, and count only a newly committed pause. |
| Time/range arithmetic | Contract deadlines used unchecked timestamp addition. Public yield overflow leaked `ArithmeticException`. | Check deadline addition and translate final interest overflow into the stable invalid-amount error. |
| Daily counters | Unlimited repeated circulation could overflow the daily counter; capped overflow produced the wrong failure category. | Saturate unlimited counters at `Long.MAX_VALUE`; compare capped budgets without overflowing and report `DAILY_LIMIT`. |
| Configuration | Bukkit integer/boolean/string getters could silently coerce invalid values. Zero had a separate effectively unbounded parser. | Require exact YAML types, validate integer range, bound zero through the shared exact parser, reject duplicate normalized codes and control-character symbols. |
| Currency identity | Unicode uppercasing could turn a non-ASCII code into an apparently valid ASCII code. | Validate the ASCII code grammar before normalization. |
| Audit completeness | Final balances alone did not expose historical range violations, orphan postings, disabled foreign keys or removed immutable triggers. | Check these invariants and trip the low-level store's writer freeze as well as the engine's deep-audit tripwire. |
| Domain audit | Beneficiary/owner/amount/maturity/control edits and successful-order counter drift could evade ordinary custody checks. | Compare custody terms with authenticated creation metadata/postings, rebuild latest wallet controls from their events, compare installment counts, validate domain types/ranges and reject unknown account topology. |
| Query cost | Portfolio work grew by repeated per-currency queries; history could scan unrelated journal rows; audit prepared the same entry query repeatedly. | Group portfolio queries, index history/reference/ranking/ownership access, reuse audit statements and batch balance/specification writes. |
| Hash allocation | Every digest built and copied a full canonical byte buffer. | Stream the existing version-1 encoding directly into SHA-256; ordering, lengths and hash format remain unchanged. |
| Queue pressure | Redundant maintenance requests accumulated behind slow tasks. A rejected join registration could leave an online player without their starter registration. | Coalesce maintenance and retry rejected registration while that player remains online. |
| Shutdown | Interruption could close the database before accepted worker requests drained. Open dashboard views could survive removal of their protection listeners. | Drain safely despite interruption, preserve interrupt status, stop callbacks and close dashboard sessions during disable. |
| Inventory events | The click listener closed/opened views inside Bukkit's inventory transaction. | Cancel the interaction, then perform the session-checked action on the next tick using Aurora's namespaced command. |
| Command replay | Only payments accepted an explicit retry key, and many generated IDs were created only inside queued work. | Accept and display optional keys for savings, escrow, order, swap, mint/burn and liquidity actions, capturing them before queueing. Wallet control commands retain their existing syntax. |
| Command feedback | Deep completions advertised denied feature arguments; namespaced aliases were not normalized; rendering errors could disappear into ignored completion stages. | Gate subtrees consistently, normalize namespaced aliases, handle empty completion input and distinguish response-render failures from operation failures. |
| Vault responses | A balance read after a committed mutation could turn success into failure and invite duplicate issuance. Disabled providers/account checks were inconsistent. | Preserve confirmed success with an explicitly unavailable (`NaN`) balance, reject disabled mutation/account creation and make account-check error handling consistent. |
| Backup evidence | Existing empty destinations/manifests could be reused; a malformed-domain audit could prevent an otherwise readable evidence snapshot. | Require new destinations, warn about unhealthy snapshots and record unavailable audit counts/head honestly when necessary. |
| Dependency/build hygiene | SQLite JDBC was pinned to an older release; invalid release-version text could enter artifact paths/plugin YAML; release runs could race. | Pin verified published 3.54.1.0, preserve multi-release metadata, validate a safe semantic-version grammar, serialize releases and scope release write permission to its job. |
| Reporting/data hygiene | Missing reports produced no workflow summary; malformed XML aborted reporting; local backup sidecars were not ignored. | Produce explicit unavailable/report-warning summaries, sort output deterministically and ignore lock/manifest sidecars. |

## Compatibility and rollout notes

- Schema **v3** adds `orders.revision` and query indexes. It does not rewrite journal hashes, posted money or contract amounts.
- Take a healthy **pre-upgrade** backup and preserve its configuration. A schema-v2 binary cannot open the upgraded database; do not bypass this by editing `user_version`.
- The older `StandingOrder` constructor remains available for integrations; the new revision accessor is additive.
- Quoted monetary strings are required. Integer and boolean settings must be YAML integers and booleans, not numeric fractions or quoted text.
- Contract quotas are bounded to 1–10000. The existing bounded 100-record list views remain unchanged; keep creation receipts if configuring larger quotas.
- Financial commands except freeze/unfreeze can take a trailing request ID. Read-only commands and backups do not take those IDs. For payment aliases, supply a currency before the ID.
- An unlimited daily counter can now display its saturated maximum instead of blocking circulation.
- A confirmed Vault mutation can return `SUCCESS` with `balance=NaN` if its balance cannot be read. Integrations must not retry that mutation. Use the exact async API instead of relying on legacy double behavior.
- An evidence manifest with an incomplete audit uses `healthy=false`, counts `-1` and head `unavailable`. It is not a verified restore point.
- The SQLite update was checked against the publisher's POM/release notes and subsequently passed real-SQLite CI tests and packaging checks on both OSes. Live Paper compatibility was not executed.

## Remaining limitations and risks

1. **CI covers only its defined scope.** Existing compilation, 153 tests, coverage gates and packaging checks passed remotely. Real historical production files, live Paper/Vault/GUI behavior, destructive fault injection and performance remain unverified. No new regression tests were added in this pass; additional targeted coverage is still valuable.
2. **Installed plugins and operators remain trusted.** The engine/SQL connection are not a sandbox. Host access can remove triggers or rewrite a self-consistent chain; independently held backup anchors remain essential.
3. **Not every mutable field is event-rebuilt.** Player-name metadata, some scheduling state/timestamps and the original recurring fee snapshot are not fully reconstructed from immutable events. New comparisons improve incident detection without claiming a complete event-sourcing rewrite.
4. **Physical commit ambiguity remains possible.** A thrown `commit()` cannot safely be treated as proof that nothing persisted. Async clients must reuse their original keys. Vault has no client replay protocol.
5. **Disk/clock/filesystem assumptions remain.** SQLite requires local durable storage and a trustworthy clock. Hard-link aliases, network/shared filesystems, multi-server writes, Folia and hot reload remain unsupported.
6. **Long tasks and completion callbacks can delay shutdown.** Accepted operations are drained rather than silently discarded. Third-party future callbacks must not block on the server thread or do network work on the ledger worker.
7. **Audits and snapshots are not free.** Their CPU/memory/lock costs grow with history. Index/query/allocation improvements are source-level optimizations, not measured throughput claims.
8. **Economic safeguards do not solve external delivery.** Refundable escrow is not atomic item commerce, starter grants still need anti-alt policy, and separate Vault shop calls are not one Aurora transaction.

See [architecture](ARCHITECTURE.md), [economics](ECONOMICS.md), [operations](OPERATIONS.md) and [security](../SECURITY.md) for the supported model.
