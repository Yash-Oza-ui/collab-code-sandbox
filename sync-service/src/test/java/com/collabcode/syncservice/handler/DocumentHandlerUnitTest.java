package com.collabcode.syncservice.handler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DocumentHandler#isDocumentChange(byte[])} (CR13)
 * and T-17 / T-20 close codes.
 *
 * <p>These tests run without a Spring context and verify the first-two-bytes
 * heuristic against the T-17 Section V2.12 / y-protocols 1.x byte layout:
 * <ul>
 *   <li>Byte 0 = 0 (sync), byte 1 = 1 (step-2) → carries document changes → true</li>
 *   <li>Byte 0 = 0 (sync), byte 1 = 2 (update) → carries document changes → true</li>
 *   <li>Byte 0 = 0 (sync), byte 1 = 0 (step-1) → state vector request → false</li>
 *   <li>Byte 0 = 1 (awareness) → not a change → false</li>
 *   <li>Byte 0 = 3 (query awareness) → not a change → false</li>
 *   <li>Short frames (< 2 bytes) → false (safe guard)</li>
 *   <li>Anything else (unknown type or sub-type, or a first byte of 0x80 or more that Yjs reads
 *       as a longer encoding of a number) → true, so a modified client cannot hide an update
 *       behind a non-canonical encoding (US-35)</li>
 * </ul>
 */
class DocumentHandlerUnitTest {

    @Test
    void step2_isDocumentChange() {
        byte[] frame = {0x00, 0x01, 0x10, 0x20}; // sync(0) + step-2(1)
        assertThat(DocumentHandler.isDocumentChange(frame)).isTrue();
    }

    @Test
    void update_isDocumentChange() {
        byte[] frame = {0x00, 0x02, 0x10, 0x20}; // sync(0) + update(2)
        assertThat(DocumentHandler.isDocumentChange(frame)).isTrue();
    }

    @Test
    void step1_isNotDocumentChange() {
        byte[] frame = {0x00, 0x00, 0x10}; // sync(0) + step-1(0)
        assertThat(DocumentHandler.isDocumentChange(frame)).isFalse();
    }

    @Test
    void awarenessFrame_isNotDocumentChange() {
        byte[] frame = {0x01, 0x00, 0x05}; // awareness(1)
        assertThat(DocumentHandler.isDocumentChange(frame)).isFalse();
    }

    @Test
    void queryAwarenessFrame_isNotDocumentChange() {
        byte[] frame = {0x03}; // query awareness(3)
        assertThat(DocumentHandler.isDocumentChange(frame)).isFalse();
    }

    @Test
    void nullFrame_isNotDocumentChange() {
        assertThat(DocumentHandler.isDocumentChange(null)).isFalse();
    }

    @Test
    void emptyFrame_isNotDocumentChange() {
        assertThat(DocumentHandler.isDocumentChange(new byte[0])).isFalse();
    }

    @Test
    void singleByteFrame_isNotDocumentChange() {
        assertThat(DocumentHandler.isDocumentChange(new byte[]{0x00})).isFalse();
    }

    // --- US-35: a modified client must not hide a change behind another encoding ---

    @Test
    void nonCanonicalSyncType_isDocumentChange() {
        // 0x80 0x00 is a valid Yjs encoding of the number 0 (sync); then 0x82 0x00 encodes 2 (update).
        byte[] frame = {(byte) 0x80, 0x00, (byte) 0x82, 0x00, 0x10, 0x20};
        assertThat(DocumentHandler.isDocumentChange(frame)).isTrue();
    }

    @Test
    void nonCanonicalSyncSubtype_isDocumentChange() {
        assertThat(DocumentHandler.isDocumentChange(new byte[]{0x00, (byte) 0x82, 0x00, 0x10})).isTrue();
        assertThat(DocumentHandler.isDocumentChange(new byte[]{0x00, (byte) 0x81, 0x00, 0x10})).isTrue();
    }

    @Test
    void nonCanonicalAwarenessType_isTreatedAsPossibleChange() {
        // Real awareness frames use the single byte 1; a longer encoding is not on the allow-list.
        assertThat(DocumentHandler.isDocumentChange(new byte[]{(byte) 0x81, 0x00, 0x05})).isTrue();
    }

    @Test
    void unknownMessageType_isTreatedAsPossibleChange() {
        assertThat(DocumentHandler.isDocumentChange(new byte[]{0x02, 0x00})).isTrue();
        assertThat(DocumentHandler.isDocumentChange(new byte[]{0x05, 0x00, 0x01})).isTrue();
        assertThat(DocumentHandler.isDocumentChange(new byte[]{(byte) 0xFF, (byte) 0xFF})).isTrue();
    }

    @Test
    void unknownSyncSubtype_isTreatedAsPossibleChange() {
        assertThat(DocumentHandler.isDocumentChange(new byte[]{0x00, 0x03, 0x10})).isTrue();
    }

    @Test
    void readOnlyFramesWithPayload_stayNotDocumentChange() {
        assertThat(DocumentHandler.isDocumentChange(new byte[]{0x01, 0x01, 0x02, 0x03})).isFalse();
        assertThat(DocumentHandler.isDocumentChange(new byte[]{0x03, 0x09})).isFalse();
        assertThat(DocumentHandler.isDocumentChange(new byte[]{0x00, 0x00, 0x7F, 0x7F})).isFalse();
    }

    @Test
    void closeCodes_defined() {
        // Verify contract close codes (T-17 V2.2, V2.5 / T-20)
        assertThat(DocumentHandler.CLOSE_UNKNOWN_ROOM).isEqualTo(4404);
        assertThat(DocumentHandler.CLOSE_UNKNOWN_DOC).isEqualTo(4405);
        assertThat(DocumentHandler.CLOSE_NOT_JOINED).isEqualTo(4406);
        assertThat(DocumentHandler.CLOSE_ROOM_CLOSED).isEqualTo(4410);
        assertThat(ControlHandler.CLOSE_UNKNOWN_ROOM).isEqualTo(4404);
        assertThat(ControlHandler.CLOSE_ROOM_CLOSED).isEqualTo(4410);
    }
}
