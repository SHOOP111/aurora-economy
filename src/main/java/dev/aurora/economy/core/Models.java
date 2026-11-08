package dev.aurora.economy.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Models {
    private Models() { }
    public record Posting(String account, String currency, long delta) implements Comparable<Posting> {
        @Override public int compareTo(Posting other) {
            int c = account.compareTo(other.account);
            return c == 0 ? currency.compareTo(other.currency) : c;
        }
    }
    public record Receipt(String id, String key, String kind, String reference, Instant time, String hash, boolean replayed) { }
    public record Escrow(String id, UUID owner, UUID beneficiary, String currency, long amount,
                         long fee, Instant expires, String state) { }
    public record Deposit(String id, UUID owner, String currency, long principal, long interest,
                          Instant unlocks, String state) { }
    public record StandingOrder(String id, UUID owner, UUID beneficiary, String currency, long amount,
                                int feeBps, long intervalSeconds, Instant nextDue, int executions,
                                int totalExecutions, String state, String reason) { }
    public record Quote(String from, String to, long input, long output, long reserveIn, long reserveOut,
                        int feeBps, Instant time) { }
    public record Portfolio(Map<String, Long> wallets, Map<String, Long> lockedSavings,
                            Map<String, Long> escrowHeld, Map<String, Long> spentToday) {
        public Portfolio {
            wallets = Map.copyOf(wallets); lockedSavings = Map.copyOf(lockedSavings);
            escrowHeld = Map.copyOf(escrowHeld); spentToday = Map.copyOf(spentToday);
        }
    }
    public record Supply(String currency, long issued, long wallets, long savings, long escrow,
                         long treasury, long markets) { }
    public record Audit(long transactions, long postings, String headHash, List<String> violations) {
        public Audit { violations = List.copyOf(violations); }
        public boolean healthy() { return violations.isEmpty(); }
    }
}
