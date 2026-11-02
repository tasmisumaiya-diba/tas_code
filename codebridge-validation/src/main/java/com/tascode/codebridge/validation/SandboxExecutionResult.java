package com.tascode.codebridge.validation;

import com.tascode.codebridge.language.Language;
import java.util.Objects;
import java.util.Optional;

/** Captured result of compiling and executing one source unit in Docker. */
public record SandboxExecutionResult(
        Language language,
        SandboxExecutionStatus status,
        Optional<Integer> exitCode,
        String stdout,
        String stderr,
        String message) {

    public SandboxExecutionResult {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(status, "status");
        exitCode = Objects.requireNonNull(exitCode, "exitCode");
        stdout = Objects.requireNonNull(stdout, "stdout");
        stderr = Objects.requireNonNull(stderr, "stderr");
        message = Objects.requireNonNull(message, "message");
    }

    public boolean completed() {
        return status == SandboxExecutionStatus.COMPLETED;
    }
}