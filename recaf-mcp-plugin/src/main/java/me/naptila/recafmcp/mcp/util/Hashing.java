package me.naptila.recafmcp.mcp.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared SHA-256 digest helpers for tool payloads, fingerprints, and conditional reads.
 */
public final class Hashing {
    private Hashing() {
    }

    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
