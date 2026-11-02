package com.tascode.codebridge;

import com.tascode.codebridge.language.Language;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringIdentitySemanticsTest {
    @Test
    void javaReferenceEqualityDoesNotSilentlyBecomeCppValueEquality() {
        var result = CodeBridge.createPipeline().convert(Language.JAVA, Language.CPP,
                "class Example { static boolean same(String left, String right) { return left == right; } }");

        assertFalse(result.successful());
        assertEquals("CB-STRING-COMPARE-SEMANTICS", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().location().isPresent());
    }
}

