package com.tascode.codebridge.validation;

/** Outcome of compiling and running a source file inside Docker. */
public enum SandboxExecutionStatus {
    COMPLETED,
    COMPILE_FAILED,
    TIMED_OUT,
    DOCKER_UNAVAILABLE,
    IMAGE_UNAVAILABLE,
    FAILED
}
