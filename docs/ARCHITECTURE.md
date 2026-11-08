# Architecture

## Boundaries

```text
Paper main thread
  commands / joins / inventory / permissions / notifications
                 │ immutable UUIDs, amounts and request IDs
                 ▼
Bounded single worker queue ───── Bukkit EconomyApi futures
                 │
                 ▼
Framework-independent EconomyEngine
  policy • custody contracts • scheduling • market math
                 │
                 ▼
LedgerStore (fair reentrant lock + exclusive process file lock)
  one JDBC connection • one atomic SQL transaction
                 │
                 ▼
SQLite WAL / FULL synchronization
  immutable journal + entries • projected balances • domain tables
```

Vault is the explicit exception to the asynchronous boundary: its interface is synchronous and enters the same protected engine directly. It never returns a fabricated success on a timeout. The server can block behind a long audit or snapshot; see the operations guide.

### Packages

- `core`: immutable records, currency parsing, checked arithmetic and business rules. No Bukkit imports.
- `storage`: schema migrations, serialized JDBC access, immutable postings, journal hashing, audit and SQLite snapshots.
- `api`: immutable asynchronous integration surface registered through Bukkit's service manager.
- `paper`: configuration validation, plugin lifecycle, permission-gated command parsing, read-only dashboard and optional Vault compatibility.

## Invariants

1. **Per transaction, per currency: `sum(posting.delta) = 0`.** Cross-currency trades are two independent balanced movements, not an implicit conversion of the supply ledger.
2. **Balance projections equal the sum of immutable historical postings.** A deep audit reconstructs every account using big integers and compares the result to materialized balances.
3. **Only `system:mint` may be negative**, bounded to `[-Long.MAX_VALUE, 0]`. Its negative balance is issued supply. Every spendable/custodial account remains nonnegative.
4. **All committed money is exact.** Parser inputs become integer minor units without rounding. Addition is checked. Ratios and products that could overflow use `BigInteger`.
5. **A request key has one actor, kind and intent.** A matching replay returns the original receipt. A different payload cannot reuse the committed key.
6. **Contract transitions and money movements are indivisible.** An accepted escrow cannot lack its payment; a successful order cannot lack its execution increment; a claimed deposit cannot retain funds.
7. **Savings yields are funded at inception.** A treasury reservation is not a promise backed by future minting.
8. **A deep audit failure trips a one-way runtime writer freeze.** Inspection and backups remain available. Restart does not bypass the startup integrity check.

## Account topology

| Account | Role |
|---|---|
| `player:<uuid>:wallet` | Player's immediately spendable money |
| `vault:<deposit-uuid>` | Principal plus fully reserved yield for one deposit |
| `escrow:<escrow-uuid>` | Principal plus snapshotted transfer fee for one escrow |
| `system:treasury` | Tax revenue, discretionary reserves and yield funding |
| `system:pool:<sorted-currency-pair>` | Treasury-owned inventory in both currencies of an AMM pair |
| `system:mint` | Negative counter-account for explicit issuance and positive burn postings |

Per currency:

```text
issued = wallets + savings custody + escrow custody + treasury + market reserves
issued = -balance(system:mint)
```

Do not sum different currencies into a single "total value" without a defined valuation model. Aurora reports supply independently for each currency.

## Atomic write path

1. Validate the API amount, configured currency and caller-provided request key.
2. Acquire the store lock and check availability / global integrity freeze.
3. Start the SQL transaction and look up the unique operation key.
4. Return the original receipt on a matching replay; reject a conflicting intent.
5. Run the business callback: verify ownership, state, maturity, limits and wallet controls; stage aggregated postings; update domain rows and spending counters.
6. Apply checked account projections; reject insufficient funds or illegal supply.
7. Verify zero-sum postings in every affected currency.
8. Hash canonical transaction metadata and sorted postings against the previous ledger head.
9. Insert the complete journal record and immutable entries.
10. Commit once; return the durable receipt. Any exception before commit rolls back **all** changes.

Contract creation foreign keys are deferred until commit so the new contract can refer to the journal transaction that creates it. No placeholder immutable journal row needs to be edited later.

### Idempotency is not a distributed exactly-once promise

A committed request is replayable after restart. A rolled-back request can be tried again after funding. Failures before acceptance do not consume a key. Clients must reuse the same request ID after losing a response; generating a new key creates a new operation. Chat payments expose and accept explicit request IDs for this reason.

The physical commit boundary is SQLite. Disk faults, process death at commit and corrupt files still require recovery procedures. Aurora does not claim infallible storage.

## Hash encoding

SHA-256 covers:

- encoding version;
- previous head, transaction UUID, operation key, intent, kind, actor and reference;
- commit timestamp;
- the number of aggregated postings;
- sorted account, currency and signed minor-unit delta tuples.

Strings are UTF-8 with explicit byte lengths. Numbers use fixed-width binary encoding. Sorting and length prefixes prevent order-dependent output and ambiguous concatenation.

SQL triggers refuse journal/entry updates and deletes. An audit verifies the chain and balance projections, plus foreign keys, SQLite integrity, custody projections and currency supply. This is an integrity mechanism, **not blockchain consensus or a cryptographic host-access barrier**. Mutable nonfinancial metadata is not wholly reconstructed from the hash chain.

## Concurrency and scheduling

Commands and joins enqueue work into a bounded FIFO queue. Full queues fail before mutation. The store also serializes direct Vault/API-engine access with a fair lock. An exclusive OS file lock prevents two plugin processes from sharing one database on the same supported filesystem.

Each maintenance pass examines at most 100 expired escrows and 100 due orders. Expiry refund keys are deterministic. Order execution keys include the contract UUID and committed execution index. The next due time and execution index advance with the payment.

Orders pay one overdue installment, then schedule the next interval from actual execution time. This is intentionally not cron catch-up: weeks of server downtime do not immediately empty a player's wallet on restart.

The worker drains accepted operations on normal shutdown before closing the connection. GUI callbacks and messages return to Paper's main thread; old inventory sessions are not overwritten by late results.

## Persistence and evolution

`PRAGMA user_version` drives sequential migrations. Version 1 is the financial/custody ledger; version 2 adds wallet incident controls. Newer unknown versions refuse to open. A migration failure rolls back its migration.

Currency precision is persisted and checked on every open. New codes may be added; existing codes cannot be removed or have their scale changed. Starter grants are one global registration operation per player—not repeated whenever configuration changes.

Fresh databases use SQLite strict tables. Early version-1 development databases may have ordinary-affinity tables; treat all pre-release database files as staging data and verify migration/audit before reuse.

## Deliberate constraints

Embedded single-server SQLite is a coherent consistency boundary, not a horizontally distributed database. Deep audits are linear in journal size and use memory proportional to accounts; they are not free. Inventory and scheduling tests do not constitute an in-game acceptance test. See [testing](TESTING.md) and [security](../SECURITY.md) for the exact scope.
