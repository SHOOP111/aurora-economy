package dev.aurora.economy.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** All persisted values are integer minor units. No floating point enters the ledger. */
public record Currency(String code, String symbol, int decimals, long startingBalance, long dailyLimit) {
    public Currency {
        code = code.toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9]{1,11}") || decimals < 0 || decimals > 6
                || startingBalance < 0 || dailyLimit < 0 || symbol == null || symbol.length() > 16) {
            throw new IllegalArgumentException("Invalid currency configuration: " + code);
        }
    }
    public long parse(String input) {
        if (input == null || input.length() > 32 || !input.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw EconomyException.invalid("Use a positive decimal amount without commas or exponent notation.");
        }
        try {
            long units = new BigDecimal(input).movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            Money.positive(units);
            return units;
        } catch (ArithmeticException e) {
            throw EconomyException.invalid("Amount is too large or has more than " + decimals + " decimal places.");
        }
    }
    public String format(long units) { return symbol + decimal(units).toPlainString() + " " + code; }
    public BigDecimal decimal(long units) { return BigDecimal.valueOf(units, decimals); }
}
