package com.collabcode.execution.controller;

import com.collabcode.execution.analysis.AnalysisResultMessage;
import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.model.RunResponse;
import com.collabcode.execution.model.RunSpec;
import com.collabcode.execution.permission.StubPermissionLookupService;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import com.collabcode.execution.snapshot.StubSavedVersionReader;
import com.collabcode.execution.store.RunResultStore;
import com.collabcode.execution.store.StoredRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit & acceptance tests for US-49: Keep a run result private or share it (FR27, NFR9, T-17 V2.10, Issue #213).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunControllerShareTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RunResultStore runResultStore;

    @MockBean
    private CodeRunner codeRunner;

    @MockBean
    private RoomBroadcastService broadcastService;

    @SpyBean
    private StubSavedVersionReader savedVersionReader;

    @SpyBean
    private StubPermissionLookupService permissionLookupService;

    private static final String REQUEST_ID = "00000000-0000-4000-8000-000000000049";
    private static final String ROOM_ID    = "room-open-us49";
    private static final String RUNNER_SESSION_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_SESSION_ID  = "22222222-2222-4222-8222-222222222222";

    @BeforeEach
    void setUp() {
        runResultStore.clear();
        savedVersionReader.reset();
        permissionLookupService.reset();
        when(codeRunner.execute(any(RunSpec.class)))
                .thenReturn(new ExecutionResult("result output\n", "", 0, 10.0, 5.0,
                        false, false, null, false, null));
    }

    @AfterEach
    void tearDown() {
        runResultStore.clear();
        savedVersionReader.reset();
        permissionLookupService.reset();
    }

    @Test
    @DisplayName("Share returns 422 INVALID_REQUEST when request_id path variable is malformed")
    void share_malformedRequestId_returns422() throws Exception {
        mockMvc.perform(post("/v1/runs/not-a-uuid/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"" + RUNNER_SESSION_ID + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Field 'request_id' is missing or invalid."))
                .andExpect(jsonPath("$.request_id").doesNotExist());
    }

    @Test
    @DisplayName("Share returns 422 INVALID_REQUEST when session_id in body is missing or invalid")
    void share_missingOrInvalidSessionId_returns422() throws Exception {
        mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Field 'session_id' is missing or invalid."))
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID));

        mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"bad-uuid\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Field 'session_id' is missing or invalid."))
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID));
    }

    @Test
    @DisplayName("Share returns 404 RUN_NOT_FOUND when run does not exist")
    void share_unknownRun_returns404() throws Exception {
        mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"" + RUNNER_SESSION_ID + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"))
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID));
    }

    @Test
    @DisplayName("Share returns 403 NOT_RUNNER when called by a different session")
    void share_differentSession_returns403() throws Exception {
        RunResponse runResp = new RunResponse();
        runResp.setRequest_id(REQUEST_ID);
        runResp.setVisibility("private");
        runResultStore.save(new StoredRun(REQUEST_ID, ROOM_ID, RUNNER_SESSION_ID, "private", runResp, Instant.now()));

        mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"" + OTHER_SESSION_ID + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_RUNNER"))
                .andExpect(jsonPath("$.error.message").value("Only the runner may share this run result."))
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID));
    }

    @Test
    @DisplayName("Share returns 403 NOT_RUNNER when runner session is no longer in room")
    void share_removedSession_returns403() throws Exception {
        String removedSessionId = StubPermissionLookupService.NOT_IN_ROOM_SESSION_ID;
        RunResponse runResp = new RunResponse();
        runResp.setRequest_id(REQUEST_ID);
        runResp.setVisibility("private");
        runResultStore.save(new StoredRun(REQUEST_ID, ROOM_ID, removedSessionId, "private", runResp, Instant.now()));

        mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"" + removedSessionId + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_RUNNER"))
                .andExpect(jsonPath("$.error.message").value("Session is no longer in the room."))
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID));
    }

    @Test
    @DisplayName("Share transitions private run to room, broadcasts to room, and forwards queued analysis messages")
    void share_privateRun_broadcastsToRoomAndForwardsAnalysis() throws Exception {
        RunResponse runResp = new RunResponse();
        runResp.setRequest_id(REQUEST_ID);
        runResp.setRoom_id(ROOM_ID);
        runResp.setVisibility("private");
        runResp.setStdout("hello open mode\n");

        StoredRun stored = new StoredRun(REQUEST_ID, ROOM_ID, RUNNER_SESSION_ID, "private", runResp, Instant.now());
        AnalysisResultMessage analysisMsg = new AnalysisResultMessage(
                REQUEST_ID, "ana-99", ROOM_ID, "complexity", "ok",
                new AnalysisResultMessage.AIStep("ok", null),
                Collections.emptyMap(), "Complexity: O(n)"
        );
        stored.addAnalysisMessage(analysisMsg);
        runResultStore.save(stored);

        mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"" + RUNNER_SESSION_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.visibility").value("room"));

        // Verify stored visibility was updated
        assertThat(runResultStore.getVisibility(REQUEST_ID)).isEqualTo("room");
        assertThat(runResp.getVisibility()).isEqualTo("room");

        // Verify broadcastService was called with null sessionId (audience: room)
        ArgumentCaptor<RunResponse> captor = ArgumentCaptor.forClass(RunResponse.class);
        verify(broadcastService, times(1)).broadcast(eq(ROOM_ID), isNull(), captor.capture());
        assertThat(captor.getValue().getVisibility()).isEqualTo("room");

        // Verify analysis message was forwarded to room
        verify(broadcastService, times(1)).broadcastAnalysisResult(
                eq(ROOM_ID), isNull(), eq("room"), eq(analysisMsg));
    }

    @Test
    @DisplayName("Sharing twice is harmless: returns 200 and does not broadcast again")
    void share_idempotent_secondCallDoesNothing() throws Exception {
        RunResponse runResp = new RunResponse();
        runResp.setRequest_id(REQUEST_ID);
        runResp.setRoom_id(ROOM_ID);
        runResp.setVisibility("private");

        StoredRun stored = new StoredRun(REQUEST_ID, ROOM_ID, RUNNER_SESSION_ID, "private", runResp, Instant.now());
        runResultStore.save(stored);

        // First share
        mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"" + RUNNER_SESSION_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("room"));

        // Second share
        mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"" + RUNNER_SESSION_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("room"));

        // broadcastService must only have been invoked once
        verify(broadcastService, times(1)).broadcast(eq(ROOM_ID), isNull(), any());
    }

    @Test
    @DisplayName("In Pair mode, share is a harmless no-op returning 200 room")
    void share_pairModeRun_isNoOp() throws Exception {
        RunResponse runResp = new RunResponse();
        runResp.setRequest_id(REQUEST_ID);
        runResp.setRoom_id(ROOM_ID);
        runResp.setVisibility("room");

        StoredRun stored = new StoredRun(REQUEST_ID, ROOM_ID, RUNNER_SESSION_ID, "room", runResp, Instant.now());
        runResultStore.save(stored);

        mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"" + RUNNER_SESSION_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.visibility").value("room"));

        // No additional broadcast
        verify(broadcastService, never()).broadcast(any(), any(), any());
    }

    @Test
    @DisplayName("Concurrency: Simultaneous share requests (e.g. from multiple tabs) broadcast run to room exactly once")
    void share_concurrentShareCallsFromMultipleTabs_broadcastsRunOnlyOnce() throws Exception {
        RunResponse runResp = new RunResponse();
        runResp.setRequest_id(REQUEST_ID);
        runResp.setRoom_id(ROOM_ID);
        runResp.setVisibility("private");
        runResp.setStdout("parallel run output\n");

        StoredRun stored = new StoredRun(REQUEST_ID, ROOM_ID, RUNNER_SESSION_ID, "private", runResp, Instant.now());
        runResultStore.save(stored);

        int threads = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);

        java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    var result = mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"session_id\":\"" + RUNNER_SESSION_ID + "\"}"))
                            .andReturn();
                    return result.getResponse().getStatus();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        for (var f : futures) {
            int status = f.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(status).isEqualTo(200);
        }
        executor.shutdown();

        // Exactly one broadcast of the run result to the room despite concurrent requests
        verify(broadcastService, times(1)).broadcast(eq(ROOM_ID), isNull(), any());
        assertThat(runResultStore.getVisibility(REQUEST_ID)).isEqualTo("room");
    }

    @Test
    @DisplayName("Concurrency: Concurrent analysis completion during share never produces duplicate room broadcasts")
    void share_concurrentAnalysisCompletionDuringShare_noDuplicateRoomBroadcasts() throws Exception {
        RunResponse runResp = new RunResponse();
        runResp.setRequest_id(REQUEST_ID);
        runResp.setRoom_id(ROOM_ID);
        runResp.setVisibility("private");

        StoredRun stored = new StoredRun(REQUEST_ID, ROOM_ID, RUNNER_SESSION_ID, "private", runResp, Instant.now());
        runResultStore.save(stored);

        int msgCount = 20;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(msgCount + 1);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(msgCount + 1);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);

        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        // Multiple analysis messages completing concurrently
        for (int i = 0; i < msgCount; i++) {
            final String aid = "analysis-conc-" + i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    AnalysisResultMessage msg = new AnalysisResultMessage(
                            REQUEST_ID, aid, ROOM_ID, "complexity", "ok",
                            new AnalysisResultMessage.AIStep("ok", null),
                            Collections.emptyMap(), "Analysis result " + aid
                    );
                    StoredRun.AnalysisBroadcastAction action = stored.registerAnalysisMessage(msg);
                    if (action == StoredRun.AnalysisBroadcastAction.BROADCAST_ROOM) {
                        broadcastService.broadcastAnalysisResult(ROOM_ID, null, "room", msg);
                    } else if (action == StoredRun.AnalysisBroadcastAction.BROADCAST_PRIVATE) {
                        broadcastService.broadcastAnalysisResult(ROOM_ID, RUNNER_SESSION_ID, "private", msg);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Concurrent share request
        futures.add(executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                mockMvc.perform(post("/v1/runs/" + REQUEST_ID + "/share")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"session_id\":\"" + RUNNER_SESSION_ID + "\"}"))
                        .andExpect(status().isOk());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));

        readyLatch.await();
        startLatch.countDown();

        for (var f : futures) {
            f.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Check argument captor for all broadcastAnalysisResult calls
        ArgumentCaptor<AnalysisResultMessage> captor = ArgumentCaptor.forClass(AnalysisResultMessage.class);
        ArgumentCaptor<String> visCaptor = ArgumentCaptor.forClass(String.class);
        verify(broadcastService, atLeastOnce()).broadcastAnalysisResult(
                eq(ROOM_ID), nullable(String.class), visCaptor.capture(), captor.capture());

        // For every message that was broadcast to room, it must appear in room broadcasts at most once
        java.util.List<String> roomBroadcastIds = new java.util.ArrayList<>();
        var messages = captor.getAllValues();
        var visibilities = visCaptor.getAllValues();
        for (int i = 0; i < messages.size(); i++) {
            if ("room".equals(visibilities.get(i))) {
                roomBroadcastIds.add(messages.get(i).getAnalysis_id());
            }
        }

        java.util.Set<String> uniqueRoomIds = new java.util.HashSet<>(roomBroadcastIds);
        assertThat(roomBroadcastIds).hasSameSizeAs(uniqueRoomIds);
    }
}
