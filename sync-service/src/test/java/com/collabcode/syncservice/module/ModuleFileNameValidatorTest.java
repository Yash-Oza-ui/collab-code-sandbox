package com.collabcode.syncservice.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ModuleFileNameValidator} (DR4, FR20.1, US-39).
 * Verifies naming rules across all five supported languages (DR1).
 */
class ModuleFileNameValidatorTest {

    // -------------------------------------------------------------------------
    // 1. Python (.py)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Python accepts .py filenames with valid basenames")
    void python_validNames() {
        assertThatCode(() -> ModuleFileNameValidator.validate("main.py", "python")).doesNotThrowAnyException();
        assertThatCode(() -> ModuleFileNameValidator.validate("solver_v2.py", "python")).doesNotThrowAnyException();
        assertThatCode(() -> ModuleFileNameValidator.validate("utils.py", "python")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"main.js", "main.cpp", "Main.java", "main.c", "main.txt", "main", ".py"})
    @DisplayName("Python rejects invalid extensions and bare extension")
    void python_invalidExtensions(String name) {
        assertThatThrownBy(() -> ModuleFileNameValidator.validate(name, "python"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // 2. JavaScript (.js)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("JavaScript accepts .js filenames with valid basenames")
    void javascript_validNames() {
        assertThatCode(() -> ModuleFileNameValidator.validate("index.js", "javascript")).doesNotThrowAnyException();
        assertThatCode(() -> ModuleFileNameValidator.validate("app.helpers.js", "javascript")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"index.py", "index.ts", "index.jsx", "index.cpp", ".js"})
    @DisplayName("JavaScript rejects non-.js extensions and bare extension")
    void javascript_invalidExtensions(String name) {
        assertThatThrownBy(() -> ModuleFileNameValidator.validate(name, "javascript"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // 3. Java (.java)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Java accepts .java filenames with valid basenames")
    void java_validNames() {
        assertThatCode(() -> ModuleFileNameValidator.validate("Main.java", "java")).doesNotThrowAnyException();
        assertThatCode(() -> ModuleFileNameValidator.validate("SolverService.java", "java")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Main.class", "Main.py", "Main.kt", "Main", ".java"})
    @DisplayName("Java rejects non-.java extensions and bare extension")
    void java_invalidExtensions(String name) {
        assertThatThrownBy(() -> ModuleFileNameValidator.validate(name, "java"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // 4. C++ (.cpp, .cc, .cxx)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("C++ accepts .cpp, .cc, and .cxx filenames")
    void cpp_validNames() {
        assertThatCode(() -> ModuleFileNameValidator.validate("main.cpp", "cpp")).doesNotThrowAnyException();
        assertThatCode(() -> ModuleFileNameValidator.validate("solver.cc", "cpp")).doesNotThrowAnyException();
        assertThatCode(() -> ModuleFileNameValidator.validate("algo.cxx", "cpp")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"main.c", "main.py", "main.h", "main.hpp", "main.rs", ".cpp", ".cc", ".cxx"})
    @DisplayName("C++ rejects non-C++ extensions and bare extensions")
    void cpp_invalidExtensions(String name) {
        assertThatThrownBy(() -> ModuleFileNameValidator.validate(name, "cpp"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // 5. C (.c)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("C accepts .c filenames with valid basenames")
    void c_validNames() {
        assertThatCode(() -> ModuleFileNameValidator.validate("main.c", "c")).doesNotThrowAnyException();
        assertThatCode(() -> ModuleFileNameValidator.validate("tree_node.c", "c")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"main.cpp", "main.py", "main.h", "main", ".c"})
    @DisplayName("C rejects non-.c extensions and bare extension")
    void c_invalidExtensions(String name) {
        assertThatThrownBy(() -> ModuleFileNameValidator.validate(name, "c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // 6. Directory traversal & security boundaries (DR4)
    // -------------------------------------------------------------------------
    @ParameterizedTest
    @ValueSource(strings = {
            "../main.py",
            "dir/main.py",
            "dir\\main.py",
            "foo..bar.py",
            "/root/main.py",
            "./main.py"
    })
    @DisplayName("Rejects directory traversal characters and subpaths")
    void directoryTraversal_rejected(String traversalName) {
        assertThatThrownBy(() -> ModuleFileNameValidator.validate(traversalName, "python"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    @DisplayName("Rejects null and blank names")
    void blankNames_rejected(String blank) {
        assertThatThrownBy(() -> ModuleFileNameValidator.validate(blank, "python"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects null name and unsupported language")
    void nullAndUnsupported_rejected() {
        assertThatThrownBy(() -> ModuleFileNameValidator.validate(null, "python"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModuleFileNameValidator.validate("main.py", "ruby"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
