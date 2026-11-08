package dev.aurora.economy.paper;

import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class VaultBridgeTest extends AdapterFixture {
    @Test void depositAndWithdrawalAreJournaledIssuance() {
        var vault = new VaultBridge(plugin);
        assertTrue(vault.depositPlayer(player, 10).transactionSuccess()); assertEquals(1010, vault.getBalance(player));
        assertTrue(vault.withdrawPlayer(player, 5).transactionSuccess()); assertEquals(1005, vault.getBalance(player));
        assertEquals(200_500, engine.supply().getFirst().issued()); assertEquals("VAULT_WITHDRAW", engine.history(alice, 1).getFirst().kind()); assertTrue(engine.audit().healthy());
    }
    @ParameterizedTest @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY, 0.001, 1e20})
    void rejectsNonFiniteNegativeFractionalOrUnsafeAmounts(double value) {
        var vault = new VaultBridge(plugin);
        assertEquals(ResponseType.FAILURE, vault.depositPlayer(player, value).type); assertEquals(ResponseType.FAILURE, vault.withdrawPlayer(player, value).type);
        assertEquals(1000, vault.getBalance(player)); assertFalse(vault.has(player, value)); assertTrue(engine.audit().healthy());
    }
    @Test void zeroIsNoopNotFakeTransaction() {
        var vault = new VaultBridge(plugin); long count = engine.audit().transactions();
        assertTrue(vault.depositPlayer(player, 0).transactionSuccess()); assertTrue(vault.withdrawPlayer(player, 0).transactionSuccess()); assertTrue(vault.has(player, 0));
        assertEquals(count, engine.audit().transactions());
    }
    @Test void insufficientWithdrawalReportsCurrentBalanceAndNoDebit() {
        var response = new VaultBridge(plugin).withdrawPlayer(player, 1001);
        assertEquals(ResponseType.FAILURE, response.type); assertEquals(1000, response.balance); assertEquals(100_000, engine.balance(alice, "COIN"));
    }
    @Test void legacyNamesAndWorldOverloadsUseSameGlobalWallet() {
        var vault = new VaultBridge(plugin); assertEquals(1000, vault.getBalance("Alice", "nether"));
        assertTrue(vault.depositPlayer("Alice", "end", 1).transactionSuccess()); assertEquals(1001, vault.getBalance(player, "world")); assertTrue(vault.has("Alice", "nether", 1001));
    }
    @Test void accountCreationCannotRepeatStarterGrant() {
        var vault = new VaultBridge(plugin); assertTrue(vault.createPlayerAccount(player)); assertTrue(vault.createPlayerAccount(player, "world")); assertTrue(vault.hasAccount(player)); assertEquals(1000, vault.getBalance(player));
    }
    @Test void unknownNamesAreNotInvented() {
        var vault = new VaultBridge(plugin); assertFalse(vault.hasAccount("NeverJoined")); assertEquals(0, vault.getBalance("NeverJoined")); assertFalse(vault.depositPlayer("NeverJoined", 1).transactionSuccess());
    }
    @Test void unsupportedBankResponsesAreHonest() {
        var vault = new VaultBridge(plugin); assertFalse(vault.hasBankSupport()); assertTrue(vault.getBanks().isEmpty());
        assertEquals(ResponseType.NOT_IMPLEMENTED, vault.createBank("bank", player).type); assertEquals(ResponseType.NOT_IMPLEMENTED, vault.bankDeposit("bank", 100).type);
    }
    @Test void metadataAndFormatting() {
        var vault = new VaultBridge(plugin); assertTrue(vault.isEnabled()); assertEquals(2, vault.fractionalDigits()); assertEquals("$1.25 COIN", vault.format(1.25)); assertEquals("AuroraEconomy", vault.getName());
    }
    @Test void offlineUuidCanReceiveFundsWithoutNameLookup() {
        OfflinePlayer offline = mock(OfflinePlayer.class); var id = java.util.UUID.randomUUID(); when(offline.getUniqueId()).thenReturn(id);
        var vault = new VaultBridge(plugin); assertTrue(vault.depositPlayer(offline, 5).transactionSuccess()); assertEquals(5, vault.getBalance(offline)); assertTrue(engine.audit().healthy());
    }
}
