package com.tascode.codebridge.validation;

import java.util.Objects;
import java.util.Optional;

/** Runs of the source and generated program, plus exact stdout/exit-code comparison. */
public record OutputComparisonResult(
        Optional<SandboxExecutionResult> sourceExecution,
        Optional<SandboxExecutionResult> targetExecution,
        Optional<Boolean> matches,
        String message) {

    public OutputComparisonResult {
        sourceExecution = Objects.requireNonNull(sourceExecution, "sourceExecution");
        targetExecution = Objects.requireNonNull(targetExecution, "targetExecution");
        matches = Objects.requireNonNull(matches, "matches");
        message = Objects.requireNonNull(message, "message");
    }
}