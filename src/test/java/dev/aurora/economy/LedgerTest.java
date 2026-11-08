package dev.aurora.economy;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.EconomyEngine;
import dev.aurora.economy.core.Policy;
import dev.aurora.economy.storage.LedgerStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.aurora.economy.core.EconomyException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class LedgerTest extends EngineFixture {
    @Test void transferBalancesAndTax() {
        var receipt = engine.transfer(alice, bob, "coin", 10_000, "one");
        assertEquals(89_975, engine.balance(alice, "COIN")); assertEquals(110_000, engine.balance(bob, "COIN"));
        assertEquals(25, engine.treasury("COIN")); assertFalse(receipt.replayed()); healthy();
    }
    @Test void requestReplayCannotChargeTwice() {
        var first = engine.transfer(alice, bob, "COIN", 1_000, "retry");
        var second = engine.transfer(alice, bob, "COIN", 1_000, "retry");
        assertEquals(first.id(), second.id()); assertTrue(second.replayed()); assertEquals(101_000, engine.balance(bob, "COIN")); healthy();
    }
    @Test void conflictingIntentIsRejected() {
        engine.transfer(alice, bob, "COIN", 1_000, "same");
        error(IDEMPOTENCY_CONFLICT, () -> engine.transfer(alice, bob, "COIN", 2_000, "same")); healthy();
    }
    @Test void keysAreActorScoped() {
        engine.transfer(alice, bob, "COIN", 1_000, "same"); engine.transfer(bob, alice, "COIN", 1_000, "same");
        assertEquals(99_997, engine.balance(alice, "COIN")); healthy();
    }
    @Test void insufficientFundsRollBackEverything() {
        long before = engine.audit().transactions();
        error(INSUFFICIENT_FUNDS, () -> engine.transfer(alice, bob, "COIN", 100_000, "too-much"));
        assertEquals(100_000, engine.balance(alice, "COIN")); assertEquals(0, engine.treasury("COIN"));
        assertEquals(0, engine.portfolio(alice).spentToday().get("COIN")); assertEquals(before, engine.audit().transactions()); healthy();
    }
    @Test void failedKeyCanBeRetriedAfterFunding() {
        error(INSUFFICIENT_FUNDS, () -> engine.transfer(alice, bob, "COIN", 150_000, "later"));
        engine.issue(mallory, EconomyEngine.wallet(alice), "COIN", 100_000, false, "fund", "ADMIN_MINT");
        assertFalse(engine.transfer(alice, bob, "COIN", 150_000, "later").replayed()); healthy();
    }
    @Test void selfPaymentAndZeroRejected() {
        error(INVALID_AMOUNT, () -> engine.transfer(alice, alice, "COIN", 1, "x"));
        error(INVALID_AMOUNT, () -> engine.transfer(alice, bob, "COIN", 0, "x"));
    }
    @Test void registrationIsOneTimeButNameUpdates() {
        engine.register(alice, "Renamed"); assertEquals(alice, engine.resolve("renamed")); assertEquals(100_000, engine.balance(alice, "COIN")); healthy();
    }
    @Test void unknownNameIsNotFabricated() { error(NOT_FOUND, () -> engine.resolve("NeverJoined")); assertEquals(alice, engine.resolve(alice.toString())); }
    @Test void survivesRestartAndReplay() {
        var first = engine.transfer(alice, bob, "COIN", 123, "restart"); store.close();
        store = new LedgerStore(directory.resolve("economy.db"), clock, currencies);
        engine = new EconomyEngine(store, clock, Policy.defaults(), currencies);
        assertEquals(first.id(), engine.transfer(alice, bob, "COIN", 123, "restart").id()); assertEquals(100_123, engine.balance(bob, "COIN")); healthy();
    }
    @Test void dailyCapIsAtomicAndResetsAtUtcMidnight() {
        var capped = List.of(new Currency("COIN", "$", 2, 100_000, 10_025), currencies.get(1));
        engine = new EconomyEngine(store, clock, Policy.defaults(), capped);
        engine.transfer(alice, bob, "COIN", 10_000, "cap");
        error(DAILY_LIMIT, () -> engine.transfer(alice, bob, "COIN", 1, "cap2"));
        clock.advance(86_400); engine.transfer(alice, bob, "COIN", 1, "tomorrow"); healthy();
    }
    @Test void issuanceAndBurnPreserveSupply() {
        fund("COIN", 25_000); engine.issue(mallory, LedgerStore.TREASURY, "COIN", 5_000, true, "burn", "ADMIN_BURN");
        assertEquals(220_000, engine.supply().getFirst().issued()); assertEquals(20_000, engine.treasury("COIN")); healthy();
    }
    @Test void immutabilityTriggersRejectEdits() {
        assertThrows(RuntimeException.class, () -> store.read(c -> { try (var s = c.createStatement()) { s.execute("UPDATE journal SET kind='FAKE'"); } return null; })); healthy();
    }
    @Test void balanceCorruptionDetected() {
        store.read(c -> { try (var s = c.createStatement()) { s.execute("UPDATE balances SET units=units+1 WHERE account='" + EconomyEngine.wallet(alice) + "' AND currency='COIN'"); } return null; });
        assertFalse(engine.audit().healthy());
    }
    @Test void journalCorruptionDetectedEvenIfTriggersRemoved() {
        store.read(c -> { try (var s = c.createStatement()) { s.execute("DROP TRIGGER journal_no_update"); s.execute("UPDATE journal SET intent='tampered' WHERE seq=1"); } return null; });
        assertFalse(engine.audit().healthy());
    }
    @Test void backupIsConsistentAndCanReopen() {
        var target = store.backup(directory.resolve("backup.db"));
        try (var copy = new LedgerStore(target, clock, currencies)) { assertTrue(copy.audit().healthy()); assertEquals(100_000, copy.balance(EconomyEngine.wallet(alice), "COIN")); }
        healthy();
    }
    @Test void precisionChangeAndCurrencyRemovalRefused() {
        store.close();
        assertThrows(RuntimeException.class, () -> new LedgerStore(directory.resolve("economy.db"), clock, List.of(new Currency("COIN", "$", 3, 0, 0), currencies.get(1))));
        assertThrows(RuntimeException.class, () -> new LedgerStore(directory.resolve("economy.db"), clock, List.of(currencies.getFirst())));
    }
    @Test void secondProcessCannotShareDatabase() { assertThrows(RuntimeException.class, () -> new LedgerStore(directory.resolve("economy.db"), clock, currencies)); }
    @Test void longOverflowRollsBack() { error(INVALID_AMOUNT, () -> engine.issue(mallory, EconomyEngine.wallet(alice), "COIN", Long.MAX_VALUE, false, "overflow", "ADMIN_MINT")); healthy(); }
    @Test void invalidRequestKeyRejected() { error(INVALID_AMOUNT, () -> engine.transfer(alice, bob, "COIN", 1, "bad key")); }
    @Test void cannotIssueDirectlyIntoCustody() { error(FORBIDDEN, () -> engine.issue(mallory, "escrow:" + UUID.randomUUID(), "COIN", 1, false, "bad", "ADMIN_MINT")); }
}
