# Operations and recovery

## First launch

Use a staging Paper 1.21.4 server on Java 21. Download the shaded artifact from a successful Actions run or verified release; no local Gradle invocation is required.

Before production, review:

- default currency and Vault enablement;
- currency codes, decimal precision, starter grants and daily limits;
- transfer, exchange and savings rates;
- active contract quotas and queue capacity;
- maintenance interval;
- permission defaults and authentication mode;
- durable disk capacity and backup location.

Restart for configuration changes. Hot reload is deliberately unsupported; do not use `/reload` or third-party plugin reloaders for a live financial database.

## Files

```text
plugins/AuroraEconomy/
  config.yml
  economy.db
  economy.db-wal          # live write-ahead log, when present
  economy.db-shm          # WAL coordination file, when present
  economy.db.lock         # exclusive process ownership; file can remain after shutdown
  backups/
    economy-<uuid>.db
    economy-<uuid>.db.manifest.txt
```

The presence of the `.lock` file is not itself a stale-lock error; the OS lock held on it establishes ownership. A second live process must not point at the same database. Network filesystems and network-wide server sharing are unsupported.

## Routine commands

```text
/eco admin supply
/eco admin audit
/eco admin backup
/eco admin inspect Player
/eco admin freeze Player Investigating suspicious account traffic
/eco admin unfreeze Player
```

Supply reports are per currency. Wallet leaderboards omit custody. An audit is deeper: it verifies journal hashing, per-transaction balance, materialized projections, custody, supply, foreign keys and SQLite integrity.

Wallet freezes are persisted and audited. They block outgoing payments, swaps, new custody, recurring execution, savings claims and Vault withdrawal. Incoming payments, refunds and authorized treasury administration still work. Orders pause rather than consuming an installment.

## Backups

`/eco admin backup` runs `VACUUM INTO` under the ledger lock, producing a consistent standalone SQLite snapshot. It also writes a manifest containing:

- backup creation time;
- health at snapshot time;
- transaction/posting counts;
- the SHA-256 ledger head;
- the SHA-256 of the actual snapshot database file.

Move **both database and manifest** to access-controlled off-server storage. Establish a retention policy yourself; Aurora does not silently delete old snapshots or upload sensitive account data. Keep at least one independently trusted ledger-head anchor.

Never simply copy `economy.db` while the server is live and assume it includes committed WAL transactions. Use the backup command, SQLite's own supported backup facilities, or a clean offline shutdown.

A backup can still be produced from a globally frozen ledger for incident evidence. `healthy=false` means evidence, **not a trusted restore point**. A manifest-write failure is reported even if its database snapshot was already created; inspect server logs and disk permissions before retrying.

## Restore drill

1. Stop the server normally and ensure no other process owns the database.
2. Preserve the current database, WAL, SHM, config, logs and manifests as incident evidence.
3. Verify the chosen backup's file SHA-256 against its independently stored manifest.
4. Put the verified snapshot in place as `economy.db`. Remove or archive old WAL/SHM files **only while offline**; they must not be paired with the restored database.
5. Keep a config containing every persisted currency with its original precision.
6. Start in an access-restricted staging environment. Startup must pass integrity checks and open the expected schema version.
7. Compare `/eco admin audit`'s head and counts with the backup manifest before opening player access.
8. Confirm wallets, open escrows, locked deposits and remaining recurring orders against operator records.
9. Expect one due recurring installment per order after maintenance—not a missed-installment catch-up burst.

Restoring a backup necessarily restores an earlier financial point in time. Payments after that point need deliberate reconciliation; never mix balances from one snapshot with journal rows from another.

## Integrity incidents

An unhealthy deep audit freezes the runtime writer. There is no `/eco admin force-unfreeze-ledger` escape hatch. Balance inspection and backups remain available to preserve evidence.

1. Restrict player access and stop further trusted external integrations.
2. Take an evidence snapshot if the database remains readable.
3. Record the reported head, violations and server logs off-host.
4. Stop the server and inspect storage, permissions, host compromise and plugin behavior.
5. Restore an independently verified healthy snapshot, or perform a reviewed offline recovery/migration with its own audit and reconciliation plan.
6. Restart and verify integrity before resuming service.

Never "repair" a ledger by editing a player's balance to hide a projection mismatch, disabling immutable triggers or resetting the chain head. Administrative mint/burn is an intentional new economic action, not a substitute for restoring corrupted history.

## Performance

The normal command path is asynchronous, bounded and serialized. Financial commits use `synchronous=FULL` for durability, so disk latency matters. Use local durable SSD storage and sufficient free space.

Large audits reconstruct history and use memory proportional to accounts; snapshots copy the entire database. Both hold the ledger lock. Schedule them during quiet periods. **Vault is synchronous and may wait behind these operations on the main thread.** Prefer Aurora's async API for new integrations, or disable the Vault bridge where legacy compatibility is unnecessary.

Contract lists retain at most 100 recent records, with open/locked/active contracts sorted first; chat pages contain eight records. Maintenance batches expired/due work in groups of at most 100 per category. Monitor queue saturation, due-work latency, storage growth and server tick time under your real workload.

No throughput or "unlimited players" claim is made without workload-specific profiling. There is no archival/pruning command in v1.0; immutable history grows over time.

## Identity and configuration migrations

Player accounts are keyed by UUID, not names. Names are last-seen convenience lookups and can change; full UUIDs are supported. Never change authentication/offline-mode identity conventions without a reviewed UUID migration, or old accounts will appear missing.

Known precision cannot change, and known currencies cannot disappear from config. New currencies may be added on restart; existing players do not receive a repeated global starter grant. Settings errors and unknown newer schema versions fail closed.

Audit-preserving migration from another economy is not implemented. Export and reconcile the source system, define one-time opening entries and use a reviewed importer with replay keys rather than hand-editing this database.

## Optional Vault

Use only one active primary economy provider. Aurora registers its default currency at high service priority when Vault is enabled and installed. Other plugins may cache a provider at startup; dependency ordering and server restarts matter.

Vault bank calls honestly return `NOT_IMPLEMENTED`; Aurora's individual funded savings contracts are not generic Vault bank accounts. Per-world overloads use the same global wallet. Legacy name overloads resolve only known names/UUIDs and never perform blocking Mojang lookup.

Watch ledger entries labeled `VAULT_DEPOSIT` and `VAULT_WITHDRAW`. Trusted shop/reward plugins can issue/burn through those methods. A compromised plugin has host-level trust and cannot be made safe merely by economy permissions.
