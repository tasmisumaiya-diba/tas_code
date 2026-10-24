package com.tascode.codebridge.diagnostic;

import java.util.Objects;
import java.util.Optional;

/** A stable, user-facing explanation produced during conversion. */
public record Diagnostic(
        Severity severity,
        String code,
        String message,
        Optional<SourceSpan> location) {

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        location = Objects.requireNonNull(location, "location");
        if (code.isBlank()) {
            throw new IllegalArgumentException("Diagnostic code must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("Diagnostic message must not be blank");
        }
    }

    public static Diagnostic at(Severity severity, String code, String message, SourceSpan location) {
        return new Diagnostic(severity, code, message, Optional.of(Objects.requireNonNull(location, "location")));
    }

    public static Diagnostic withoutLocation(Severity severity, String code, String message) {
        return new Diagnostic(severity, code, message, Optional.empty());
    }
}
