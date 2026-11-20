package com.collabcode.syncservice.module;

import java.util.Locale;
import java.util.Set;

/**
 * Validates module file names and extensions against the room's language (DR4, FR20.1, US-39).
 *
 * <p>Matches the naming rules established in {@code execution-service/runtime-images/README.md}:
 * <ul>
 *   <li>File names must be plain names with no directory traversal characters ({@code /}, {@code \}, {@code ..}).</li>
 *   <li>File names cannot be bare extensions or hidden files (e.g. {@code .py}).</li>
 *   <li>File extensions must strictly match the workspace programming language:
 *     <ul>
 *       <li>{@code cpp}: {@code .cpp}, {@code .cc}, {@code .cxx}</li>
 *       <li>{@code python}: {@code .py}</li>
 *       <li>{@code c}: {@code .c}</li>
 *       <li>{@code java}: {@code .java}</li>
 *       <li>{@code javascript}: {@code .js}</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class ModuleFileNameValidator {

    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("cpp", "python", "c", "java", "javascript");

    private ModuleFileNameValidator() {}

    /**
     * Validates that {@code name} is a valid file name for {@code language}.
     *
     * @param name filename to validate
     * @param language the workspace programming language (DR1)
     * @throws IllegalArgumentException if the name is null, blank, contains traversal characters, or has wrong extension
     */
    public static void validate(String name, String language) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Field 'name' is missing or invalid.");
        }

        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException(
                    "File name '" + name + "' is invalid. Plain file name with language extension required; directory traversal is forbidden.");
        }

        if (name.startsWith(".")) {
            throw new IllegalArgumentException(
                    "File name '" + name + "' is invalid. Hidden files and bare extensions are not allowed.");
        }

        if (language == null || !SUPPORTED_LANGUAGES.contains(language.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Language '" + language + "' is not supported.");
        }

        if (!hasValidExtension(name, language)) {
            throw new IllegalArgumentException(
                    "File name '" + name + "' does not match language '" + language + "'.");
        }
    }

    /**
     * Returns true if {@code fileName} ends with a valid extension for {@code language}.
     */
    public static boolean hasValidExtension(String fileName, String language) {
        if (fileName == null || language == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "python" -> lower.endsWith(".py") && lower.length() > 3;
            case "javascript" -> lower.endsWith(".js") && lower.length() > 3;
            case "java" -> lower.endsWith(".java") && lower.length() > 5;
            case "cpp" -> (lower.endsWith(".cpp") && lower.length() > 4)
                    || (lower.endsWith(".cc") && lower.length() > 3)
                    || (lower.endsWith(".cxx") && lower.length() > 4);
            case "c" -> lower.endsWith(".c") && lower.length() > 2;
            default -> false;
        };
    }
}
