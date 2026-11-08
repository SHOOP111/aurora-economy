package dev.aurora.economy.paper;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.Policy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

record Settings(List<Currency> currencies, String defaultCurrency, Policy policy,
                boolean vault, int queueCapacity, int maintenanceSeconds) {
    Settings { currencies = List.copyOf(currencies); }
    static Settings load(FileConfiguration config) {
        var section = config.getConfigurationSection("currencies");
        if (section == null || section.getKeys(false).isEmpty()) throw new IllegalArgumentException("Missing currencies section");
        List<Currency> currencies = new ArrayList<>();
        var codes = new HashSet<String>();
        for (String key : section.getKeys(false)) {
            var node = section.getConfigurationSection(key);
            if (node == null) throw new IllegalArgumentException("Invalid currency section: " + key);
            String symbol = text(node, "symbol", key); int decimals = integer(node, "decimals", 2);
            Currency spec = new Currency(key, symbol, decimals, 0, 0);
            if (!codes.add(spec.code())) throw new IllegalArgumentException("Duplicate currency: " + spec.code());
            long start = spec.parseNonnegative(text(node, "starting-balance", "0"));
            long limit = spec.parseNonnegative(text(node, "daily-limit", "0"));
            currencies.add(new Currency(key, symbol, decimals, start, limit));
        }
        String defaultCode = text(config, "default-currency", "COIN").toUpperCase(Locale.ROOT);
        if (!codes.contains(defaultCode)) throw new IllegalArgumentException("Default currency is not configured");
        Policy policy = new Policy(integer(config, "policy.transfer-fee-bps", 25), integer(config, "policy.swap-fee-bps", 30),
                integer(config, "policy.savings-apr-bps", 350), integer(config, "policy.max-active-orders", 10),
                integer(config, "policy.max-open-escrows", 20), integer(config, "policy.max-open-deposits", 20));
        int queue = integer(config, "worker.queue-capacity", 512), interval = integer(config, "worker.maintenance-seconds", 10);
        if (queue < 32 || queue > 10_000 || interval < 1 || interval > 300) throw new IllegalArgumentException("Invalid worker settings");
        Object vault = config.get("vault-enabled", true);
        if (!(vault instanceof Boolean enabled)) throw new IllegalArgumentException("vault-enabled must be a boolean");
        return new Settings(currencies, defaultCode, policy, enabled, queue, interval);
    }
    private static String text(ConfigurationSection section, String key, String fallback) {
        Object value = section.get(key, fallback);
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a string (quote monetary amounts)");
        return text;
    }
    private static int integer(ConfigurationSection section, String key, int fallback) {
        Object value = section.get(key, fallback);
        // Bukkit's getInt silently truncates fractions and oversized longs. Configuration must fail closed instead.
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " is out of range");
        return (int) number;
    }
}
