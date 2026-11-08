package dev.aurora.economy.core;

public record Policy(int transferFeeBps, int swapFeeBps, int savingsAprBps,
                     int maxActiveOrders, int maxOpenEscrows, int maxOpenDeposits) {
    public Policy {
        if (transferFeeBps < 0 || transferFeeBps > 1_000 || swapFeeBps < 0 || swapFeeBps > 1_000
                || savingsAprBps < 0 || savingsAprBps > 10_000 || maxActiveOrders < 1
                || maxActiveOrders > 10_000 || maxOpenEscrows < 1 || maxOpenEscrows > 10_000
                || maxOpenDeposits < 1 || maxOpenDeposits > 10_000) throw new IllegalArgumentException("Invalid policy");
    }
    public static Policy defaults() { return new Policy(25, 30, 350, 10, 20, 20); }
}
