package com.collabcode.execution.util;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.model.RunResponse;
import com.collabcode.execution.model.RunSpec;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import com.collabcode.execution.snapshot.MemberInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for US-52 (FR29) execution-service delivery behavior:
 * - Deterministic SHA-256 input hashing in {@link InputHasher}
 * - Internal delivery payload carries {@code input_hash} and {@code files} (filenames only)
 * - Client-facing V2.9 HTTP {@link RunResponse} JSON does not expose internal metadata fields
 */
@SpringBootTest
@AutoConfigureMockMvc
class InputHasherAndBroadcastUS52Test {

    private static final String REQUEST_ID = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10";
    private static final String ROOM_ID    = "room-us52-exec-test";
    private static final String SESSION_ID = "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CodeRunner codeRunner;

    @MockBean
    private RoomBroadcastService broadcastService;

    @Test
    @DisplayName("InputHasher produces equal hashes for equal stdin and distinct hashes for different stdin")
    void inputHasher_sameAndDifferentStdin() {
        assertThat(InputHasher.hash("abc\n")).isEqualTo(InputHasher.hash("abc\n"));
        assertThat(InputHasher.hash("abc\n")).isNotEqualTo(InputHasher.hash("xyz\n"));
        assertThat(InputHasher.hash("")).isEqualTo(InputHasher.EMPTY_SHA256);
        assertThat(InputHasher.hash(null)).isEqualTo(InputHasher.EMPTY_SHA256);
    }

    @Test
    @DisplayName("InputHasher hashes optional input files deterministically regardless of map order")
    void inputHasher_withInputFiles_isOrderIndependent() {
        Map<String, byte[]> files1 = new LinkedHashMap<>();
        files1.put("b.dat", "2".getBytes(StandardCharsets.UTF_8));
        files1.put("a.dat", "1".getBytes(StandardCharsets.UTF_8));

        Map<String, byte[]> files2 = new LinkedHashMap<>();
        files2.put("a.dat", "1".getBytes(StandardCharsets.UTF_8));
        files2.put("b.dat", "2".getBytes(StandardCharsets.UTF_8));

        assertThat(InputHasher.hash("in", files1)).isEqualTo(InputHasher.hash("in", files2));
        assertThat(InputHasher.hash("in", files1)).isNotEqualTo(InputHasher.hash("in"));
    }

    @Test
    @DisplayName("RoomBroadcastService payload includes input_hash and file names only (no source code or raw stdin)")
    void buildPayload_includesInputHashAndFilenamesOnly() {
        RoomBroadcastService service = new RoomBroadcastService(objectMapper);
        RunResponse resp = new RunResponse();
        resp.setRequest_id(REQUEST_ID);
        resp.setRoom_id(ROOM_ID);
        resp.setLanguage("python");
        resp.setVisibility("room");
        resp.setRunner(new MemberInfo("m_1", "Ada"));
        resp.setRan_at("2026-11-01T10:20:05.087Z");
        resp.setInputHash(InputHasher.hash("secret-stdin"));
        resp.setRunFiles(List.of("main.py"));

        Map<String, Object> payload = service.buildPayload(resp, SESSION_ID);
        assertThat(payload.get("input_hash")).isEqualTo(InputHasher.hash("secret-stdin"));
        assertThat(payload.get("files")).isEqualTo(List.of("main.py"));
        assertThat(payload).doesNotContainKey("stdin");
    }

    @Test
    @DisplayName("RunController populates internal inputHash/runFiles for delivery while keeping HTTP RunResponse strictly V2.9")
    void runController_setsDeliveryMetadataWithoutPollutingHttpResponse() throws Exception {
        when(codeRunner.execute(any(RunSpec.class))).thenReturn(new ExecutionResult(
                "42\n", "", 0, 18.0, 6.4,
                false, false, null, false, null
        ));

        String rawStdin = "42\n";
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "stdin":      "42\\n",
                  "source": {
                    "kind": "inline",
                    "files": [
                      { "name": "main.py", "content": "print(input())" }
                    ],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        MvcResult mvcResult = mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        // Client-facing HTTP JSON stays strictly V2.9 (no inputHash/runFiles leaked)
        String httpBody = mvcResult.getResponse().getContentAsString();
        assertThat(httpBody).doesNotContain("inputHash");
        assertThat(httpBody).doesNotContain("runFiles");

        // Broadcast receives the RunResponse populated with inputHash and filenames
        ArgumentCaptor<RunResponse> respCaptor = ArgumentCaptor.forClass(RunResponse.class);
        verify(broadcastService, times(1)).broadcast(
                org.mockito.ArgumentMatchers.eq(ROOM_ID),
                org.mockito.ArgumentMatchers.eq(SESSION_ID),
                respCaptor.capture());

        RunResponse delivered = respCaptor.getValue();
        assertThat(delivered.getInputHash()).isEqualTo(InputHasher.hash(rawStdin));
        assertThat(delivered.getRunFiles()).containsExactly("main.py");
    }
}
