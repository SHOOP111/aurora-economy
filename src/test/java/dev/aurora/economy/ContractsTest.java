package dev.aurora.economy;

import org.junit.jupiter.api.Test;
import static dev.aurora.economy.core.EconomyException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractsTest extends EngineFixture {
    @Test void escrowReservesThenSettlesExactlyOnce() {
        var create = engine.createEscrow(alice, bob, "COIN", 10_000, 3_600, "offer");
        assertEquals(89_975, engine.balance(alice, "COIN")); assertEquals(100_000, engine.balance(bob, "COIN")); assertEquals(10_025, engine.portfolio(alice).escrowHeld().get("COIN"));
        var accepted = engine.acceptEscrow(bob, create.reference(), "accept");
        assertEquals(110_000, engine.balance(bob, "COIN")); assertEquals(25, engine.treasury("COIN"));
        assertTrue(engine.acceptEscrow(bob, create.reference(), "accept").replayed()); assertNotNull(accepted.hash()); healthy();
    }
    @Test void escrowCancelRefundsFeeToo() {
        var create = engine.createEscrow(alice, bob, "COIN", 10_000, 60, "offer");
        engine.cancelEscrow(alice, create.reference(), "cancel"); assertEquals(100_000, engine.balance(alice, "COIN")); assertEquals(0, engine.treasury("COIN")); healthy();
    }
    @Test void escrowPermissionsAreNotCosmetic() {
        var id = engine.createEscrow(alice, bob, "COIN", 1_000, 60, "offer").reference();
        error(FORBIDDEN, () -> engine.acceptEscrow(mallory, id, "steal")); error(FORBIDDEN, () -> engine.cancelEscrow(bob, id, "steal")); healthy();
    }
    @Test void expirationAtBoundaryCannotBeAccepted() {
        var id = engine.createEscrow(alice, bob, "COIN", 1_000, 60, "offer").reference(); clock.advance(60);
        error(EXPIRED, () -> engine.acceptEscrow(bob, id, "late")); assertEquals(1, engine.maintain().expired()); assertEquals(0, engine.maintain().expired());
        assertEquals(100_000, engine.balance(alice, "COIN")); assertEquals("EXPIRED", engine.escrows(alice).getFirst().state()); healthy();
    }
    @Test void closedEscrowCannotBePaidAgainWithNewKey() {
        var id = engine.createEscrow(alice, bob, "COIN", 1_000, 60, "offer").reference(); engine.acceptEscrow(bob, id, "accept");
        error(INVALID_STATE, () -> engine.acceptEscrow(bob, id, "different")); error(INVALID_STATE, () -> engine.cancelEscrow(alice, id, "cancel")); healthy();
    }
    @Test void failedCreationDoesNotLeakContract() {
        error(INSUFFICIENT_FUNDS, () -> engine.createEscrow(alice, bob, "COIN", 200_000, 60, "offer")); assertTrue(engine.escrows(alice).isEmpty()); healthy();
    }
    @Test void fundedSavingsReservesFullYieldUpfront() {
        fund("COIN", 1_000);
        var id = engine.openDeposit(alice, "COIN", 10_000, 365, "save").reference();
        assertEquals(650, engine.treasury("COIN")); assertEquals(10_350, engine.portfolio(alice).lockedSavings().get("COIN"));
        error(LOCKED, () -> engine.claimDeposit(alice, id, "early")); clock.advance(365 * 86_400L);
        engine.claimDeposit(alice, id, "claim"); assertEquals(100_350, engine.balance(alice, "COIN"));
        assertTrue(engine.claimDeposit(alice, id, "claim").replayed()); healthy();
    }
    @Test void savingsDoesNotPrintUnfundedInterest() {
        error(INSUFFICIENT_FUNDS, () -> engine.openDeposit(alice, "COIN", 10_000, 365, "unfunded")); assertTrue(engine.deposits(alice).isEmpty()); assertEquals(100_000, engine.balance(alice, "COIN")); healthy();
    }
    @Test void anotherPlayerCannotClaimSavings() {
        fund("COIN", 1_000); var id = engine.openDeposit(alice, "COIN", 10_000, 365, "save").reference(); clock.advance(365 * 86_400L);
        error(FORBIDDEN, () -> engine.claimDeposit(bob, id, "steal")); healthy();
    }
    @Test void claimedSavingsCannotBeClaimedTwice() {
        fund("COIN", 1_000); var id = engine.openDeposit(alice, "COIN", 10_000, 1, "save").reference(); clock.advance(86_400);
        engine.claimDeposit(alice, id, "claim"); error(INVALID_STATE, () -> engine.claimDeposit(alice, id, "again")); healthy();
    }
    @Test void savingsCanPayZeroRoundedInterestWithoutTreasury() { engine.openDeposit(alice, "COIN", 1, 1, "tiny"); healthy(); }
    @Test void contractStateCorruptionIsCaughtByCustodyAudit() {
        engine.createEscrow(alice, bob, "COIN", 1_000, 60, "offer");
        store.read(c -> { try (var s = c.createStatement()) { s.execute("UPDATE escrows SET state='ACCEPTED'"); } return null; }); assertFalse(engine.audit().healthy());
    }
    @Test void invalidContractBounds() {
        error(INVALID_AMOUNT, () -> engine.createEscrow(alice, bob, "COIN", 1, 59, "short"));
        error(INVALID_AMOUNT, () -> engine.openDeposit(alice, "COIN", 1, 0, "short"));
        error(INVALID_AMOUNT, () -> engine.openDeposit(alice, "COIN", 1, 366, "long"));
    }
}
