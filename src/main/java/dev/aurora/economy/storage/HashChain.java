package dev.aurora.economy.storage;

import dev.aurora.economy.core.Models.Posting;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Versioned, length-prefixed canonical encoding; account ordering is deterministic. */
public final class HashChain {
    public static final String GENESIS = "0".repeat(64);
    private HashChain() { }
    public static String digest(String previous, String id, String key, String intent, String kind,
                                String actor, String reference, long time, List<Posting> postings) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(1);
                for (String value : List.of(previous, id, key, intent, kind, actor, reference)) {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(bytes.length); out.write(bytes);
                }
                out.writeLong(time); out.writeInt(postings.size());
                for (Posting entry : postings.stream().sorted().toList()) {
                    for (String value : List.of(entry.account(), entry.currency())) {
                        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                        out.writeInt(bytes.length); out.write(bytes);
                    }
                    out.writeLong(entry.delta());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
