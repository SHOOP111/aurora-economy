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
    private final LedgerStore store;
    private final Clock clock;
    private final Policy policy;
    private final Map<String, Currency> currencies;
    public record Maintenance(int expired, int paid, int paused) { }

    public EconomyEngine(LedgerStore store, Clock clock, Policy policy, List<Currency> configured) {
        if (configured.isEmpty()) throw new IllegalArgumentException("At least one currency is required");
        this.store = store; this.clock = clock; this.policy = policy;
        Map<String, Currency> map = new LinkedHashMap<>();
        for (Currency currency : configured) {
            if (map.put(currency.code(), currency) != null) throw new IllegalArgumentException("Duplicate currency");
        }
        currencies = java.util.Collections.unmodifiableMap(map);
    }
    public Collection<Currency> currencies() { return currencies.values(); }
    public Policy policy() { return policy; }
    public Currency currency(String code) {
        Currency currency = currencies.get(code.toUpperCase(Locale.ROOT));
        if (currency == null) throw new EconomyException(UNKNOWN_CURRENCY, "Unknown currency: " + code);
        return currency;
    }
    public static String wallet(UUID owner) { return "player:" + owner + ":wallet"; }
    public static String escrowAccount(String id) { return "escrow:" + id; }
    public static String depositAccount(String id) { return "vault:" + id; }
    public String poolAccount(String a, String b) {
        String first = currency(a).code(), second = currency(b).code();
        if (first.equals(second)) throw EconomyException.invalid("Select two different currencies.");
        return "system:pool:" + (first.compareTo(second) < 0 ? first + "_" + second : second + "_" + first).toLowerCase(Locale.ROOT);
    }
    private static String intent(Object... values) {
        // Inputs here are validated UUIDs, enum-like codes and numeric values, never arbitrary user text.
        return java.util.Arrays.stream(values).map(Object::toString).collect(java.util.stream.Collectors.joining("|"));
    }
    private static String key(String kind, UUID actor, String request) {
        if (request == null || !request.matches("[A-Za-z0-9._:-]{1,96}")) throw EconomyException.invalid("Invalid request identifier.");
        return kind + ":" + actor + ":" + request;
    }
    private static void different(UUID a, UUID b) {
        if (a.equals(b)) throw EconomyException.invalid("You cannot pay yourself.");
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
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) name = player.toString();
        String safeName = name;
        store.read(c -> {
            execute(c, "INSERT INTO players VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,seen_at=excluded.seen_at", player.toString(), safeName, clock.millis());
            return null;
        });
        return store.write("REGISTER:" + player, player.toString(), "REGISTER", player.toString(), tx -> {
            for (Currency currency : currencies.values()) tx.move(MINT, wallet(player), currency.code(), currency.startingBalance());
            return player.toString();
        });
    }
    /** Only known names are resolved. Never invokes blocking Mojang lookup or invents offline UUIDs. */
    public UUID resolve(String name) {
        try { return UUID.fromString(name); }
        catch (IllegalArgumentException ignored) { }
        return store.read(c -> {
            try (var s = sql(c, "SELECT uuid FROM players WHERE name=? COLLATE NOCASE ORDER BY seen_at DESC LIMIT 1", name); var rs = s.executeQuery()) {
                if (!rs.next()) throw new EconomyException(NOT_FOUND, "Player must have joined this server, or use their UUID.");
                return UUID.fromString(rs.getString(1));
            }
        });
    }
    public long balance(UUID owner, String code) { return store.balance(wallet(owner), currency(code).code()); }
    public long treasury(String code) { return store.balance(TREASURY, currency(code).code()); }
    public List<Receipt> history(UUID player, int limit) { return store.history(wallet(player), limit); }

    public Receipt transfer(UUID owner, UUID recipient, String code, long amount, String request) {
        different(owner, recipient); Money.positive(amount); String currency = currency(code).code();
        return store.write(key("PAY", owner, request), intent(owner, recipient, currency, amount), "PAY", owner.toString(), tx -> {
            payment(tx, owner, recipient, currency, amount, policy.transferFeeBps()); return recipient.toString();
        });
    }
    private void payment(Tx tx, UUID owner, UUID recipient, String code, long amount, int feeBps) throws SQLException {
        long fee = Money.fee(amount, feeBps), total = Money.add(amount, fee);
        spend(tx, owner, code, total);
        tx.move(wallet(owner), wallet(recipient), code, amount);
        tx.move(wallet(owner), TREASURY, code, fee);
    }
    private void spend(Tx tx, UUID owner, String code, long amount) throws SQLException {
        String day = Instant.ofEpochMilli(tx.now()).atZone(ZoneOffset.UTC).toLocalDate().toString();
        long prior = scalar(tx.connection(), "SELECT units FROM daily_spend WHERE owner=? AND currency=? AND day=?", owner.toString(), code, day);
        long next = Money.add(prior, amount), limit = currency(code).dailyLimit();
        if (limit > 0 && next > limit) throw new EconomyException(DAILY_LIMIT, "UTC daily spending limit reached for " + code + ".");
        execute(tx.connection(), "INSERT INTO daily_spend VALUES(?,?,?,?) ON CONFLICT(owner,currency,day) DO UPDATE SET units=excluded.units", owner.toString(), code, day, next);
    }
    /** Used by authorized administration / Vault only; attribution is always journaled. */
    public Receipt issue(UUID actor, String targetAccount, String code, long amount, boolean burn, String request, String source) {
        Money.positive(amount); String currency = currency(code).code();
        if (!targetAccount.equals(TREASURY) && !targetAccount.matches("player:[a-f0-9-]{36}:wallet")) throw new EconomyException(FORBIDDEN, "Invalid issuance target.");
        if (!source.matches("[A-Z_]{1,32}")) throw new IllegalArgumentException("Invalid issuance source");
        return store.write(key(source, actor, request), intent(targetAccount, currency, amount, burn), source, actor.toString(), tx -> {
            if (burn) tx.move(targetAccount, MINT, currency, amount); else tx.move(MINT, targetAccount, currency, amount);
            return targetAccount;
        });
    }

    public Receipt createEscrow(UUID owner, UUID beneficiary, String code, long amount, long lifetimeSeconds, String request) {
        different(owner, beneficiary); Money.positive(amount); String currency = currency(code).code();
        if (lifetimeSeconds < 60 || lifetimeSeconds > 30L * 86_400) throw EconomyException.invalid("Escrow lifetime must be 1 minute to 30 days.");
        return store.write(key("ESCROW_CREATE", owner, request), intent(owner, beneficiary, currency, amount, lifetimeSeconds), "ESCROW_CREATE", owner.toString(), tx -> {
            long active = scalar(tx.connection(), "SELECT COUNT(*) FROM escrows WHERE owner=? AND state='OPEN'", owner.toString());
            if (active >= policy.maxOpenEscrows()) throw new EconomyException(LIMIT_REACHED, "Too many open escrows.");
            long fee = Money.fee(amount, policy.transferFeeBps()), total = Money.add(amount, fee);
            spend(tx, owner, currency, total);
            tx.move(wallet(owner), escrowAccount(tx.id()), currency, total);
            execute(tx.connection(), "INSERT INTO escrows VALUES(?,?,?,?,?,?,?,'OPEN')", tx.id(), owner.toString(), beneficiary.toString(), currency, amount, fee, tx.now() + lifetimeSeconds * 1_000);
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
        return store.read(c -> {
            List<Escrow> result = new ArrayList<>();
            try (var s = sql(c, "SELECT * FROM escrows WHERE owner=? OR beneficiary=? ORDER BY expires_at DESC LIMIT 100", player.toString(), player.toString()); var rs = s.executeQuery()) {
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
            if (scalar(tx.connection(), "SELECT COUNT(*) FROM deposits WHERE owner=? AND state='LOCKED'", owner.toString()) >= policy.maxOpenDeposits()) {
                throw new EconomyException(LIMIT_REACHED, "Too many locked savings deposits.");
            }
            long interest = Money.interest(principal, policy.savingsAprBps(), days * 86_400L);
            Money.add(principal, interest);
            if (tx.balance(TREASURY, currency) < interest) throw new EconomyException(INSUFFICIENT_FUNDS, "Treasury cannot fund this yield. Ask an administrator to fund it.");
            tx.move(wallet(owner), depositAccount(tx.id()), currency, principal);
            // Reserve the full promised yield now: maturity never depends on future minting.
            tx.move(TREASURY, depositAccount(tx.id()), currency, interest);
            execute(tx.connection(), "INSERT INTO deposits VALUES(?,?,?,?,?,?,'LOCKED')", tx.id(), owner.toString(), currency, principal, interest, tx.now() + days * 86_400_000L);
            return tx.id();
        });
    }
    public Receipt claimDeposit(UUID owner, String id, String request) {
        String target = canonicalId(id);
        return store.write(key("BANK_CLAIM", owner, request), intent(owner, target), "BANK_CLAIM", owner.toString(), tx -> {
            Deposit deposit = deposit(tx.connection(), target);
            if (!deposit.owner().equals(owner)) throw new EconomyException(FORBIDDEN, "This deposit belongs to another player.");
            if (!deposit.state().equals("LOCKED")) throw new EconomyException(INVALID_STATE, "Deposit has already been claimed.");
            if (tx.now() < deposit.unlocks().toEpochMilli()) throw new EconomyException(LOCKED, "Deposit unlocks at " + deposit.unlocks() + ".");
            tx.move(depositAccount(target), wallet(owner), deposit.currency(), Money.add(deposit.principal(), deposit.interest()));
            execute(tx.connection(), "UPDATE deposits SET state='CLAIMED' WHERE id=?", target); return target;
        });
    }
    public List<Deposit> deposits(UUID owner) {
        return store.read(c -> {
            List<Deposit> result = new ArrayList<>();
            try (var s = sql(c, "SELECT * FROM deposits WHERE owner=? ORDER BY unlock_at DESC LIMIT 100", owner.toString()); var rs = s.executeQuery()) {
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
            if (scalar(tx.connection(), "SELECT COUNT(*) FROM orders WHERE owner=? AND state IN ('ACTIVE','PAUSED')", owner.toString()) >= policy.maxActiveOrders()) {
                throw new EconomyException(LIMIT_REACHED, "Too many active or paused recurring payments.");
            }
            execute(tx.connection(), "INSERT INTO orders(id,owner,beneficiary,currency,amount,fee_bps,interval_seconds,next_due,executions,total_executions,state) VALUES(?,?,?,?,?,?,?,?,0,?,'ACTIVE')",
                    tx.id(), owner.toString(), beneficiary.toString(), currency, amount, policy.transferFeeBps(), intervalSeconds, tx.now() + intervalSeconds * 1_000, total);
            return tx.id();
        });
    }
    public Receipt changeOrder(UUID owner, String id, boolean resume, String request) {
        String target = canonicalId(id), kind = resume ? "ORDER_RESUME" : "ORDER_CANCEL";
        return store.write(key(kind, owner, request), intent(owner, target), kind, owner.toString(), tx -> {
            StandingOrder order = order(tx.connection(), target);
            if (!order.owner().equals(owner)) throw new EconomyException(FORBIDDEN, "This order belongs to another player.");
            if (resume && !order.state().equals("PAUSED")) throw new EconomyException(INVALID_STATE, "Only paused orders can be resumed.");
            if (!resume && !List.of("ACTIVE", "PAUSED").contains(order.state())) throw new EconomyException(INVALID_STATE, "Order is already closed.");
            execute(tx.connection(), "UPDATE orders SET state=?,reason='',next_due=? WHERE id=?", resume ? "ACTIVE" : "CANCELLED", tx.now() + order.intervalSeconds() * 1_000, target);
            return target;
        });
    }
    public List<StandingOrder> orders(UUID owner) {
        return store.read(c -> {
            List<StandingOrder> result = new ArrayList<>();
            try (var s = sql(c, "SELECT * FROM orders WHERE owner=? ORDER BY next_due DESC LIMIT 100", owner.toString()); var rs = s.executeQuery()) {
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
                rs.getInt("executions"), rs.getInt("total_executions"), rs.getString("state"), rs.getString("reason"));
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
            Receipt receipt = store.write("ESCROW_EXPIRE:" + id, id, "ESCROW_EXPIRE", "scheduler", tx -> {
                Escrow escrow = escrow(tx.connection(), id);
                if (escrow.state().equals("OPEN") && escrow.expires().toEpochMilli() <= tx.now()) refund(tx, escrow, "EXPIRED");
                return id;
            });
            if (!receipt.replayed()) refunds++;
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
                    if (!current.state().equals("ACTIVE") || current.executions() != scheduled.executions() || current.nextDue().toEpochMilli() > tx.now()) {
                        throw new EconomyException(INVALID_STATE, "Order changed while scheduled.");
                    }
                    payment(tx, current.owner(), current.beneficiary(), current.currency(), current.amount(), current.feeBps());
                    int executed = current.executions() + 1;
                    // Deliberately no offline catch-up burst: one installment, then a full new interval.
                    execute(tx.connection(), "UPDATE orders SET executions=?,next_due=?,state=? WHERE id=?", executed,
                            tx.now() + current.intervalSeconds() * 1_000, executed >= current.totalExecutions() ? "COMPLETED" : "ACTIVE", current.id());
                    return current.id();
                });
                if (!receipt.replayed()) payments++;
            } catch (EconomyException e) {
                if (e.code() == INVALID_STATE) continue;
                if (e.code() != INSUFFICIENT_FUNDS && e.code() != DAILY_LIMIT && e.code() != INVALID_AMOUNT) throw e;
                // A new failure after a manual resume needs a fresh key, not the old paused receipt.
                store.write("ORDER_PAUSE:" + run + ":" + scheduled.nextDue().toEpochMilli(), intent(run, e.code()), "ORDER_PAUSE", "scheduler", tx -> {
                    StandingOrder current = order(tx.connection(), scheduled.id());
                    if (current.state().equals("ACTIVE") && current.executions() == scheduled.executions()) {
                        execute(tx.connection(), "UPDATE orders SET state='PAUSED',reason=? WHERE id=?", e.code().name(), current.id());
                    }
                    return current.id();
                });
                pauses++;
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
            Quote quote = makeQuote(a, b, input, tx.balance(pool, a), tx.balance(pool, b), tx.now());
            if (quote.output() < minimumOutput) throw new EconomyException(SLIPPAGE, "Quote moved below your minimum output. Nothing was charged.");
            spend(tx, owner, a, input);
            tx.move(wallet(owner), pool, a, input); tx.move(pool, wallet(owner), b, quote.output());
            return Long.toString(quote.output());
        });
    }

    public Portfolio portfolio(UUID owner) {
        return store.read(c -> {
            Map<String, Long> wallets = new LinkedHashMap<>(), savings = new LinkedHashMap<>(), held = new LinkedHashMap<>(), spent = new LinkedHashMap<>();
            String day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().toString();
            for (Currency currency : currencies.values()) {
                String code = currency.code(); wallets.put(code, LedgerStore.storedBalance(c, wallet(owner), code));
                savings.put(code, scalar(c, "SELECT COALESCE(SUM(principal+interest),0) FROM deposits WHERE owner=? AND currency=? AND state='LOCKED'", owner.toString(), code));
                held.put(code, scalar(c, "SELECT COALESCE(SUM(amount+fee),0) FROM escrows WHERE owner=? AND currency=? AND state='OPEN'", owner.toString(), code));
                spent.put(code, scalar(c, "SELECT units FROM daily_spend WHERE owner=? AND currency=? AND day=?", owner.toString(), code, day));
            }
            return new Portfolio(wallets, savings, held, spent);
        });
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
        return store.read(c -> {
            Audit ledger = store.audit(); List<String> errors = new ArrayList<>(ledger.violations());
            Map<String, Long> expected = new LinkedHashMap<>();
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT * FROM escrows")) {
                while (rs.next()) {
                    Escrow e = mapEscrow(rs); expected.put(escrowAccount(e.id()) + "|" + e.currency(), e.state().equals("OPEN") ? Money.add(e.amount(), e.fee()) : 0);
                }
            }
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT * FROM deposits")) {
                while (rs.next()) {
                    Deposit d = mapDeposit(rs); expected.put(depositAccount(d.id()) + "|" + d.currency(), d.state().equals("LOCKED") ? Money.add(d.principal(), d.interest()) : 0);
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
            return new Audit(ledger.transactions(), ledger.postings(), ledger.headHash(), errors);
        });
    }
    private static String canonicalId(String id) {
        try { return UUID.fromString(id).toString(); }
        catch (IllegalArgumentException e) { throw EconomyException.invalid("Use the full UUID shown when the contract was created."); }
    }
}
