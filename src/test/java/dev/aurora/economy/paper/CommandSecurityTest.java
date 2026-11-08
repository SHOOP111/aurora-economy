package dev.aurora.economy.paper;

import dev.aurora.economy.core.EconomyException;
import java.util.Arrays;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandSecurityTest extends AdapterFixture {
    void execute(String label, String line) {
        new EconomyCommand(plugin).onCommand(player, mock(Command.class), label, line.isEmpty() ? new String[0] : line.split(" "));
    }
    @ParameterizedTest @ValueSource(strings = {"admin mint Alice 1 COIN", "admin burn Bob 1 COIN", "admin liquidity COIN GEM 1 1", "admin supply", "admin audit", "admin backup", "admin inspect Bob", "admin freeze Alice Investigation", "admin unfreeze Alice", "wallet Bob", "pay Bob 1 COIN", "bank deposit 1 COIN 1", "escrow create Bob 1 COIN 60", "orders create Bob 1 COIN 60 2", "exchange swap COIN GEM 1 1"})
    void permissionChecksHappenBeforeAnyStorageWork(String line) {
        execute("eco", line);
        verify(plugin, never()).async(any());
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(plugin).failure(eq(player), error.capture());
        assertEquals(EconomyException.Code.FORBIDDEN, ((EconomyException) error.getValue()).code());
        assertEquals(100_000, engine.balance(alice, "COIN"));
    }
    @ParameterizedTest @ValueSource(strings = {"-1", "0", "NaN", "1e5", "0.001", "92233720368547758.08"})
    void invalidAmountsAreRejectedBeforeQueuing(String amount) {
        when(player.hasPermission("aurora.pay")).thenReturn(true); execute("pay", "Bob " + amount + " COIN");
        verify(plugin, never()).async(any()); verify(plugin).failure(eq(player), any(EconomyException.class));
    }
    @Test void payAliasCannotBypassPermissions() { execute("pay", "Bob 10"); verify(plugin, never()).async(any()); verify(plugin).failure(eq(player), any(EconomyException.class)); }
    @Test void authorizedPayUsesRealLedger() {
        when(player.hasPermission("aurora.pay")).thenReturn(true); execute("pay", "Bob 10.00 COIN");
        assertEquals(98_997, engine.balance(alice, "COIN")); assertEquals(101_000, engine.balance(bob, "COIN")); assertTrue(engine.audit().healthy());
    }
    @Test void explicitChatRequestIdMakesRetriesSafe() {
        when(player.hasPermission("aurora.pay")).thenReturn(true);
        execute("pay", "Bob 10.00 COIN persisted-chat-request"); execute("pay", "Bob 10.00 COIN persisted-chat-request");
        assertEquals(101_000, engine.balance(bob, "COIN")); assertEquals(98_997, engine.balance(alice, "COIN")); assertTrue(engine.audit().healthy());
    }
    @Test void balanceAliasCannotReadSomeoneElseWithoutPermission() { execute("bal", "Bob"); verify(plugin, never()).async(any()); }
    @Test void authorizedConsoleCanFundTreasury() {
        CommandSender console = mock(CommandSender.class); when(console.hasPermission(anyString())).thenReturn(true);
        new EconomyCommand(plugin).onCommand(console, mock(Command.class), "eco", new String[]{"admin", "mint", "treasury", "100", "COIN"});
        assertEquals(10_000, engine.treasury("COIN")); assertTrue(engine.audit().healthy());
    }
    @Test void consoleCannotUsePlayerOnlyTransfer() {
        CommandSender console = mock(CommandSender.class); when(console.hasPermission(anyString())).thenReturn(true);
        new EconomyCommand(plugin).onCommand(console, mock(Command.class), "pay", new String[]{"Bob", "1"}); verify(plugin, never()).async(any()); verify(plugin).failure(eq(console), any(EconomyException.class));
    }
    @Test void tabsDoNotAdvertiseAdministrativeCapabilities() {
        var suggestions = new EconomyCommand(plugin).onTabComplete(player, mock(Command.class), "eco", new String[]{""}); assertFalse(suggestions.contains("admin"));
        assertTrue(new EconomyCommand(plugin).onTabComplete(player, mock(Command.class), "eco", new String[]{"admin", ""}).isEmpty());
    }
    @Test void malformedCommandsDoNotReachLedger() {
        when(player.hasPermission(anyString())).thenReturn(true);
        for (String line : Arrays.asList("pay", "bank deposit", "escrow accept", "orders create", "exchange swap", "admin mint")) execute("eco", line);
        verify(plugin, never()).async(any());
    }
}
