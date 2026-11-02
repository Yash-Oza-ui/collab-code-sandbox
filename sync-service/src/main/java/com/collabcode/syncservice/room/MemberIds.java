package com.collabcode.syncservice.room;

import java.security.SecureRandom;

/**
 * Public, per-room member ids (T-17 V2.3): short, opaque, prefixed {@code m_}.
 * A member is identified publicly by this id; the private session id is never
 * exposed in {@code roster}, module data or any broadcast.
 */
public final class MemberIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private MemberIds() {
    }

    public static String generate() {
        byte[] bytes = new byte[6]; // 48 bits
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("m_");
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]);
            sb.append(HEX[b & 0xF]);
        }
        return sb.toString();
    }
}
