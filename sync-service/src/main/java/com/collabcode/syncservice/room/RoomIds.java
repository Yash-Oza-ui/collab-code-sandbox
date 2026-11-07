package com.collabcode.syncservice.room;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Server-generated room ids (T-17 V2.1, decision 2; NFR16, US-62).
 *
 * <p>At least 128 bits from a secure random generator, written in base64url
 * without {@code =} padding, so the id uses only {@code [A-Za-z0-9_-]} (the
 * §3.2 charset) and is 22 characters long. The client cannot choose it.
 */
public final class RoomIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private RoomIds() {
    }

    /** A fresh 128-bit room id, base64url without padding (22 characters). */
    public static String generate() {
        byte[] bytes = new byte[16]; // 128 bits
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
