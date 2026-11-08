package dev.aurora.economy;

import dev.aurora.economy.api.DefaultEconomyApi;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiTest extends EngineFixture {
    @Test void asynchronousTransferUsesReplaySafeLedger() {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var api = new DefaultEconomyApi(engine, worker);
            var first = api.transfer(alice, bob, "COIN", 1_000, "api-one").join();
            assertEquals(first.id(), api.transfer(alice, bob, "COIN", 1_000, "api-one").join().id()); assertEquals(101_000, api.balance(bob, "COIN").join()); healthy();
        }
    }
    @Test void executorRejectionReturnsFailedFutureBeforeMutation() {
        var api = new DefaultEconomyApi(engine, work -> { throw new RejectedExecutionException("full"); });
        var error = assertThrows(CompletionException.class, () -> api.transfer(alice, bob, "COIN", 1_000, "rejected").join());
        assertInstanceOf(RejectedExecutionException.class, error.getCause()); assertEquals(100_000, engine.balance(alice, "COIN")); healthy();
    }
    @Test void exceptionalFutureDoesNotHideDomainError() {
        var api = new DefaultEconomyApi(engine, Runnable::run);
        var error = assertThrows(CompletionException.class, () -> api.transfer(alice, alice, "COIN", 1_000, "bad").join());
        assertInstanceOf(dev.aurora.economy.core.EconomyException.class, error.getCause());
    }
    @Test void contractFuturesExposePersistentReferences() {
        var api = new DefaultEconomyApi(engine, Runnable::run);
        String id = api.escrow(alice, bob, "COIN", 1_000, 60, "api-offer").join().reference(); api.acceptEscrow(bob, id, "api-accept").join(); assertEquals(101_000, api.balance(bob, "COIN").join()); healthy();
    }
    @Test void returnedPortfolioCannotBeMutated() {
        var snapshot = new DefaultEconomyApi(engine, Runnable::run).portfolio(alice).join();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.wallets().put("COIN", 1L));
    }
}
