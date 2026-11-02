package com.tascode.codebridge.validation;

import com.tascode.codebridge.language.Language;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompilerValidationServiceTest {
    private final CompilerValidationService validator = new CompilerValidationService(
            "missing-tascode-gcc", "missing-tascode-g++", Duration.ofSeconds(2));

    @Test void compilesJavaWithoutExecutingTheProgram() {
        var result = validator.validate(Language.JAVA,
                "public class TasCodeGenerated { public static int value() { return 42; } }");
        assertEquals(ValidationStatus.VALID, result.status());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test void compilesPublicJavaTypesUsingTheirDeclaredFileName() {
        var result = validator.validate(Language.JAVA,
                "class Helper {} public class Example { public static int value() { return 42; } }");
        assertEquals(ValidationStatus.VALID, result.status(), result.diagnostics().toString());
    }

    @Test void compilesJavaTypesWithPackageDeclarations() {
        var result = validator.validate(Language.JAVA,
                "package demo.validation; public class PackagedExample { public static int value() { return 42; } }");
        assertEquals(ValidationStatus.VALID, result.status(), result.diagnostics().toString());
    }

    @Test void reportsJavaCompilerDiagnosticsForInvalidSource() {
        var result = validator.validate(Language.JAVA,
                "public class TasCodeGenerated { public static int value() { return missing; } }");
        assertEquals(ValidationStatus.INVALID, result.status());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.location().isPresent()));
    }

    @Test void reportsUnavailableNativeCompilerClearly() {
        var result = validator.validate(Language.C, "int value(void) { return 42; }");
        assertEquals(ValidationStatus.COMPILER_UNAVAILABLE, result.status());
        assertEquals("CB-VALIDATE-COMPILER-MISSING", result.diagnostics().getFirst().code());
    }

    @Test void nativeCompilerDiagnosticsIncludeSourceLocationWhenAvailable() {
        var diagnostic = CompilerValidationService.nativeDiagnostic("<stdin>:4:7: error: expected expression");
        assertEquals("CB-VALIDATE-COMPILER-DIAGNOSTIC", diagnostic.code());
        assertEquals(4, diagnostic.location().orElseThrow().startLine());
        assertEquals(7, diagnostic.location().orElseThrow().startColumn());
    }

    @Test void nativeCompilerDiagnosticsRemainValidWithoutAParsedLocation() {
        var diagnostic = CompilerValidationService.nativeDiagnostic("compiler rejected input");
        assertTrue(diagnostic.location().isEmpty());
    }
    @Test void rejectsOversizedInputBeforeStartingCompiler() {
        var result = validator.validate(Language.CPP, "x".repeat(1_000_001));
        assertEquals(ValidationStatus.INVALID, result.status());
        assertEquals("CB-VALIDATE-SOURCE-LIMIT", result.diagnostics().getFirst().code());
    }
}
