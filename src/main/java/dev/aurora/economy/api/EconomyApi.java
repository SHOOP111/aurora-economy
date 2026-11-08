package dev.aurora.economy.api;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.Models.*;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Register through Bukkit's ServicesManager. Amounts are always integer minor units.
 * Futures never promise Bukkit's main thread. Reuse the same request ID after an uncertain result. */
public interface EconomyApi {
    Collection<Currency> currencies();
    CompletableFuture<Long> balance(UUID owner, String currency);
    CompletableFuture<Portfolio> portfolio(UUID owner);
    CompletableFuture<Receipt> transfer(UUID sender, UUID recipient, String currency, long units, String requestId);
    CompletableFuture<Receipt> escrow(UUID sender, UUID recipient, String currency, long units, long lifetimeSeconds, String requestId);
    CompletableFuture<Receipt> acceptEscrow(UUID recipient, String escrowId, String requestId);
    CompletableFuture<Receipt> cancelEscrow(UUID sender, String escrowId, String requestId);
    CompletableFuture<Receipt> save(UUID owner, String currency, long principal, int days, String requestId);
    CompletableFuture<Receipt> claim(UUID owner, String depositId, String requestId);
    CompletableFuture<Quote> quote(String from, String to, long input);
    CompletableFuture<Receipt> swap(UUID owner, String from, String to, long input, long minOutput, String requestId);
}
