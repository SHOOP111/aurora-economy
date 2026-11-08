package dev.aurora.economy.paper;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.Models.Portfolio;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Read-only, session-owned UI. No items or title matching can authorize a financial operation. */
final class Dashboard implements Listener {
    private final AuroraEconomyPlugin plugin;
    Dashboard(AuroraEconomyPlugin plugin) { this.plugin = plugin; }
    private static final class Session implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;
        Session(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    void open(Player player) {
        Session session = new Session(player.getUniqueId());
        session.inventory = Bukkit.createInventory(session, 45, Component.text("Aurora • Financial Observatory", NamedTextColor.DARK_AQUA));
        for (int i = 0; i < 45; i++) session.inventory.setItem(i, icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        session.inventory.setItem(22, icon(Material.CLOCK, "Loading verified portfolio…", List.of("Ledger requests run off the server thread.")));
        player.openInventory(session.inventory);
        UUID owner = player.getUniqueId();
        plugin.async(() -> plugin.engine().portfolio(owner)).whenComplete((portfolio, error) -> plugin.main(() -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != session) return;
            if (error != null) { plugin.failure(player, error); player.closeInventory(); return; }
            render(session.inventory, portfolio);
        }));
    }
    private void render(Inventory inventory, Portfolio p) {
        List<String> wallet = new ArrayList<>(), savings = new ArrayList<>(), held = new ArrayList<>(), budget = new ArrayList<>();
        for (Currency c : plugin.engine().currencies()) {
            wallet.add(c.format(p.wallets().get(c.code()))); savings.add(c.format(p.lockedSavings().get(c.code()))); held.add(c.format(p.escrowHeld().get(c.code())));
            budget.add(c.code() + ": " + c.decimal(p.spentToday().get(c.code())) + " / " + (c.dailyLimit() == 0 ? "unlimited" : c.decimal(c.dailyLimit())));
        }
        wallet.add(p.frozen() ? "FROZEN: " + p.freezeReason() : "Click to view wallets."); savings.add("Promised yield is already reserved."); savings.add("Click to list your deposit contracts.");
        held.add("Click to list incoming and outgoing escrows.");
        inventory.setItem(10, icon(Material.GOLD_INGOT, "Liquid wallets", wallet));
        inventory.setItem(12, icon(Material.ENDER_CHEST, "Solvency-first savings", savings));
        inventory.setItem(14, icon(Material.CHEST, "Escrow custody", held));
        inventory.setItem(16, icon(Material.EMERALD, "Reserve-backed exchange", List.of("Treasury-owned constant-product pools", "Explicit minimum-output protection", "/eco exchange quote COIN GEM 10.00")));
        inventory.setItem(22, icon(Material.COMPASS, "Your money, accounted for", List.of("Wallets ≠ locked funds", "Every movement has a ledger receipt", "No speculative or unfunded yield")));
        inventory.setItem(28, icon(Material.CLOCK, "Recurring payments", List.of("Click to list orders", "Offline-safe; no catch-up bursts")));
        inventory.setItem(30, icon(Material.WRITABLE_BOOK, "Ledger receipts", List.of("Click for recent wallet transactions")));
        inventory.setItem(32, icon(Material.SHIELD, "UTC spending budget", budget));
        inventory.setItem(34, icon(Material.BOOK, "Command guide", List.of("Click for all commands")));
    }
    private static ItemStack icon(Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta); return item;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Session session)) return;
        event.setCancelled(true); // Also cancels shift clicks, hotbar swaps, collect-to-cursor and bottom inventory actions.
        if (!(event.getWhoClicked() instanceof Player player) || !session.owner.equals(player.getUniqueId())) return;
        String command = switch (event.getRawSlot()) {
            case 10 -> "eco wallet";
            case 12 -> "eco bank list";
            case 14 -> "eco escrow list";
            case 28 -> "eco orders list";
            case 30 -> "eco history";
            case 34 -> "eco help";
            default -> null;
        };
        if (command != null) { player.closeInventory(); player.performCommand(command); }
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Session) event.setCancelled(true);
    }
}
