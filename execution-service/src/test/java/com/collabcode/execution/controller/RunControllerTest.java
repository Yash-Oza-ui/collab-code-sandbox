package com.collabcode.execution.controller;

import com.collabcode.execution.model.RunRequest;
import com.collabcode.execution.model.RunResponse;
import com.collabcode.execution.runner.DockerRunner;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunControllerTest {

    @Test
    void run_python_returnsExecutionResult() throws Exception {
        DockerRunner dockerRunner = mock(DockerRunner.class);

        when(dockerRunner.execute("print('hello')", ""))
                .thenReturn(new DockerRunner.ExecutionResult(
                        "hello\n", "", 0, 25
                ));

        RunController controller = new RunController(dockerRunner);

        RunRequest request = new RunRequest();
        request.setLanguage("python");
        request.setCode("print('hello')");

        ResponseEntity<RunResponse> response = controller.run(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("hello\n", response.getBody().getStdout());
        assertEquals("", response.getBody().getStderr());
        assertEquals(0, response.getBody().getExit_code());
        verify(dockerRunner).execute("print('hello')", "");
    }

    @Test
    void run_supportedButUnavailableLanguages_returns501() throws Exception {
        DockerRunner dockerRunner = mock(DockerRunner.class);
        RunController controller = new RunController(dockerRunner);

        for (String language : new String[]{"cpp", "c", "java", "javascript"}) {
            RunRequest request = new RunRequest();
            request.setLanguage(language);
            request.setCode("test");

            ResponseEntity<RunResponse> response = controller.run(request);

            assertEquals(501, response.getStatusCode().value(), language);
        }

        verifyNoInteractions(dockerRunner);
    }

    @Test
    void run_unsupportedLanguage_throws422() {
        DockerRunner dockerRunner = mock(DockerRunner.class);
        RunController controller = new RunController(dockerRunner);

        RunRequest request = new RunRequest();
        request.setLanguage("ruby");
        request.setCode("puts 'hello'");

        assertThrows(
                RunController.UnsupportedLanguageException.class,
                () -> controller.run(request)
        );
    }
}
