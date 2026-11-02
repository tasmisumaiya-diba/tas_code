package com.tascode.codebridge.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tascode.codebridge.language.Language;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DockerSandboxExecutionServiceTest {
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
