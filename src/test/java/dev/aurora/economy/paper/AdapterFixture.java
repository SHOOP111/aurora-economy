package dev.aurora.economy.paper;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.EconomyEngine;
import dev.aurora.economy.core.Policy;
import dev.aurora.economy.storage.LedgerStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

abstract class AdapterFixture {
    @TempDir Path path;
    final UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
    final UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");
    AuroraEconomyPlugin plugin;
    LedgerStore store;
    EconomyEngine engine;
    Player player;
    @BeforeEach void setupAdapter() {
        var currencies = List.of(new Currency("COIN", "$", 2, 100_000, 0), new Currency("GEM", "♦", 0, 0, 0));
        store = new LedgerStore(path.resolve("adapter.db"), Clock.systemUTC(), currencies);
        engine = new EconomyEngine(store, Clock.systemUTC(), Policy.defaults(), currencies);
        engine.register(alice, "Alice"); engine.register(bob, "Bob");
        plugin = mock(AuroraEconomyPlugin.class);
        when(plugin.engine()).thenReturn(engine);
        when(plugin.store()).thenReturn(store);
        when(plugin.settings()).thenReturn(new Settings(currencies, "COIN", Policy.defaults(), true, 512, 10));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("aurora-test"));
        when(plugin.getServer()).thenReturn(mock(Server.class));
        when(plugin.isEnabled()).thenReturn(true);
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; }).when(plugin).main(any(Runnable.class));
        when(plugin.async(any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(0);
            try { return CompletableFuture.completedFuture(operation.get()); }
            catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
        });
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(alice);
        when(player.getName()).thenReturn("Alice");
        when(player.hasPermission("aurora.use")).thenReturn(true);
    }
    @AfterEach void cleanupAdapter() { store.close(); }
}
