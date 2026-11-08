package dev.aurora.economy;

import dev.aurora.economy.core.EconomyEngine;
import dev.aurora.economy.core.Policy;
import org.junit.jupiter.api.Test;
import static dev.aurora.economy.core.EconomyException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class LimitsAndAuditTest extends EngineFixture {
    void restrict() { engine = new EconomyEngine(store, clock, new Policy(25, 30, 350, 1, 1, 1), currencies); }
    @Test void escrowQuotaReleasesOnClosure() {
        restrict(); var id = engine.createEscrow(alice, bob, "COIN", 100, 60, "one").reference();
        error(LIMIT_REACHED, () -> engine.createEscrow(alice, bob, "COIN", 100, 60, "two")); engine.cancelEscrow(alice, id, "cancel");
        engine.createEscrow(alice, bob, "COIN", 100, 60, "two"); healthy();
    }
    @Test void pausedOrdersStillCountTowardsQuota() {
        restrict(); engine.createOrder(alice, bob, "COIN", 200_000, 60, 2, "one"); clock.advance(60); engine.maintain();
        error(LIMIT_REACHED, () -> engine.createOrder(alice, bob, "COIN", 100, 60, 2, "two")); healthy();
    }
    @Test void lockedDepositsCountTowardsQuota() {
        restrict(); engine.openDeposit(alice, "COIN", 1, 1, "one"); error(LIMIT_REACHED, () -> engine.openDeposit(alice, "COIN", 1, 1, "two")); healthy();
    }
    @Test void auditTripwireFreezesWritesButAllowsInspection() {
        store.read(c -> { try (var s = c.createStatement()) { s.execute("UPDATE balances SET units=units+1 WHERE account='" + EconomyEngine.wallet(alice) + "' AND currency='COIN'"); } return null; });
        assertFalse(engine.audit().healthy()); assertTrue(store.frozen()); error(INTEGRITY, () -> engine.transfer(alice, bob, "COIN", 100, "blocked")); assertNotNull(engine.portfolio(alice));
        assertNotNull(store.backup(directory.resolve("evidence.db")));
    }
    @Test void sqlCannotPersistFractionalMoney() {
        assertThrows(RuntimeException.class, () -> store.read(c -> { try (var s = c.createStatement()) { s.execute("UPDATE balances SET units=units+0.5 WHERE currency='COIN'"); } return null; })); healthy();
    }
    @Test void walletStatementsShowNetDebitIncludingFee() {
        engine.transfer(alice, bob, "COIN", 10_000, "statement"); var line = engine.statement(alice, 1).getFirst(); assertEquals(-10_025, line.delta()); assertEquals("COIN", line.currency()); healthy();
    }
    @Test void leaderboardExcludesLockedCustody() {
        engine.openDeposit(alice, "COIN", 1, 1, "save"); var leaders = engine.leaders("COIN", 10); assertEquals(bob, leaders.getFirst().player()); assertEquals(99_999, leaders.get(1).units()); healthy();
    }
}
