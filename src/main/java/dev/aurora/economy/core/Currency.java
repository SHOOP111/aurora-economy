package dev.aurora.economy.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** All persisted values are integer minor units. No floating point enters the ledger. */
public record Currency(String code, String symbol, int decimals, long startingBalance, long dailyLimit) {
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9]{1,11}");
    private static final java.util.regex.Pattern AMOUNT = java.util.regex.Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    public Currency {
        if (code == null || !CODE.matcher(code).matches() || decimals < 0 || decimals > 6
                || startingBalance < 0 || dailyLimit < 0 || symbol == null || symbol.length() > 16
                || symbol.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid currency configuration: " + code);
        }
        code = code.toUpperCase(Locale.ROOT);
    }
    public long parse(String input) {
        return Money.positive(parseNonnegative(input));
    }
    /** Configuration may use zero, but is subject to the same bounded exact parser. */
    public long parseNonnegative(String input) {
        if (input == null || input.length() > 32 || !AMOUNT.matcher(input).matches()) {
            throw EconomyException.invalid("Use a positive decimal amount without commas or exponent notation.");
        }
        try {
            long units = new BigDecimal(input).movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            return units;
        } catch (ArithmeticException e) {
            throw EconomyException.invalid("Amount is too large or has more than " + decimals + " decimal places.");
        }
    }
    public String format(long units) { return symbol + decimal(units).toPlainString() + " " + code; }
    public BigDecimal decimal(long units) { return BigDecimal.valueOf(units, decimals); }
}
