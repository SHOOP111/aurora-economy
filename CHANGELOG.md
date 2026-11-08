# Changelog

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
