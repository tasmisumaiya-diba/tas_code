package com.tascode.codebridge.validation;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.language.Language;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Compiles generated Java, C, or C++ source for syntax and type validation.
 * It never launches the generated executable. C/C++ compilers receive source on
 * standard input and run in syntax-only mode.
 */
public final class CompilerValidationService implements CompilationValidator {
    private static final int MAX_SOURCE_CHARS = 1_000_000;
    private static final int MAX_COMPILER_OUTPUT_CHARS = 4_000;
    private static final int MAX_JAVA_DIAGNOSTICS = 100;
    private static final Pattern NATIVE_LOCATION = Pattern.compile("(?m)^<stdin>:(\\d+):(\\d+):");
    private final String cCompiler;
    private final String cppCompiler;
    private final Duration timeout;

    public CompilerValidationService() {
        this("gcc", "g++", Duration.ofSeconds(10));
    }

    public CompilerValidationService(String cCompiler, String cppCompiler, Duration timeout) {
        this.cCompiler = requireExecutable(cCompiler, "cCompiler");
        this.cppCompiler = requireExecutable(cppCompiler, "cppCompiler");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    @Override
    public CompilationResult validate(Language language, String source) {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(source, "source");
        if (source.length() > MAX_SOURCE_CHARS) {
            return result(language, ValidationStatus.INVALID, "CB-VALIDATE-SOURCE-LIMIT",
                    "Source exceeds the compiler validation size limit.");
        }
        return switch (language) {
            case JAVA -> validateJava(source);
            case C -> validateNative(language, cCompiler, "c", source);
            case CPP -> validateNative(language, cppCompiler, "c++", source);
        };
    }

    private CompilationResult validateJava(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return result(Language.JAVA, ValidationStatus.COMPILER_UNAVAILABLE, "CB-VALIDATE-JAVA-COMPILER-MISSING",
                    "A JDK Java compiler is unavailable; configure IntelliJ or JAVA_HOME to use a JDK.");
        }
        DiagnosticCollector<javax.tools.JavaFileObject> collector = new DiagnosticCollector<>();
        Path output = null;
        try {
            output = Files.createTempDirectory("tascode-java-compile-");
            var unit = new StringJavaSource(source, DockerSandboxExecutionService.javaSourceFileName(source));
            var fileManager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8);
            try (fileManager) {
                boolean success = Boolean.TRUE.equals(compiler.getTask(null, fileManager, collector,
                        List.of("--release", "21", "-proc:none", "-Xmaxerrs", Integer.toString(MAX_JAVA_DIAGNOSTICS), "-Xmaxwarns", Integer.toString(MAX_JAVA_DIAGNOSTICS), "-d", output.toString()), null, List.of(unit)).call());
                List<Diagnostic> diagnostics = new ArrayList<>();
                var compilerDiagnostics = collector.getDiagnostics();
                int emitted = 0;
                for (var item : compilerDiagnostics) {
                    if (emitted++ >= MAX_JAVA_DIAGNOSTICS) break;
                    Severity severity = item.getKind() == javax.tools.Diagnostic.Kind.ERROR ? Severity.ERROR
                            : item.getKind() == javax.tools.Diagnostic.Kind.WARNING || item.getKind() == javax.tools.Diagnostic.Kind.MANDATORY_WARNING
                                    ? Severity.WARNING : Severity.INFO;
                    String message = item.getMessage(java.util.Locale.ROOT);
                    if (item.getLineNumber() > 0) {
                        int line = Math.toIntExact(item.getLineNumber());
                        int column = Math.max(1, Math.toIntExact(item.getColumnNumber()));
                        diagnostics.add(Diagnostic.at(severity, "CB-VALIDATE-JAVA-DIAGNOSTIC", message,
                                new SourceSpan(line, column, line, column)));
                    } else {
                        diagnostics.add(Diagnostic.withoutLocation(severity, "CB-VALIDATE-JAVA-DIAGNOSTIC", message));
                    }
                }
                if (collector.getDiagnostics().size() > MAX_JAVA_DIAGNOSTICS) {
                    diagnostics.add(Diagnostic.withoutLocation(Severity.WARNING, "CB-VALIDATE-DIAGNOSTICS-TRUNCATED",
                            "Additional Java compiler diagnostics were omitted."));
                }
                if (!success && diagnostics.isEmpty()) {
                    diagnostics.add(Diagnostic.withoutLocation(Severity.ERROR, "CB-VALIDATE-JAVA-FAILED",
                            "The Java compiler rejected the generated source."));
                }
                return new CompilationResult(Language.JAVA, success ? ValidationStatus.VALID : ValidationStatus.INVALID, diagnostics);
            }
        } catch (IOException | RuntimeException exception) {
            return result(Language.JAVA, ValidationStatus.INVALID, "CB-VALIDATE-JAVA-FAILED",
                    "Java compilation could not be completed: " + safeMessage(exception));
        } finally {
            deleteTree(output);
        }
    }

    private CompilationResult validateNative(Language language, String compiler, String sourceKind, String source) {
        Process process;
        try {
            process = new ProcessBuilder(compiler, "-x", sourceKind, "-fsyntax-only", "-")
                    .redirectErrorStream(true).start();
        } catch (IOException exception) {
            return result(language, ValidationStatus.COMPILER_UNAVAILABLE, "CB-VALIDATE-COMPILER-MISSING",
                    "The " + language.id() + " compiler ('" + compiler + "') could not be started.");
        }

        CompletableFuture<String> output = CompletableFuture.supplyAsync(
                () -> readCompilerOutput(process.getInputStream()));
        CompletableFuture<Void> sourceWriter = CompletableFuture.runAsync(() -> writeCompilerInput(process, source));

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminateProcessTree(process);
                closeCompilerInput(process);
                process.waitFor(1, TimeUnit.SECONDS);
                awaitQuietly(sourceWriter);
                output.cancel(true);
                return result(language, ValidationStatus.TIMED_OUT, "CB-VALIDATE-TIMEOUT",
                        "Compilation exceeded the configured time limit.");
            }
            awaitQuietly(sourceWriter);
            String compilerOutput = trimOutput(output.get(2, TimeUnit.SECONDS));
            if (process.exitValue() == 0) return new CompilationResult(language, ValidationStatus.VALID, List.of());
            String message = compilerOutput.isBlank() ? "The compiler rejected the generated source." : compilerOutput;
            return new CompilationResult(language, ValidationStatus.INVALID, List.of(nativeDiagnostic(message)));
        } catch (InterruptedException exception) {
            terminateProcessTree(process);
            closeCompilerInput(process);
            sourceWriter.cancel(true);
            Thread.currentThread().interrupt();
            return result(language, ValidationStatus.TIMED_OUT, "CB-VALIDATE-INTERRUPTED",
                    "Compilation was interrupted.");
        } catch (Exception exception) {
            terminateProcessTree(process);
            closeCompilerInput(process);
            sourceWriter.cancel(true);
            return result(language, ValidationStatus.INVALID, "CB-VALIDATE-COMPILER-FAILED",
                    "Compiler validation failed: " + safeMessage(exception));
        }
    }

    private static void writeCompilerInput(Process process, String source) {
        try (var input = process.getOutputStream()) {
            input.write(source.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Compilers can exit early after rejecting input; the process result carries the diagnostic.
        }
    }

    private static void closeCompilerInput(Process process) {
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    private static void terminateProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void awaitQuietly(CompletableFuture<Void> future) {
        try { future.get(2, TimeUnit.SECONDS); }
        catch (Exception ignored) { future.cancel(true); }
    }

    private static String readCompilerOutput(InputStream input) {
        int byteLimit = MAX_COMPILER_OUTPUT_CHARS * 4;
        try (input; var kept = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = byteLimit - kept.size();
                if (remaining > 0) kept.write(buffer, 0, Math.min(read, remaining));
            }
            String output = new String(kept.toByteArray(), StandardCharsets.UTF_8);
            return trimOutput(output);
        } catch (IOException exception) {
            return "Compiler output could not be read: " + safeMessage(exception);
        }
    }
    static Diagnostic nativeDiagnostic(String message) {
        Matcher matcher = NATIVE_LOCATION.matcher(message);
        if (matcher.find()) {
            int line = Integer.parseInt(matcher.group(1));
            int column = Integer.parseInt(matcher.group(2));
            return Diagnostic.at(Severity.ERROR, "CB-VALIDATE-COMPILER-DIAGNOSTIC", message,
                    new SourceSpan(line, column, line, column));
        }
        return Diagnostic.withoutLocation(Severity.ERROR, "CB-VALIDATE-COMPILER-DIAGNOSTIC", message);
    }
    private static CompilationResult result(Language language, ValidationStatus status, String code, String message) {
        return new CompilationResult(language, status,
                List.of(Diagnostic.withoutLocation(Severity.ERROR, code, message)));
    }

    private static String requireExecutable(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String trimOutput(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() <= MAX_COMPILER_OUTPUT_CHARS) return normalized;
        return normalized.substring(0, MAX_COMPILER_OUTPUT_CHARS) + " … (compiler output truncated)";
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static final class StringJavaSource extends SimpleJavaFileObject {
        private final String source;
        private StringJavaSource(String source, String fileName) {
            super(URI.create("string:///" + fileName), Kind.SOURCE);
            this.source = source;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
    }
}
