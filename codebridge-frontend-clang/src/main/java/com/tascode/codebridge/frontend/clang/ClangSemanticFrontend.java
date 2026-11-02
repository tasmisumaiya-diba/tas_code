package com.tascode.codebridge.frontend.clang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.language.Language;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs Clang/libclang semantic analysis inside a restricted, networkless Docker container. */
public final class ClangSemanticFrontend {
    public static final String DEFAULT_IMAGE = "tascode-clang-frontend:1";
    private static final int MAX_SOURCE_CHARS = 1_000_000;
    private static final int MAX_AST_BYTES = 32 * 1024 * 1024;
    private final String dockerExecutable;
    private final String image;
    private final Duration timeout;
    private final ClangAstJsonDecoder decoder = new ClangAstJsonDecoder();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());

    public ClangSemanticFrontend() { this("docker", DEFAULT_IMAGE, Duration.ofSeconds(20)); }

    public ClangSemanticFrontend(String dockerExecutable, String image, Duration timeout) {
        this.dockerExecutable = Objects.requireNonNull(dockerExecutable);
        this.image = Objects.requireNonNull(image);
        this.timeout = Objects.requireNonNull(timeout);
        if (dockerExecutable.isBlank() || image.isBlank() || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Docker executable, frontend image, and positive timeout are required.");
        }
    }

    public String encode(ClangSemanticResult result) throws IOException { return mapper.writeValueAsString(result); }

    public ClangSemanticResult analyze(Language language, String source) {
        if (language != Language.C && language != Language.CPP) return failure("CB-CLANG-LANGUAGE-UNSUPPORTED", "Clang frontend accepts C or C++ input.");
        if (source == null || source.isBlank()) return failure("CB-CLANG-SOURCE-EMPTY", "Source code is empty.");
        if (source.length() > MAX_SOURCE_CHARS) return failure("CB-CLANG-SOURCE-LIMIT", "Source exceeds the Clang frontend size limit.");
        ProcessResult imageCheck = run(List.of(dockerExecutable, "image", "inspect", image), Duration.ofSeconds(5), null);
        if (imageCheck.failure != null) return failure("CB-CLANG-DOCKER-UNAVAILABLE", "Docker is not reachable: " + imageCheck.failure);
        if (imageCheck.timedOut) return failure("CB-CLANG-DOCKER-UNAVAILABLE", "Docker image inspection timed out.");
        if (imageCheck.exitCode != 0) {
            if (dockerUnavailable(imageCheck.stderr)) return failure("CB-CLANG-DOCKER-UNAVAILABLE", "Docker is not reachable: " + imageCheck.stderr.trim());
            return failure("CB-CLANG-IMAGE-UNAVAILABLE", "Build the local Clang frontend image with: docker build -f codebridge-frontend-clang/docker/Dockerfile -t " + image + " codebridge-frontend-clang/docker");
        }
        Path folder = null;
        String container = "tascode-clang-" + UUID.randomUUID().toString().replace("-", "");
        try {
            folder = Files.createTempDirectory("tascode-clang-front-");
            String fileName = language == Language.C ? "input.c" : "input.cpp";
            Path sourcePath = folder.resolve(fileName);
            Files.writeString(sourcePath, source, StandardCharsets.UTF_8);
            String absolute = sourcePath.toAbsolutePath().toString().replace('\\', '/');
            String standard = language == Language.C ? "c17" : "c++23";
            List<String> command = dockerCommand(dockerExecutable, image, container, absolute, fileName, language, standard);
            ProcessResult execution = run(command, timeout, container);
            if (execution.failure != null) return failure("CB-CLANG-DOCKER-UNAVAILABLE", "Clang frontend could not start: " + execution.failure);
            if (execution.timedOut) {
                killContainer(container);
                return failure("CB-CLANG-TIMEOUT", "Clang semantic analysis exceeded its time limit.");
            }
            if (execution.truncated) return failure("CB-CLANG-AST-LIMIT", "Clang semantic AST exceeded the output size limit.");
            String json = execution.stdout.strip();
            if (json.isEmpty()) return failure("CB-CLANG-FRONTEND-FAILED", firstNonBlank(execution.stderr, "Clang frontend returned no AST."));
            try {
                ClangAstJsonDecoder.DecodedFrontendDocument decoded = decoder.decode(json);
                List<Diagnostic> diagnostics = new ArrayList<>(decoded.diagnostics());
                if (execution.exitCode != 0 && dockerUnavailable(execution.stderr)) return failure("CB-CLANG-DOCKER-UNAVAILABLE", "Docker is not reachable: " + execution.stderr.trim());
                if (execution.exitCode != 0 && diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR)) {
                    diagnostics.add(Diagnostic.withoutLocation(Severity.ERROR, "CB-CLANG-FRONTEND-FAILED",
                            firstNonBlank(execution.stderr, "Clang frontend exited with status " + execution.exitCode + ".")));
                }
                return new ClangSemanticResult(Optional.of(decoded.model()), diagnostics);
            } catch (IOException malformed) {
                return failure("CB-CLANG-AST-INVALID", "Clang frontend returned an invalid semantic AST: " + safeMessage(malformed));
            }
        } catch (IOException exception) {
            return failure("CB-CLANG-IO-FAILED", "Clang frontend could not stage source: " + safeMessage(exception));
        } finally {
            deleteTree(folder);
        }
    }

    static List<String> dockerCommand(String docker, String image, String container, String sourcePath,
                                     String fileName, Language language, String standard) {
        String languageFlag = language == Language.C ? "c" : "c++";
        return List.of(docker, "run", "--rm", "--pull=never", "--name", container, "--network=none", "--memory=768m", "--cpus=1",
                "--pids-limit=64", "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--user=65532:65532", "--mount",
                "type=bind,source=" + sourcePath + ",target=/work/" + fileName + ",readonly", "--workdir=/work",
                image, languageFlag, standard, "/work/" + fileName);
    }

    private ProcessResult run(List<String> command, Duration timeout, String container) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            Process active = process;
            CompletableFuture<LimitedText> stdout = CompletableFuture.supplyAsync(() -> readLimited(active.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readLimited(active.getErrorStream()).text());
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminateProcessTree(process);
                process.waitFor(1, TimeUnit.SECONDS);
                return new ProcessResult(-1, stdout.join().text(), stderr.join(), true, false, null);
            }
            LimitedText output = stdout.get(2, TimeUnit.SECONDS);
            return new ProcessResult(process.exitValue(), output.text(), stderr.get(2, TimeUnit.SECONDS), false, output.truncated(), null);
        } catch (IOException exception) {
            if (process != null) terminateProcessTree(process);
            return new ProcessResult(-1, "", safeMessage(exception), false, false, safeMessage(exception));
        } catch (InterruptedException exception) {
            if (process != null) terminateProcessTree(process);
            if (container != null) killContainer(container);
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, "", "Interrupted.", false, false, "Interrupted.");
        } catch (Exception exception) {
            if (process != null) terminateProcessTree(process);
            return new ProcessResult(-1, "", safeMessage(exception), false, false, safeMessage(exception));
        }
    }

    private void killContainer(String name) {
        boolean restoreInterrupt = Thread.interrupted();
        Process process = null;
        try {
            process = new ProcessBuilder(dockerExecutable, "kill", name).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) terminateProcessTree(process);
        } catch (InterruptedException interrupted) {
            if (process != null) terminateProcessTree(process);
            restoreInterrupt = true;
        } catch (Exception ignored) {
            if (process != null) terminateProcessTree(process);
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private static void terminateProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static LimitedText readLimited(InputStream input) {
        try (input; ByteArrayOutputStream kept = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            boolean truncated = false;
            while ((read = input.read(buffer)) != -1) {
                int remaining = MAX_AST_BYTES - kept.size();
                if (remaining > 0) kept.write(buffer, 0, Math.min(read, remaining));
                if (read > remaining) truncated = true;
            }
            return new LimitedText(kept.toString(StandardCharsets.UTF_8), truncated);
        } catch (IOException exception) { return new LimitedText("", false); }
    }

    static boolean dockerUnavailable(String message) {
        String text = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return text.contains("permission denied") || text.contains("cannot connect") || text.contains("failed to connect")
                || text.contains("error during connect") || text.contains("connection refused")
                || text.contains("cannot find the file") || text.contains("docker daemon") || text.contains("dockerdesktoplinuxengine");
    }

    private static ClangSemanticResult failure(String code, String message) {
        return new ClangSemanticResult(Optional.empty(), List.of(Diagnostic.withoutLocation(Severity.ERROR, code, message)));
    }
    private static String firstNonBlank(String primary, String fallback) { return primary == null || primary.isBlank() ? fallback : primary.trim(); }
    private static String safeMessage(Exception exception) { return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); }
    private static void deleteTree(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
    private record LimitedText(String text, boolean truncated) { }
    private record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated, String failure) { }
}
