package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for POST /v1/rooms (T-20 Femina part, T-17 V2.1) against the
 * acceptance criteria of issue #65.
 *
 * <p>Uses {@code RANDOM_PORT} like {@code RelayIntegrationTest}: the full
 * server (including the WebSocket container) must start, so a MOCK web
 * environment cannot be used here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomControllerTest {

    /** 128-bit base64url without padding = 22 chars from [A-Za-z0-9_-]. */
    private static final String ROOM_ID_PATTERN = "[A-Za-z0-9_-]{22}";

    private final ObjectMapper om = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RoomStore store;

    private ResponseEntity<String> create(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(
                "http://localhost:" + port + "/v1/rooms",
                new HttpEntity<>(json, headers),
                String.class);
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return om.readTree(response.getBody());
    }

    @Test
    void createOpenRoom_returns201_withServerGeneratedId() throws Exception {
        ResponseEntity<String> response = create("{\"mode\":\"open\",\"language\":\"python\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode b = body(response);
        assertThat(b.get("mode").asText()).isEqualTo("open");
        assertThat(b.get("preset").isNull()).isTrue();
        assertThat(b.get("language").asText()).isEqualTo("python");
        assertThat(b.get("room_id").asText()).matches(ROOM_ID_PATTERN);
        assertThat(b.get("owner_token").asText()).isNotEmpty();
    }

    @Test
    void createPairRoom_returns201_withPreset() throws Exception {
        ResponseEntity<String> response = create("{\"mode\":\"pair\",\"preset\":\"teach_interview\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode b = body(response);
        assertThat(b.get("mode").asText()).isEqualTo("pair");
        assertThat(b.get("preset").asText()).isEqualTo("teach_interview");
        assertThat(b.get("language").isNull()).isTrue();
        assertThat(b.get("room_id").asText()).matches(ROOM_ID_PATTERN);
    }

    @Test
    void presetInOpenMode_isRejected() throws Exception {
        ResponseEntity<String> response =
                create("{\"mode\":\"open\",\"language\":\"python\",\"preset\":\"teach_interview\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void languageInPairMode_isRejected() throws Exception {
        ResponseEntity<String> response =
                create("{\"mode\":\"pair\",\"preset\":\"teach_interview\",\"language\":\"python\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void missingMode_isRejected() throws Exception {
        ResponseEntity<String> response = create("{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void unsupportedLanguageInOpenMode_isRejected() throws Exception {
        ResponseEntity<String> response = create("{\"mode\":\"open\",\"language\":\"rust\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode error = body(response).get("error");
        assertThat(error.get("code").asText()).isEqualTo("UNSUPPORTED_LANGUAGE");
        assertThat(error.get("message").asText())
                .isEqualTo("Language 'rust' is not supported. Use one of: cpp, python, c, java, javascript.");
    }

    @Test
    void missingLanguageInOpenMode_isInvalidRequest() throws Exception {
        ResponseEntity<String> response = create("{\"mode\":\"open\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void aiSwitch_defaultsPerPreset() throws Exception {
        // Teach/Interview → AI off by default (FR35.2)
        String teachId = body(create("{\"mode\":\"pair\",\"preset\":\"teach_interview\"}"))
                .get("room_id").asText();
        assertThat(store.get(teachId).isAiEnabled()).isFalse();

        // Any other room → AI on by default
        String openId = body(create("{\"mode\":\"open\",\"language\":\"python\"}"))
                .get("room_id").asText();
        assertThat(store.get(openId).isAiEnabled()).isTrue();
    }

    @Test
    void clientSuppliedRoomId_isIgnored() throws Exception {
        ResponseEntity<String> response =
                create("{\"mode\":\"open\",\"language\":\"python\",\"room_id\":\"client-chosen-id\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String roomId = body(response).get("room_id").asText();
        assertThat(roomId).isNotEqualTo("client-chosen-id");
        assertThat(roomId).matches(ROOM_ID_PATTERN);
    }

    @Test
    void ownerToken_isStoredOnlyAsHash() throws Exception {
        ResponseEntity<String> response = create("{\"mode\":\"open\",\"language\":\"python\"}");
        JsonNode b = body(response);
        String roomId = b.get("room_id").asText();
        String token = b.get("owner_token").asText();

        Room room = store.get(roomId);
        assertThat(room).isNotNull();
        assertThat(room.getOwnerTokenHash()).isNotEqualTo(token);            // plaintext never stored
        assertThat(room.getOwnerTokenHash()).isEqualTo(OwnerTokens.hash(token));
        assertThat(OwnerTokens.matches(token, room.getOwnerTokenHash())).isTrue();
    }
}
