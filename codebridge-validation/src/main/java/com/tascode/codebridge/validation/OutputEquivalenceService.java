package com.tascode.codebridge.validation;

import com.tascode.codebridge.language.Language;
import java.util.Objects;
import java.util.Optional;

/** Runs both programs in Docker and compares their exact standard output and exit codes. */
public final class OutputEquivalenceService {
    private final DockerSandboxExecutionService sandbox;

    public OutputEquivalenceService(DockerSandboxExecutionService sandbox) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    }

    public OutputComparisonResult compare(Language sourceLanguage, String source, Language targetLanguage,
            String generated, String standardInput) {
        Objects.requireNonNull(sourceLanguage, "sourceLanguage");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetLanguage, "targetLanguage");
        Objects.requireNonNull(generated, "generated");
        Objects.requireNonNull(standardInput, "standardInput");

        SandboxExecutionResult sourceRun = sandbox.execute(sourceLanguage, source, standardInput);
        if (!sourceRun.completed()) {
            return new OutputComparisonResult(Optional.of(sourceRun), Optional.empty(), Optional.empty(),
                    "Source program could not be executed: " + sourceRun.message());
        }
        SandboxExecutionResult targetRun = sandbox.execute(targetLanguage, generated, standardInput);
        if (!targetRun.completed()) {
            return new OutputComparisonResult(Optional.of(sourceRun), Optional.of(targetRun), Optional.empty(),
                    "Generated program could not be executed: " + targetRun.message());
        }
        if (sourceRun.stdout().contains(DockerSandboxExecutionService.OUTPUT_TRUNCATION_MARKER)
                || targetRun.stdout().contains(DockerSandboxExecutionService.OUTPUT_TRUNCATION_MARKER)) {
            return new OutputComparisonResult(Optional.of(sourceRun), Optional.of(targetRun), Optional.empty(),
                    "Output comparison is inconclusive because captured output exceeded the size limit.");
        }
        boolean matches = sourceRun.stdout().equals(targetRun.stdout())
                && sourceRun.exitCode().equals(targetRun.exitCode());
        return new OutputComparisonResult(Optional.of(sourceRun), Optional.of(targetRun), Optional.of(matches),
                matches ? "Program output and exit codes match." : "Program output or exit codes differ.");
    }
}