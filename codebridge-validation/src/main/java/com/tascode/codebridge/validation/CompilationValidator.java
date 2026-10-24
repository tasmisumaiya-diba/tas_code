package com.tascode.codebridge.validation;

import com.tascode.codebridge.language.Language;

/** Checks generated source by compiling it; this API never executes the resulting program. */
@FunctionalInterface
public interface CompilationValidator {
    CompilationResult validate(Language language, String source);
}
