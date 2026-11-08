package dev.aurora.economy.core;

import dev.aurora.economy.core.Models.*;
import dev.aurora.economy.storage.LedgerStore;
import dev.aurora.economy.storage.LedgerStore.Tx;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static dev.aurora.economy.core.EconomyException.Code.*;
import static dev.aurora.economy.storage.LedgerStore.MINT;
import static dev.aurora.economy.storage.LedgerStore.TREASURY;

/** Framework-independent business logic. Every public financial mutation is replay-safe. */
public final class EconomyEngine {
    private static final String UUID_GRAMMAR = "[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}";
    private static final java.util.regex.Pattern FULL_UUID = java.util.regex.Pattern.compile(UUID_GRAMMAR);
    private static final java.util.regex.Pattern PLAYER_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final java.util.regex.Pattern REQUEST_ID = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,96}");
    private static final java.util.regex.Pattern PLAYER_WALLET = java.util.regex.Pattern.compile("player:[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}:wallet");
    private final LedgerStore store;
    private final Clock clock;
    private final Policy policy;
    private final Map<String, Currency> currencies;
    public record Maintenance(int expired, int paid, int paused) { }

    public EconomyEngine(LedgerStore store, Clock clock, Policy policy, List<Currency> configured) {
        if (configured.isEmpty()) throw new IllegalArgumentException("At least one currency is required");
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        Map<String, Currency> map = new LinkedHashMap<>();
        for (Currency currency : configured) {
            if (map.put(currency.code(), currency) != null) throw new IllegalArgumentException("Duplicate currency");
        }
        currencies = java.util.Collections.unmodifiableMap(map);
    }
    public Collection<Currency> currencies() { return currencies.values(); }
    public Policy policy() { return policy; }
    public Currency currency(String code) {
        if (code == null) throw new EconomyException(UNKNOWN_CURRENCY, "Currency is required.");
        Currency currency = currencies.get(code.toUpperCase(Locale.ROOT));
        if (currency == null) throw new EconomyException(UNKNOWN_CURRENCY, "Unknown currency: " + code);
        return currency;
    }
    private static UUID requireUuid(UUID owner) {
        if (owner == null) throw EconomyException.invalid("Player UUID is required.");
        return owner;
    }
    public static String wallet(UUID owner) { return "player:" + requireUuid(owner) + ":wallet"; }
    public static String escrowAccount(String id) { return "escrow:" + canonicalId(id); }
    public static String depositAccount(String id) { return "vault:" + canonicalId(id); }
    public String poolAccount(String a, String b) {
        String first = currency(a).code(), second = currency(b).code();
        if (first.equals(second)) throw EconomyException.invalid("Select two different currencies.");
        return "system:pool:" + (first.compareTo(second) < 0 ? first + "_" + second : second + "_" + first).toLowerCase(Locale.ROOT);
    }
    private static String intent(Object... values) {
        // Variable free text is only ever the last field. All earlier fields have fixed safe grammars.
        return java.util.Arrays.stream(values).map(Object::toString).collect(java.util.stream.Collectors.joining("|"));
    }
    private static String key(String kind, UUID actor, String request) {
        requireUuid(actor);
        if (request == null || !REQUEST_ID.matcher(request).matches()) throw EconomyException.invalid("Invalid request identifier.");
        return kind + ":" + actor + ":" + request;
    }
    private static void different(UUID a, UUID b) {
        if (requireUuid(a).equals(requireUuid(b))) throw EconomyException.invalid("You cannot pay yourself.");
    }
    private static PreparedStatement sql(Connection c, String query, Object... args) throws SQLException {
        PreparedStatement statement = c.prepareStatement(query);
        try { for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]); }
        catch (SQLException e) { statement.close(); throw e; }
        return statement;
    }
    private static void execute(Connection c, String query, Object... args) throws SQLException {
        try (var s = sql(c, query, args)) { s.executeUpdate(); }
    }
    private static long scalar(Connection c, String query, Object... args) throws SQLException {
        try (var s = sql(c, query, args); var rs = s.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
    }

    public Receipt register(UUID player, String name) {
        requireUuid(player);
        String safeName = name != null && PLAYER_NAME.matcher(name).matches() ? name : player.toString();
        LedgerStore.SqlRead<Void> refresh = c -> {
            execute(c, "INSERT INTO players(uuid,name,seen_at) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=CASE WHEN excluded.name=excluded.uuid THEN players.name ELSE excluded.name END,seen_at=MAX(players.seen_at,excluded.seen_at)", player.toString(), safeName, clock.millis());
            return null;
        };
        return store.write("REGISTER:" + player, player.toString(), "REGISTER", player.toString(), tx -> {
            refresh.read(tx.connection());
            for (Currency currency : currencies.values()) tx.move(MINT, wallet(player), currency.code(), currency.startingBalance());
            return player.toString();
        }, refresh);
    }
    /** Only known names are resolved. Never invokes blocking Mojang lookup or invents offline UUIDs. */
    public UUID resolve(String name) {
        if (name == null) throw EconomyException.invalid("Player name or UUID is required.");
        if (FULL_UUID.matcher(name).matches()) return UUID.fromString(name);
        if (!PLAYER_NAME.matcher(name).matches()) throw EconomyException.invalid("Use a player name or full UUID.");
        return store.read(c -> {
            try (var s = sql(c, "SELECT uuid FROM players WHERE name=? COLLATE NOCASE ORDER BY seen_at DESC,uuid LIMIT 1", name); var rs = s.executeQuery()) {
                if (!rs.next()) throw new EconomyException(NOT_FOUND, "Player must have joined this server, or use their UUID.");
                return UUID.fromString(rs.getString(1));
            }
        });
    }
    public boolean accountExists(UUID owner) {
        requireUuid(owner);
        return store.read(c -> scalar(c, "SELECT COUNT(*) FROM players WHERE uuid=?", owner.toString()) > 0);
    }
    public long balance(UUID owner, String code) { return store.balance(wallet(owner), currency(code).code()); }
    public long treasury(String code) { return store.balance(TREASURY, currency(code).code()); }
    public List<Receipt> history(UUID player, int limit) { return store.history(wallet(player), limit); }
    public List<Models.Statement> statement(UUID player, int limit) { return store.statement(wallet(player), limit); }
    public List<Leader> leaders(String code, int limit) {
        String currency = currency(code).code();
        return store.read(c -> {
            List<Leader> rows = new ArrayList<>();
            try (var s = sql(c, "SELECT b.account,COALESCE(p.name,substr(b.account,8,36)),b.units FROM balances b LEFT JOIN players p ON b.account='player:'||p.uuid||':wallet' WHERE b.currency=? AND b.account LIKE 'player:%:wallet' ORDER BY b.units DESC,b.account LIMIT ?", currency, Math.max(1, Math.min(limit, 50))); var rs = s.executeQuery()) {
                while (rs.next()) rows.add(new Leader(UUID.fromString(rs.getString(1).substring(7, 43)), rs.getString(2), rs.getLong(3)));
            }
            return List.copyOf(rows);
        });
    }

    public WalletControl control(UUID owner) { requireUuid(owner); return store.read(c -> control(c, owner)); }
    private static WalletControl control(Connection c, UUID owner) throws SQLException {
        try (var s = sql(c, "SELECT frozen,reason,updated_at FROM wallet_controls WHERE owner=?", owner.toString()); var rs = s.executeQuery()) {
            return rs.next() ? new WalletControl(owner, rs.getInt(1) == 1, rs.getString(2), Instant.ofEpochMilli(rs.getLong(3)))
                    : new WalletControl(owner, false, "", Instant.EPOCH);
        }
    }
    public Receipt setFrozen(UUID actor, UUID owner, boolean frozen, String reason, String request) {
        requireUuid(owner);
        if (reason == null || reason.isBlank() || reason.length() > 160 || reason.chars().anyMatch(Character::isISOControl)) {
            throw EconomyException.invalid("Supply a printable freeze reason of 1 to 160 characters.");
        }
        String kind = frozen ? "WALLET_FREEZE" : "WALLET_UNFREEZE";
        return store.write(key(kind, actor, request), intent(owner, frozen, reason), kind, actor.toString(), tx -> {
            execute(tx.connection(), "INSERT INTO wallet_controls VALUES(?,?,?,?) ON CONFLICT(owner) DO UPDATE SET frozen=excluded.frozen,reason=excluded.reason,updated_at=excluded.updated_at",
                    owner.toString(), frozen ? 1 : 0, reason, tx.now()); return owner.toString();
        });
    }
    private static void spendable(Tx tx, UUID owner) throws SQLException {
        WalletControl control = control(tx.connection(), owner);
        if (control.frozen()) throw new EconomyException(ACCOUNT_FROZEN, "Wallet is frozen: " + control.reason());
    }
    public Receipt transfer(UUID owner, UUID recipient, String code, long amount, String request) {
        different(owner, recipient); Money.positive(amount); String currency = currency(code).code();
        return store.write(key("PAY", owner, request), intent(owner, recipient, currency, amount), "PAY", owner.toString(), tx -> {
            payment(tx, owner, recipient, currency, amount, policy.transferFeeBps()); return recipient.toString();
        });
    }
    private void payment(Tx tx, UUID owner, UUID recipient, String code, long amount, int feeBps) throws SQLException {
        spendable(tx, owner);
        long fee = Money.fee(amount, feeBps), total = Money.add(amount, fee);
        spend(tx, owner, code, total);
        tx.move(wallet(owner), wallet(recipient), code, amount);
        tx.move(wallet(owner), TREASURY, code, fee);
    }
    private void spend(Tx tx, UUID owner, String code, long amount) throws SQLException {
        String day = Instant.ofEpochMilli(tx.now()).atZone(ZoneOffset.UTC).toLocalDate().toString();
        long prior = scalar(tx.connection(), "SELECT units FROM daily_spend WHERE owner=? AND currency=? AND day=?", owner.toString(), code, day);
        long limit = currency(code).dailyLimit();
        if (limit > 0 && (prior >= limit || amount > limit - prior)) throw new EconomyException(DAILY_LIMIT, "UTC daily spending limit reached for " + code + ".");
        // Unlimited circulation can exceed the counter's range without exceeding issued supply.
        long next = amount > Long.MAX_VALUE - prior ? Long.MAX_VALUE : prior + amount;
        execute(tx.connection(), "INSERT INTO daily_spend VALUES(?,?,?,?) ON CONFLICT(owner,currency,day) DO UPDATE SET units=excluded.units", owner.toString(), code, day, next);
    }
    /** Used by authorized administration / Vault only; attribution is always journaled. */
    public Receipt issue(UUID actor, String targetAccount, String code, long amount, boolean burn, String request, String source) {
        requireUuid(actor);
        Money.positive(amount); String currency = currency(code).code();
        if (targetAccount == null) throw new EconomyException(FORBIDDEN, "Invalid issuance target.");
        if (!targetAccount.equals(TREASURY) && !PLAYER_WALLET.matcher(targetAccount).matches()) {
            throw new EconomyException(FORBIDDEN, "Invalid issuance target.");
        }
        if (source == null || !List.of("ADMIN_MINT", "ADMIN_BURN", "VAULT_DEPOSIT", "VAULT_WITHDRAW").contains(source)
                || burn != (source.equals("ADMIN_BURN") || source.equals("VAULT_WITHDRAW"))) {
            throw new EconomyException(FORBIDDEN, "Invalid issuance source or direction.");
        }
        if (source.startsWith("VAULT_") && !targetAccount.equals(wallet(actor))) {
            throw new EconomyException(FORBIDDEN, "Vault operations must target the attributed player's wallet.");
        }
        return store.write(key(source, actor, request), intent(targetAccount, currency, amount, burn), source, actor.toString(), tx -> {
            if (source.equals("VAULT_WITHDRAW")) { spendable(tx, actor); spend(tx, actor, currency, amount); }
            if (burn) tx.move(targetAccount, MINT, currency, amount); else tx.move(MINT, targetAccount, currency, amount);
            return targetAccount;
        });
    }

    public Receipt createEscrow(UUID owner, UUID beneficiary, String code, long amount, long lifetimeSeconds, String request) {
        different(owner, beneficiary); Money.positive(amount); String currency = currency(code).code();
        if (lifetimeSeconds < 60 || lifetimeSeconds > 30L * 86_400) throw EconomyException.invalid("Escrow lifetime must be 1 minute to 30 days.");
        return store.write(key("ESCROW_CREATE", owner, request), intent(owner, beneficiary, currency, amount, lifetimeSeconds), "ESCROW_CREATE", owner.toString(), tx -> {
            spendable(tx, owner);
            long active = scalar(tx.connection(), "SELECT COUNT(*) FROM escrows WHERE owner=? AND state='OPEN'", owner.toString());
            if (active >= policy.maxOpenEscrows()) throw new EconomyException(LIMIT_REACHED, "Too many open escrows.");
            long fee = Money.fee(amount, policy.transferFeeBps()), total = Money.add(amount, fee);
            spend(tx, owner, currency, total);
            tx.move(wallet(owner), escrowAccount(tx.id()), currency, total);
            execute(tx.connection(), "INSERT INTO escrows VALUES(?,?,?,?,?,?,?,'OPEN')", tx.id(), owner.toString(), beneficiary.toString(), currency, amount, fee, Money.add(tx.now(), lifetimeSeconds * 1_000));
            return tx.id();
        });
    }
    public Receipt acceptEscrow(UUID beneficiary, String id, String request) {
        id = canonicalId(id); String target = id;
        return store.write(key("ESCROW_ACCEPT", beneficiary, request), intent(beneficiary, id), "ESCROW_ACCEPT", beneficiary.toString(), tx -> {
            Escrow escrow = escrow(tx.connection(), target);
            if (!escrow.beneficiary().equals(beneficiary)) throw new EconomyException(FORBIDDEN, "Only the beneficiary can accept this escrow.");
            open(escrow.state());
            if (tx.now() >= escrow.expires().toEpochMilli()) throw new EconomyException(EXPIRED, "This escrow has expired; a refund is being processed.");
            tx.move(escrowAccount(target), wallet(beneficiary), escrow.currency(), escrow.amount());
            tx.move(escrowAccount(target), TREASURY, escrow.currency(), escrow.fee());
            execute(tx.connection(), "UPDATE escrows SET state='ACCEPTED' WHERE id=?", target); return target;
        });
    }
    public Receipt cancelEscrow(UUID owner, String id, String request) {
        String target = canonicalId(id);
        return store.write(key("ESCROW_CANCEL", owner, request), intent(owner, target), "ESCROW_CANCEL", owner.toString(), tx -> {
            Escrow escrow = escrow(tx.connection(), target);
            if (!escrow.owner().equals(owner)) throw new EconomyException(FORBIDDEN, "Only the payer can cancel this escrow.");
            open(escrow.state()); refund(tx, escrow, "CANCELLED"); return target;
        });
    }
    private static void open(String state) {
        if (!state.equals("OPEN")) throw new EconomyException(INVALID_STATE, "Escrow is already " + state.toLowerCase(Locale.ROOT) + ".");
    }
    private void refund(Tx tx, Escrow escrow, String state) throws SQLException {
        tx.move(escrowAccount(escrow.id()), wallet(escrow.owner()), escrow.currency(), Money.add(escrow.amount(), escrow.fee()));
        execute(tx.connection(), "UPDATE escrows SET state=? WHERE id=?", state, escrow.id());
    }
    public List<Escrow> escrows(UUID player) {
        requireUuid(player);
        return store.read(c -> {
            List<Escrow> result = new ArrayList<>();
            try (var s = sql(c, "SELECT * FROM escrows WHERE owner=? OR beneficiary=? ORDER BY (state='OPEN') DESC,expires_at DESC LIMIT 100", player.toString(), player.toString()); var rs = s.executeQuery()) {
                while (rs.next()) result.add(mapEscrow(rs));
            }
            return List.copyOf(result);
        });
    }
    private static Escrow escrow(Connection c, String id) throws SQLException {
        try (var s = sql(c, "SELECT * FROM escrows WHERE id=?", id); var rs = s.executeQuery()) {
            if (!rs.next()) throw new EconomyException(NOT_FOUND, "Escrow not found."); return mapEscrow(rs);
        }
    }
    private static Escrow mapEscrow(ResultSet rs) throws SQLException {
        return new Escrow(rs.getString("id"), UUID.fromString(rs.getString("owner")), UUID.fromString(rs.getString("beneficiary")), rs.getString("currency"),
                rs.getLong("amount"), rs.getLong("fee"), Instant.ofEpochMilli(rs.getLong("expires_at")), rs.getString("state"));
    }

    public Receipt openDeposit(UUID owner, String code, long principal, int days, String request) {
        Money.positive(principal); String currency = currency(code).code();
        if (days < 1 || days > 365) throw EconomyException.invalid("Savings term must be 1 to 365 days.");
        return store.write(key("BANK_OPEN", owner, request), intent(owner, currency, principal, days), "BANK_OPEN", owner.toString(), tx -> {
            spendable(tx, owner);
            if (scalar(tx.connection(), "SELECT COUNT(*) FROM deposits WHERE owner=? AND state='LOCKED'", owner.toString()) >= policy.maxOpenDeposits()) {
                throw new EconomyException(LIMIT_REACHED, "Too many locked savings deposits.");
            }
            long interest = Money.interest(principal, policy.savingsAprBps(), days * 86_400L);
            Money.add(principal, interest);
            if (tx.balance(TREASURY, currency) < interest) throw new EconomyException(INSUFFICIENT_FUNDS, "Treasury cannot fund this yield. Ask an administrator to fund it.");
            tx.move(wallet(owner), depositAccount(tx.id()), currency, principal);
            // Reserve the full promised yield now: maturity never depends on future minting.
            tx.move(TREASURY, depositAccount(tx.id()), currency, interest);
            execute(tx.connection(), "INSERT INTO deposits VALUES(?,?,?,?,?,?,'LOCKED')", tx.id(), owner.toString(), currency, principal, interest, Money.add(tx.now(), days * 86_400_000L));
            return tx.id();
        });
    }
    public Receipt claimDeposit(UUID owner, String id, String request) {
        String target = canonicalId(id);
        return store.write(key("BANK_CLAIM", owner, request), intent(owner, target), "BANK_CLAIM", owner.toString(), tx -> {
            Deposit deposit = deposit(tx.connection(), target);
            if (!deposit.owner().equals(owner)) throw new EconomyException(FORBIDDEN, "This deposit belongs to another player.");
            spendable(tx, owner);
            if (!deposit.state().equals("LOCKED")) throw new EconomyException(INVALID_STATE, "Deposit has already been claimed.");
            if (tx.now() < deposit.unlocks().toEpochMilli()) throw new EconomyException(LOCKED, "Deposit unlocks at " + deposit.unlocks() + ".");
            tx.move(depositAccount(target), wallet(owner), deposit.currency(), Money.add(deposit.principal(), deposit.interest()));
            execute(tx.connection(), "UPDATE deposits SET state='CLAIMED' WHERE id=?", target); return target;
        });
    }
    public List<Deposit> deposits(UUID owner) {
        requireUuid(owner);
        return store.read(c -> {
            List<Deposit> result = new ArrayList<>();
            try (var s = sql(c, "SELECT * FROM deposits WHERE owner=? ORDER BY (state='LOCKED') DESC,unlock_at DESC LIMIT 100", owner.toString()); var rs = s.executeQuery()) {
                while (rs.next()) result.add(mapDeposit(rs));
            }
            return List.copyOf(result);
        });
    }
    private static Deposit deposit(Connection c, String id) throws SQLException {
        try (var s = sql(c, "SELECT * FROM deposits WHERE id=?", id); var rs = s.executeQuery()) {
            if (!rs.next()) throw new EconomyException(NOT_FOUND, "Savings deposit not found."); return mapDeposit(rs);
        }
    }
    private static Deposit mapDeposit(ResultSet rs) throws SQLException {
        return new Deposit(rs.getString("id"), UUID.fromString(rs.getString("owner")), rs.getString("currency"), rs.getLong("principal"),
                rs.getLong("interest"), Instant.ofEpochMilli(rs.getLong("unlock_at")), rs.getString("state"));
    }

    public Receipt createOrder(UUID owner, UUID beneficiary, String code, long amount, long intervalSeconds, int total, String request) {
        different(owner, beneficiary); Money.positive(amount); String currency = currency(code).code();
        if (intervalSeconds < 60 || intervalSeconds > 30L * 86_400 || total < 1 || total > 10_000) throw EconomyException.invalid("Interval: 1 minute to 30 days; repetitions: 1 to 10000.");
        Money.add(amount, Money.fee(amount, policy.transferFeeBps()));
        return store.write(key("ORDER_CREATE", owner, request), intent(owner, beneficiary, currency, amount, intervalSeconds, total), "ORDER_CREATE", owner.toString(), tx -> {
            spendable(tx, owner);
            if (scalar(tx.connection(), "SELECT COUNT(*) FROM orders WHERE owner=? AND state IN ('ACTIVE','PAUSED')", owner.toString()) >= policy.maxActiveOrders()) {
                throw new EconomyException(LIMIT_REACHED, "Too many active or paused recurring payments.");
            }
            execute(tx.connection(), "INSERT INTO orders(id,owner,beneficiary,currency,amount,fee_bps,interval_seconds,next_due,executions,total_executions,state) VALUES(?,?,?,?,?,?,?,?,0,?,'ACTIVE')",
                    tx.id(), owner.toString(), beneficiary.toString(), currency, amount, policy.transferFeeBps(), intervalSeconds, Money.add(tx.now(), intervalSeconds * 1_000), total);
            return tx.id();
        });
    }
    public Receipt changeOrder(UUID owner, String id, boolean resume, String request) {
        String target = canonicalId(id), kind = resume ? "ORDER_RESUME" : "ORDER_CANCEL";
        return store.write(key(kind, owner, request), intent(owner, target), kind, owner.toString(), tx -> {
            StandingOrder order = order(tx.connection(), target);
            if (!order.owner().equals(owner)) throw new EconomyException(FORBIDDEN, "This order belongs to another player.");
            if (resume) spendable(tx, owner);
            if (resume && !order.state().equals("PAUSED")) throw new EconomyException(INVALID_STATE, "Only paused orders can be resumed.");
            if (!resume && !List.of("ACTIVE", "PAUSED").contains(order.state())) throw new EconomyException(INVALID_STATE, "Order is already closed.");
            execute(tx.connection(), "UPDATE orders SET state=?,reason='',next_due=?,revision=? WHERE id=?", resume ? "ACTIVE" : "CANCELLED",
                    Money.add(tx.now(), order.intervalSeconds() * 1_000), Money.add(order.revision(), 1), target);
            return target;
        });
    }
    public List<StandingOrder> orders(UUID owner) {
        requireUuid(owner);
        return store.read(c -> {
            List<StandingOrder> result = new ArrayList<>();
            try (var s = sql(c, "SELECT * FROM orders WHERE owner=? ORDER BY (state IN ('ACTIVE','PAUSED')) DESC,next_due DESC LIMIT 100", owner.toString()); var rs = s.executeQuery()) {
                while (rs.next()) result.add(mapOrder(rs));
            }
            return List.copyOf(result);
        });
    }
    private static StandingOrder order(Connection c, String id) throws SQLException {
        try (var s = sql(c, "SELECT * FROM orders WHERE id=?", id); var rs = s.executeQuery()) {
            if (!rs.next()) throw new EconomyException(NOT_FOUND, "Recurring order not found."); return mapOrder(rs);
        }
    }
    private static StandingOrder mapOrder(ResultSet rs) throws SQLException {
        return new StandingOrder(rs.getString("id"), UUID.fromString(rs.getString("owner")), UUID.fromString(rs.getString("beneficiary")), rs.getString("currency"),
                rs.getLong("amount"), rs.getInt("fee_bps"), rs.getLong("interval_seconds"), Instant.ofEpochMilli(rs.getLong("next_due")),
                rs.getInt("executions"), rs.getInt("total_executions"), rs.getString("state"), rs.getString("reason"), rs.getLong("revision"));
    }
    public Maintenance maintain() {
        long now = clock.millis();
        List<String> expired = store.read(c -> {
            List<String> ids = new ArrayList<>();
            try (var s = sql(c, "SELECT id FROM escrows WHERE state='OPEN' AND expires_at<=? ORDER BY expires_at LIMIT 100", now); var rs = s.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
            return ids;
        });
        int refunds = 0, payments = 0, pauses = 0;
        for (String id : expired) {
            try {
                Receipt receipt = store.write("ESCROW_EXPIRE:" + id, id, "ESCROW_EXPIRE", "scheduler", tx -> {
                    Escrow escrow = escrow(tx.connection(), id);
                    if (!escrow.state().equals("OPEN") || escrow.expires().toEpochMilli() > tx.now()) {
                        throw new EconomyException(INVALID_STATE, "Escrow is no longer due for expiry.");
                    }
                    refund(tx, escrow, "EXPIRED"); return id;
                });
                if (!receipt.replayed()) refunds++;
            } catch (EconomyException e) { if (e.code() != INVALID_STATE) throw e; }
        }
        List<StandingOrder> due = store.read(c -> {
            List<StandingOrder> result = new ArrayList<>();
            try (var s = sql(c, "SELECT * FROM orders WHERE state='ACTIVE' AND next_due<=? ORDER BY next_due LIMIT 100", now); var rs = s.executeQuery()) {
                while (rs.next()) result.add(mapOrder(rs));
            }
            return result;
        });
        for (StandingOrder scheduled : due) {
            String run = scheduled.id() + ":" + scheduled.executions();
            try {
                Receipt receipt = store.write("ORDER_RUN:" + run, run, "ORDER_RUN", "scheduler", tx -> {
                    StandingOrder current = order(tx.connection(), scheduled.id());
                    if (!current.state().equals("ACTIVE") || current.executions() != scheduled.executions() || current.revision() != scheduled.revision()
                            || !current.nextDue().equals(scheduled.nextDue()) || current.nextDue().toEpochMilli() > tx.now()) {
                        throw new EconomyException(INVALID_STATE, "Order changed while scheduled.");
                    }
                    payment(tx, current.owner(), current.beneficiary(), current.currency(), current.amount(), current.feeBps());
                    int executed = current.executions() + 1;
                    // Deliberately no offline catch-up burst: one installment, then a full new interval.
                    execute(tx.connection(), "UPDATE orders SET executions=?,next_due=?,state=? WHERE id=?", executed,
                            Money.add(tx.now(), current.intervalSeconds() * 1_000), executed >= current.totalExecutions() ? "COMPLETED" : "ACTIVE", current.id());
                    return current.id();
                });
                if (!receipt.replayed()) payments++;
            } catch (EconomyException e) {
                if (e.code() == INVALID_STATE) continue;
                if (e.code() != INSUFFICIENT_FUNDS && e.code() != DAILY_LIMIT && e.code() != INVALID_AMOUNT && e.code() != ACCOUNT_FROZEN) throw e;
                try {
                    Receipt paused = store.write("ORDER_PAUSE:" + run + ":" + scheduled.revision() + ":" + e.code(), intent(run, scheduled.revision(), e.code()), "ORDER_PAUSE", "scheduler", tx -> {
                        StandingOrder current = order(tx.connection(), scheduled.id());
                        if (!current.state().equals("ACTIVE") || current.executions() != scheduled.executions()
                                || current.revision() != scheduled.revision() || !current.nextDue().equals(scheduled.nextDue())) {
                            throw new EconomyException(INVALID_STATE, "Order changed before it could be paused.");
                        }
                        execute(tx.connection(), "UPDATE orders SET state='PAUSED',reason=? WHERE id=?", e.code().name(), current.id());
                        return current.id();
                    });
                    if (!paused.replayed()) pauses++;
                } catch (EconomyException changed) { if (changed.code() != INVALID_STATE) throw changed; }
            }
        }
        return new Maintenance(refunds, payments, pauses);
    }

    public Receipt fundPool(UUID actor, String first, String second, long firstAmount, long secondAmount, String request) {
        String a = currency(first).code(), b = currency(second).code(), pool = poolAccount(a, b);
        Money.positive(firstAmount); Money.positive(secondAmount);
        return store.write(key("POOL_FUND", actor, request), intent(a, b, firstAmount, secondAmount), "POOL_FUND", actor.toString(), tx -> {
            tx.move(TREASURY, pool, a, firstAmount); tx.move(TREASURY, pool, b, secondAmount); return pool;
        });
    }
    public Quote quote(String from, String to, long input) {
        Money.positive(input); String a = currency(from).code(), b = currency(to).code(), pool = poolAccount(a, b);
        return store.read(c -> makeQuote(a, b, input, LedgerStore.storedBalance(c, pool, a), LedgerStore.storedBalance(c, pool, b), clock.millis()));
    }
    private Quote makeQuote(String a, String b, long input, long reserveIn, long reserveOut, long now) {
        long output = Money.swapOutput(reserveIn, reserveOut, input, policy.swapFeeBps());
        if (output <= 0) throw EconomyException.invalid("Trade is too small to produce one target minor unit.");
        Money.add(reserveIn, input);
        return new Quote(a, b, input, output, reserveIn, reserveOut, policy.swapFeeBps(), Instant.ofEpochMilli(now));
    }
    public Receipt swap(UUID owner, String from, String to, long input, long minimumOutput, String request) {
        Money.positive(input); Money.positive(minimumOutput);
        String a = currency(from).code(), b = currency(to).code(), pool = poolAccount(a, b);
        return store.write(key("SWAP", owner, request), intent(owner, a, b, input, minimumOutput), "SWAP", owner.toString(), tx -> {
            spendable(tx, owner);
            Quote quote = makeQuote(a, b, input, tx.balance(pool, a), tx.balance(pool, b), tx.now());
            if (quote.output() < minimumOutput) throw new EconomyException(SLIPPAGE, "Quote moved below your minimum output. Nothing was charged.");
            spend(tx, owner, a, input);
            tx.move(wallet(owner), pool, a, input); tx.move(pool, wallet(owner), b, quote.output());
            return Long.toString(quote.output());
        });
    }

    public Portfolio portfolio(UUID owner) {
        requireUuid(owner);
        return store.read(c -> {
            Map<String, Long> wallets = new LinkedHashMap<>(), savings = new LinkedHashMap<>(), held = new LinkedHashMap<>(), spent = new LinkedHashMap<>();
            String day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().toString();
            for (Currency currency : currencies.values()) {
                String code = currency.code(); wallets.put(code, 0L); savings.put(code, 0L); held.put(code, 0L); spent.put(code, 0L);
            }
            collectAmounts(c, wallets, "SELECT currency,units FROM balances WHERE account=?", wallet(owner));
            collectAmounts(c, savings, "SELECT currency,SUM(principal+interest) FROM deposits WHERE owner=? AND state='LOCKED' GROUP BY currency", owner.toString());
            collectAmounts(c, held, "SELECT currency,SUM(amount+fee) FROM escrows WHERE owner=? AND state='OPEN' GROUP BY currency", owner.toString());
            collectAmounts(c, spent, "SELECT currency,units FROM daily_spend WHERE owner=? AND day=?", owner.toString(), day);
            WalletControl control = control(c, owner);
            return new Portfolio(wallets, savings, held, spent, control.frozen(), control.reason());
        });
    }
    private static void collectAmounts(Connection c, Map<String, Long> amounts, String query, Object... args) throws SQLException {
        try (var s = sql(c, query, args); var rs = s.executeQuery()) {
            while (rs.next()) {
                String code = rs.getString(1);
                if (!amounts.containsKey(code)) throw new EconomyException(INTEGRITY, "Unconfigured currency in portfolio.");
                amounts.put(code, rs.getLong(2));
            }
        }
    }
    public List<Supply> supply() {
        return store.read(c -> {
            List<Supply> result = new ArrayList<>();
            for (Currency currency : currencies.values()) {
                String code = currency.code(); long wallets = 0, savings = 0, escrow = 0, treasury = 0, markets = 0, issued = 0;
                try (var s = sql(c, "SELECT account,units FROM balances WHERE currency=?", code); var rs = s.executeQuery()) {
                    while (rs.next()) {
                        String account = rs.getString(1); long value = rs.getLong(2);
                        if (account.equals(MINT)) issued = Math.negateExact(value);
                        else if (account.equals(TREASURY)) treasury = value;
                        else if (account.startsWith("player:")) wallets = Math.addExact(wallets, value);
                        else if (account.startsWith("vault:")) savings = Math.addExact(savings, value);
                        else if (account.startsWith("escrow:")) escrow = Math.addExact(escrow, value);
                        else if (account.startsWith("system:pool:")) markets = Math.addExact(markets, value);
                    }
                }
                result.add(new Supply(code, issued, wallets, savings, escrow, treasury, markets));
            }
            return List.copyOf(result);
        });
    }
    public Audit audit() {
        // Nested reads are safe: the store uses a reentrant lock, giving one consistent audit view.
        try {
            return auditConsistent();
        } catch (RuntimeException e) {
            store.freeze();
            throw new EconomyException(INTEGRITY, "Deep audit could not complete. Ledger writer is frozen; preserve evidence and investigate.", e);
        }
    }
    private Audit auditConsistent() {
        return store.read(c -> {
            Audit ledger = store.audit(); List<String> errors = new ArrayList<>(ledger.violations());
            auditDomain(c, errors);
            Map<String, Long> expected = new LinkedHashMap<>();
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT e.*,j.intent AS opening_intent,j.actor AS opening_actor,j.kind AS opening_kind,j.reference AS opening_reference,j.created_at AS opening_time,COALESCE(p.delta,0) AS opening_units FROM escrows e LEFT JOIN journal j ON j.id=e.id LEFT JOIN entries p ON p.tx_id=e.id AND p.account='escrow:'||e.id AND p.currency=e.currency")) {
                while (rs.next()) {
                    Escrow e = mapEscrow(rs);
                    String[] terms = rs.getString("opening_intent").split("\\|", -1);
                    long lifetime = terms.length == 5 ? Long.parseLong(terms[4]) : 0;
                    if (!"ESCROW_CREATE".equals(rs.getString("opening_kind")) || !e.owner().toString().equals(rs.getString("opening_actor"))
                            || !e.id().equals(rs.getString("opening_reference")) || e.owner().equals(e.beneficiary()) || e.amount() <= 0 || e.fee() < 0
                            || lifetime < 60 || lifetime > 30L * 86_400
                            || !intent(e.owner(), e.beneficiary(), currency(e.currency()).code(), e.amount(), lifetime).equals(rs.getString("opening_intent"))
                            || e.expires().toEpochMilli() != Money.add(rs.getLong("opening_time"), lifetime * 1_000)
                            || rs.getLong("opening_units") != Money.add(e.amount(), e.fee())) errors.add("Escrow terms mismatch: " + e.id());
                    expected.put(escrowAccount(e.id()) + "|" + e.currency(), e.state().equals("OPEN") ? Money.add(e.amount(), e.fee()) : 0);
                }
            }
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT d.*,j.intent AS opening_intent,j.actor AS opening_actor,j.kind AS opening_kind,j.reference AS opening_reference,j.created_at AS opening_time,COALESCE(p.delta,0) AS opening_units FROM deposits d LEFT JOIN journal j ON j.id=d.id LEFT JOIN entries p ON p.tx_id=d.id AND p.account='vault:'||d.id AND p.currency=d.currency")) {
                while (rs.next()) {
                    Deposit d = mapDeposit(rs);
                    String[] terms = rs.getString("opening_intent").split("\\|", -1);
                    int days = terms.length == 4 ? Integer.parseInt(terms[3]) : 0;
                    if (!"BANK_OPEN".equals(rs.getString("opening_kind")) || !d.owner().toString().equals(rs.getString("opening_actor"))
                            || !d.id().equals(rs.getString("opening_reference")) || d.principal() <= 0 || d.interest() < 0 || days < 1 || days > 365
                            || !intent(d.owner(), currency(d.currency()).code(), d.principal(), days).equals(rs.getString("opening_intent"))
                            || d.unlocks().toEpochMilli() != Money.add(rs.getLong("opening_time"), days * 86_400_000L)
                            || rs.getLong("opening_units") != Money.add(d.principal(), d.interest())) errors.add("Savings terms mismatch: " + d.id());
                    expected.put(depositAccount(d.id()) + "|" + d.currency(), d.state().equals("LOCKED") ? Money.add(d.principal(), d.interest()) : 0);
                }
            }
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT account,currency,units FROM balances WHERE account LIKE 'escrow:%' OR account LIKE 'vault:%'")) {
                while (rs.next()) {
                    String key = rs.getString(1) + "|" + rs.getString(2); long units = rs.getLong(3);
                    Long value = expected.remove(key);
                    if (value == null || value != units) errors.add("Custody mismatch: " + key);
                }
            }
            if (expected.values().stream().anyMatch(n -> n != 0)) errors.add("Missing custody balances");
            for (Supply s : supply()) {
                BigInteger total = BigInteger.ZERO;
                for (long value : new long[]{s.wallets(), s.savings(), s.escrow(), s.treasury(), s.markets()}) total = total.add(BigInteger.valueOf(value));
                if (!total.equals(BigInteger.valueOf(s.issued()))) errors.add("Supply mismatch: " + s.currency());
            }
            if (!errors.isEmpty()) store.freeze();
            return new Audit(ledger.transactions(), ledger.postings(), ledger.headHash(), errors);
        });
    }
    private void auditDomain(Connection c, List<String> errors) throws SQLException {
        Map<String, Integer> storedCurrencies = new LinkedHashMap<>();
        try (var s = c.createStatement(); var rs = s.executeQuery("SELECT code,decimals FROM currency_specs")) {
            while (rs.next()) storedCurrencies.put(rs.getString(1), rs.getInt(2));
        }
        Map<String, Integer> configured = new LinkedHashMap<>();
        currencies.values().forEach(currency -> configured.put(currency.code(), currency.decimals()));
        if (!configured.equals(storedCurrencies)) errors.add("Persisted currency specifications mismatch");

        try (var s = c.createStatement(); var rs = s.executeQuery("SELECT account,currency FROM balances")) {
            while (rs.next()) if (!supportedAccount(rs.getString(1), rs.getString(2))) errors.add("Unknown account topology: " + rs.getString(1));
        }

        // Mutable incident controls must agree with the most recent immutable administrative event.
        Map<UUID, WalletControl> controls = new LinkedHashMap<>();
        try (var s = c.createStatement(); var rs = s.executeQuery("SELECT * FROM journal WHERE kind IN ('WALLET_FREEZE','WALLET_UNFREEZE') ORDER BY seq")) {
            while (rs.next()) {
                String[] terms = rs.getString("intent").split("\\|", 3);
                UUID owner = UUID.fromString(canonicalId(terms[0]));
                boolean frozen = "WALLET_FREEZE".equals(rs.getString("kind"));
                if (terms.length != 3 || !Boolean.toString(frozen).equals(terms[1]) || !owner.toString().equals(rs.getString("reference"))) {
                    errors.add("Invalid wallet control event: " + rs.getString("id")); continue;
                }
                controls.put(owner, new WalletControl(owner, frozen, terms[2], Instant.ofEpochMilli(rs.getLong("created_at"))));
            }
        }
        try (var s = c.createStatement(); var rs = s.executeQuery("SELECT * FROM wallet_controls")) {
            while (rs.next()) {
                UUID owner = UUID.fromString(canonicalId(rs.getString("owner")));
                WalletControl expected = controls.remove(owner);
                WalletControl actual = new WalletControl(owner, rs.getInt("frozen") == 1, rs.getString("reason"), Instant.ofEpochMilli(rs.getLong("updated_at")));
                if (!actual.equals(expected)) errors.add("Wallet control projection mismatch: " + owner);
            }
        }
        if (!controls.isEmpty()) errors.add("Missing wallet controls");

        try (var s = c.createStatement(); var rs = s.executeQuery("SELECT o.*,j.intent AS opening_intent,j.kind AS opening_kind,j.actor AS opening_actor,j.reference AS opening_reference,(SELECT COUNT(*) FROM journal r WHERE r.kind='ORDER_RUN' AND r.reference=o.id) AS run_count FROM orders o LEFT JOIN journal j ON j.id=o.id")) {
            while (rs.next()) {
                StandingOrder order = mapOrder(rs);
                if (!"ORDER_CREATE".equals(rs.getString("opening_kind")) || !order.owner().toString().equals(rs.getString("opening_actor"))
                        || !order.id().equals(rs.getString("opening_reference")) || order.owner().equals(order.beneficiary())
                        || order.amount() <= 0 || order.feeBps() < 0 || order.feeBps() > 1_000 || order.intervalSeconds() < 60 || order.intervalSeconds() > 30L * 86_400
                        || order.totalExecutions() < 1 || order.totalExecutions() > 10_000 || order.executions() < 0 || order.executions() > order.totalExecutions()
                        || order.revision() < 0 || order.executions() != rs.getLong("run_count")
                        || order.state().equals("COMPLETED") != (order.executions() == order.totalExecutions())
                        || !intent(order.owner(), order.beneficiary(), currency(order.currency()).code(), order.amount(), order.intervalSeconds(), order.totalExecutions()).equals(rs.getString("opening_intent"))) {
                    errors.add("Recurring order terms/counter mismatch: " + order.id());
                }
            }
        }
        for (String[] contract : new String[][]{{"escrows", "ESCROW_CREATE"}, {"deposits", "BANK_OPEN"}, {"orders", "ORDER_CREATE"}}) {
            if (scalar(c, "SELECT COUNT(*) FROM journal j LEFT JOIN " + contract[0] + " d ON d.id=j.id WHERE j.kind=? AND d.id IS NULL", contract[1]) != 0) {
                errors.add("Missing contracts: " + contract[0]);
            }
        }
        for (String[] fields : new String[][]{
                {"currency_specs", "decimals", "decimals NOT BETWEEN 0 AND 6"},
                {"players", "seen_at", "length(uuid)<>36"},
                {"daily_spend", "units", "units<0 OR currency NOT IN (SELECT code FROM currency_specs)"},
                {"escrows", "amount,fee,expires_at", "state NOT IN ('OPEN','ACCEPTED','CANCELLED','EXPIRED')"},
                {"deposits", "principal,interest,unlock_at", "state NOT IN ('LOCKED','CLAIMED')"},
                {"orders", "amount,fee_bps,interval_seconds,next_due,executions,total_executions,revision", "state NOT IN ('ACTIVE','PAUSED','CANCELLED','COMPLETED') OR amount<=0 OR fee_bps NOT BETWEEN 0 AND 1000 OR interval_seconds NOT BETWEEN 60 AND 2592000 OR total_executions NOT BETWEEN 1 AND 10000 OR executions<0 OR executions>total_executions OR revision<0"},
                {"wallet_controls", "frozen,updated_at", "frozen NOT IN (0,1)"}}) {
            String types = java.util.Arrays.stream(fields[1].split(",")).map(field -> "typeof(" + field + ")<>'integer'").collect(java.util.stream.Collectors.joining(" OR "));
            if (scalar(c, "SELECT COUNT(*) FROM " + fields[0] + " WHERE " + types + " OR " + fields[2]) != 0) errors.add("Invalid domain storage: " + fields[0]);
        }
    }

    private boolean supportedAccount(String account, String code) {
        if (account == null || !currencies.containsKey(code)) return false;
        if (account.equals(MINT) || account.equals(TREASURY) || PLAYER_WALLET.matcher(account).matches()) return true;
        if (account.startsWith("escrow:") || account.startsWith("vault:")) {
            String id = account.substring(account.indexOf(':') + 1);
            return FULL_UUID.matcher(id).matches() && id.equals(id.toLowerCase(Locale.ROOT));
        }
        if (account.startsWith("system:pool:")) {
            String[] pair = account.substring(12).toUpperCase(Locale.ROOT).split("_", -1);
            return pair.length == 2 && currencies.containsKey(pair[0]) && currencies.containsKey(pair[1])
                    && pair[0].compareTo(pair[1]) < 0 && (code.equals(pair[0]) || code.equals(pair[1]))
                    && account.equals(poolAccount(pair[0], pair[1]));
        }
        return false;
    }

    public Backup backup(java.nio.file.Path target) {
        java.util.Objects.requireNonNull(target, "target");
        return store.read(c -> {
            java.nio.file.Path manifestTarget = java.nio.file.Path.of(target.toAbsolutePath().normalize() + ".manifest.txt");
            if (java.nio.file.Files.exists(manifestTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new EconomyException(STORAGE, "Backup manifest destination already exists.");
            }
            Audit report;
            try { report = audit(); }
            catch (EconomyException e) {
                if (e.code() != INTEGRITY) throw e;
                report = new Audit(-1, -1, "unavailable", List.of("Deep audit could not complete; snapshot is incident evidence only."));
            }
            java.nio.file.Path database = store.backup(target), manifest = java.nio.file.Path.of(database + ".manifest.txt");
            try {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                try (var input = new java.security.DigestInputStream(java.nio.file.Files.newInputStream(database), digest)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
                String text = "Aurora Economy backup v1\ncreated_at=" + clock.instant() + "\nhealthy=" + report.healthy()
                        + "\ntransactions=" + report.transactions() + "\npostings=" + report.postings()
                        + "\nledger_head=" + report.headHash() + "\ndatabase_sha256=" + java.util.HexFormat.of().formatHex(digest.digest()) + "\n";
                java.nio.file.Files.writeString(manifest, text, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
                throw new EconomyException(STORAGE, "Snapshot was created, but its manifest could not be written. Check server storage.", e);
            }
            return new Backup(database, manifest, report);
        });
    }
    private static String canonicalId(String id) {
        if (id == null || !FULL_UUID.matcher(id).matches()) {
            throw EconomyException.invalid("Use the full UUID shown when the contract was created.");
        }
        return UUID.fromString(id).toString();
    }
}
