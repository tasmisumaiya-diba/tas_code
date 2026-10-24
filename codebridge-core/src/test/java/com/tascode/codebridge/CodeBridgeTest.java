package com.tascode.codebridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CodeBridgeTest {

    @Test
    void versionIsPresent() {
        assertEquals("0.1.0-SNAPSHOT", CodeBridge.version());
        assertFalse(CodeBridge.version().isBlank());
    }
}
