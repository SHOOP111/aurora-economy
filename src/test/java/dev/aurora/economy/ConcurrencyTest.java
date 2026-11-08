package dev.aurora.economy;

import dev.aurora.economy.core.EconomyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTest extends EngineFixture {
    @Test void concurrentReplayAppliesOnce() throws Exception {
        try (var workers = Executors.newFixedThreadPool(12)) {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 120; i++) calls.add(() -> engine.transfer(alice, bob, "COIN", 1_000, "shared").id());
            var results = workers.invokeAll(calls); String id = results.getFirst().get();
            for (var result : results) assertEquals(id, result.get());
        }
        assertEquals(101_000, engine.balance(bob, "COIN")); healthy();
    }
    @Test void concurrentOverspendNeverGoesNegative() throws Exception {
        try (var workers = Executors.newFixedThreadPool(12)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String request = "payment-" + i;
                calls.add(() -> { try { engine.transfer(alice, bob, "COIN", 2_000, request); return true; }
                    catch (EconomyException e) { assertEquals(EconomyException.Code.INSUFFICIENT_FUNDS, e.code()); return false; } });
            }
            int succeeded = 0;
            for (var result : workers.invokeAll(calls)) if (result.get()) succeeded++;
            assertEquals(49, succeeded);
        }
        assertEquals(1_755, engine.balance(alice, "COIN")); assertEquals(198_000, engine.balance(bob, "COIN")); healthy();
    }
    @Test void randomizedTrafficConservesSupply() {
        Random random = new Random(2026);
        for (int i = 0; i < 1_000; i++) {
            boolean direction = random.nextBoolean();
            engine.transfer(direction ? alice : bob, direction ? bob : alice, "COIN", random.nextLong(1, 100), "random-" + i);
        }
        var supply = engine.supply().getFirst(); assertEquals(200_000, supply.issued()); assertEquals(supply.issued(), supply.wallets() + supply.treasury()); healthy();
    }
    @Test void escrowAcceptVersusCancelHasOneWinner() throws Exception {
        var id = engine.createEscrow(alice, bob, "COIN", 10_000, 60, "race").reference();
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> accept = () -> { try { engine.acceptEscrow(bob, id, "accept"); return true; } catch (EconomyException e) { assertEquals(EconomyException.Code.INVALID_STATE, e.code()); return false; } };
            Callable<Boolean> cancel = () -> { try { engine.cancelEscrow(alice, id, "cancel"); return true; } catch (EconomyException e) { assertEquals(EconomyException.Code.INVALID_STATE, e.code()); return false; } };
            int winners = 0; for (var result : workers.invokeAll(List.of(accept, cancel))) if (result.get()) winners++;
            assertEquals(1, winners);
        }
        assertEquals(0, engine.portfolio(alice).escrowHeld().get("COIN")); healthy();
    }
}
