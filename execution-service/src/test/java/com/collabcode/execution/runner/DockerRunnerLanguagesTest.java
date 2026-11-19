package com.collabcode.execution.runner;

import com.collabcode.execution.config.ExecutionLanguageProperties;
import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for multi-language runner configuration and execution logic (DR1, US-14).
 * Verifies all 5 coursework languages in priority order: C++, Python, C, Java, JavaScript.
 */
class DockerRunnerLanguagesTest {

    private ExecutionLimitsProperties limits;
    private ExecutionLanguageProperties languageProperties;
    private DockerRunner runner;

    @BeforeEach
    void setUp() {
        limits = new ExecutionLimitsProperties();
        limits.setWallTimeSeconds(5);
        limits.setMemoryMb(128);
        limits.setPidsLimit(64);
        limits.setMaxOutputBytes(1024 * 1024);
        limits.setCompileTimeoutSeconds(10);
        limits.setMaxRequestBodyBytes(1024 * 1024);

        languageProperties = new ExecutionLanguageProperties();
        runner = new DockerRunner(limits, languageProperties);
    }

    @Test
    @DisplayName("All five DR1 languages are mapped to their pinned runtime images")
    void allFiveLanguagesMappedToPinnedImages() {
        Map<String, String> images = languageProperties.getImages();

        assertEquals("collab-sandbox-cpp:0.1.0", images.get("cpp"));
        assertEquals("collab-sandbox-python:0.1.0", images.get("python"));
        assertEquals("collab-sandbox-c:0.1.0", images.get("c"));
        assertEquals("collab-sandbox-java:0.1.0", images.get("java"));
        assertEquals("collab-sandbox-javascript:0.1.0", images.get("javascript"));

        assertTrue(languageProperties.isSupportedLanguage("cpp"));
        assertTrue(languageProperties.isSupportedLanguage("python"));
        assertTrue(languageProperties.isSupportedLanguage("c"));
        assertTrue(languageProperties.isSupportedLanguage("java"));
        assertTrue(languageProperties.isSupportedLanguage("javascript"));
    }

    @Test
    @DisplayName("Compiled vs interpreted languages are properly identified (NFR4.5)")
    void compiledVsInterpretedIdentification() {
        assertTrue(ExecutionLanguageProperties.isCompiledLanguage("cpp"));
        assertTrue(ExecutionLanguageProperties.isCompiledLanguage("c"));
        assertTrue(ExecutionLanguageProperties.isCompiledLanguage("java"));

        assertFalse(ExecutionLanguageProperties.isCompiledLanguage("python"));
        assertFalse(ExecutionLanguageProperties.isCompiledLanguage("javascript"));
        assertFalse(ExecutionLanguageProperties.isCompiledLanguage(null));
        assertFalse(ExecutionLanguageProperties.isCompiledLanguage("unknown"));
    }

    @Test
    @DisplayName("Unsupported language is rejected before container execution")
    void unsupportedLanguageRejected() {
        RunSpec spec = new RunSpec(
                "ruby",
                List.of(new RunFile("main.rb", "puts 1")),
                "main.rb",
                ""
        );

        ExecutionResult result = runner.execute(spec);

        assertEquals(2, result.getExitCode());
        assertEquals("non_zero_exit", result.getFailureKind());
        assertTrue(result.getStderr().contains("Unsupported language"));
    }

    @Test
    @DisplayName("Custom image overrides can be configured via ExecutionLanguageProperties (DR3)")
    void imageOverridesSupported() {
        languageProperties.setImages(Map.of("cpp", "custom-registry/cpp:1.0.0"));

        assertEquals("custom-registry/cpp:1.0.0", languageProperties.getImageForLanguage("cpp"));
        assertEquals("collab-sandbox-python:0.1.0", languageProperties.getImageForLanguage("python"));
    }

    @Test
    @DisplayName("Entry file not present in files list is rejected with validation error")
    void missingEntryFileRejected() {
        RunSpec spec = new RunSpec(
                "cpp",
                List.of(new RunFile("helper.cpp", "// helper")),
                "main.cpp",
                ""
        );

        ExecutionResult result = runner.execute(spec);

        assertEquals(2, result.getExitCode());
        assertTrue(result.getStderr().contains("Entry file was not included"));
    }
}
