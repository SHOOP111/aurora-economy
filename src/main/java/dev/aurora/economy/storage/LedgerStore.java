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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Single-writer embedded ledger. Domain changes and monetary postings share one transaction. */
public final class LedgerStore implements AutoCloseable {
    private static final java.util.regex.Pattern ACCOUNT = java.util.regex.Pattern.compile("[a-z0-9:_-]{1,120}");
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(LedgerStore.class.getName());
    public static final String MINT = "system:mint";
    public static final String TREASURY = "system:treasury";
    private final Connection connection;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final FileChannel guardChannel;
    private final FileLock guard;
    private final Path databasePath;
    private final Set<String> currencyCodes;
    private Tx activeTx;
    private boolean closed;
    private boolean broken;
    private volatile boolean frozen;
    public void freeze() { lock.lock(); try { frozen = true; } finally { lock.unlock(); } }
    public boolean frozen() { return frozen; }

    @FunctionalInterface public interface SqlRead<T> { T read(Connection c) throws SQLException; }
    @FunctionalInterface public interface Work { String apply(Tx tx) throws SQLException; }
    private record Account(String account, String currency) { }

    public LedgerStore(Path path, Clock clock, List<Currency> currencies) {
        this.clock = Objects.requireNonNull(clock, "clock");
        currencies = List.copyOf(currencies);
        Map<String, Currency> configured = new HashMap<>();
        if (currencies.isEmpty()) throw new IllegalArgumentException("At least one currency is required");
        for (Currency currency : currencies) {
            if (configured.put(currency.code(), currency) != null) throw new IllegalArgumentException("Duplicate currency");
        }
        currencyCodes = Set.copyOf(configured.keySet());
        FileChannel channel = null; FileLock fileLock = null; Connection db = null;
        try {
            path = path.toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            path = Files.exists(path) ? path.toRealPath() : path.getParent().toRealPath().resolve(path.getFileName());
            channel = FileChannel.open(Path.of(path + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = channel.tryLock();
            if (fileLock == null) throw new IOException("Another economy process owns this database");
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + path);
            Schema.migrate(db);
            try (var s = db.createStatement(); var rs = s.executeQuery("SELECT code,decimals FROM currency_specs")) {
                while (rs.next()) {
                    Currency currency = configured.get(rs.getString(1));
                    if (currency == null || currency.decimals() != rs.getInt(2)) {
                        throw new IOException("Removing currencies or changing persisted precision is forbidden: " + rs.getString(1));
                    }
                }
            }
            Connection opened = db;
            Schema.transaction(opened, ignored -> {
                try (var s = opened.prepareStatement("INSERT OR IGNORE INTO currency_specs VALUES(?,?)")) {
                    for (Currency currency : configured.values()) { s.setString(1, currency.code()); s.setInt(2, currency.decimals()); s.addBatch(); }
                    s.executeBatch();
                }
            });
        } catch (Exception e) {
            try { if (db != null) db.close(); } catch (SQLException cleanup) { e.addSuppressed(cleanup); }
            try { if (fileLock != null) fileLock.release(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            try { if (channel != null) channel.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw new EconomyException(EconomyException.Code.STORAGE, "Cannot open economy database.", e);
        }
        connection = db; guardChannel = channel; guard = fileLock; databasePath = path;
    }

    public final class Tx {
        private final String id;
        private final long now;
        private final Map<Account, Long> deltas = new LinkedHashMap<>();
        private Tx(String id, long now) { this.id = id; this.now = now; }
        private void ensureActive() {
            if (!lock.isHeldByCurrentThread() || activeTx != this) throw new IllegalStateException("Transaction is no longer active on this thread");
        }
        public Connection connection() { ensureActive(); return connection; }
        public String id() { ensureActive(); return id; }
        public long now() { ensureActive(); return now; }
        public long balance(String account, String currency) throws SQLException {
            ensureActive(); validateAccount(account, currency);
            return Money.add(storedBalance(connection, account, currency), deltas.getOrDefault(new Account(account, currency), 0L));
        }
        public void move(String from, String to, String currency, long units) {
            ensureActive(); validateAccount(from, currency); validateAccount(to, currency);
            if (from.equals(to)) throw new IllegalArgumentException("Movement requires different accounts");
            if (units == 0) return;
            Money.positive(units);
            post(from, currency, -units); post(to, currency, units);
        }
        private void post(String account, String currency, long units) {
            Account key = new Account(account, currency);
            deltas.merge(key, units, Money::add);
        }
        private List<Posting> postings() {
            return deltas.entrySet().stream().filter(e -> e.getValue() != 0)
                    .map(e -> new Posting(e.getKey().account(), e.getKey().currency(), e.getValue())).sorted().toList();
        }
    }

    private void validateAccount(String account, String currency) {
        if (account == null || !ACCOUNT.matcher(account).matches()) throw new IllegalArgumentException("Invalid internal account");
        if (currency == null || !currencyCodes.contains(currency)) throw new EconomyException(EconomyException.Code.UNKNOWN_CURRENCY, "Unknown ledger currency");
    }

    public <T> T read(SqlRead<T> read) {
        lock.lock();
        try { ensureOpen(); return read.read(connection); }
        catch (SQLException e) { throw storage(e); }
        finally { lock.unlock(); }
    }

    public Receipt write(String key, String intent, String kind, String actor, Work work) {
        return write(key, intent, kind, actor, work, null);
    }

    /** A matching replay may atomically refresh nonfinancial registration metadata. */
    public Receipt write(String key, String intent, String kind, String actor, Work work, SqlRead<Void> onReplay) {
        if (key == null || key.isBlank() || key.length() > 200 || intent == null || intent.length() > 2_000
                || kind == null || !kind.matches("[A-Z_]{1,32}") || actor == null || actor.isBlank() || actor.length() > 120) {
            throw EconomyException.invalid("Invalid request metadata.");
        }
        Objects.requireNonNull(work, "work");
        lock.lock();
        try {
            ensureOpen();
            if (activeTx != null || !connection.getAutoCommit()) throw new EconomyException(EconomyException.Code.INVALID_STATE, "Nested ledger writes are forbidden.");
            if (frozen) throw new EconomyException(EconomyException.Code.INTEGRITY, "Ledger is frozen after a failed audit. Stop the server and investigate.");
            connection.setAutoCommit(false);
            Throwable failure = null;
            try {
                Receipt replay = null;
                try (var s = connection.prepareStatement("SELECT * FROM journal WHERE op_key=?")) {
                    s.setString(1, key);
                    try (var rs = s.executeQuery()) {
                        if (rs.next()) {
                            if (!intent.equals(rs.getString("intent")) || !kind.equals(rs.getString("kind")) || !actor.equals(rs.getString("actor"))) {
                                throw new EconomyException(EconomyException.Code.IDEMPOTENCY_CONFLICT, "Request key was already used for a different operation.");
                            }
                            replay = receipt(rs, true);
                        }
                    }
                }
                if (replay != null) {
                    if (onReplay != null) onReplay.read(connection);
                    connection.commit(); return replay;
                }
                String id = UUID.randomUUID().toString(); long now = clock.millis();
                Tx tx = new Tx(id, now); activeTx = tx;
                String reference = work.apply(tx);
                if (reference == null) reference = "";
                if (reference.length() > 2_000) throw EconomyException.invalid("Transaction reference is too long.");
                List<Posting> postings = tx.postings();
                Map<String, BigInteger> sums = new HashMap<>();
                Map<Account, Long> nextBalances = new LinkedHashMap<>();
                for (Posting posting : postings) {
                    sums.merge(posting.currency(), BigInteger.valueOf(posting.delta()), BigInteger::add);
                    long next = Money.add(storedBalance(connection, posting.account(), posting.currency()), posting.delta());
                    if (next < 0 && !posting.account().equals(MINT)) {
                        throw new EconomyException(EconomyException.Code.INSUFFICIENT_FUNDS, "Insufficient " + posting.currency() + " funds.");
                    }
                    if (next == Long.MIN_VALUE || (posting.account().equals(MINT) && next > 0)) {
                        throw EconomyException.invalid("Currency supply exceeds its supported range.");
                    }
                    nextBalances.put(new Account(posting.account(), posting.currency()), next);
                }
                if (sums.values().stream().anyMatch(value -> value.signum() != 0)) {
                    freeze(); throw new EconomyException(EconomyException.Code.INTEGRITY, "Unbalanced ledger transaction.");
                }
                try (var s = connection.prepareStatement("INSERT INTO balances VALUES(?,?,?) ON CONFLICT(account,currency) DO UPDATE SET units=excluded.units")) {
                    for (var entry : nextBalances.entrySet()) {
                        s.setString(1, entry.getKey().account()); s.setString(2, entry.getKey().currency()); s.setLong(3, entry.getValue()); s.addBatch();
                    }
                    s.executeBatch();
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
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
                try { connection.rollback(); }
                catch (SQLException cleanup) { broken = true; frozen = true; e.addSuppressed(cleanup); }
                throw e;
            } finally {
                activeTx = null;
                // Never switch to autocommit after a failed rollback: that could commit partial work.
                if (!broken) {
                    try { connection.setAutoCommit(true); }
                    catch (SQLException cleanup) {
                        broken = true; frozen = true;
                        if (failure != null) failure.addSuppressed(cleanup);
                        else LOGGER.log(java.util.logging.Level.SEVERE, "Ledger commit succeeded but connection cleanup failed; writer is unavailable. Preserve the successful receipt and do not retry with a new key.", cleanup);
                    }
                }
            }
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
            try (var s = c.prepareStatement("SELECT j.* FROM journal j JOIN (SELECT DISTINCT tx_id FROM entries WHERE account=?) e ON e.tx_id=j.id ORDER BY j.seq DESC LIMIT ?")) {
                s.setString(1, account); s.setInt(2, bounded);
                try (var rs = s.executeQuery()) { while (rs.next()) result.add(receipt(rs, false)); }
            }
            return List.copyOf(result);
        });
    }
    public List<dev.aurora.economy.core.Models.Statement> statement(String account, int limit) {
        return read(c -> {
            List<dev.aurora.economy.core.Models.Statement> rows = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT j.*,e.currency,e.delta FROM journal j JOIN entries e ON e.tx_id=j.id WHERE e.account=? ORDER BY j.seq DESC,e.currency LIMIT ?")) {
                s.setString(1, account); s.setInt(2, Math.max(1, Math.min(limit, 50)));
                try (var rs = s.executeQuery()) {
                    while (rs.next()) rows.add(new dev.aurora.economy.core.Models.Statement(receipt(rs, false), rs.getString("currency"), rs.getLong("delta")));
                }
            }
            return List.copyOf(rows);
        });
    }
    private static Receipt receipt(ResultSet rs, boolean replay) throws SQLException {
        return new Receipt(rs.getString("id"), rs.getString("op_key"), rs.getString("kind"), rs.getString("reference"),
                Instant.ofEpochMilli(rs.getLong("created_at")), rs.getString("hash"), replay);
    }

    public Audit audit() {
        try { return auditConsistent(); }
        catch (RuntimeException e) { freeze(); throw e; }
    }
    private Audit auditConsistent() {
        return read(c -> {
            List<String> violations = new ArrayList<>();
            Map<Account, BigInteger> projection = new HashMap<>();
            long count = 0, postingCount = 0; String previous = HashChain.GENESIS;
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT * FROM journal ORDER BY seq");
                 var p = c.prepareStatement("SELECT account,currency,delta FROM entries WHERE tx_id=? ORDER BY account,currency")) {
                while (rs.next()) {
                    count++; List<Posting> postings = new ArrayList<>(); Map<String, BigInteger> totals = new HashMap<>();
                    p.setString(1, rs.getString("id"));
                    try (var entries = p.executeQuery()) {
                        while (entries.next()) {
                            Posting entry = new Posting(entries.getString(1), entries.getString(2), entries.getLong(3));
                            postings.add(entry); postingCount++;
                            Account account = new Account(entry.account(), entry.currency());
                            BigInteger projected = projection.merge(account, BigInteger.valueOf(entry.delta()), BigInteger::add);
                            BigInteger minimum = entry.account().equals(MINT) ? BigInteger.valueOf(-Long.MAX_VALUE) : BigInteger.ZERO;
                            BigInteger maximum = entry.account().equals(MINT) ? BigInteger.ZERO : BigInteger.valueOf(Long.MAX_VALUE);
                            if (projected.compareTo(minimum) < 0 || projected.compareTo(maximum) > 0) violations.add("Historical balance outside range: " + account);
                            if (!currencyCodes.contains(entry.currency()) || !ACCOUNT.matcher(entry.account()).matches() || entry.delta() == 0) {
                                violations.add("Invalid posting in transaction " + rs.getString("id"));
                            }
                            totals.merge(entry.currency(), BigInteger.valueOf(entry.delta()), BigInteger::add);
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
                    if (!currencyCodes.contains(account.currency()) || !ACCOUNT.matcher(account.account()).matches()) violations.add("Invalid materialized account: " + account);
                    if (value < 0 && !account.account().equals(MINT)) violations.add("Negative account: " + account);
                    if (account.account().equals(MINT) && (value > 0 || value == Long.MIN_VALUE)) violations.add("Invalid issued supply: " + account);
                }
            }
            if (projection.values().stream().anyMatch(n -> n.signum() != 0)) violations.add("Missing materialized balances");
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT (SELECT COUNT(*) FROM balances WHERE typeof(units)<>'integer')+(SELECT COUNT(*) FROM entries WHERE typeof(delta)<>'integer')+(SELECT COUNT(*) FROM journal WHERE typeof(created_at)<>'integer')")) {
                if (rs.next() && rs.getLong(1) != 0) violations.add("Non-integer monetary or timestamp storage detected");
            }
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM entries")) {
                if (!rs.next() || rs.getLong(1) != postingCount) violations.add("Orphan ledger entries detected");
            }
            try (var s = c.createStatement(); var rs = s.executeQuery("PRAGMA foreign_keys")) {
                if (!rs.next() || rs.getInt(1) != 1) violations.add("Foreign key enforcement is disabled");
            }
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT name FROM sqlite_schema WHERE type='trigger' AND name IN ('journal_no_update','journal_no_delete','entries_no_update','entries_no_delete')")) {
                int triggers = 0; while (rs.next()) triggers++;
                if (triggers != 4) violations.add("Immutable ledger triggers are missing");
            }
            try (var s = c.createStatement(); var rs = s.executeQuery("PRAGMA foreign_key_check")) {
                if (rs.next()) violations.add("Foreign key integrity violation");
            }
            try (var s = c.createStatement(); var rs = s.executeQuery("PRAGMA quick_check")) {
                if (!rs.next() || !"ok".equals(rs.getString(1))) violations.add("SQLite integrity check failed");
            }
            Audit report = new Audit(count, postingCount, previous, violations);
            if (!report.healthy()) freeze();
            return report;
        });
    }

    /** VACUUM INTO is a consistent online snapshot, unlike copying a live WAL database. */
    public Path backup(Path target) {
        Objects.requireNonNull(target, "target");
        return read(c -> {
            if (!c.getAutoCommit()) throw new EconomyException(EconomyException.Code.INVALID_STATE, "Cannot snapshot an active transaction.");
            Path destination = target.toAbsolutePath().normalize();
            try {
                Files.createDirectories(destination.getParent());
                destination = destination.getParent().toRealPath().resolve(destination.getFileName());
                if (destination.equals(databasePath) || Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Backup destination must be a new file");
                }
            } catch (IOException e) { throw storage(e); }
            try (PreparedStatement s = c.prepareStatement("VACUUM INTO ?")) {
                s.setString(1, destination.toString()); s.execute();
            }
            return destination;
        });
    }
    private void ensureOpen() {
        if (closed || broken) throw new EconomyException(EconomyException.Code.STORAGE, "Economy is shutting down or its connection is unavailable.");
    }
    private static EconomyException storage(Exception e) { return new EconomyException(EconomyException.Code.STORAGE, "Database operation failed; contact an administrator.", e); }
    @Override public void close() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            Exception failure = null;
            try { connection.close(); } catch (SQLException e) { failure = e; }
            try { guard.release(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            try { guardChannel.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            if (failure != null) throw storage(failure);
        }
        finally { lock.unlock(); }
    }
}
