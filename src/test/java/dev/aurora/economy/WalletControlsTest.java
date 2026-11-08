package dev.aurora.economy;

import dev.aurora.economy.core.EconomyEngine;
import dev.aurora.economy.core.Policy;
import dev.aurora.economy.storage.LedgerStore;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static dev.aurora.economy.core.EconomyException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class WalletControlsTest extends EngineFixture {
    void freeze() { engine.setFrozen(mallory, alice, true, "Investigating suspicious traffic", "freeze"); }
    @Test void frozenWalletCannotPayButCanReceive() {
        freeze(); error(ACCOUNT_FROZEN, () -> engine.transfer(alice, bob, "COIN", 100, "blocked"));
        engine.transfer(bob, alice, "COIN", 100, "incoming"); assertEquals(100_100, engine.balance(alice, "COIN")); assertTrue(engine.portfolio(alice).frozen()); healthy();
    }
    @Test void frozenWalletCannotOpenCustodyOrOrders() {
        freeze(); error(ACCOUNT_FROZEN, () -> engine.createEscrow(alice, bob, "COIN", 100, 60, "offer"));
        error(ACCOUNT_FROZEN, () -> engine.openDeposit(alice, "COIN", 1, 1, "save"));
        error(ACCOUNT_FROZEN, () -> engine.createOrder(alice, bob, "COIN", 100, 60, 2, "salary")); healthy();
    }
    @Test void refundRemainsAvailableWhileFrozen() {
        var id = engine.createEscrow(alice, bob, "COIN", 100, 60, "offer").reference(); freeze(); engine.cancelEscrow(alice, id, "refund"); assertEquals(100_000, engine.balance(alice, "COIN")); healthy();
    }
    @Test void existingRecurringOrderPausesWhenOwnerIsFrozen() {
        engine.createOrder(alice, bob, "COIN", 100, 60, 2, "salary"); freeze(); clock.advance(60); assertEquals(1, engine.maintain().paused());
        assertEquals("ACCOUNT_FROZEN", engine.orders(alice).getFirst().reason()); healthy();
    }
    @Test void vaultWithdrawalCannotBypassFreeze() {
        freeze(); error(ACCOUNT_FROZEN, () -> engine.issue(alice, EconomyEngine.wallet(alice), "COIN", 100, true, "vault", "VAULT_WITHDRAW")); healthy();
    }
    @Test void unfreezeIsAuditedAndReplaySafe() {
        freeze(); var receipt = engine.setFrozen(mallory, alice, false, "Investigation complete", "release");
        assertTrue(engine.setFrozen(mallory, alice, false, "Investigation complete", "release").replayed());
        engine.transfer(alice, bob, "COIN", 100, "unblocked"); assertFalse(engine.control(alice).frozen()); assertEquals("WALLET_UNFREEZE", receipt.kind()); healthy();
    }
    @Test void controlsPersistAcrossRestart() {
        freeze(); store.close(); store = new LedgerStore(directory.resolve("economy.db"), clock, currencies); engine = new EconomyEngine(store, clock, Policy.defaults(), currencies);
        assertTrue(engine.control(alice).frozen()); error(ACCOUNT_FROZEN, () -> engine.transfer(alice, bob, "COIN", 100, "blocked")); healthy();
    }
    @Test void backupManifestAnchorsExactSnapshot() throws Exception {
        var backup = engine.backup(directory.resolve("snapshot.db")); String manifest = Files.readString(backup.manifest());
        assertTrue(manifest.contains("ledger_head=" + backup.audit().headHash())); assertTrue(manifest.contains("healthy=true"));
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup.database())); assertTrue(manifest.contains(java.util.HexFormat.of().formatHex(digest)));
    }
    @Test void versionOneDatabaseMigratesWithoutChangingLedger() {
        String head = engine.audit().headHash();
        store.read(c -> { try (var s = c.createStatement()) { s.execute("DROP TABLE wallet_controls"); s.execute("PRAGMA user_version=1"); } return null; });
        store.close(); store = new LedgerStore(directory.resolve("economy.db"), clock, currencies); engine = new EconomyEngine(store, clock, Policy.defaults(), currencies);
        assertEquals(head, engine.audit().headHash()); assertFalse(engine.control(alice).frozen()); healthy();
    }
    @Test void freezeReasonCannotInjectControlCharacters() { error(INVALID_AMOUNT, () -> engine.setFrozen(mallory, alice, true, "bad\nreason", "bad")); }
}
