package dev.aurora.economy;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.Money;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
    final Currency coin = new Currency("coin", "$", 2, 0, 0);
    @ParameterizedTest @CsvSource({"0.01,1", "1,100", "1000.25,100025", "0001.00,100", "92233720368547758.07,9223372036854775807"})
    void exactParsing(String value, long expected) { assertEquals(expected, coin.parse(value)); }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "+1", "1e2", "NaN", "Infinity", "1,000", " 1", ".1", "1.", "0.001", "92233720368547758.08", "", "０１"})
    void rejectsDangerousAmounts(String value) { assertThrows(RuntimeException.class, () -> coin.parse(value)); }
    @Test void roundTripMax() { assertEquals(Long.MAX_VALUE, coin.parse(coin.decimal(Long.MAX_VALUE).toPlainString())); }
    @Test void format() { assertEquals("$1.01 COIN", coin.format(101)); }
    @Test void checkedOverflow() { assertThrows(RuntimeException.class, () -> Money.add(Long.MAX_VALUE, 1)); }
    @Test void feeRoundsUp() { assertEquals(1, Money.fee(1, 25)); assertEquals(25, Money.fee(10_000, 25)); assertEquals(0, Money.fee(1, 0)); }
    @Test void interestUsesWholeMinorUnits() { assertEquals(350, Money.interest(10_000, 350, 365 * 86_400L)); assertEquals(0, Money.interest(1, 350, 86_400)); }
    @Test void bigIntermediatesDoNotOverflow() { assertEquals(Long.MAX_VALUE, Money.fee(Long.MAX_VALUE, 10_000)); assertEquals(Long.MAX_VALUE, Money.interest(Long.MAX_VALUE, 10_000, 365 * 86_400L)); }
    @Test void randomizedConstantProductNeverDecreases() {
        Random random = new Random(0xA0A0A);
        for (int i = 0; i < 10_000; i++) {
            long x = random.nextLong(1, 1_000_000_000), y = random.nextLong(1, 1_000_000_000), in = random.nextLong(1, 100_000_000);
            long out = Money.swapOutput(x, y, in, 30);
            assertTrue(out >= 0 && out < y);
            BigInteger before = BigInteger.valueOf(x).multiply(BigInteger.valueOf(y));
            BigInteger after = BigInteger.valueOf(x + in).multiply(BigInteger.valueOf(y - out));
            assertTrue(after.compareTo(before) >= 0);
        }
    }
    @Test void currencyValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Currency("!bad", "$", 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Currency("COIN", "$", 7, 0, 0));
    }
}
