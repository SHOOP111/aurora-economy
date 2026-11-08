package dev.aurora.economy.storage;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.EconomyException;
import dev.aurora.economy.core.Money;
import dev.aurora.economy.core.Models.Audit;
import dev.aurora.economy.core.Models.Posting;
import dev.aurora.economy.core.Models.Receipt;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Single-writer embedded ledger. Domain changes and monetary postings share one transaction. */
public final class LedgerStore implements AutoCloseable {
    public static final String MINT = "system:mint";
    public static final String TREASURY = "system:treasury";
    private final Connection connection;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final FileChannel guardChannel;
    private final FileLock guard;
    private boolean closed;

    @FunctionalInterface public interface SqlRead<T> { T read(Connection c) throws SQLException; }
    @FunctionalInterface public interface Work { String apply(Tx tx) throws SQLException; }
    private record Account(String account, String currency) { }

    public LedgerStore(Path path, Clock clock, List<Currency> currencies) {
        this.clock = clock;
        FileChannel channel = null; FileLock fileLock = null; Connection db = null;
        try {
            path = path.toAbsolutePath();
            Files.createDirectories(path.getParent());
            channel = FileChannel.open(Path.of(path + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = channel.tryLock();
            if (fileLock == null) throw new IOException("Another economy process owns this database");
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + path);
            Schema.migrate(db);
            Map<String, Currency> configured = new HashMap<>();
            for (Currency currency : currencies) {
                if (configured.put(currency.code(), currency) != null) throw new IllegalArgumentException("Duplicate currency");
            }
            try (var s = db.createStatement(); var rs = s.executeQuery("SELECT code,decimals FROM currency_specs")) {
                while (rs.next()) {
                    Currency currency = configured.get(rs.getString(1));
                    if (currency == null || currency.decimals() != rs.getInt(2)) {
                        throw new IOException("Removing currencies or changing persisted precision is forbidden: " + rs.getString(1));
                    }
                }
            }
            try (var s = db.prepareStatement("INSERT OR IGNORE INTO currency_specs VALUES(?,?)")) {
                for (Currency currency : currencies) { s.setString(1, currency.code()); s.setInt(2, currency.decimals()); s.executeUpdate(); }
            }
        } catch (Exception e) {
            try { if (db != null) db.close(); } catch (SQLException ignored) { }
            try { if (fileLock != null) fileLock.release(); if (channel != null) channel.close(); } catch (IOException ignored) { }
            throw new EconomyException(EconomyException.Code.STORAGE, "Cannot open economy database.", e);
        }
        connection = db; guardChannel = channel; guard = fileLock;
    }

    public final class Tx {
        private final String id;
        private final long now;
        private final Map<Account, Long> deltas = new LinkedHashMap<>();
        private Tx(String id, long now) { this.id = id; this.now = now; }
        public Connection connection() { return connection; }
        public String id() { return id; }
        public long now() { return now; }
        public long balance(String account, String currency) throws SQLException {
            return Money.add(storedBalance(connection, account, currency), deltas.getOrDefault(new Account(account, currency), 0L));
        }
        public void move(String from, String to, String currency, long units) {
            if (units == 0) return;
            Money.positive(units);
            post(from, currency, -units); post(to, currency, units);
        }
        private void post(String account, String currency, long units) {
            if (!account.matches("[a-z0-9:_-]{1,120}")) throw new IllegalArgumentException("Invalid internal account");
            Account key = new Account(account, currency);
            deltas.merge(key, units, Money::add);
        }
        private List<Posting> postings() {
            return deltas.entrySet().stream().filter(e -> e.getValue() != 0)
                    .map(e -> new Posting(e.getKey().account(), e.getKey().currency(), e.getValue())).sorted().toList();
        }
    }

    public <T> T read(SqlRead<T> read) {
        lock.lock();
        try { ensureOpen(); return read.read(connection); }
        catch (SQLException e) { throw storage(e); }
        finally { lock.unlock(); }
    }

    public Receipt write(String key, String intent, String kind, String actor, Work work) {
        if (key == null || key.isBlank() || key.length() > 200 || intent == null || intent.length() > 2_000) {
            throw EconomyException.invalid("Invalid request key or intent.");
        }
        lock.lock();
        try {
            ensureOpen(); connection.setAutoCommit(false);
            try {
                try (var s = connection.prepareStatement("SELECT * FROM journal WHERE op_key=?")) {
                    s.setString(1, key);
                    try (var rs = s.executeQuery()) {
                        if (rs.next()) {
                            if (!intent.equals(rs.getString("intent")) || !kind.equals(rs.getString("kind")) || !actor.equals(rs.getString("actor"))) {
                                throw new EconomyException(EconomyException.Code.IDEMPOTENCY_CONFLICT, "Request key was already used for a different operation.");
                            }
                            Receipt replay = receipt(rs, true); connection.commit(); return replay;
                        }
                    }
                }
                String id = UUID.randomUUID().toString(); long now = clock.millis();
                Tx tx = new Tx(id, now);
                String reference = work.apply(tx);
                if (reference == null) reference = "";
                List<Posting> postings = tx.postings();
                Map<String, BigInteger> sums = new HashMap<>();
                for (Posting posting : postings) {
                    sums.merge(posting.currency(), BigInteger.valueOf(posting.delta()), BigInteger::add);
                    long next = Money.add(storedBalance(connection, posting.account(), posting.currency()), posting.delta());
                    if (next < 0 && !posting.account().equals(MINT)) {
                        throw new EconomyException(EconomyException.Code.INSUFFICIENT_FUNDS, "Insufficient " + posting.currency() + " funds.");
                    }
                    if (next == Long.MIN_VALUE || (posting.account().equals(MINT) && next > 0)) {
                        throw EconomyException.invalid("Currency supply exceeds its supported range.");
                    }
                    try (var s = connection.prepareStatement("INSERT INTO balances VALUES(?,?,?) ON CONFLICT(account,currency) DO UPDATE SET units=excluded.units")) {
                        s.setString(1, posting.account()); s.setString(2, posting.currency()); s.setLong(3, next); s.executeUpdate();
                    }
                }
                if (sums.values().stream().anyMatch(value -> value.signum() != 0)) {
                    throw new EconomyException(EconomyException.Code.INTEGRITY, "Unbalanced ledger transaction.");
                }
                String previous = HashChain.GENESIS;
                try (var s = connection.createStatement(); var rs = s.executeQuery("SELECT hash FROM journal ORDER BY seq DESC LIMIT 1")) {
                    if (rs.next()) previous = rs.getString(1);
                }
                String hash = HashChain.digest(previous, id, key, intent, kind, actor, reference, now, postings);
                try (var s = connection.prepareStatement("INSERT INTO journal(id,op_key,intent,kind,actor,reference,created_at,previous_hash,hash) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    Object[] args = {id, key, intent, kind, actor, reference, now, previous, hash};
                    for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
                    s.executeUpdate();
                }
                try (var s = connection.prepareStatement("INSERT INTO entries VALUES(?,?,?,?)")) {
                    for (Posting posting : postings) {
                        s.setString(1, id); s.setString(2, posting.account()); s.setString(3, posting.currency()); s.setLong(4, posting.delta()); s.addBatch();
                    }
                    s.executeBatch();
                }
                connection.commit();
                return new Receipt(id, key, kind, reference, Instant.ofEpochMilli(now), hash, false);
            } catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException e) { throw storage(e); }
        finally { lock.unlock(); }
    }

    public long balance(String account, String currency) { return read(c -> storedBalance(c, account, currency)); }
    public static long storedBalance(Connection c, String account, String currency) throws SQLException {
        try (var s = c.prepareStatement("SELECT units FROM balances WHERE account=? AND currency=?")) {
            s.setString(1, account); s.setString(2, currency);
            try (var rs = s.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }
    public List<Receipt> history(String account, int limit) {
        int bounded = Math.max(1, Math.min(50, limit));
        return read(c -> {
            List<Receipt> result = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT j.* FROM journal j WHERE EXISTS(SELECT 1 FROM entries e WHERE e.tx_id=j.id AND e.account=?) ORDER BY j.seq DESC LIMIT ?")) {
                s.setString(1, account); s.setInt(2, bounded);
                try (var rs = s.executeQuery()) { while (rs.next()) result.add(receipt(rs, false)); }
            }
            return List.copyOf(result);
        });
    }
    private static Receipt receipt(ResultSet rs, boolean replay) throws SQLException {
        return new Receipt(rs.getString("id"), rs.getString("op_key"), rs.getString("kind"), rs.getString("reference"),
                Instant.ofEpochMilli(rs.getLong("created_at")), rs.getString("hash"), replay);
    }

    public Audit audit() {
        return read(c -> {
            List<String> violations = new ArrayList<>();
            Map<Account, BigInteger> projection = new HashMap<>();
            long count = 0, postingCount = 0; String previous = HashChain.GENESIS;
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT * FROM journal ORDER BY seq")) {
                while (rs.next()) {
                    count++; List<Posting> postings = new ArrayList<>(); Map<String, BigInteger> totals = new HashMap<>();
                    try (var p = c.prepareStatement("SELECT account,currency,delta FROM entries WHERE tx_id=? ORDER BY account,currency")) {
                        p.setString(1, rs.getString("id"));
                        try (var entries = p.executeQuery()) {
                            while (entries.next()) {
                                Posting entry = new Posting(entries.getString(1), entries.getString(2), entries.getLong(3));
                                postings.add(entry); postingCount++;
                                projection.merge(new Account(entry.account(), entry.currency()), BigInteger.valueOf(entry.delta()), BigInteger::add);
                                totals.merge(entry.currency(), BigInteger.valueOf(entry.delta()), BigInteger::add);
                            }
                        }
                    }
                    String computed = HashChain.digest(previous, rs.getString("id"), rs.getString("op_key"), rs.getString("intent"),
                            rs.getString("kind"), rs.getString("actor"), rs.getString("reference"), rs.getLong("created_at"), postings);
                    if (!previous.equals(rs.getString("previous_hash")) || !computed.equals(rs.getString("hash"))) {
                        violations.add("Hash mismatch at transaction " + rs.getString("id"));
                    }
                    if (totals.values().stream().anyMatch(n -> n.signum() != 0)) violations.add("Unbalanced transaction " + rs.getString("id"));
                    previous = rs.getString("hash");
                }
            }
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT account,currency,units FROM balances")) {
                while (rs.next()) {
                    Account account = new Account(rs.getString(1), rs.getString(2)); long value = rs.getLong(3);
                    BigInteger expected = projection.remove(account);
                    if (!BigInteger.valueOf(value).equals(expected == null ? BigInteger.ZERO : expected)) violations.add("Balance projection mismatch: " + account);
                    if (value < 0 && !account.account().equals(MINT)) violations.add("Negative account: " + account);
                    if (account.account().equals(MINT) && (value > 0 || value == Long.MIN_VALUE)) violations.add("Invalid issued supply: " + account);
                }
            }
            if (projection.values().stream().anyMatch(n -> n.signum() != 0)) violations.add("Missing materialized balances");
            try (var s = c.createStatement(); var rs = s.executeQuery("PRAGMA foreign_key_check")) {
                if (rs.next()) violations.add("Foreign key integrity violation");
            }
            try (var s = c.createStatement(); var rs = s.executeQuery("PRAGMA quick_check")) {
                if (!rs.next() || !"ok".equals(rs.getString(1))) violations.add("SQLite integrity check failed");
            }
            return new Audit(count, postingCount, previous, violations);
        });
    }

    /** VACUUM INTO is a consistent online snapshot, unlike copying a live WAL database. */
    public Path backup(Path target) {
        return read(c -> {
            try { Files.createDirectories(target.toAbsolutePath().getParent()); }
            catch (IOException e) { throw storage(e); }
            try (PreparedStatement s = c.prepareStatement("VACUUM INTO ?")) {
                s.setString(1, target.toAbsolutePath().toString()); s.execute();
            }
            return target.toAbsolutePath();
        });
    }
    private void ensureOpen() { if (closed) throw new EconomyException(EconomyException.Code.STORAGE, "Economy is shutting down."); }
    private static EconomyException storage(Exception e) { return new EconomyException(EconomyException.Code.STORAGE, "Database operation failed; contact an administrator.", e); }
    @Override public void close() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            try { connection.close(); }
            finally { guard.release(); guardChannel.close(); }
        } catch (SQLException | IOException e) { throw storage(e); }
        finally { lock.unlock(); }
    }
}
