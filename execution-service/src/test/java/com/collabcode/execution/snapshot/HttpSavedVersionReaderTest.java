package com.collabcode.execution.snapshot;

import com.collabcode.execution.exception.ModuleNotFoundException;
import com.collabcode.execution.exception.ModuleNotRunnableException;
import com.collabcode.execution.exception.ModuleNotSavedException;
import com.collabcode.execution.exception.RoomNotFoundException;
import com.collabcode.execution.exception.RoomServiceUnavailableException;
import com.collabcode.execution.model.SavedSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSavedVersionReaderTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> lastReceivedToken = new AtomicReference<>();
    private final AtomicReference<String> lastReceivedBody = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/internal/rooms/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastReceivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                lastReceivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Successful snapshot read returns SnapshotResult with modules and sends internal token")
    void readSnapshot_targetModule_success() {
        responseStatus = 200;
        responseBody = """
                {
                  "room_id": "test-room",
                  "entry": "main.py",
                  "modules": [
                    {
                      "module_id": "mod_123",
                      "name": "main.py",
                      "content": "print('hello from saved')",
                      "version": 1,
                      "saved_by": { "member_id": "m_1", "display_name": "Ada" },
                      "owner": { "member_id": "m_1", "display_name": "Ada" },
                      "saved_at": "2026-11-01T10:00:00Z"
                    }
                  ]
                }
                """;

        HttpSavedVersionReader reader = new HttpSavedVersionReader(
                objectMapper,
                "http://127.0.0.1:" + port,
                "secret-internal-token"
        );

        SavedSource source = new SavedSource("module", "mod_123");
        SnapshotResult result = reader.readSnapshot("test-room", source);

        assertThat(result).isNotNull();
        assertThat(result.getRoom_id()).isEqualTo("test-room");
        assertThat(result.getEntry()).isEqualTo("main.py");
        assertThat(result.getModules()).hasSize(1);
        SnapshotModule mod = result.getModules().get(0);
        assertThat(mod.getModule_id()).isEqualTo("mod_123");
        assertThat(mod.getName()).isEqualTo("main.py");
        assertThat(mod.getContent()).isEqualTo("print('hello from saved')");
        assertThat(mod.getVersion()).isEqualTo(1);
        assertThat(mod.getSaved_by().getDisplay_name()).isEqualTo("Ada");
        assertThat(mod.getSaved_at()).isEqualTo("2026-11-01T10:00:00Z");

        assertThat(lastReceivedToken.get()).isEqualTo("secret-internal-token");
        assertThat(lastReceivedBody.get()).contains("\"target\":\"module\"").contains("\"module_id\":\"mod_123\"");
    }

    @Test
    @DisplayName("Never-saved module returns 422 MODULE_NOT_SAVED -> throws ModuleNotSavedException")
    void readSnapshot_moduleNotSaved_throwsModuleNotSavedException() {
        responseStatus = 422;
        responseBody = """
                {
                  "error": {
                    "code": "MODULE_NOT_SAVED",
                    "message": "Module 'main.py' has never been saved. Save it first."
                  }
                }
                """;

        HttpSavedVersionReader reader = new HttpSavedVersionReader(
                objectMapper,
                "http://127.0.0.1:" + port,
                "secret-token"
        );

        SavedSource source = new SavedSource("module", "mod_never_saved");
        assertThatThrownBy(() -> reader.readSnapshot("test-room", source))
                .isInstanceOf(ModuleNotSavedException.class)
                .hasMessage("Module 'main.py' has never been saved. Save it first.");
    }

    @Test
    @DisplayName("Module not runnable returns 422 MODULE_NOT_RUNNABLE -> throws ModuleNotRunnableException")
    void readSnapshot_moduleNotRunnable_throwsModuleNotRunnableException() {
        responseStatus = 422;
        responseBody = """
                {
                  "error": {
                    "code": "MODULE_NOT_RUNNABLE",
                    "message": "Module 'calc.py' is not marked runnable."
                  }
                }
                """;

        HttpSavedVersionReader reader = new HttpSavedVersionReader(
                objectMapper,
                "http://127.0.0.1:" + port,
                "secret-token"
        );

        SavedSource source = new SavedSource("module", "mod_not_runnable");
        assertThatThrownBy(() -> reader.readSnapshot("test-room", source))
                .isInstanceOf(ModuleNotRunnableException.class)
                .hasMessage("Module 'calc.py' is not marked runnable.");
    }

    @Test
    @DisplayName("Module not found returns 404 MODULE_NOT_FOUND -> throws ModuleNotFoundException")
    void readSnapshot_moduleNotFound_throwsModuleNotFoundException() {
        responseStatus = 404;
        responseBody = """
                {
                  "error": {
                    "code": "MODULE_NOT_FOUND",
                    "message": "Module not found: mod_unknown"
                  }
                }
                """;

        HttpSavedVersionReader reader = new HttpSavedVersionReader(
                objectMapper,
                "http://127.0.0.1:" + port,
                "secret-token"
        );

        SavedSource source = new SavedSource("module", "mod_unknown");
        assertThatThrownBy(() -> reader.readSnapshot("test-room", source))
                .isInstanceOf(ModuleNotFoundException.class)
                .hasMessage("Module not found: mod_unknown");
    }

    @Test
    @DisplayName("Room not found returns 404 ROOM_NOT_FOUND -> throws RoomNotFoundException")
    void readSnapshot_roomNotFound_throwsRoomNotFoundException() {
        responseStatus = 404;
        responseBody = """
                {
                  "error": {
                    "code": "ROOM_NOT_FOUND",
                    "message": "Room not found: bad-room"
                  }
                }
                """;

        HttpSavedVersionReader reader = new HttpSavedVersionReader(
                objectMapper,
                "http://127.0.0.1:" + port,
                "secret-token"
        );

        SavedSource source = new SavedSource("module", "mod_1");
        assertThatThrownBy(() -> reader.readSnapshot("bad-room", source))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("Room not found: bad-room");
    }

    @Test
    @DisplayName("Service unavailable 503 -> throws RoomServiceUnavailableException")
    void readSnapshot_serviceUnavailable_throwsRoomServiceUnavailableException() {
        responseStatus = 503;
        responseBody = """
                {
                  "error": {
                    "code": "ROOM_SERVICE_UNAVAILABLE",
                    "message": "Service unavailable."
                  }
                }
                """;

        HttpSavedVersionReader reader = new HttpSavedVersionReader(
                objectMapper,
                "http://127.0.0.1:" + port,
                "secret-token"
        );

        SavedSource source = new SavedSource("module", "mod_1");
        assertThatThrownBy(() -> reader.readSnapshot("test-room", source))
                .isInstanceOf(RoomServiceUnavailableException.class);
    }

    @Test
    @DisplayName("Unreachable server fails closed -> throws RoomServiceUnavailableException")
    void readSnapshot_unreachableServer_throwsRoomServiceUnavailableException() {
        // Stop server immediately to make port unreachable
        server.stop(0);

        HttpSavedVersionReader reader = new HttpSavedVersionReader(
                objectMapper,
                "http://127.0.0.1:" + port,
                "secret-token"
        );

        SavedSource source = new SavedSource("module", "mod_1");
        assertThatThrownBy(() -> reader.readSnapshot("test-room", source))
                .isInstanceOf(RoomServiceUnavailableException.class)
                .hasMessageContaining("Room service is unavailable");
    }
}
