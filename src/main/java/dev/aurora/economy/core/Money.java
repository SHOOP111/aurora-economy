package dev.aurora.economy.core;

import java.math.BigInteger;

public final class Money {
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000);
    private static final BigInteger FEE_ROUNDING = BigInteger.valueOf(9_999);
    private static final BigInteger YEAR_DENOMINATOR = BigInteger.valueOf(10_000L * 365 * 86_400);
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
        if (basisPoints == 0) return 0;
        return BigInteger.valueOf(units).multiply(BigInteger.valueOf(basisPoints))
                .add(FEE_ROUNDING).divide(BASIS_POINTS).longValueExact();
    }
    public static long interest(long principal, int aprBasisPoints, long seconds) {
        positive(principal);
        if (aprBasisPoints < 0 || seconds < 0) throw new IllegalArgumentException("Invalid interest terms");
        if (aprBasisPoints == 0 || seconds == 0) return 0;
        try {
            return BigInteger.valueOf(principal).multiply(BigInteger.valueOf(aprBasisPoints))
                    .multiply(BigInteger.valueOf(seconds)).divide(YEAR_DENOMINATOR).longValueExact();
        } catch (ArithmeticException e) { throw EconomyException.invalid("Interest exceeds the supported range."); }
    }
    public static long swapOutput(long reserveIn, long reserveOut, long input, int feeBps) {
        positive(input);
        if (reserveIn <= 0 || reserveOut <= 0) throw new EconomyException(EconomyException.Code.NO_LIQUIDITY, "This pool is not funded.");
        if (feeBps < 0 || feeBps >= 10_000) throw new IllegalArgumentException("Invalid swap fee");
        // Do not round the effective input before evaluating x*y=k.
        BigInteger effective = BigInteger.valueOf(input).multiply(BigInteger.valueOf(10_000L - feeBps));
        return BigInteger.valueOf(reserveOut).multiply(effective)
                .divide(BigInteger.valueOf(reserveIn).multiply(BASIS_POINTS).add(effective)).longValueExact();
    }
}
