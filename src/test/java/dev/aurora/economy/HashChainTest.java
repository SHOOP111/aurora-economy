package dev.aurora.economy;

import dev.aurora.economy.core.Models.Posting;
import dev.aurora.economy.storage.HashChain;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HashChainTest {
    String hash(String key, String intent, List<Posting> postings) { return HashChain.digest(HashChain.GENESIS, "id", key, intent, "PAY", "actor", "ref", 123, postings); }
    @Test void canonicalOrderingIsStable() {
        var a = new Posting("a", "COIN", -100); var b = new Posting("b", "COIN", 100);
        assertEquals(hash("key", "intent", List.of(a, b)), hash("key", "intent", List.of(b, a)));
    }
    @Test void lengthPrefixesPreventAmbiguousConcatenation() { assertNotEquals(hash("ab", "c", List.of()), hash("a", "bc", List.of())); }
    @Test void monetaryTamperingChangesDigest() { assertNotEquals(hash("key", "intent", List.of(new Posting("a", "COIN", 1))), hash("key", "intent", List.of(new Posting("a", "COIN", 2)))); }
    @Test void previousHeadIsAuthenticated() {
        assertNotEquals(HashChain.digest(HashChain.GENESIS, "id", "key", "intent", "PAY", "actor", "ref", 123, List.of()), HashChain.digest("1".repeat(64), "id", "key", "intent", "PAY", "actor", "ref", 123, List.of()));
    }
}
