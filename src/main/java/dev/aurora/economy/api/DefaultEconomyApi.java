package dev.aurora.economy.api;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.EconomyEngine;
import dev.aurora.economy.core.Models.*;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

public final class DefaultEconomyApi implements EconomyApi {
    private final EconomyEngine engine;
    private final Executor executor;
    public DefaultEconomyApi(EconomyEngine engine, Executor executor) {
        this.engine = Objects.requireNonNull(engine, "engine"); this.executor = Objects.requireNonNull(executor, "executor");
    }
    private <T> CompletableFuture<T> submit(Supplier<T> operation) {
        try { return CompletableFuture.supplyAsync(operation, executor); }
        catch (RejectedExecutionException e) { return CompletableFuture.failedFuture(e); }
    }
    @Override public Collection<Currency> currencies() { return engine.currencies(); }
    @Override public CompletableFuture<Long> balance(UUID owner, String code) { return submit(() -> engine.balance(owner, code)); }
    @Override public CompletableFuture<Portfolio> portfolio(UUID owner) { return submit(() -> engine.portfolio(owner)); }
    @Override public CompletableFuture<Receipt> transfer(UUID a, UUID b, String c, long n, String key) { return submit(() -> engine.transfer(a, b, c, n, key)); }
    @Override public CompletableFuture<Receipt> escrow(UUID a, UUID b, String c, long n, long seconds, String key) { return submit(() -> engine.createEscrow(a, b, c, n, seconds, key)); }
    @Override public CompletableFuture<Receipt> acceptEscrow(UUID owner, String id, String key) { return submit(() -> engine.acceptEscrow(owner, id, key)); }
    @Override public CompletableFuture<Receipt> cancelEscrow(UUID owner, String id, String key) { return submit(() -> engine.cancelEscrow(owner, id, key)); }
    @Override public CompletableFuture<Receipt> save(UUID owner, String c, long n, int days, String key) { return submit(() -> engine.openDeposit(owner, c, n, days, key)); }
    @Override public CompletableFuture<Receipt> claim(UUID owner, String id, String key) { return submit(() -> engine.claimDeposit(owner, id, key)); }
    @Override public CompletableFuture<Quote> quote(String from, String to, long n) { return submit(() -> engine.quote(from, to, n)); }
    @Override public CompletableFuture<Receipt> swap(UUID owner, String a, String b, long n, long min, String key) { return submit(() -> engine.swap(owner, a, b, n, min, key)); }
}
