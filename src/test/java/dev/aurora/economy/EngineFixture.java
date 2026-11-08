package dev.aurora.economy;

import dev.aurora.economy.core.Currency;
import dev.aurora.economy.core.EconomyEngine;
import dev.aurora.economy.core.EconomyException;
import dev.aurora.economy.core.Policy;
import dev.aurora.economy.storage.LedgerStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

abstract class EngineFixture {
    @TempDir Path directory;
    final UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
    final UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");
    final UUID mallory = UUID.fromString("33333333-3333-3333-3333-333333333333");
    MutableClock clock;
    LedgerStore store;
    EconomyEngine engine;
    List<Currency> currencies;
    @BeforeEach void setup() {
        clock = new MutableClock();
        currencies = List.of(new Currency("COIN", "$", 2, 100_000, 0), new Currency("GEM", "♦", 0, 1_000, 0));
        store = new LedgerStore(directory.resolve("economy.db"), clock, currencies);
        engine = new EconomyEngine(store, clock, Policy.defaults(), currencies);
        engine.register(alice, "Alice"); engine.register(bob, "Bob");
    }
    @AfterEach void close() { if (store != null) store.close(); }
    void fund(String code, long units) { engine.issue(mallory, LedgerStore.TREASURY, code, units, false, UUID.randomUUID().toString(), "ADMIN_MINT"); }
    void error(EconomyException.Code code, Runnable work) { assertEquals(code, assertThrows(EconomyException.class, work::run).code()); }
    void healthy() { assertTrue(engine.audit().healthy(), () -> engine.audit().violations().toString()); }
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
