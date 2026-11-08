# Contributing

Aurora's financial invariants take precedence over feature count.

1. Open an issue describing the economic behavior, trust boundary and failure/recovery semantics. Report exploitable security flaws privately instead.
2. Keep business logic independent of Bukkit. Use immutable UUIDs and minor-unit amounts at the adapter boundary.
3. Route every monetary change and associated contract transition through one ledger transaction. Never edit projected balances directly.
4. Require replay keys for new financial APIs. Test both identical retries and conflicting requests.
5. Use integer/big-integer arithmetic, bound user input and explicitly specify rounding.
6. Add real SQLite rollback, ownership, restart and concurrency tests for new contracts. Add a sequential schema migration; never repurpose a known currency scale.
7. Push a branch and open a pull request. **Use the GitHub Actions workflow for all compilation/tests**, rather than running them on the project owner's PC.
8. Inspect both Linux and Windows jobs, coverage reports and artifact checks. Update the economics, API, operations and security docs with any new guarantees or limitations.

Do not add cosmetic feature stubs, silent money creation, mutable journal "repair" commands, auto-trading profit claims, hot reload or mock-only financial tests. Do not add benchmark claims without reproducible workload measurements.

Installed plugins are trusted, but ordinary player input is not. Bukkit inventory changes are not atomic with SQL commits; an item-market feature needs its own durable delivery protocol and explicit recovery plan.

The project is MIT licensed. Maintain compatibility with Java 21/Paper 1.21.4 unless a documented release intentionally changes the baseline. Preserve SQLite native resources and service-loader metadata when changing packaging.
