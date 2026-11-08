# Developer API

## Load the service

Add AuroraEconomy as a dependency in your plugin descriptor. Compile against the published thin/plugin artifact **without shading Aurora classes into your own JAR**; duplicate service interface classes break Bukkit service identity.

```yaml
depend: [AuroraEconomy]
```

```java
import dev.aurora.economy.api.EconomyApi;

var registration = getServer().getServicesManager().getRegistration(EconomyApi.class);
if (registration == null) throw new IllegalStateException("Aurora API unavailable");
EconomyApi economy = registration.getProvider();
```

Release artifacts include a thin JAR and sources JAR for integration development. The shaded regular JAR is the server installation artifact. There is no Maven Central publication in v1.0.

## Money is minor units

```java
var coin = economy.currencies().stream()
    .filter(currency -> currency.code().equals("COIN"))
    .findFirst().orElseThrow();
long units = coin.parse("12.50"); // 1250, when COIN has 2 decimal places
String display = coin.format(units);
```

All API financial amounts are `long` minor units, not doubles. All currencies and result maps are immutable snapshots. Do not add currencies together as if their units were interchangeable.

## Replay-safe transfer

Persist the request ID in your own business operation **before** calling the economy. For example, an order ID or invoice ID is better than a freshly generated UUID on every retry.

```java
UUID buyer = playerUuid;
UUID seller = sellerUuid;
String requestId = "shop-order-8f921";

economy.transfer(buyer, seller, "COIN", coin.parse("12.50"), requestId)
    .whenComplete((receipt, error) -> {
        if (error != null) {
            // Inspect the cause. Do not deliver a product on an exceptional result.
            return;
        }
        // Persist receipt.id(), receipt.hash() and receipt.replayed().
        // Your own delivery layer must also be idempotent!
        getServer().getScheduler().runTask(this, () -> {
            // Safe place for Bukkit player/inventory work.
        });
    });
```

The sender owes the configured transfer fee in addition to `units`. Both movements commit atomically. Reusing the same actor-scoped key with the same intent returns the original receipt; changing recipient, amount or currency produces `IDEMPOTENCY_CONFLICT`.

Accepted request IDs contain 1–96 ASCII letters, digits, `.`, `_`, `:`, or `-`. The engine scopes them by operation kind and actor UUID. Currency codes are canonicalized before intent comparison.

A lost response is not proof of failure. Retry **the same** request ID. A new request ID authorizes a new transfer. A rolled-back request can be retried after its precondition is fixed.

## Futures and backpressure

Every financial API call returns a `CompletableFuture`. It can complete on Aurora's worker thread; never assume Paper's main thread. The queue is bounded. Rejection completes exceptionally with `RejectedExecutionException` before the operation runs.

Do not call `.join()`/`.get()` on the server thread. Do not perform blocking network operations inside your completion callback; schedule your own work. Handle plugin shutdown and unavailable services as normal lifecycle states.

`EconomyException` exposes stable codes including invalid amount, insufficient funds, daily limit, account frozen, forbidden ownership, contract state, slippage, storage and integrity. Exceptional completion usually wraps it in a `CompletionException`. Null UUIDs/currencies and malformed or abbreviated contract UUIDs are rejected explicitly. A full UUID supplied to name resolution is an identity, not proof of registration; pre-registration receiving remains supported.

## Contracts and exchange

```java
// Hold a refundable payment for one hour.
economy.escrow(buyer, seller, "COIN", 1250L, 3600L, "escrow-order-8f921")
    .thenAccept(receipt -> {
        String escrowId = receipt.reference(); // full UUID
    });

// Each transition also has its own persisted request ID.
economy.acceptEscrow(seller, escrowId, "accept-order-8f921");
economy.cancelEscrow(buyer, escrowId, "cancel-order-8f921");

// Fully funded savings, 30 days; positive yield requires treasury reserves.
economy.save(buyer, "COIN", 25000L, 30, "savings-2026-01");
economy.claim(buyer, depositId, "claim-2026-01");

// Observe price, then require the expected minimum at execution.
economy.quote("COIN", "GEM", 10000L).thenCompose(quote ->
    economy.swap(buyer, "COIN", "GEM", quote.input(), quote.output(), "trade-8f921")
);
```

These snippets illustrate separate business actions; do not invoke acceptance and cancellation as a single workflow. Claiming before maturity fails. Quotes are not reservations. Swap receipt references contain the actual output minor units as a decimal integer string, whereas creation receipts reference contract UUIDs.

Escrow does not atomically deliver Minecraft items. Database currency writes and Bukkit inventories are different consistency domains. Your plugin must implement durable, idempotent delivery/recovery; do not pretend a callback makes an item sale exactly-once.

The async service intentionally exposes player financial operations, not administrative minting, direct SQL, migrations or ledger-freeze escape hatches. Server operators administer these through permission-checked commands. All in-process plugins are nevertheless trusted code, not a security sandbox.

## Vault compatibility

Vault exposes only the configured default currency. Its `OfflinePlayer` overloads use the supplied UUID. Name overloads resolve only known names or explicit UUIDs, and world overloads map to the same global wallet.

- `depositPlayer`: journaled external issuance from `system:mint`.
- `withdrawPlayer`: journaled burn to `system:mint`, subject to wallet freeze and daily spending budget.
- Negative, nonfinite and inexact fractional inputs are rejected; zero is a no-op success.
- Input minor units above `2^53-1` are rejected for double-interface safety.
- Vault balance responses still use doubles and can lose display precision for enormous balances. Use the long API for exact values.
- Failures do not report an uncommitted success, and ordinary financial failures return the current balance when readable. If a mutation has already been confirmed committed and only the subsequent balance lookup fails, its response remains `SUCCESS` with `balance=Double.NaN` and an explanatory message. Do not repeat that deposit/withdrawal; the money already moved.
- Issuance sources/directions are checked in the engine; Vault cannot target a wallet different from its attributed player or use a deposit source to bypass withdrawal controls.
- Generic Vault banks return `NOT_IMPLEMENTED`.

Vault calls are synchronous and can block while waiting for the ledger lock. No timeout is used to falsely report failure while a later commit might succeed. Vault has no request IDs; another plugin repeating a deposit call is a new issuance, not an Aurora replay.
