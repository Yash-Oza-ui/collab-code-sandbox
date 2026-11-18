package com.collabcode.syncservice.room;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RoomIds} — the unguessable-room-link guarantees of
 * NFR16 / US-62 (issue #131).
 *
 * <p>These prove, directly on the generator, the properties a join link relies
 * on: a 22-character id from the §3.2 base64url charset, at least 128 bits of
 * entropy, and no repeats across a large batch. The server-side "the client
 * cannot choose the id" and "an unknown id is closed with 4404" criteria are
 * covered by {@code RoomControllerTest} and {@code RelayIntegrationTest}.
 */
class RoomIdsTest {

    /** US-62 definition-of-done: 10 000 generated ids are all different. */
    private static final int BATCH = 10_000;

    @Test
    void generate_is22Chars_fromBase64UrlCharset() {
        for (int i = 0; i < 1_000; i++) {
            String id = RoomIds.generate();
            assertThat(id).hasSize(22);
            // §3.2 charset, comfortably inside the 1..64 character rule.
            assertThat(id).matches("[A-Za-z0-9_-]{22}");
        }
    }

    @Test
    void generate_decodesToAtLeast128Bits() {
        // 22 base64url chars (no padding) decode to 16 bytes = 128 bits of
        // secure-random entropy, which is exactly what NFR16 requires.
        for (int i = 0; i < 1_000; i++) {
            byte[] raw = Base64.getUrlDecoder().decode(RoomIds.generate());
            assertThat(raw.length)
                    .as("a room id must carry at least 128 bits (16 bytes) of entropy")
                    .isGreaterThanOrEqualTo(16);
        }
    }

    @Test
    void generate_producesNoRepeats_inLargeBatch() {
        Set<String> seen = new HashSet<>(BATCH * 2);
        for (int i = 0; i < BATCH; i++) {
            assertThat(seen.add(RoomIds.generate()))
                    .as("room id number %d repeated a previous one", i)
                    .isTrue();
        }
        assertThat(seen).hasSize(BATCH);
    }
}
