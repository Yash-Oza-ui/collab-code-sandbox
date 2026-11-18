package com.collabcode.syncservice.run;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic SHA-256 hashing of program execution inputs (FR29, US-52, US-68).
 *
 * <p>Produces a 64-character lowercase hexadecimal SHA-256 digest:
 * <ul>
 *   <li>Hashes the exact UTF-8 bytes of {@code stdin} (treating {@code null} as {@code ""}).</li>
 *   <li>When separate input data files are provided, appends each file in deterministic
 *       lexicographical filename order as {@code \0<name_utf8>\0<content_bytes>}.</li>
 * </ul>
 *
 * <p>Source code files are intentionally excluded so that slowdown baseline queries (US-68,
 * {@code docs/ai-ml-contract.md} §16.2) compare different code versions run against the same
 * input. Empty {@code stdin} with no input files yields {@link #EMPTY_SHA256}.
 */
public final class InputHasher {

    /** SHA-256 hex digest of the empty byte sequence ({@code ""}). */
    public static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private InputHasher() {
    }

    /**
     * Computes the lowercase hexadecimal SHA-256 digest of the exact UTF-8 bytes of {@code stdin}.
     */
    public static String hash(String stdin) {
        return hash(stdin, null);
    }

    /**
     * Computes the lowercase hexadecimal SHA-256 digest of {@code stdin} and optional input files.
     *
     * @param stdin      standard input string ({@code null} is treated as {@code ""})
     * @param inputFiles optional map of input filename to raw file bytes ({@code null} or empty
     *                   hashes {@code stdin} bytes only)
     * @return 64-character lowercase hexadecimal SHA-256 digest
     */
    public static String hash(String stdin, Map<String, byte[]> inputFiles) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] stdinBytes = stdin != null ? stdin.getBytes(StandardCharsets.UTF_8) : new byte[0];
            md.update(stdinBytes);

            if (inputFiles != null && !inputFiles.isEmpty()) {
                TreeMap<String, byte[]> sorted = new TreeMap<>();
                for (Map.Entry<String, byte[]> entry : inputFiles.entrySet()) {
                    if (entry.getKey() != null) {
                        sorted.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : new byte[0]);
                    }
                }
                for (Map.Entry<String, byte[]> entry : sorted.entrySet()) {
                    md.update((byte) 0);
                    md.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update(entry.getValue());
                }
            }

            byte[] digest = md.digest();
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
}
