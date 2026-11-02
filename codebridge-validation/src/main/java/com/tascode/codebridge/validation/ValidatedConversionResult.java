package com.tascode.codebridge.validation;

import com.tascode.codebridge.pipeline.ConversionResult;
import java.util.Objects;
import java.util.Optional;

/** Conversion output paired with its compilation check, when conversion reached generation. */
public record ValidatedConversionResult(ConversionResult conversion, Optional<CompilationResult> validation) {
    public ValidatedConversionResult {
        Objects.requireNonNull(conversion, "conversion");
        validation = Objects.requireNonNull(validation, "validation");
    }

    public boolean successful() {
        return conversion.successful() && validation.map(CompilationResult::successful).orElse(false);
    }
}