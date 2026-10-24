package com.tascode.codebridge.validation;

/** Outcome of checking whether generated source is accepted by its compiler. */
public enum ValidationStatus {
    VALID,
    INVALID,
    COMPILER_UNAVAILABLE,
    TIMED_OUT
}
