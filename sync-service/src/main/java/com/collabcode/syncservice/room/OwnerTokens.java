package com.collabcode.syncservice.room;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Owner tokens (T-10, T-17 V2.1).
 *
 * <p>A long random token is returned once at room creation; the server stores
 * only its SHA-256 hash and compares later in constant time. The plaintext
 * token is never stored, logged or placed in a URL.
 */
public final class OwnerTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OwnerTokens() {
    }

    /** A fresh 256-bit random token, base64url without padding. */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** SHA-256 of the token as lowercase hex (what the room stores). */
    public static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** Constant-time check of a presented token against a stored hash. */
    public static boolean matches(String token, String storedHash) {
        if (token == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(token).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
