package com.tascode.codebridge.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tascode.codebridge.language.Language;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DockerSandboxExecutionServiceTest {
    @Test
    void rejectsOversizeSourceAndInputBeforeCallingDocker() {
        var sandbox = new DockerSandboxExecutionService("missing-docker-executable", Duration.ofSeconds(1));

        var sourceResult = sandbox.execute(Language.C, "x".repeat(1_000_001), "");
        var inputResult = sandbox.execute(Language.C, "int main() { return 0; }", "x".repeat(100_001));

        assertEquals(SandboxExecutionStatus.FAILED, sourceResult.status());
        assertEquals(SandboxExecutionStatus.FAILED, inputResult.status());
        assertTrue(sourceResult.message().contains("size limit"), sourceResult.message());
        assertTrue(inputResult.message().contains("size limit"), inputResult.message());
        assertTrue(sourceResult.exitCode().isEmpty());
        assertTrue(inputResult.exitCode().isEmpty());
    }

    @Test
    void javaSandboxSelectsPublicTopLevelTypeForSourceFileName() {
        assertEquals("Application.java", DockerSandboxExecutionService.javaSourceFileName(
                "class Helper {} public class Application {}"));
        assertEquals("Launcher.java", DockerSandboxExecutionService.javaSourceFileName(
                "class Outer { class Nested {} } public class Launcher {}"));
        assertEquals("Person.java", DockerSandboxExecutionService.javaSourceFileName(
                "/* class Fake */ public record Person(int age) {}"));
        assertEquals("Utility.java", DockerSandboxExecutionService.javaSourceFileName(
                "class Utility {} class Another {}"));
        assertEquals("TasCodeGenerated.java", DockerSandboxExecutionService.javaSourceFileName(
                "// class in a comment\n"));
    }

    @Test
    void javaSandboxRunsPackagedMainClassByItsFullyQualifiedName() {
        String source = "/* package fake; */\npackage demo.tools;\npublic class Runner {}";
        String mainClass = DockerSandboxExecutionService.javaMainClass(source,
                DockerSandboxExecutionService.javaSourceFileName(source));
        var command = DockerSandboxExecutionService.dockerCommand(Language.JAVA, Path.of("C:/sandbox"),
                "codebridge-packaged-java", "Runner.java", mainClass, "docker");

        assertEquals("demo.tools.Runner", mainClass);
        assertTrue(String.join(" ", command).contains("exec java -cp /tmp/classes demo.tools.Runner"));
    }

    @Test
    void outputCaptureMarksTruncationAndKeepsUnicodeCodePointsWhole() {
        String exactLimit = "x".repeat(64_000);
        String overLimit = "x".repeat(64_001);
        String unicodeOverLimit = "x".repeat(63_999) + "😀";

        String exactResult = DockerSandboxExecutionService.readLimited(
                new ByteArrayInputStream(exactLimit.getBytes(StandardCharsets.UTF_8)));
        String truncatedResult = DockerSandboxExecutionService.readLimited(
                new ByteArrayInputStream(overLimit.getBytes(StandardCharsets.UTF_8)));
        String unicodeResult = DockerSandboxExecutionService.readLimited(
                new ByteArrayInputStream(unicodeOverLimit.getBytes(StandardCharsets.UTF_8)));

        assertEquals(exactLimit, exactResult);
        assertTrue(truncatedResult.endsWith(DockerSandboxExecutionService.OUTPUT_TRUNCATION_MARKER));
        assertEquals(64_000 + DockerSandboxExecutionService.OUTPUT_TRUNCATION_MARKER.length(), truncatedResult.length());
        assertTrue(unicodeResult.endsWith(DockerSandboxExecutionService.OUTPUT_TRUNCATION_MARKER));
        String retainedUnicode = unicodeResult.substring(0,
                unicodeResult.length() - DockerSandboxExecutionService.OUTPUT_TRUNCATION_MARKER.length());
        assertFalse(retainedUnicode.isEmpty());
        assertFalse(Character.isHighSurrogate(retainedUnicode.charAt(retainedUnicode.length() - 1)));
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void processTimeoutStillAppliesWhileChildDoesNotReadLargeStandardInput() {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        List<String> command = List.of(javaExecutable, "-cp", System.getProperty("java.class.path"),
                NonReadingStdinProcess.class.getName());

        DockerSandboxExecutionService.ProcessResult result = DockerSandboxExecutionService.runProcess(
                command, "x".repeat(1_000_000), Duration.ofMillis(300));

        assertTrue(result.timedOut(), "Process should time out even while the stdin pipe is blocked.");
        assertNull(result.failure());
    }

    @Test
    void dockerCommandDisablesNetworkAndConstrainsContainer() {
        var command = DockerSandboxExecutionService.dockerCommand(
                Language.C, Path.of("C:/sandbox"), "codebridge-test", "program.c", "docker");
        String arguments = String.join(" ", command);

        assertTrue(arguments.contains("--network none"));
        assertTrue(arguments.contains("--memory 128m"));
        assertTrue(arguments.contains("--memory-swap 128m"));
        assertTrue(arguments.contains("--cpus 1"));
        assertTrue(arguments.contains("--pids-limit 32"));
        assertTrue(arguments.contains("--read-only"));
        assertTrue(arguments.contains("--cap-drop ALL"));
        assertTrue(arguments.contains("--security-opt no-new-privileges"));
        assertTrue(arguments.contains("--pull=never"));
        assertFalse(arguments.contains("int main"));
    }

    @Test
    void eachLanguageCompilesAndExecutesInsideTheContainerCommand() {
        String c = String.join(" ", DockerSandboxExecutionService.dockerCommand(
                Language.C, Path.of("C:/sandbox"), "codebridge-c", "program.c", "docker"));
        String cpp = String.join(" ", DockerSandboxExecutionService.dockerCommand(
                Language.CPP, Path.of("C:/sandbox"), "codebridge-cpp", "program.cpp", "docker"));
        String java = String.join(" ", DockerSandboxExecutionService.dockerCommand(
                Language.JAVA, Path.of("C:/sandbox"), "codebridge-java", "TasCodeGenerated.java", "docker"));

        assertTrue(c.contains("gcc -std=c11"));
        assertTrue(cpp.contains("g++ -std=c++20"));
        assertTrue(java.contains("javac --release 21 -proc:none"));
        assertTrue(java.contains("exec java -cp /tmp/classes TasCodeGenerated"));
        String javaExample = String.join(" ", DockerSandboxExecutionService.dockerCommand(
                Language.JAVA, Path.of("C:/sandbox"), "codebridge-java-source", "JavaExample.java", "docker"));
        assertTrue(javaExample.contains("/workspace/JavaExample.java"));
        assertTrue(javaExample.contains("exec java -cp /tmp/classes JavaExample"));
        assertTrue(c.contains("CODEBRIDGE_COMPILER_FAILED"));
        assertTrue(cpp.contains("CODEBRIDGE_COMPILER_FAILED"));
        assertTrue(java.contains("CODEBRIDGE_COMPILER_FAILED"));
        assertTrue(c.contains("/workspace/program.c"));
        assertTrue(cpp.contains("/workspace/program.cpp"));
        assertTrue(java.contains("/workspace/TasCodeGenerated.java"));
    }
}
