
package com.collabcode.execution.controller;

import com.collabcode.execution.model.RunRequest;
import com.collabcode.execution.model.RunResponse;
import com.collabcode.execution.runner.DockerRunner;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/v1")
public class RunController {

    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("cpp", "python", "c", "java", "javascript");

    private final DockerRunner dockerRunner;

    public RunController(DockerRunner dockerRunner) {
        this.dockerRunner = dockerRunner;
    }

    @PostMapping("/runs")
    public ResponseEntity<RunResponse> run(
            @Valid @RequestBody RunRequest req
    ) throws Exception {

        String language = req.getLanguage();

        if (language == null || !SUPPORTED_LANGUAGES.contains(language)) {
            throw new UnsupportedLanguageException(language);
        }

        if (!"python".equals(language)) {
            RunResponse response = RunResponse.fromExecution(
                    req, "", "Runtime not implemented: " + language,
                    501, 0
            );

            return ResponseEntity
                    .status(HttpStatus.NOT_IMPLEMENTED)
                    .body(response);
        }

        DockerRunner.ExecutionResult result =
                dockerRunner.execute(req.getCode(), req.getStdin());

        RunResponse response = RunResponse.fromExecution(
                req,
                result.stdout(),
                result.stderr(),
                result.exitCode(),
                result.executionTimeMs(),
                result.timedOut(),
                result.outputTruncated(),
                result.oomKilled()
        );

        return ResponseEntity.ok(response);
    }

    public static class UnsupportedLanguageException
            extends RuntimeException {

        private final String language;

        public UnsupportedLanguageException(String language) {
            super("Unsupported language: " + language);
            this.language = language;
        }

        public String getLanguage() {
            return language;
        }
    }
}
