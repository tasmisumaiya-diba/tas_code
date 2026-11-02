package com.tascode.codebridge.validation;

import com.tascode.codebridge.language.Language;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Compiles and runs user programs only inside restricted Docker containers. */
public final class DockerSandboxExecutionService {
    private static final int MAX_SOURCE_CHARS = 1_000_000;
    private static final int MAX_INPUT_CHARS = 100_000;
    private static final int MAX_OUTPUT_CHARS = 64_000;
    static final String OUTPUT_TRUNCATION_MARKER = "<<<CODEBRIDGE_OUTPUT_TRUNCATED>>>";
    private static final Duration IMAGE_CHECK_TIMEOUT = Duration.ofSeconds(5);
    private static final String C_IMAGE = "gcc:14";
    private static final String JAVA_IMAGE = "eclipse-temurin:21-jdk";
    private final String dockerExecutable;
    private final Duration timeout;

    public DockerSandboxExecutionService() {
        this("docker", Duration.ofSeconds(10));
    }

    public DockerSandboxExecutionService(String dockerExecutable, Duration timeout) {
        this.dockerExecutable = Objects.requireNonNull(dockerExecutable, "dockerExecutable");
        if (dockerExecutable.isBlank()) throw new IllegalArgumentException("dockerExecutable must not be blank");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    public SandboxExecutionResult execute(Language language, String source, String standardInput) {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(standardInput, "standardInput");
        if (source.length() > MAX_SOURCE_CHARS || standardInput.length() > MAX_INPUT_CHARS) {
            return result(language, SandboxExecutionStatus.FAILED, Optional.empty(), "", "",
                    "Source or test input exceeds the Docker execution size limit.");
        }
        String image = image(language);
        ProcessResult imageStatus = runProcess(List.of(dockerExecutable, "image", "inspect", image), "", IMAGE_CHECK_TIMEOUT);
        if (imageStatus.failure != null) return unavailable(language, imageStatus.failure);
        if (imageStatus.timedOut) return unavailable(language, "Docker image inspection timed out.");
        if (imageStatus.exitCode != 0) {
            String details = firstNonBlank(imageStatus.stderr, imageStatus.stdout);
            return result(language, SandboxExecutionStatus.IMAGE_UNAVAILABLE, Optional.empty(), "", details,
                    "Required sandbox image '" + image + "' is unavailable. Pull it with: docker pull " + image);
        }

        Path folder = null;
        String containerName = "codebridge-" + UUID.randomUUID().toString().replace("-", "");
        try {
            folder = Files.createTempDirectory("codebridge-sandbox-");
            String fileName = switch (language) {
                case C -> "program.c";
                case CPP -> "program.cpp";
                case JAVA -> javaSourceFileName(source);
            };
            Files.writeString(folder.resolve(fileName), source, StandardCharsets.UTF_8);
            String javaMainClass = language == Language.JAVA ? javaMainClass(source, fileName) : null;
            List<String> command = dockerCommand(language, folder, containerName, fileName, javaMainClass, dockerExecutable);
            ProcessResult execution = runProcess(command, standardInput, timeout);
            if (execution.failure != null) return unavailable(language, execution.failure);
            if (execution.timedOut) {
                killContainer(containerName);
                return result(language, SandboxExecutionStatus.TIMED_OUT, Optional.empty(),
                        execution.stdout, execution.stderr, "Program exceeded the Docker execution time limit.");
            }
            if (execution.exitCode == 125 && execution.stderr.contains("CODEBRIDGE_COMPILER_FAILED")) {
                return result(language, SandboxExecutionStatus.COMPILE_FAILED, Optional.of(execution.exitCode),
                        execution.stdout, execution.stderr, "The sandbox compiler rejected the program.");
            }
            return result(language, SandboxExecutionStatus.COMPLETED, Optional.of(execution.exitCode),
                    execution.stdout, execution.stderr, "Program completed in Docker.");
        } catch (IOException exception) {
            return unavailable(language, safeMessage(exception));
        } finally {
            deleteTree(folder);
        }
    }

    static List<String> dockerCommand(Language language, Path folder, String containerName, String fileName, String docker) {
        return dockerCommand(language, folder, containerName, fileName,
                language == Language.JAVA ? javaClassNameFromFile(fileName) : null, docker);
    }

    static List<String> dockerCommand(Language language, Path folder, String containerName, String fileName,
            String javaMainClass, String docker) {
        String runCommand = switch (language) {
            case C -> "gcc -std=c11 -O0 -o /tmp/codebridge-program /workspace/program.c 2>/tmp/codebridge-build.err || { cat /tmp/codebridge-build.err >&2; echo CODEBRIDGE_COMPILER_FAILED >&2; exit 125; }; exec /tmp/codebridge-program";
            case CPP -> "g++ -std=c++20 -O0 -o /tmp/codebridge-program /workspace/program.cpp 2>/tmp/codebridge-build.err || { cat /tmp/codebridge-build.err >&2; echo CODEBRIDGE_COMPILER_FAILED >&2; exit 125; }; exec /tmp/codebridge-program";
            case JAVA -> {
                String className = Objects.requireNonNull(javaMainClass, "javaMainClass");
                if (!className.matches("\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*")) {
                    throw new IllegalArgumentException("Invalid Java main class name for sandbox execution.");
                }
                yield "mkdir -p /tmp/classes && javac --release 21 -proc:none -d /tmp/classes /workspace/" + fileName
                        + " 2>/tmp/codebridge-build.err || { cat /tmp/codebridge-build.err >&2; echo CODEBRIDGE_COMPILER_FAILED >&2; exit 125; }; exec java -cp /tmp/classes " + className;
            }
        };
        String mount = "type=bind,source=" + folder.toAbsolutePath().toString().replace('\\', '/') + ",target=/workspace,readonly";
        List<String> args = new ArrayList<>(List.of(docker, "run", "--rm", "--interactive", "--init", "--pull=never",
                "--name", containerName,
                "--network", "none",
                "--memory", "128m", "--memory-swap", "128m", "--cpus", "1", "--pids-limit", "32",
                "--read-only", "--tmpfs", "/tmp:rw,nosuid,nodev,exec,size=64m",
                "--user", "65534:65534", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--mount", mount, "--workdir", "/workspace", image(language), "sh", "-c", runCommand));
        return List.copyOf(args);
    }

    static String javaSourceFileName(String source) {
        String masked = maskJavaCommentsAndLiterals(source);
        int depth = 0;
        int declarationStart = 0;
        String firstTopLevelType = null;
        for (int i = 0; i < masked.length();) {
            char current = masked.charAt(i);
            if (depth == 0 && (current == 'c' || current == 'i' || current == 'e' || current == 'r')) {
                String typeKeyword = typeKeywordAt(masked, i);
                if (typeKeyword != null) {
                    int nameStart = skipWhitespace(masked, i + typeKeyword.length());
                    int nameEnd = identifierEnd(masked, nameStart);
                    if (nameEnd > nameStart) {
                        String typeName = masked.substring(nameStart, nameEnd);
                        if (firstTopLevelType == null) firstTopLevelType = typeName;
                        if (containsPublicModifier(masked, declarationStart, i)) return typeName + ".java";
                        i = nameEnd;
                        continue;
                    }
                }
            }
            if (current == '{') depth++;
            else if (current == '}' && depth > 0) {
                depth--;
                if (depth == 0) declarationStart = i + 1;
            } else if (current == ';' && depth == 0) declarationStart = i + 1;
            i++;
        }
        return (firstTopLevelType == null ? "TasCodeGenerated" : firstTopLevelType) + ".java";
    }

    private static String typeKeywordAt(String source, int at) {
        for (String keyword : List.of("class", "interface", "enum", "record")) {
            int end = at + keyword.length();
            if (source.startsWith(keyword, at) && (at == 0 || !Character.isJavaIdentifierPart(source.charAt(at - 1)))
                    && (end == source.length() || !Character.isJavaIdentifierPart(source.charAt(end)))) return keyword;
        }
        return null;
    }

    private static int skipWhitespace(String source, int at) {
        while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++;
        return at;
    }

    private static int identifierEnd(String source, int at) {
        if (at >= source.length() || !(Character.isJavaIdentifierStart(source.charAt(at)) || source.charAt(at) == '$')) return at;
        at++;
        while (at < source.length() && (Character.isJavaIdentifierPart(source.charAt(at)) || source.charAt(at) == '$')) at++;
        return at;
    }

    private static boolean containsPublicModifier(String source, int start, int end) {
        Matcher modifier = Pattern.compile("\\bpublic\\b").matcher(source.substring(start, end));
        return modifier.find();
    }

    private static String javaClassNameFromFile(String fileName) {
        if (!fileName.matches("\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*\\.java")) {
            throw new IllegalArgumentException("Invalid Java source file name for sandbox execution.");
        }
        return fileName.substring(0, fileName.length() - ".java".length());
    }

    static String javaMainClass(String source, String fileName) {
        String simpleName = javaClassNameFromFile(fileName);
        Matcher packageMatcher = Pattern.compile("\\bpackage\\s+(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*)\\s*;")
                .matcher(maskJavaCommentsAndLiterals(source));
        return packageMatcher.find() ? packageMatcher.group(1) + "." + simpleName : simpleName;
    }

    private static String maskJavaCommentsAndLiterals(String source) {
        char[] text = source.toCharArray();
        boolean lineComment = false, blockComment = false, escaped = false;
        char quote = 0;
        for (int i = 0; i < text.length; i++) {
            char c = text[i], next = i + 1 < text.length ? text[i + 1] : 0;
            if (lineComment) { if (c == 10) lineComment = false; else text[i] = ' '; continue; }
            if (blockComment) {
                if (c == '*' && next == '/') { text[i] = text[i + 1] = ' '; i++; blockComment = false; }
                else if (c != 10 && c != 13) text[i] = ' ';
                continue;
            }
            if (quote != 0) {
                if (c != 10 && c != 13) text[i] = ' ';
                if (escaped) escaped = false;
                else if (c == 92) escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '/' && next == '/') { text[i] = text[i + 1] = ' '; i++; lineComment = true; continue; }
            if (c == '/' && next == '*') { text[i] = text[i + 1] = ' '; i++; blockComment = true; continue; }
            if (c == 34 || c == 39) { quote = c; text[i] = ' '; }
        }
        return new String(text);
    }
    private static String image(Language language) {
        return language == Language.JAVA ? JAVA_IMAGE : C_IMAGE;
    }

    private void killContainer(String containerName) {
        ProcessResult ignored = runProcess(List.of(dockerExecutable, "kill", containerName), "", Duration.ofSeconds(2));
    }

    private static SandboxExecutionResult unavailable(Language language, String message) {
        return result(language, SandboxExecutionStatus.DOCKER_UNAVAILABLE, Optional.empty(), "", message,
                "Docker could not be reached from this application session.");
    }

    private static SandboxExecutionResult result(Language language, SandboxExecutionStatus status,
            Optional<Integer> exitCode, String stdout, String stderr, String message) {
        return new SandboxExecutionResult(language, status, exitCode, stdout, stderr, message);
    }

    static ProcessResult runProcess(List<String> command, String input, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            return ProcessResult.failed(safeMessage(exception));
        }
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readLimited(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readLimited(process.getErrorStream()));
        CompletableFuture<Void> stdin = CompletableFuture.runAsync(() -> writeInput(process, input));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                closeStdin(process);
                awaitQuietly(stdin);
                return ProcessResult.timedOut(stdout.join(), stderr.join());
            }
            awaitQuietly(stdin);
            return ProcessResult.completed(process.exitValue(), stdout.get(2, TimeUnit.SECONDS), stderr.get(2, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            closeStdin(process);
            stdin.cancel(true);
            Thread.currentThread().interrupt();
            return ProcessResult.failed("Docker command was interrupted.");
        } catch (Exception exception) {
            process.destroyForcibly();
            closeStdin(process);
            stdin.cancel(true);
            return ProcessResult.failed(safeMessage(exception));
        }
    }

    private static void writeInput(Process process, String input) {
        try (var stdin = process.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // A child may exit without consuming all input; its exit status remains authoritative.
        }
    }

    private static void closeStdin(Process process) {
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    private static void awaitQuietly(CompletableFuture<Void> future) {
        try { future.get(2, TimeUnit.SECONDS); }
        catch (Exception ignored) { future.cancel(true); }
    }

    static String readLimited(InputStream input) {
        try (input; var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            StringBuilder kept = new StringBuilder(MAX_OUTPUT_CHARS + OUTPUT_TRUNCATION_MARKER.length());
            boolean truncated = false;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (truncated) continue;
                int remaining = MAX_OUTPUT_CHARS - kept.length();
                if (read <= remaining) {
                    kept.append(buffer, 0, read);
                    continue;
                }
                int retained = Math.max(0, remaining);
                kept.append(buffer, 0, retained);
                if (!kept.isEmpty() && Character.isHighSurrogate(kept.charAt(kept.length() - 1))) {
                    kept.setLength(kept.length() - 1);
                }
                truncated = true;
            }
            if (truncated) kept.append(OUTPUT_TRUNCATION_MARKER);
            return kept.toString();
        } catch (IOException exception) {
            return "Output could not be read: " + safeMessage(exception);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second.trim()) : first.trim();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut, String failure) {
        static ProcessResult completed(int exitCode, String stdout, String stderr) {
            return new ProcessResult(exitCode, stdout, stderr, false, null);
        }
        static ProcessResult timedOut(String stdout, String stderr) {
            return new ProcessResult(-1, stdout, stderr, true, null);
        }
        static ProcessResult failed(String failure) {
            return new ProcessResult(-1, "", failure, false, failure);
        }
    }
}
