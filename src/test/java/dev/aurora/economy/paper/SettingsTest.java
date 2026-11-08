package dev.aurora.economy.paper;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    private YamlConfiguration defaults() {
        var stream = Objects.requireNonNull(getClass().getResourceAsStream("/config.yml"));
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    @Test void shippedConfigurationIsValid() {
        Settings settings = Settings.load(defaults()); assertEquals("COIN", settings.defaultCurrency()); assertEquals(2, settings.currencies().size());
        assertEquals(100_000, settings.currencies().getFirst().startingBalance()); assertEquals(5_000_000, settings.currencies().getFirst().dailyLimit());
    }
    @Test void invalidDefaultCurrencyFailsClosed() { var config = defaults(); config.set("default-currency", "NOPE"); assertThrows(IllegalArgumentException.class, () -> Settings.load(config)); }
    @Test void negativeStartingBalanceRejected() { var config = defaults(); config.set("currencies.COIN.starting-balance", "-1"); assertThrows(RuntimeException.class, () -> Settings.load(config)); }
    @Test void workerQueueBoundsChecked() { var config = defaults(); config.set("worker.queue-capacity", 1); assertThrows(IllegalArgumentException.class, () -> Settings.load(config)); }
    @Test void ratesAreBounded() { var config = defaults(); config.set("policy.savings-apr-bps", 10001); assertThrows(IllegalArgumentException.class, () -> Settings.load(config)); }
    @Test void precisionIsBounded() { var config = defaults(); config.set("currencies.COIN.decimals", 7); assertThrows(IllegalArgumentException.class, () -> Settings.load(config)); }
}
