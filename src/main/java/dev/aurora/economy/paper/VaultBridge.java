package dev.aurora.economy.paper;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.EconomyEngine;
import dev.aurora.economy.core.EconomyException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

/** Legacy synchronous compatibility. Prefer EconomyApi for async, replay-safe integrations. */
@SuppressWarnings("deprecation")
final class VaultBridge implements Economy {
    private final AuroraEconomyPlugin plugin;
    private final EconomyEngine engine;
    private final Currency currency;
    VaultBridge(AuroraEconomyPlugin plugin) {
        this.plugin = plugin; engine = plugin.engine(); currency = engine.currency(plugin.settings().defaultCurrency());
    }
    static void register(AuroraEconomyPlugin plugin) {
        plugin.getServer().getServicesManager().register(Economy.class, new VaultBridge(plugin), plugin, ServicePriority.High);
        plugin.getLogger().info("Vault bridge registered for " + plugin.settings().defaultCurrency() + ". Vault writes are synchronous and journaled as external issuance/burn.");
    }
    private long units(double value) {
        if (!Double.isFinite(value) || value < 0) throw EconomyException.invalid("Vault amount must be finite and nonnegative.");
        try {
            long units = BigDecimal.valueOf(value).movePointRight(currency.decimals()).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            if (units > 9_007_199_254_740_991L) throw EconomyException.invalid("Vault amount exceeds its safe integer range. Use EconomyApi.");
            return units;
        } catch (ArithmeticException e) { throw EconomyException.invalid("Vault amount exceeds precision or range."); }
    }
    private double balance(UUID owner) { return currency.decimal(engine.balance(owner, currency.code())).doubleValue(); }
    private EconomyResponse mutate(UUID owner, double amount, boolean withdraw) {
        boolean committed = false;
        try {
            if (!isEnabled()) throw new EconomyException(EconomyException.Code.STORAGE, "Economy is unavailable.");
            long units = units(amount);
            if (units > 0) {
                engine.issue(owner, EconomyEngine.wallet(owner), currency.code(), units, withdraw, UUID.randomUUID().toString(), withdraw ? "VAULT_WITHDRAW" : "VAULT_DEPOSIT");
                committed = true;
            }
            return new EconomyResponse(amount, balance(owner), ResponseType.SUCCESS, "");
        } catch (EconomyException e) {
            if (e.code() == EconomyException.Code.STORAGE || e.code() == EconomyException.Code.INTEGRITY) plugin.getLogger().log(Level.SEVERE, "Vault operation failed", e);
            // A balance-read failure after commit must not invite another issuance/burn attempt.
            if (committed) return new EconomyResponse(amount, Double.NaN, ResponseType.SUCCESS, "Committed; current balance unavailable. Do not retry.");
            double current = 0;
            try { current = balance(owner); } catch (EconomyException ignored) { /* storage failed closed */ }
            return new EconomyResponse(0, current, ResponseType.FAILURE, e.code() + ": " + e.getMessage());
        }
    }
    private EconomyResponse mutate(String name, double amount, boolean withdraw) {
        try { return mutate(engine.resolve(name), amount, withdraw); }
        catch (EconomyException e) { return new EconomyResponse(0, 0, ResponseType.FAILURE, e.getMessage()); }
    }
    private boolean enough(UUID owner, double amount) {
        try { return engine.balance(owner, currency.code()) >= units(amount); }
        catch (EconomyException e) { return false; }
    }
    private static EconomyResponse unsupported() { return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "Vault banks are unsupported; use Aurora's funded savings contracts."); }
    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "AuroraEconomy"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return currency.decimals(); }
    @Override public String currencyNamePlural() { return currency.code(); }
    @Override public String currencyNameSingular() { return currency.code(); }
    @Override public String format(double amount) {
        try { return currency.format(units(amount)); } catch (EconomyException e) { return "Invalid " + currency.code() + " amount"; }
    }
    @Override public boolean hasAccount(String name) {
        try { return engine.accountExists(engine.resolve(name)); } catch (EconomyException e) { return false; }
    }
    @Override public boolean hasAccount(OfflinePlayer player) {
        try { return engine.accountExists(player.getUniqueId()); } catch (EconomyException e) { return false; }
    }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer player, String world) { return hasAccount(player); }
    @Override public double getBalance(OfflinePlayer player) { return balance(player.getUniqueId()); }
    @Override public double getBalance(String name) {
        try { return balance(engine.resolve(name)); }
        catch (EconomyException e) { if (e.code() == EconomyException.Code.NOT_FOUND) return 0; throw e; }
    }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public boolean has(OfflinePlayer player, double amount) { return enough(player.getUniqueId(), amount); }
    @Override public boolean has(String name, double amount) {
        try { return enough(engine.resolve(name), amount); } catch (EconomyException e) { return false; }
    }
    @Override public boolean has(OfflinePlayer player, String world, double amount) { return has(player, amount); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double a) { return mutate(p.getUniqueId(), a, true); }
    @Override public EconomyResponse withdrawPlayer(String p, double a) { return mutate(p, a, true); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String w, double a) { return withdrawPlayer(p, a); }
    @Override public EconomyResponse withdrawPlayer(String p, String w, double a) { return withdrawPlayer(p, a); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double a) { return mutate(p.getUniqueId(), a, false); }
    @Override public EconomyResponse depositPlayer(String p, double a) { return mutate(p, a, false); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String w, double a) { return depositPlayer(p, a); }
    @Override public EconomyResponse depositPlayer(String p, String w, double a) { return depositPlayer(p, a); }
    @Override public boolean createPlayerAccount(OfflinePlayer player) {
        try { if (!isEnabled()) return false; engine.register(player.getUniqueId(), player.getName()); return true; } catch (EconomyException e) { return false; }
    }
    @Override public boolean createPlayerAccount(String name) {
        try { if (!isEnabled()) return false; engine.register(engine.resolve(name), name); return true; } catch (EconomyException e) { return false; }
    }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String w) { return createPlayerAccount(p); }
    @Override public boolean createPlayerAccount(String p, String w) { return createPlayerAccount(p); }
    @Override public EconomyResponse createBank(String n, String p) { return unsupported(); }
    @Override public EconomyResponse createBank(String n, OfflinePlayer p) { return unsupported(); }
    @Override public EconomyResponse deleteBank(String n) { return unsupported(); }
    @Override public EconomyResponse bankBalance(String n) { return unsupported(); }
    @Override public EconomyResponse bankHas(String n, double a) { return unsupported(); }
    @Override public EconomyResponse bankWithdraw(String n, double a) { return unsupported(); }
    @Override public EconomyResponse bankDeposit(String n, double a) { return unsupported(); }
    @Override public EconomyResponse isBankOwner(String n, String p) { return unsupported(); }
    @Override public EconomyResponse isBankOwner(String n, OfflinePlayer p) { return unsupported(); }
    @Override public EconomyResponse isBankMember(String n, String p) { return unsupported(); }
    @Override public EconomyResponse isBankMember(String n, OfflinePlayer p) { return unsupported(); }
    @Override public List<String> getBanks() { return List.of(); }
}
