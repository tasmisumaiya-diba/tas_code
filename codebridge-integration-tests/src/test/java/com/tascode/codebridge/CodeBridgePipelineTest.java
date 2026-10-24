package com.tascode.codebridge;

import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.language.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CodeBridgePipelineTest {
    private static com.tascode.codebridge.pipeline.ConversionResult convert(Language from, Language to, String source) {
        return CodeBridge.createPipeline().convert(from, to, source);
    }
    @TempDir Path tempDir;
    @Test void cToCRoundTripPreservesProgramStructure() {
        var result = convert(Language.C, Language.C, "int main() { int x = 1 + 2 * 3; return x; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().contains("int x = (1 + (2 * 3));"));
        assertTrue(result.output().orElseThrow().contains("return x;"));
    }

    @Test void cToCppUsesCppEmptyParameterSyntax() {
        var result = convert(Language.C, Language.CPP, "int main(void) { return 0; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().contains("int main()"));
    }

    @Test void unsupportedSyntaxReturnsLocatedDiagnostic() {
        var result = convert(Language.C, Language.C, "int main() { int *p; return 0; }");
        assertFalse(result.successful());
        assertEquals(Severity.ERROR, result.diagnostics().getFirst().severity());
        assertEquals("CB-PARSE-C-UNSUPPORTED", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().location().isPresent());
    }

    @Test void javaMethodConvertsToCAndBackThroughSharedIr() {
        var javaToC = convert(Language.JAVA, Language.C, "public class Calculator { public static int add(int left, int right) { return left + right; } }");
        assertTrue(javaToC.successful(), () -> javaToC.diagnostics().toString());
        assertTrue(javaToC.output().orElseThrow().contains("int add(int left, int right)"));

        var cToJava = convert(Language.C, Language.JAVA, "int add(int left, int right) { return left + right; }");
        assertTrue(cToJava.successful(), () -> cToJava.diagnostics().toString());
        assertTrue(cToJava.output().orElseThrow().contains("public static int add(int left, int right)"));
    }

    @Test void javaArraysAreRejectedWithStableDiagnostic() {
        var result = convert(Language.JAVA, Language.C, "class Example { int[] values; }");
        assertFalse(result.successful());
        assertEquals("CB-PARSE-JAVA-UNSUPPORTED", result.diagnostics().getFirst().code());
    }

    @Test void generatedJavaCompilesWithoutExecutingIt() throws Exception {
        var result = convert(Language.C, Language.JAVA, "_Bool is_ready(_Bool value) { return true; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        Path source = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("classes"));
        Files.writeString(source, result.output().orElseThrow());
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK compiler");
        int exit = compiler.run(null, null, null, "-d", classes.toString(), source.toString());
        assertEquals(0, exit, result.output().orElseThrow());
    }

    @Test void everyLanguagePairConvertsTheSharedFunctionSubset() {
        String c = "int sum(int left, int right) { return left + right; }";
        String java = "class Calculator { public static int sum(int left, int right) { return left + right; } }";
        for (Language from : Language.values()) {
            String source = from == Language.JAVA ? java : c;
            for (Language to : Language.values()) {
                var result = convert(from, to, source);
                assertTrue(result.successful(), () -> from + " -> " + to + ": " + result.diagnostics());
                assertTrue(result.output().orElseThrow().contains("sum"));
            }
        }
    }

    @Test void supportedHeadersAreRemovedBeforeParsingAndRestoredForCLikeTargets() {
        var result = convert(Language.C, Language.C, "#include <stdio.h>\nint answer() { return 42; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().startsWith("#include <stdio.h>"));
    }

    @Test void simpleLiteralMacrosAreExpandedAndPreserveColumns() {
        var result = convert(Language.C, Language.C, "#define MAX 10\nint answer() { return MAX + 1; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().contains("return (10 + 1);"));
    }

    @Test void unsupportedMacroExpansionReturnsAStableDiagnostic() {
        var result = convert(Language.C, Language.C, "#define X 100\nint answer() { return X; }");
        assertFalse(result.successful());
        assertEquals("CB-PREPROC-MACRO-EXPANSION-LENGTH", result.diagnostics().getFirst().code());
        assertEquals(2, result.diagnostics().getFirst().location().orElseThrow().startLine());
    }

    @Test void directiveTextInsideBlockCommentsIsNotPreprocessed() {
        String source = "int value() { /* start\n#define FAKE 123\n*/ return 1; }";
        var result = convert(Language.C, Language.C, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().contains("return 1;"));
    }
    @Test void standardLibraryMappingsComeFromTheRulesModuleTable() {
        var cToJava = convert(Language.C, Language.JAVA, "int main() { printf(\"%d\", 42); return 0; }");
        assertTrue(cToJava.successful(), () -> cToJava.diagnostics().toString());
        assertTrue(cToJava.output().orElseThrow().contains("System.out.printf(\"%d\", 42)"));

        var javaToC = convert(Language.JAVA, Language.C, "class Printer { public static void print() { System.out.printf(\"ready\"); } }");
        assertTrue(javaToC.successful(), () -> javaToC.diagnostics().toString());
        assertTrue(javaToC.output().orElseThrow().contains("printf(\"ready\")"));
    }
}

