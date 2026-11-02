package com.tascode.codebridge.frontend.clang;

import com.tascode.codebridge.language.Language;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClangSemanticFrontendTest {
    @Test void dockerCommandIsDirectAndAppliesFrontendIsolationLimits() {
        List<String> command = ClangSemanticFrontend.dockerCommand("docker", ClangSemanticFrontend.DEFAULT_IMAGE,
                "tascode-clang-example", "C:/work/input.c", "input.c", Language.C, "c17");
        assertEquals("docker", command.getFirst());
        assertTrue(command.contains("--network=none"));
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--memory=768m"));
        assertTrue(command.contains("--pids-limit=64"));
        assertTrue(command.contains("--pull=never"));
        assertFalse(command.contains("sh"));
        assertEquals("c17", command.get(command.size() - 2));
        assertEquals("/work/input.c", command.getLast());
    }

    @Test void distinguishesDockerDaemonErrorsFromMissingImageErrors() {
        assertTrue(ClangSemanticFrontend.dockerUnavailable("open //./pipe/dockerDesktopLinuxEngine: Access is denied"));
        assertFalse(ClangSemanticFrontend.dockerUnavailable("Error response from daemon: No such image"));
    }

    @Test void semanticResultIsJsonSerializableForTheAnalyzeEndpoint() throws Exception {
        ClangSemanticResult result = new ClangSemanticResult(Optional.empty(), List.of());
        String json = new ClangSemanticFrontend("docker", ClangSemanticFrontend.DEFAULT_IMAGE, Duration.ofSeconds(1)).encode(result);
        assertTrue(json.contains("\"model\":null"), json);
        assertTrue(json.contains("\"diagnostics\":[]"), json);
    }
}
