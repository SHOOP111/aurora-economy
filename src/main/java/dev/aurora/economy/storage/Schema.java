package dev.aurora.economy.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class Schema {
    private Schema() { }
    static void migrate(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            try (var rs = s.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!rs.next() || !"wal".equalsIgnoreCase(rs.getString(1))) throw new SQLException("SQLite WAL mode is required");
            }
            s.execute("PRAGMA synchronous=FULL");
            int version;
            try (var rs = s.executeQuery("PRAGMA user_version")) { rs.next(); version = rs.getInt(1); }
            if (version > 3 || version < 0) throw new SQLException("Unsupported database version; downgrade refused");
            if (version == 3) return;
            if (version == 2) { addSchedulingRevision(c); return; }
            if (version == 1) { addWalletControls(c); addSchedulingRevision(c); return; }
        }
        transaction(c, s -> {
            s.execute("CREATE TABLE currency_specs(code TEXT PRIMARY KEY, decimals INTEGER NOT NULL) STRICT");
            s.execute("CREATE TABLE players(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, seen_at INTEGER NOT NULL) STRICT");
            s.execute("CREATE INDEX players_name ON players(name COLLATE NOCASE, seen_at DESC)");
            s.execute("""
                CREATE TABLE journal(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
                    op_key TEXT NOT NULL UNIQUE, intent TEXT NOT NULL, kind TEXT NOT NULL,
                    actor TEXT NOT NULL, reference TEXT NOT NULL, created_at INTEGER NOT NULL,
                    previous_hash TEXT NOT NULL, hash TEXT NOT NULL) STRICT
                """);
            s.execute("""
                CREATE TABLE entries(tx_id TEXT NOT NULL REFERENCES journal(id), account TEXT NOT NULL,
                    currency TEXT NOT NULL REFERENCES currency_specs(code), delta INTEGER NOT NULL CHECK(delta<>0),
                    PRIMARY KEY(tx_id,account,currency)) STRICT
                """);
            s.execute("CREATE INDEX entries_account ON entries(account,currency)");
            s.execute("""
                CREATE TABLE balances(account TEXT NOT NULL, currency TEXT NOT NULL REFERENCES currency_specs(code),
                    units INTEGER NOT NULL, PRIMARY KEY(account,currency),
                    CHECK((account='system:mint' AND units BETWEEN -9223372036854775807 AND 0)
                       OR (account<>'system:mint' AND units>=0))) STRICT
                """);
            s.execute("""
                CREATE TABLE daily_spend(owner TEXT NOT NULL, currency TEXT NOT NULL,
                    day TEXT NOT NULL, units INTEGER NOT NULL CHECK(units>=0), PRIMARY KEY(owner,currency,day)) STRICT
                """);
            s.execute("""
                CREATE TABLE escrows(id TEXT PRIMARY KEY REFERENCES journal(id) DEFERRABLE INITIALLY DEFERRED, owner TEXT NOT NULL,
                    beneficiary TEXT NOT NULL, currency TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount>0),
                    fee INTEGER NOT NULL CHECK(fee>=0), expires_at INTEGER NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('OPEN','ACCEPTED','CANCELLED','EXPIRED'))) STRICT
                """);
            s.execute("CREATE INDEX escrows_due ON escrows(state,expires_at)");
            s.execute("""
                CREATE TABLE deposits(id TEXT PRIMARY KEY REFERENCES journal(id) DEFERRABLE INITIALLY DEFERRED, owner TEXT NOT NULL,
                    currency TEXT NOT NULL, principal INTEGER NOT NULL CHECK(principal>0),
                    interest INTEGER NOT NULL CHECK(interest>=0), unlock_at INTEGER NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('LOCKED','CLAIMED'))) STRICT
                """);
            s.execute("""
                CREATE TABLE orders(id TEXT PRIMARY KEY REFERENCES journal(id) DEFERRABLE INITIALLY DEFERRED, owner TEXT NOT NULL,
                    beneficiary TEXT NOT NULL, currency TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount>0),
                    fee_bps INTEGER NOT NULL, interval_seconds INTEGER NOT NULL, next_due INTEGER NOT NULL,
                    executions INTEGER NOT NULL, total_executions INTEGER NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('ACTIVE','PAUSED','CANCELLED','COMPLETED')),
                    reason TEXT NOT NULL DEFAULT '') STRICT
                """);
            s.execute("CREATE INDEX orders_due ON orders(state,next_due)");
            // Journal records are immutable through SQL, including accidental application updates.
            for (String table : new String[]{"journal", "entries"}) {
                s.execute("CREATE TRIGGER " + table + "_no_update BEFORE UPDATE ON " + table
                        + " BEGIN SELECT RAISE(ABORT,'immutable ledger'); END");
                s.execute("CREATE TRIGGER " + table + "_no_delete BEFORE DELETE ON " + table
                        + " BEGIN SELECT RAISE(ABORT,'immutable ledger'); END");
            }
            s.execute("PRAGMA user_version=1");
        });
        addWalletControls(c);
        addSchedulingRevision(c);
    }
    private static void addSchedulingRevision(Connection c) throws SQLException {
        transaction(c, s -> {
            // IF NOT EXISTS also tolerates recovery/staging databases with already-created query indexes.
            boolean found = false;
            try (var rs = s.executeQuery("PRAGMA table_info(orders)")) {
                while (rs.next()) if ("revision".equals(rs.getString("name"))) found = true;
            }
            if (!found) s.execute("ALTER TABLE orders ADD COLUMN revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0)");
            s.execute("CREATE INDEX IF NOT EXISTS entries_history ON entries(account,tx_id)");
            s.execute("CREATE INDEX IF NOT EXISTS journal_reference ON journal(reference,kind)");
            s.execute("CREATE INDEX IF NOT EXISTS balances_ranking ON balances(currency,units DESC,account)");
            s.execute("CREATE INDEX IF NOT EXISTS escrows_owner ON escrows(owner,state,currency)");
            s.execute("CREATE INDEX IF NOT EXISTS escrows_beneficiary ON escrows(beneficiary)");
            s.execute("CREATE INDEX IF NOT EXISTS deposits_owner ON deposits(owner,state,currency)");
            s.execute("CREATE INDEX IF NOT EXISTS orders_owner ON orders(owner,state)");
            s.execute("PRAGMA user_version=3");
        });
    }
    private static void addWalletControls(Connection c) throws SQLException {
        transaction(c, s -> {
            s.execute("""
                CREATE TABLE wallet_controls(owner TEXT PRIMARY KEY, frozen INTEGER NOT NULL CHECK(frozen IN (0,1)),
                    reason TEXT NOT NULL, updated_at INTEGER NOT NULL) STRICT
                """);
            s.execute("PRAGMA user_version=2");
        });
    }
    @FunctionalInterface interface Migration { void apply(Statement statement) throws SQLException; }
    static void transaction(Connection c, Migration migration) throws SQLException {
        c.setAutoCommit(false);
        Throwable failure = null;
        boolean safeToReset = false;
        try (Statement s = c.createStatement()) {
            migration.apply(s); c.commit(); safeToReset = true;
        } catch (SQLException | RuntimeException | Error e) {
            failure = e;
            try { c.rollback(); safeToReset = true; }
            catch (SQLException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        } finally {
            // A failed rollback must never be followed by an implicit autocommit of partial DDL.
            if (safeToReset) {
                try { c.setAutoCommit(true); }
                catch (SQLException cleanup) { if (failure != null) failure.addSuppressed(cleanup); else throw cleanup; }
            }
        }
    }
}
