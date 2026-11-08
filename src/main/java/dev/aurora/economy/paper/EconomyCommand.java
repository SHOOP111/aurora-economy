package dev.aurora.economy.paper;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.EconomyException;
import dev.aurora.economy.core.Models.*;
import dev.aurora.economy.storage.LedgerStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

final class EconomyCommand implements TabExecutor {
    private static final UUID CONSOLE = new UUID(0, 0);
    private final AuroraEconomyPlugin plugin;
    EconomyCommand(AuroraEconomyPlugin plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] original) {
        try {
            permit(sender, "aurora.use");
            String[] args = normalize(label, original);
            if (args.length == 0) {
                if (sender instanceof Player player) plugin.dashboard().open(player); else help(sender);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "help" -> { exact(args, 1, "/eco help"); help(sender); }
                case "menu" -> { exact(args, 1, "/eco menu"); plugin.dashboard().open(player(sender)); }
                case "wallet" -> wallet(sender, args);
                case "pay" -> pay(sender, args);
                case "bank" -> bank(sender, args);
                case "escrow" -> escrow(sender, args);
                case "orders" -> orders(sender, args);
                case "exchange" -> exchange(sender, args);
                case "history" -> {
                    exact(args, 1, "/eco history"); UUID owner = player(sender).getUniqueId();
                    run(sender, () -> plugin.engine().statement(owner, 12), rows -> {
                        if (rows.isEmpty()) say(sender, "No wallet transactions yet.");
                        for (var row : rows) plugin.reply(sender, Component.text(row.receipt().time() + "  " + row.receipt().kind() + "  " + plugin.engine().currency(row.currency()).format(row.delta()) + "  ", NamedTextColor.GRAY).append(copy(row.receipt().id())));
                    });
                }
                case "top" -> {
                    if (args.length > 2) throw usage("/eco top [currency]");
                    String code = args.length == 2 ? args[1] : plugin.settings().defaultCurrency(); Currency c = plugin.engine().currency(code);
                    run(sender, () -> plugin.engine().leaders(c.code(), 10), rows -> {
                        say(sender, "Liquid wallet leaderboard • " + c.code() + " (locked funds excluded)");
                        int rank = 1; for (var row : rows) say(sender, rank++ + ". " + row.name() + " • " + c.format(row.units()));
                    });
                }
                case "admin" -> admin(sender, args);
                default -> throw usage("Unknown command. Use /eco help.");
            }
        } catch (EconomyException e) { plugin.failure(sender, e); }
        return true;
    }
    private static String[] normalize(String label, String[] args) {
        String alias = label.substring(label.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (alias.equals("pay")) return prepend("pay", args);
        if (List.of("bal", "balance", "money").contains(alias)) return prepend("wallet", args);
        return args;
    }
    private static String[] prepend(String prefix, String[] args) {
        String[] result = new String[args.length + 1]; result[0] = prefix; System.arraycopy(args, 0, result, 1, args.length); return result;
    }
    private void wallet(CommandSender sender, String[] args) {
        if (args.length > 2) throw usage("/eco wallet [player]");
        UUID owner = args.length == 1 ? player(sender).getUniqueId() : null;
        if (args.length == 2) permit(sender, "aurora.balance.others");
        run(sender, () -> plugin.engine().portfolio(owner == null ? plugin.engine().resolve(args[1]) : owner), portfolio -> showPortfolio(sender, portfolio));
    }
    private void showPortfolio(CommandSender sender, Portfolio p) {
        if (p.frozen()) plugin.reply(sender, Component.text("WALLET FROZEN: " + p.freezeReason(), NamedTextColor.RED));
        for (Currency c : plugin.engine().currencies()) say(sender, c.code() + " • wallet " + c.format(p.wallets().get(c.code())) + " • savings " + c.format(p.lockedSavings().get(c.code())) + " • escrow " + c.format(p.escrowHeld().get(c.code())));
    }
    private void pay(CommandSender sender, String[] args) {
        permit(sender, "aurora.pay");
        if (args.length < 3 || args.length > 5) throw usage("/eco pay <player> <amount> [currency] [request-id]");
        UUID owner = player(sender).getUniqueId(); Currency c = plugin.engine().currency(args.length >= 4 ? args[3] : plugin.settings().defaultCurrency());
        long amount = c.parse(args[2]); String request = checkedRequest(args.length == 5 ? args[4] : request());
        plugin.reply(sender, Component.text("Payment request: ", NamedTextColor.GRAY).append(copy(request)));
        run(sender, () -> plugin.engine().transfer(owner, plugin.engine().resolve(args[1]), c.code(), amount, request), receipt -> {
            success(sender, receipt);
            Player recipient = plugin.getServer().getPlayer(UUID.fromString(receipt.reference()));
            if (recipient != null && !receipt.replayed()) say(recipient, "Received " + c.format(amount) + " from " + sender.getName() + ".");
        });
    }
    private void bank(CommandSender sender, String[] args) {
        permit(sender, "aurora.bank"); UUID owner = player(sender).getUniqueId();
        if (args.length < 2) throw usage("/eco bank list | deposit <amount> <currency> <days> | claim <id>");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                int page = page(args);
                run(sender, () -> plugin.engine().deposits(owner), rows -> paged(sender, rows, page, d -> Component.text(d.state() + "  " + plugin.engine().currency(d.currency()).format(d.principal()) + " + yield " + plugin.engine().currency(d.currency()).format(d.interest()) + "  unlock " + d.unlocks() + "  ", NamedTextColor.GRAY).append(contract(d.id(), "eco bank claim "))));
            }
            case "deposit" -> {
                String request = mutationRequest(sender, args, 5, "/eco bank deposit <amount> <currency> <days>"); Currency c = plugin.engine().currency(args[3]); long amount = c.parse(args[2]); int days = (int) number(args[4], 1, 365);
                run(sender, () -> plugin.engine().openDeposit(owner, c.code(), amount, days, request), receipt -> contractSuccess(sender, receipt, "eco bank claim "));
            }
            case "claim" -> {
                String request = mutationRequest(sender, args, 3, "/eco bank claim <full-id>"); run(sender, () -> plugin.engine().claimDeposit(owner, args[2], request), receipt -> success(sender, receipt));
            }
            default -> throw usage("/eco bank list | deposit <amount> <currency> <days> | claim <id>");
        }
    }
    private void escrow(CommandSender sender, String[] args) {
        permit(sender, "aurora.escrow"); UUID owner = player(sender).getUniqueId();
        if (args.length < 2) throw usage("/eco escrow list | create <player> <amount> <currency> <minutes> | accept/cancel <id>");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                int page = page(args);
                run(sender, () -> plugin.engine().escrows(owner), rows -> paged(sender, rows, page, e -> Component.text(e.state() + "  " + (e.owner().equals(owner) ? "OUT → " + e.beneficiary() : "IN ← " + e.owner()) + "  " + plugin.engine().currency(e.currency()).format(e.amount()) + "  expires " + e.expires() + "  ", NamedTextColor.GRAY).append(contract(e.id(), e.owner().equals(owner) ? "eco escrow cancel " : "eco escrow accept "))));
            }
            case "create" -> {
                String request = mutationRequest(sender, args, 6, "/eco escrow create <player> <amount> <currency> <minutes>"); Currency c = plugin.engine().currency(args[4]); long amount = c.parse(args[3]), seconds = number(args[5], 1, 43_200) * 60;
                run(sender, () -> plugin.engine().createEscrow(owner, plugin.engine().resolve(args[2]), c.code(), amount, seconds, request), receipt -> contractSuccess(sender, receipt, "eco escrow cancel "));
            }
            case "accept", "cancel" -> {
                String request = mutationRequest(sender, args, 3, "/eco escrow " + args[1] + " <full-id>"); boolean accept = args[1].equalsIgnoreCase("accept");
                run(sender, () -> accept ? plugin.engine().acceptEscrow(owner, args[2], request) : plugin.engine().cancelEscrow(owner, args[2], request), receipt -> success(sender, receipt));
            }
            default -> throw usage("/eco escrow list | create <player> <amount> <currency> <minutes> | accept/cancel <id>");
        }
    }
    private void orders(CommandSender sender, String[] args) {
        permit(sender, "aurora.orders"); UUID owner = player(sender).getUniqueId();
        if (args.length < 2) throw usage("/eco orders list | create <player> <amount> <currency> <minutes> <repetitions> | resume/cancel <id>");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                int page = page(args);
                run(sender, () -> plugin.engine().orders(owner), rows -> paged(sender, rows, page, o -> Component.text(o.state() + " " + o.reason() + "  " + plugin.engine().currency(o.currency()).format(o.amount()) + "  " + o.executions() + "/" + o.totalExecutions() + "  due " + o.nextDue() + "  ", NamedTextColor.GRAY).append(contract(o.id(), o.state().equals("PAUSED") ? "eco orders resume " : "eco orders cancel "))));
            }
            case "create" -> {
                String request = mutationRequest(sender, args, 7, "/eco orders create <player> <amount> <currency> <minutes> <repetitions>"); Currency c = plugin.engine().currency(args[4]); long amount = c.parse(args[3]), seconds = number(args[5], 1, 43_200) * 60; int total = (int) number(args[6], 1, 10_000);
                run(sender, () -> plugin.engine().createOrder(owner, plugin.engine().resolve(args[2]), c.code(), amount, seconds, total, request), receipt -> contractSuccess(sender, receipt, "eco orders cancel "));
            }
            case "resume", "cancel" -> {
                String request = mutationRequest(sender, args, 3, "/eco orders " + args[1] + " <full-id>"); boolean resume = args[1].equalsIgnoreCase("resume");
                run(sender, () -> plugin.engine().changeOrder(owner, args[2], resume, request), receipt -> success(sender, receipt));
            }
            default -> throw usage("/eco orders list | create <player> <amount> <currency> <minutes> <repetitions> | resume/cancel <id>");
        }
    }
    private void exchange(CommandSender sender, String[] args) {
        permit(sender, "aurora.exchange");
        if (args.length < 2) throw usage("/eco exchange quote <from> <to> <amount> | swap <from> <to> <amount> <min-output>");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "quote" -> {
                exact(args, 5, "/eco exchange quote <from> <to> <amount>"); Currency from = plugin.engine().currency(args[2]), to = plugin.engine().currency(args[3]); long input = from.parse(args[4]);
                run(sender, () -> plugin.engine().quote(from.code(), to.code(), input), q -> {
                    say(sender, from.format(q.input()) + " → " + to.format(q.output()) + " • pool fee " + q.feeBps() + " bps");
                    say(sender, "Quote is not a reservation. Set min-output to protect against price movement.");
                    plugin.reply(sender, Component.text("Prepare protected trade", NamedTextColor.AQUA).clickEvent(ClickEvent.suggestCommand("/eco exchange swap " + from.code() + " " + to.code() + " " + from.decimal(input) + " " + to.decimal(q.output()))));
                });
            }
            case "swap" -> {
                String request = mutationRequest(sender, args, 6, "/eco exchange swap <from> <to> <amount> <min-output>"); UUID owner = player(sender).getUniqueId(); Currency from = plugin.engine().currency(args[2]), to = plugin.engine().currency(args[3]); long input = from.parse(args[4]), minimum = to.parse(args[5]);
                run(sender, () -> plugin.engine().swap(owner, from.code(), to.code(), input, minimum, request), receipt -> { say(sender, "Received " + to.format(Long.parseLong(receipt.reference())) + "."); success(sender, receipt); });
            }
            default -> throw usage("/eco exchange quote <from> <to> <amount> | swap <from> <to> <amount> <min-output>");
        }
    }
    private void admin(CommandSender sender, String[] args) {
        permit(sender, "aurora.admin"); UUID actor = sender instanceof Player p ? p.getUniqueId() : CONSOLE;
        if (args.length < 2) throw usage("/eco admin mint | burn | liquidity | supply | audit | backup | inspect");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "mint", "burn" -> {
                String request = mutationRequest(sender, args, 5, "/eco admin " + args[1] + " <player|treasury> <amount> <currency>"); Currency c = plugin.engine().currency(args[4]); long amount = c.parse(args[3]); boolean burn = args[1].equalsIgnoreCase("burn");
                run(sender, () -> plugin.engine().issue(actor, args[2].equalsIgnoreCase("treasury") ? LedgerStore.TREASURY : dev.aurora.economy.core.EconomyEngine.wallet(plugin.engine().resolve(args[2])), c.code(), amount, burn, request, burn ? "ADMIN_BURN" : "ADMIN_MINT"), receipt -> success(sender, receipt));
            }
            case "liquidity" -> {
                String request = mutationRequest(sender, args, 6, "/eco admin liquidity <currency-a> <currency-b> <amount-a> <amount-b>"); Currency a = plugin.engine().currency(args[2]), b = plugin.engine().currency(args[3]); long x = a.parse(args[4]), y = b.parse(args[5]);
                run(sender, () -> plugin.engine().fundPool(actor, a.code(), b.code(), x, y, request), receipt -> success(sender, receipt));
            }
            case "supply" -> {
                exact(args, 2, "/eco admin supply"); run(sender, plugin.engine()::supply, rows -> {
                    for (Supply s : rows) { Currency c = plugin.engine().currency(s.currency()); say(sender, s.currency() + " • issued " + c.format(s.issued()) + " • wallets " + c.format(s.wallets()) + " • savings " + c.format(s.savings()) + " • escrow " + c.format(s.escrow()) + " • treasury " + c.format(s.treasury()) + " • pools " + c.format(s.markets())); }
                });
            }
            case "audit" -> {
                exact(args, 2, "/eco admin audit"); run(sender, plugin.engine()::audit, audit -> {
                    say(sender, (audit.healthy() ? "PASS" : "FAIL") + " • " + audit.transactions() + " transactions • " + audit.postings() + " postings");
                    plugin.reply(sender, Component.text("Ledger head: ", NamedTextColor.GRAY).append(copy(audit.headHash())));
                    audit.violations().stream().limit(20).forEach(problem -> plugin.reply(sender, Component.text(problem, NamedTextColor.RED)));
                    if (audit.violations().size() > 20) say(sender, (audit.violations().size() - 20) + " additional violations; preserve a snapshot for investigation.");
                });
            }
            case "backup" -> {
                exact(args, 2, "/eco admin backup"); var path = plugin.getDataFolder().toPath().resolve("backups/economy-" + UUID.randomUUID() + ".db");
                run(sender, () -> plugin.engine().backup(path), result -> {
                    say(sender, "Consistent SQLite snapshot + audit manifest saved: " + result.database().getFileName() + ". Keep both off-server.");
                    if (!result.audit().healthy()) plugin.reply(sender, Component.text("WARNING: audit unhealthy/unavailable. This snapshot is incident evidence, not a trusted restore point.", NamedTextColor.RED));
                });
            }
            case "freeze", "unfreeze" -> {
                boolean frozen = args[1].equalsIgnoreCase("freeze");
                if (frozen && args.length < 4) throw usage("/eco admin freeze <player> <reason>");
                if (!frozen) exact(args, 3, "/eco admin unfreeze <player>");
                String reason = frozen ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "Administrator released wallet";
                run(sender, () -> plugin.engine().setFrozen(actor, plugin.engine().resolve(args[2]), frozen, reason, request()), receipt -> success(sender, receipt));
            }
            case "inspect" -> {
                exact(args, 3, "/eco admin inspect <player>"); run(sender, () -> plugin.engine().portfolio(plugin.engine().resolve(args[2])), result -> showPortfolio(sender, result));
            }
            default -> throw usage("/eco admin mint | burn | liquidity | supply | audit | backup | inspect");
        }
    }
    private <T> void run(CommandSender sender, Supplier<T> operation, java.util.function.Consumer<T> success) {
        plugin.async(operation).whenComplete((result, error) -> {
            if (error != null) plugin.failure(sender, error);
            else plugin.main(() -> {
                try { success.accept(result); }
                catch (RuntimeException e) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Economy operation completed but response rendering failed", e);
                    plugin.reply(sender, Component.text("Operation completed, but its response could not be displayed. Check server logs; reuse the same request ID if retrying a financial action.", NamedTextColor.RED));
                }
            });
        });
    }
    private void success(CommandSender sender, Receipt receipt) {
        plugin.reply(sender, Component.text(receipt.kind() + (receipt.replayed() ? " already committed (safe replay). Receipt " : " committed. Receipt "), NamedTextColor.GREEN).append(copy(receipt.id())));
    }
    private void contractSuccess(CommandSender sender, Receipt receipt, String action) {
        success(sender, receipt); plugin.reply(sender, Component.text("Contract (click to prepare action): ", NamedTextColor.GRAY).append(contract(receipt.reference(), action)));
    }
    private static Component copy(String id) {
        return Component.text(id, NamedTextColor.AQUA).clickEvent(ClickEvent.copyToClipboard(id)).hoverEvent(HoverEvent.showText(Component.text("Click to copy")));
    }
    private static Component contract(String id, String action) {
        return Component.text(id, NamedTextColor.AQUA).clickEvent(ClickEvent.suggestCommand("/" + action + id)).hoverEvent(HoverEvent.showText(Component.text("Prepare command; review before sending")));
    }
    private <T> void paged(CommandSender sender, List<T> rows, int page, Function<T, Component> render) {
        int pages = Math.max(1, (rows.size() + 7) / 8);
        if (page > pages) { say(sender, "Only " + pages + " page(s)."); return; }
        say(sender, "Contracts • page " + page + "/" + pages + " • IDs are clickable");
        if (rows.isEmpty()) say(sender, "No contracts yet.");
        rows.stream().skip((page - 1L) * 8).limit(8).forEach(row -> plugin.reply(sender, render.apply(row)));
    }
    private void say(CommandSender sender, String message) { plugin.reply(sender, Component.text(message, NamedTextColor.GRAY)); }
    private static Player player(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new EconomyException(EconomyException.Code.FORBIDDEN, "This command requires a player. Console can use admin commands."); return player;
    }
    private static void permit(CommandSender sender, String node) {
        if (!sender.hasPermission(node)) throw new EconomyException(EconomyException.Code.FORBIDDEN, "Missing permission: " + node);
    }
    private static EconomyException usage(String message) { return EconomyException.invalid(message); }
    private static void exact(String[] args, int length, String usage) { if (args.length != length) throw usage(usage); }
    private static long number(String input, long min, long max) {
        try { long n = Long.parseLong(input); if (n < min || n > max) throw new NumberFormatException(); return n; }
        catch (NumberFormatException e) { throw usage("Use a whole number from " + min + " to " + max + "."); }
    }
    private static int page(String[] args) {
        if (args.length < 2 || args.length > 3) throw usage("/eco <bank|escrow|orders> list [page]");
        return args.length == 3 ? (int) number(args[2], 1, 13) : 1;
    }
    private String mutationRequest(CommandSender sender, String[] args, int length, String syntax) {
        if (args.length != length && args.length != length + 1) throw usage(syntax + " [request-id]");
        String request = checkedRequest(args.length == length + 1 ? args[length] : request());
        plugin.reply(sender, Component.text("Request: ", NamedTextColor.GRAY).append(copy(request)));
        return request;
    }
    private static String checkedRequest(String request) {
        if (!request.matches("[A-Za-z0-9._:-]{1,96}")) throw usage("Request IDs need 1–96 ASCII letters, digits, '.', '_', ':', or '-'.");
        return request;
    }
    private static String request() { return UUID.randomUUID().toString(); }
    private void help(CommandSender sender) {
        say(sender, "Aurora Economy • amounts are exact decimals • fees are charged on top of payments");
        for (String line : List.of("/eco — financial dashboard", "/eco wallet [player] | /eco top [currency] | /eco history", "/pay <player> <amount> [currency] [request-id]", "/eco bank list [page] | deposit <amount> <currency> <days> | claim <id>", "/eco escrow list [page] | create <player> <amount> <currency> <minutes> | accept/cancel <id>", "/eco orders list [page] | create <player> <amount> <currency> <minutes> <repetitions> | resume/cancel <id>", "/eco exchange quote <from> <to> <amount>", "/eco exchange swap <from> <to> <amount> <minimum-output>")) say(sender, line);
        say(sender, "Financial actions accept a final [request-id] for safe retries (except wallet freeze/unfreeze).");
        if (sender.hasPermission("aurora.admin")) say(sender, "/eco admin mint/burn <player|treasury> <amount> <currency> | liquidity <a> <b> <amount-a> <amount-b> | supply | audit | backup | inspect <player> | freeze <player> <reason> | unfreeze <player>");
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] original) {
        if (!sender.hasPermission("aurora.use")) return List.of();
        String[] args = normalize(alias, original.length == 0 ? new String[]{""} : original); List<String> options = new ArrayList<>();
        String feature = args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1 && List.of("pay", "bank", "escrow", "orders", "exchange", "admin").contains(feature)
                && !sender.hasPermission("aurora." + feature)) return List.of();
        if (args.length > 1 && feature.equals("wallet") && !sender.hasPermission("aurora.balance.others")) return List.of();
        if (args.length == 1) {
            options.addAll(List.of("help", "menu", "wallet", "top", "history"));
            for (String option : List.of("pay", "bank", "escrow", "orders", "exchange")) if (sender.hasPermission("aurora." + option)) options.add(option);
            if (sender.hasPermission("aurora.admin")) options.add("admin");
        } else if (args.length == 2) {
            options.addAll(switch (args[0].toLowerCase(Locale.ROOT)) {
                case "bank" -> sender.hasPermission("aurora.bank") ? List.of("list", "deposit", "claim") : List.of();
                case "escrow" -> sender.hasPermission("aurora.escrow") ? List.of("list", "create", "accept", "cancel") : List.of();
                case "orders" -> sender.hasPermission("aurora.orders") ? List.of("list", "create", "resume", "cancel") : List.of();
                case "exchange" -> sender.hasPermission("aurora.exchange") ? List.of("quote", "swap") : List.of();
                case "admin" -> sender.hasPermission("aurora.admin") ? List.of("mint", "burn", "liquidity", "supply", "audit", "backup", "inspect", "freeze", "unfreeze") : List.of();
                case "top" -> plugin.engine().currencies().stream().map(Currency::code).toList();
                case "pay", "wallet" -> plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
                default -> List.of();
            });
        } else {
            boolean currencySlot = args[0].equalsIgnoreCase("pay") && args.length == 4
                    || args[0].equalsIgnoreCase("bank") && args[1].equalsIgnoreCase("deposit") && args.length == 4
                    || List.of("escrow", "orders").contains(feature) && args[1].equalsIgnoreCase("create") && args.length == 5
                    || args[0].equalsIgnoreCase("exchange") && (args.length == 3 || args.length == 4)
                    || args[0].equalsIgnoreCase("admin") && sender.hasPermission("aurora.admin") && (List.of("mint", "burn").contains(args[1].toLowerCase(Locale.ROOT)) && args.length == 5 || args[1].equalsIgnoreCase("liquidity") && (args.length == 3 || args.length == 4));
            if (currencySlot) plugin.engine().currencies().forEach(c -> options.add(c.code()));
            if (args.length == 3 && (List.of("escrow", "orders").contains(feature) && args[1].equalsIgnoreCase("create")
                    || feature.equals("admin") && List.of("mint", "burn", "freeze", "unfreeze", "inspect").contains(args[1].toLowerCase(Locale.ROOT)))) {
                plugin.getServer().getOnlinePlayers().forEach(p -> options.add(p.getName()));
                if (feature.equals("admin") && List.of("mint", "burn").contains(args[1].toLowerCase(Locale.ROOT))) options.add("treasury");
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().toList();
    }
}
