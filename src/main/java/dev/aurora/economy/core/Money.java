package dev.aurora.economy.core;

import java.math.BigInteger;

public final class Money {
    private Money() { }
    public static long positive(long units) {
        if (units <= 0) throw EconomyException.invalid("Amount must be greater than zero.");
        return units;
    }
    public static long add(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException e) { throw EconomyException.invalid("Amount exceeds the supported range."); }
    }
    public static long fee(long units, int basisPoints) {
        positive(units);
        if (basisPoints < 0 || basisPoints > 10_000) throw new IllegalArgumentException("Invalid fee");
        return BigInteger.valueOf(units).multiply(BigInteger.valueOf(basisPoints))
                .add(BigInteger.valueOf(9_999)).divide(BigInteger.valueOf(10_000)).longValueExact();
    }
    public static long interest(long principal, int aprBasisPoints, long seconds) {
        positive(principal);
        if (aprBasisPoints < 0 || seconds < 0) throw new IllegalArgumentException("Invalid interest terms");
        return BigInteger.valueOf(principal).multiply(BigInteger.valueOf(aprBasisPoints))
                .multiply(BigInteger.valueOf(seconds)).divide(BigInteger.valueOf(10_000L * 365 * 86_400)).longValueExact();
    }
    public static long swapOutput(long reserveIn, long reserveOut, long input, int feeBps) {
        positive(input);
        if (reserveIn <= 0 || reserveOut <= 0) throw new EconomyException(EconomyException.Code.NO_LIQUIDITY, "This pool is not funded.");
        if (feeBps < 0 || feeBps >= 10_000) throw new IllegalArgumentException("Invalid swap fee");
        // Do not round the effective input before evaluating x*y=k.
        BigInteger effective = BigInteger.valueOf(input).multiply(BigInteger.valueOf(10_000L - feeBps));
        return BigInteger.valueOf(reserveOut).multiply(effective)
                .divide(BigInteger.valueOf(reserveIn).multiply(BigInteger.valueOf(10_000)).add(effective)).longValueExact();
    }
}
