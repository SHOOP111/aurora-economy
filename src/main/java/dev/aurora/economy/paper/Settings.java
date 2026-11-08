package dev.aurora.economy.paper;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.Policy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;

record Settings(List<Currency> currencies, String defaultCurrency, Policy policy,
                boolean vault, int queueCapacity, int maintenanceSeconds) {
    Settings { currencies = List.copyOf(currencies); }
    static Settings load(FileConfiguration config) {
        var section = Objects.requireNonNull(config.getConfigurationSection("currencies"), "Missing currencies section");
        List<Currency> currencies = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            var node = Objects.requireNonNull(section.getConfigurationSection(key), "Invalid currency section: " + key);
            String symbol = node.getString("symbol", key); int decimals = node.getInt("decimals", 2);
            Currency spec = new Currency(key, symbol, decimals, 0, 0);
            long start = nonnegative(spec, node.getString("starting-balance", "0"));
            long limit = nonnegative(spec, node.getString("daily-limit", "0"));
            currencies.add(new Currency(key, symbol, decimals, start, limit));
        }
        String defaultCode = config.getString("default-currency", "COIN").toUpperCase(Locale.ROOT);
        if (currencies.stream().noneMatch(c -> c.code().equals(defaultCode))) throw new IllegalArgumentException("Default currency is not configured");
        Policy policy = new Policy(config.getInt("policy.transfer-fee-bps", 25), config.getInt("policy.swap-fee-bps", 30),
                config.getInt("policy.savings-apr-bps", 350), config.getInt("policy.max-active-orders", 10),
                config.getInt("policy.max-open-escrows", 20), config.getInt("policy.max-open-deposits", 20));
        int queue = config.getInt("worker.queue-capacity", 512), interval = config.getInt("worker.maintenance-seconds", 10);
        if (queue < 32 || queue > 10_000 || interval < 1 || interval > 300) throw new IllegalArgumentException("Invalid worker settings");
        return new Settings(currencies, defaultCode, policy, config.getBoolean("vault-enabled", true), queue, interval);
    }
    private static long nonnegative(Currency currency, String value) {
        if (value != null && value.matches("0+(?:\\.0+)?")) return 0;
        return currency.parse(value);
    }
}
