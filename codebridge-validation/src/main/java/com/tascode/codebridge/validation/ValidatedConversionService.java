package com.tascode.codebridge.validation;

import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.pipeline.ConversionPipeline;
import java.util.Objects;
import java.util.Optional;

/** Runs deterministic conversion, then compiles the generated source without executing it. */
public final class ValidatedConversionService {
    private final ConversionPipeline pipeline;
    private final CompilationValidator validator;

    public ValidatedConversionService(ConversionPipeline pipeline, CompilationValidator validator) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public ValidatedConversionResult convert(Language from, Language to, String source) {
        var conversion = pipeline.convert(from, to, source);
        if (!conversion.successful()) return new ValidatedConversionResult(conversion, Optional.empty());
        var compilation = validator.validate(to, conversion.output().orElseThrow());
        return new ValidatedConversionResult(conversion, Optional.of(compilation));
    }
}