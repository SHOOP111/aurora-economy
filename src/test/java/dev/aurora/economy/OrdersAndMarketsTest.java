package dev.aurora.economy;

import dev.aurora.economy.core.EconomyEngine;
import dev.aurora.economy.core.Policy;
import dev.aurora.economy.storage.LedgerStore;
import org.junit.jupiter.api.Test;
import static dev.aurora.economy.core.EconomyException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class OrdersAndMarketsTest extends EngineFixture {
    @Test void recurringPaymentsAdvanceAtomically() {
        engine.createOrder(alice, bob, "COIN", 1_000, 60, 2, "salary"); clock.advance(60);
        assertEquals(1, engine.maintain().paid()); assertEquals(0, engine.maintain().paid()); assertEquals(101_000, engine.balance(bob, "COIN"));
        clock.advance(60); assertEquals(1, engine.maintain().paid()); assertEquals("COMPLETED", engine.orders(alice).getFirst().state()); healthy();
    }
    @Test void offlineTimeDoesNotCreateCatchUpBurst() {
        engine.createOrder(alice, bob, "COIN", 1_000, 60, 100, "salary"); clock.advance(1_000_000);
        assertEquals(1, engine.maintain().paid()); assertEquals(0, engine.maintain().paid()); assertEquals(1, engine.orders(alice).getFirst().executions()); healthy();
    }
    @Test void failedOrdersPauseWithoutLosingInstallment() {
        var id = engine.createOrder(alice, bob, "COIN", 200_000, 60, 2, "large").reference(); clock.advance(60);
        assertEquals(1, engine.maintain().paused()); assertEquals("PAUSED", engine.orders(alice).getFirst().state()); assertEquals(0, engine.orders(alice).getFirst().executions());
        assertEquals(0, engine.maintain().paused());
        engine.issue(mallory, EconomyEngine.wallet(alice), "COIN", 200_000, false, "fund", "ADMIN_MINT");
        engine.changeOrder(alice, id, true, "resume"); clock.advance(60); assertEquals(1, engine.maintain().paid()); healthy();
    }
    @Test void rePausedOrderWorksAfterMultipleResumes() {
        var id = engine.createOrder(alice, bob, "COIN", 200_000, 60, 2, "large").reference(); clock.advance(60); engine.maintain();
        engine.changeOrder(alice, id, true, "resume"); clock.advance(60); assertEquals(1, engine.maintain().paused()); assertEquals("PAUSED", engine.orders(alice).getFirst().state()); healthy();
    }
    @Test void canceledOrdersNeverRun() {
        var id = engine.createOrder(alice, bob, "COIN", 1_000, 60, 2, "salary").reference(); engine.changeOrder(alice, id, false, "cancel"); clock.advance(600);
        assertEquals(0, engine.maintain().paid()); error(INVALID_STATE, () -> engine.changeOrder(alice, id, true, "resume")); healthy();
    }
    @Test void cannotChangeSomeoneElsesOrder() {
        var id = engine.createOrder(alice, bob, "COIN", 1_000, 60, 2, "salary").reference(); error(FORBIDDEN, () -> engine.changeOrder(bob, id, false, "cancel")); healthy();
    }
    @Test void schedulerReplaySurvivesRestart() {
        engine.createOrder(alice, bob, "COIN", 1_000, 60, 2, "salary"); clock.advance(60); engine.maintain(); store.close();
        store = new LedgerStore(directory.resolve("economy.db"), clock, currencies); engine = new EconomyEngine(store, clock, Policy.defaults(), currencies);
        assertEquals(0, engine.maintain().paid()); assertEquals(101_000, engine.balance(bob, "COIN")); healthy();
    }
    void pool() { fund("COIN", 1_000_000); fund("GEM", 100_000); engine.fundPool(mallory, "COIN", "GEM", 1_000_000, 100_000, "seed"); }
    @Test void reserveBackedSwapConservesBothCurrencies() {
        pool(); var quote = engine.quote("COIN", "GEM", 10_000); assertEquals(987, quote.output());
        var receipt = engine.swap(alice, "COIN", "GEM", 10_000, quote.output(), "trade");
        assertEquals("987", receipt.reference()); assertEquals(90_000, engine.balance(alice, "COIN")); assertEquals(1_987, engine.balance(alice, "GEM"));
        assertTrue(engine.swap(alice, "COIN", "GEM", 10_000, quote.output(), "trade").replayed()); healthy();
    }
    @Test void slippageFailureChargesNothing() {
        pool(); var quote = engine.quote("COIN", "GEM", 10_000); engine.swap(bob, "COIN", "GEM", 50_000, 1, "move");
        error(SLIPPAGE, () -> engine.swap(alice, "COIN", "GEM", 10_000, quote.output(), "stale")); assertEquals(100_000, engine.balance(alice, "COIN")); healthy();
    }
    @Test void reverseDirectionUsesSamePool() { pool(); assertEquals(engine.poolAccount("COIN", "GEM"), engine.poolAccount("GEM", "COIN")); engine.swap(alice, "GEM", "COIN", 10, 1, "reverse"); healthy(); }
    @Test void unfundedPoolsCannotCreateMoney() { error(NO_LIQUIDITY, () -> engine.quote("COIN", "GEM", 1_000)); error(NO_LIQUIDITY, () -> engine.swap(alice, "COIN", "GEM", 1_000, 1, "empty")); healthy(); }
    @Test void fundingIsAllOrNothing() {
        fund("COIN", 1_000); error(INSUFFICIENT_FUNDS, () -> engine.fundPool(mallory, "COIN", "GEM", 1_000, 1_000, "seed")); assertEquals(1_000, engine.treasury("COIN")); healthy();
    }
    @Test void identicalCurrenciesAndZeroMinimumAreRejected() {
        error(INVALID_AMOUNT, () -> engine.quote("COIN", "COIN", 1_000)); error(INVALID_AMOUNT, () -> engine.swap(alice, "COIN", "GEM", 1_000, 0, "bad"));
    }
}
