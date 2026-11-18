package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.model.RunResponse;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.DockerRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-08 runner hookup tests (FR4.1, FR4.2, FR4.5): POST /v1/runs through the REAL
 * {@link DockerRunner}, not a mock and not the fake runner.
 *
 * {@code RunControllerUS08Test} (Jay) proves the endpoint contract with a mocked
 * runner. This class proves that the same endpoint works end to end when the
 * Docker runner (US-09) is behind it: success, failure, stdin, syntax error,
 * unsupported language and delivery to the room.
 *
 * Skipped (not failed) when the Docker engine or the project Python image is
 * missing, like {@code DockerRunnerTest}. Only the sync-service delivery
 * ({@link RoomBroadcastService}) is mocked, so no other service is needed.
 *
 * The wall-time limit is raised to 15 s for these tests because starting a
 * container on a developer machine can take longer than the 5 s placeholder in
 * application.yml. The limit itself is US-13's work, not this test's.
 */
@SpringBootTest(properties = "app.execution.limits.wall-time-seconds=15")
@AutoConfigureMockMvc
class RunControllerUS08DockerTest {

    private static final String IMAGE = "collab-sandbox-python:0.1.0";

    private static final String REQUEST_ID = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10";
    private static final String ROOM_ID    = "room-demo-01";
    private static final String SESSION_ID = "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CodeRunner codeRunner;

    @MockBean
    private RoomBroadcastService broadcastService;

    @BeforeAll
    static void requireDockerAndImage() throws Exception {
        Assumptions.assumeTrue(
                dockerSucceeds("info"),
                "Docker engine is unavailable");
        Assumptions.assumeTrue(
                dockerSucceeds("image", "inspect", IMAGE),
                "Build the project Python runtime image before running integration tests");
    }

    // ------------------------------------------------------------------ wiring

    @Test
    @DisplayName("The runner behind POST /v1/runs is the Docker runner (US-09)")
    void endpointUsesTheDockerRunner() {
        assertThat(codeRunner).isInstanceOf(DockerRunner.class);
    }

    // ------------------------------------------------------------------ success

    @Test
    @DisplayName("Success: stdout 'hello\\n', stderr empty, exit 0 (FR4.2)")
    void successReturnsHello() throws Exception {
        mockMvc.perform(run("python", "print('hello')", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.stdout").value("hello\n"))
                .andExpect(jsonPath("$.stderr").value(""))
                .andExpect(jsonPath("$.exit_code").value(0))
                .andExpect(jsonPath("$.failure_kind").isEmpty())
                .andExpect(jsonPath("$.timed_out").value(false))
                .andExpect(jsonPath("$.oom_killed").value(false))
                .andExpect(jsonPath("$.output_truncated").value(false))
                .andExpect(jsonPath("$.execution_time_ms", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.peak_memory_mb").exists());
    }

    // ------------------------------------------------------------------ failure

    @Test
    @DisplayName("Failure: stderr 'boom', exit 2, not an empty response (FR4.2)")
    void failureReturnsBoomAndExitCodeTwo() throws Exception {
        mockMvc.perform(run("python",
                        "import sys\nsys.stderr.write('boom')\nsys.exit(2)", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout").value(""))
                .andExpect(jsonPath("$.stderr").value(containsString("boom")))
                .andExpect(jsonPath("$.exit_code").value(2))
                .andExpect(jsonPath("$.failure_kind").value("non_zero_exit"));
    }

    @Test
    @DisplayName("Runtime exception: traceback in stderr, HTTP 200, not a server error")
    void runtimeExceptionIsAResultNotAServerError() throws Exception {
        mockMvc.perform(run("python", "raise RuntimeError('intentional')", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stderr").value(containsString("intentional")))
                .andExpect(jsonPath("$.exit_code").value(not(0)))
                .andExpect(jsonPath("$.failure_kind").value("non_zero_exit"));
    }

    // ------------------------------------------------------------------ stdin

    @Test
    @DisplayName("Stdin: input().upper() with stdin 'abc' gives 'ABC\\n' (FR4.1)")
    void stdinIsPassedToTheProgram() throws Exception {
        mockMvc.perform(run("python", "print(input().upper())", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout").value("ABC\n"))
                .andExpect(jsonPath("$.exit_code").value(0));
    }

    // ------------------------------------------------------------------ syntax error

    @Test
    @DisplayName("Syntax error: error text in stderr, HTTP 200, not a server error")
    void syntaxErrorIsAResultNotAServerError() throws Exception {
        mockMvc.perform(run("python", "print(", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stderr").value(containsString("SyntaxError")))
                .andExpect(jsonPath("$.exit_code").value(not(0)))
                .andExpect(jsonPath("$.failure_kind").value("compile_error"));
    }

    // ------------------------------------------------------------------ unsupported language

    @Test
    @DisplayName("Unsupported language: clear 4xx (422 UNSUPPORTED_LANGUAGE), no container is started")
    void unsupportedLanguageIsRejectedBeforeAnyRun() throws Exception {
        mockMvc.perform(run("ruby", "puts 'hi'", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_LANGUAGE"))
                .andExpect(jsonPath("$.error.message").value(containsString("ruby")));

        verifyNoInteractions(broadcastService);
    }

    // ------------------------------------------------------------------ delivery to the room

    @Test
    @DisplayName("The real result is delivered to the room after the run (FR4.5)")
    void realResultIsBroadcastToTheRoom() throws Exception {
        mockMvc.perform(run("python", "print('hello')", null))
                .andExpect(status().isOk());

        verify(broadcastService).broadcast(
                eq(ROOM_ID),
                eq(SESSION_ID),
                argThat((RunResponse response) ->
                        response != null
                                && "hello\n".equals(response.getStdout())
                                && response.getExit_code() == 0));
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder run(
            String language, String code, String stdin) throws Exception {

        Map<String, Object> file = new LinkedHashMap<>();
        file.put("name", "main.py");
        file.put("content", code);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "inline");
        source.put("files", List.of(file));
        source.put("entry", "main.py");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", REQUEST_ID);
        body.put("room_id", ROOM_ID);
        body.put("session_id", SESSION_ID);
        body.put("language", language);
        if (stdin != null) {
            body.put("stdin", stdin);
        }
        body.put("source", source);

        return post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private static boolean dockerSucceeds(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.addAll(List.of(args));

            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
