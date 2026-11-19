package com.collabcode.execution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps each supported programming language to its sandboxed Docker runtime image (DR1, DR3, US-14).
 * Values are read from configuration (application.yml under app.execution.images).
 *
 * Supported languages and testing priority order (DR1):
 *   1. C++ ("cpp")
 *   2. Python ("python")
 *   3. C ("c")
 *   4. Java ("java")
 *   5. JavaScript ("javascript")
 */
@Component
@ConfigurationProperties(prefix = "app.execution")
public class ExecutionLanguageProperties {

    public static final Map<String, String> DEFAULT_IMAGES = Map.of(
            "cpp", "collab-sandbox-cpp:0.1.0",
            "python", "collab-sandbox-python:0.2.0",
            "c", "collab-sandbox-c:0.1.0",
            "java", "collab-sandbox-java:0.1.0",
            "javascript", "collab-sandbox-javascript:0.1.0"
    );

    private static final Set<String> COMPILED_LANGUAGES = Set.of("cpp", "c", "java");

    private Map<String, String> images = new HashMap<>(DEFAULT_IMAGES);

    public Map<String, String> getImages() {
        return Collections.unmodifiableMap(images);
    }

    public void setImages(Map<String, String> images) {
        if (images != null) {
            this.images.putAll(images);
        }
    }

    public String getImageForLanguage(String language) {
        if (language == null) {
            return null;
        }
        return images.get(language.toLowerCase(Locale.ROOT));
    }

    public boolean isSupportedLanguage(String language) {
        if (language == null) {
            return false;
        }
        return images.containsKey(language.toLowerCase(Locale.ROOT));
    }

    public static boolean isCompiledLanguage(String language) {
        if (language == null) {
            return false;
        }
        return COMPILED_LANGUAGES.contains(language.toLowerCase(Locale.ROOT));
    }
}
