package dev.aurora.economy.paper;

import dev.aurora.economy.api.DefaultEconomyApi;
import dev.aurora.economy.api.EconomyApi;
import dev.aurora.economy.core.EconomyEngine;
import dev.aurora.economy.core.EconomyException;
import dev.aurora.economy.storage.LedgerStore;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class AuroraEconomyPlugin extends JavaPlugin implements Listener {
    private LedgerStore store;
    private EconomyEngine engine;
    private Settings settings;
    private ThreadPoolExecutor worker;
    private Dashboard dashboard;
    @Override public void onEnable() {
        try {
            saveDefaultConfig(); settings = Settings.load(getConfig());
            Clock clock = Clock.systemUTC();
            store = new LedgerStore(getDataFolder().toPath().resolve("economy.db"), clock, settings.currencies());
            engine = new EconomyEngine(store, clock, settings.policy(), settings.currencies());
            var audit = engine.audit();
            if (!audit.healthy()) throw new IllegalStateException("Integrity check failed: " + audit.violations());
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(settings.queueCapacity()), r -> {
                Thread thread = new Thread(r, "aurora-ledger"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            dashboard = new Dashboard(this);
            var command = new EconomyCommand(this);
            var registered = Objects.requireNonNull(getCommand("economy"));
            registered.setExecutor(command); registered.setTabCompleter(command);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(dashboard, this);
            getServer().getServicesManager().register(EconomyApi.class, new DefaultEconomyApi(engine, worker), this, ServicePriority.Normal);
            if (settings.vault() && getServer().getPluginManager().isPluginEnabled("Vault")) VaultBridge.register(this);
            long ticks = settings.maintenanceSeconds() * 20L;
            getServer().getScheduler().runTaskTimer(this, () -> async(engine::maintain).whenComplete((report, error) -> {
                if (error != null && !(unwrap(error) instanceof RejectedExecutionException)) getLogger().log(Level.SEVERE, "Maintenance failed", unwrap(error));
            }), ticks, ticks);
            for (Player player : getServer().getOnlinePlayers()) registerPlayer(player);
            getLogger().info("Aurora ready: " + settings.currencies().size() + " currencies; " + audit.transactions() + " verified transactions. Head " + audit.headHash());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Aurora failed closed. No financial operations will be accepted.", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @EventHandler public void joined(PlayerJoinEvent event) { registerPlayer(event.getPlayer()); }
    private void registerPlayer(Player player) {
        var id = player.getUniqueId(); var name = player.getName();
        async(() -> engine.register(id, name)).whenComplete((receipt, error) -> { if (error != null) failure(player, error); });
    }
    @Override public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        getServer().getServicesManager().unregisterAll(this);
        if (worker != null) {
            worker.shutdown();
            try {
                // Never drop queued writes silently. Shutdown waits for accepted requests to drain.
                if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                    getLogger().warning("Ledger queue is still draining; waiting for safe shutdown.");
                    while (!worker.awaitTermination(30, TimeUnit.SECONDS)) getLogger().warning("Still draining ledger queue.");
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (store != null) {
            try { store.close(); } catch (RuntimeException e) { getLogger().log(Level.SEVERE, "Database close failed", e); }
        }
    }
    EconomyEngine engine() { return engine; }
    LedgerStore store() { return store; }
    Settings settings() { return settings; }
    Dashboard dashboard() { return dashboard; }
    <T> CompletableFuture<T> async(Supplier<T> operation) {
        try {
            if (worker == null || worker.isShutdown()) throw new RejectedExecutionException("Economy unavailable");
            return CompletableFuture.supplyAsync(operation, worker);
        } catch (RejectedExecutionException e) { return CompletableFuture.failedFuture(e); }
    }
    void main(Runnable task) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) task.run();
        else {
            try { getServer().getScheduler().runTask(this, () -> { if (isEnabled()) task.run(); }); }
            catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* shutdown race; ledger result remains durable */ }
        }
    }
    void reply(CommandSender sender, Component text) {
        main(() -> { if (!(sender instanceof Player player) || player.isOnline()) sender.sendMessage(Component.text("Aurora › ", NamedTextColor.GOLD).append(text)); });
    }
    void failure(CommandSender sender, Throwable error) {
        Throwable root = unwrap(error);
        String message;
        if (root instanceof EconomyException problem) {
            message = problem.code() + ": " + problem.getMessage();
            if (problem.code() == EconomyException.Code.STORAGE || problem.code() == EconomyException.Code.INTEGRITY) getLogger().log(Level.SEVERE, "Economy operation failed", root);
        } else if (root instanceof RejectedExecutionException) message = "Economy is busy or shutting down. Try again shortly.";
        else {
            getLogger().log(Level.SEVERE, "Unexpected economy error", root);
            message = "Unexpected error. Contact an administrator; no success has been confirmed.";
        }
        reply(sender, Component.text(message, NamedTextColor.RED));
    }
    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }
}
