package com.collabcode.syncservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the owner check GET /v1/rooms/{room_id}/owner (T-10, FR9.5)
 * against the acceptance criteria of issue #16: the right token is accepted,
 * a wrong or missing token is refused, and an unknown room is not found.
 *
 * <p>Uses RANDOM_PORT like the other sync-service tests so the full server
 * (including the WebSocket container) starts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerControllerTest {

    private final ObjectMapper om = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    /** Create a room and return [roomId, ownerToken]. */
    private String[] createRoom() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                base() + "/v1/rooms",
                new HttpEntity<>("{\"mode\":\"open\",\"language\":\"python\"}", headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = om.readTree(response.getBody());
        return new String[]{body.get("room_id").asText(), body.get("owner_token").asText()};
    }

    private ResponseEntity<String> checkOwner(String roomId, String ownerToken) {
        HttpHeaders headers = new HttpHeaders();
        if (ownerToken != null) {
            headers.add("X-Owner-Token", ownerToken);
        }
        return rest.exchange(
                base() + "/v1/rooms/" + roomId + "/owner",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void correctToken_returns200_isOwnerTrue() throws Exception {
        String[] room = createRoom();

        ResponseEntity<String> response = checkOwner(room[0], room[1]);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = om.readTree(response.getBody());
        assertThat(body.get("room_id").asText()).isEqualTo(room[0]);
        assertThat(body.get("is_owner").asBoolean()).isTrue();
    }

    @Test
    void wrongToken_returns403_notOwner() throws Exception {
        String[] room = createRoom();

        ResponseEntity<String> response = checkOwner(room[0], "wrong-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(om.readTree(response.getBody()).get("error").get("code").asText())
                .isEqualTo("NOT_OWNER");
    }

    @Test
    void missingToken_returns403_notOwner() throws Exception {
        String[] room = createRoom();

        ResponseEntity<String> response = checkOwner(room[0], null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(om.readTree(response.getBody()).get("error").get("code").asText())
                .isEqualTo("NOT_OWNER");
    }

    @Test
    void unknownRoom_returns404_roomNotFound() throws Exception {
        ResponseEntity<String> response = checkOwner("ThisRoomDoesNotExist00", "any-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(om.readTree(response.getBody()).get("error").get("code").asText())
                .isEqualTo("ROOM_NOT_FOUND");
    }
}
