package com.tascode.codebridge;

import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.language.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodeBridgePipelineTest {
    private static com.tascode.codebridge.pipeline.ConversionResult convert(Language from, Language to, String source) {
        return CodeBridge.createPipeline().convert(from, to, source);
    }
    @TempDir Path tempDir;
    private static int classesCounter;

    private void compileCpp(String source) throws Exception {
        Process compiler;
        try { compiler = new ProcessBuilder("g++", "-x", "c++", "-std=c++20", "-fsyntax-only", "-").start(); }
        catch (java.io.IOException unavailable) { org.junit.jupiter.api.Assumptions.assumeTrue(false, "g++ is not installed"); return; }
        compiler.getOutputStream().write(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        compiler.getOutputStream().close();
        String errors = new String(compiler.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, compiler.waitFor(), errors + "\n" + source);
    }
    @Test void cToCRoundTripPreservesProgramStructure() {
        var result = convert(Language.C, Language.C, "int main() { int x = 1 + 2 * 3; return x; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().contains("int x = (1 + (2 * 3));"));
        assertTrue(result.output().orElseThrow().contains("return x;"));
    }

    @Test void cEnumsWithLiteralValuesConvertToCppAndCompile() throws Exception {
        var result = convert(Language.C, Language.CPP,
                "enum Color { RED, GREEN = 4, BLUE }; int colorValue() { enum Color value = GREEN; return value; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("enum Color {"));
        assertTrue(generated.contains("GREEN = 4"));
        assertTrue(generated.contains("BLUE = 5"));
        assertTrue(generated.contains("int value = GREEN;"));
        compileCpp(generated);
    }

    @Test void cEnumExpressionsAndJavaTargetAreRejectedWithClearDiagnostics() {
        var expression = convert(Language.C, Language.CPP, "enum Code { FIRST = 1, NEXT = FIRST + 1 }; int f() { return NEXT; }");
        assertFalse(expression.successful());
        assertEquals("CB-C-ENUM-UNSUPPORTED", expression.diagnostics().getFirst().code());
        assertTrue(expression.diagnostics().getFirst().location().isPresent());

        var java = convert(Language.C, Language.JAVA, "enum Color { RED, BLUE }; int f() { return RED; }");
        assertFalse(java.successful());
        assertEquals("CB-C-ENUM-TARGET-UNSUPPORTED", java.diagnostics().getFirst().code());
    }

    @Test void javaBytePrimitivesAndArraysConvertToCompilableCppAndBack() {
        String source = "class ByteExample { public static byte clamp(byte input) { byte[] values = new byte[2]; values[0] = input; return values[0]; } }";
        var cpp = convert(Language.JAVA, Language.CPP, source);
        assertTrue(cpp.successful(), () -> cpp.diagnostics().toString());
        String generated = cpp.output().orElseThrow();
        assertTrue(generated.contains("#include <cstdint>"), generated);
        assertTrue(generated.contains("#include <vector>"), generated);
        assertTrue(generated.contains("std::int8_t clamp(std::int8_t input)"), generated);
        assertTrue(generated.contains("std::vector<std::int8_t> values(2)"), generated);
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, generated);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE) assertTrue(validation.successful(), () -> validation.diagnostics().toString());
        var roundTrip = convert(Language.CPP, Language.JAVA, generated);
        assertTrue(roundTrip.successful(), () -> roundTrip.diagnostics().toString());
        assertTrue(roundTrip.output().orElseThrow().contains("byte clamp(byte input)"), roundTrip.output().orElseThrow());
        assertTrue(roundTrip.output().orElseThrow().contains("byte[] values = new byte[2]"), roundTrip.output().orElseThrow());
        var javaValidation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.JAVA, roundTrip.output().orElseThrow());
        if (javaValidation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE) assertTrue(javaValidation.successful(), () -> javaValidation.diagnostics().toString());
    }

    @Test void javaByteNarrowingIsLimitedToInRangeIntegerConstantsAndCRejectsByte() {
        var inRange = convert(Language.JAVA, Language.CPP, "class B { public static byte value() { byte result = 127; return result; } }");
        assertTrue(inRange.successful(), () -> inRange.diagnostics().toString());
        var outOfRange = convert(Language.JAVA, Language.CPP, "class B { public static byte value() { byte result = 128; return result; } }");
        assertFalse(outOfRange.successful());
        assertTrue(outOfRange.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-MISMATCH")), () -> outOfRange.diagnostics().toString());
        var c = convert(Language.JAVA, Language.C, "class B { public static byte value() { return 1; } }");
        assertFalse(c.successful());
        assertTrue(c.diagnostics().stream().anyMatch(d -> d.code().equals("CB-UNSUP-FEATURE") && d.message().contains("byte")), () -> c.diagnostics().toString());
    }

    @Test void cppInt8AndVectorInt8ConvertToCompilableJava() {
        String source = "#include <cstdint>\n#include <vector>\nstd::int8_t echo(std::int8_t input) { std::vector<std::int8_t> values(2); values[0] = input; return values[0]; }";
        var conversion = convert(Language.CPP, Language.JAVA, source);
        assertTrue(conversion.successful(), () -> conversion.diagnostics().toString());
        String generated = conversion.output().orElseThrow();
        assertTrue(generated.contains("byte echo(byte input)"), generated);
        assertTrue(generated.contains("byte[] values = new byte[2]"), generated);
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.JAVA, generated);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE) assertTrue(validation.successful(), () -> validation.diagnostics().toString());
    }

    @Test void cMainBecomesRunnableJavaEntryPoint() throws Exception {
        var result = convert(Language.C, Language.JAVA,
                "int main() { printf(\"ready\"); return 0; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("public static void main(String[] args)"), generated);
        assertFalse(generated.contains("public static int main()"), generated);
        assertFalse(generated.contains("return 0;"), generated);
        assertFalse(generated.contains("System.exit"), generated);
        Path source = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("runnable-java-classes"));
        Files.writeString(source, generated);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString()), generated);
    }

    @Test void cMainWithCommentsLiteralPrintfAndParenthesizedReturnConvertsToJava() throws Exception {
        String source = """
                #include <stdio.h>

                int main()
                {
                    // Print Name
                    printf("Name   : Alexandra Abramov\\n");
                    // Print Date of Birth
                    printf("DOB    : July 14, 1975\\n");
                    // Print Mobile Number
                    printf("Mobile : 99-9999999999\\n");
                    // Indicate successful execution
                    return(0);
                }
                """;
        var result = convert(Language.C, Language.JAVA, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("Name   : Alexandra Abramov\\n"), generated);
        assertTrue(generated.contains("DOB    : July 14, 1975\\n"), generated);
        assertTrue(generated.contains("Mobile : 99-9999999999\\n"), generated);
        Path javaFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("literal-output-java-classes"));
        Files.writeString(javaFile, generated);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), javaFile.toString()), generated);
    }
    @Test void generatedJavaMainCanConvertBackToNativeLanguages() {
        var java = convert(Language.C, Language.JAVA,
                "#include <stdio.h>\nint main() { int first = 2; int second = 3; printf(\"%d\\n\", first + second); return 0; }");
        assertTrue(java.successful(), () -> java.diagnostics().toString());
        for (Language target : List.of(Language.C, Language.CPP)) {
            var nativeResult = convert(Language.JAVA, target, java.output().orElseThrow());
            assertTrue(nativeResult.successful(), () -> target + ": " + nativeResult.diagnostics());
            String generated = nativeResult.output().orElseThrow();
            assertTrue(generated.contains("main("), generated);
            assertTrue(generated.contains("printf("), generated);
            assertTrue(generated.contains("first + second"), generated);
            assertTrue(generated.contains("return 0;"), generated);
            var validator = new com.tascode.codebridge.validation.CompilerValidationService()
                    .validate(target, generated);
            if (validator.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE) {
                assertTrue(validator.successful(), () -> target + ": " + validator.diagnostics());
            }
        }
    }

    @Test void generatedNonzeroJavaLauncherExitStatusConvertsBackToC() {
        var java = convert(Language.C, Language.JAVA, "int main() { return 7; }");
        assertTrue(java.successful(), () -> java.diagnostics().toString());
        var c = convert(Language.JAVA, Language.C, java.output().orElseThrow());
        assertTrue(c.successful(), () -> c.diagnostics().toString());
        assertTrue(c.output().orElseThrow().contains("return 7;"), c.output().orElseThrow());
        assertFalse(c.output().orElseThrow().contains("return 0;"), c.output().orElseThrow());
    }

    @Test void javaLauncherArgumentsAreRejectedWhenTheBodyUsesThem() {
        var result = convert(Language.JAVA, Language.C,
                "class Example { public static void main(String[] args) { System.out.println(args[0]); } }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("command-line arguments")),
                () -> result.diagnostics().toString());
    }

    @Test void cMainEntryPointUsesVoidSignatureAndPreservesNonzeroExitStatus() {
        var result = convert(Language.C, Language.JAVA, "int main() { return 7; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("public static void main(String[] args)"), generated);
        assertTrue(generated.contains("System.exit(7); return;"), generated);
        assertFalse(generated.contains("public static int main()"), generated);
    }

    @Test void cBitwiseShiftsAndCompoundAssignmentsConvertToJava() throws Exception {
        String source = "int compute(int value) { value |= 2; value <<= 1; value ^= 3; return (~value & 15) >> 1; }";
        var result = convert(Language.C, Language.JAVA, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("value = (value | 2)"), generated);
        assertTrue(generated.contains("value = (value << 1)"), generated);
        assertTrue(generated.contains("value = (value ^ 3)"), generated);
        var compilation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.JAVA, generated);
        assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

        var invalid = convert(Language.C, Language.JAVA, "double invalid(double value) { return value & 1; }");
        assertFalse(invalid.successful());
        assertTrue(invalid.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-INTEGRAL-OPERANDS")),
                invalid.diagnostics().toString());
    }

    @Test void unsupportedCConstructFamiliesHaveSpecificLocatedDiagnostics() {
        List<String[]> samples = List.of(
                new String[]{"int read(int *value) { return *value; }", "CB-C-POINTER-UNSUPPORTED"},
                new String[]{"struct Point { int x; }; int read() { return 0; }", "CB-C-AGGREGATE-UNSUPPORTED"},
                new String[]{"int read(int value) { switch (value) { case 1: return 1; default: return 0; } }", "CB-C-SWITCH-UNSUPPORTED"},
                new String[]{"int read(int value) { goto done; done: return value; }", "CB-C-GOTO-UNSUPPORTED"},
                new String[]{"void main() { return; }", "CB-C-MAIN-UNSUPPORTED"},
                new String[]{"int main(int argc) { return argc; }", "CB-C-MAIN-UNSUPPORTED"},
                new String[]{"int total = 2; int main() { return total; }", "CB-C-GLOBAL-STATE-UNSUPPORTED"},
                new String[]{"unsigned int main() { return 0; }", "CB-C-QUALIFIER-UNSUPPORTED"});
        for (String[] sample : samples) {
            var result = convert(Language.C, Language.JAVA, sample[0]);
            assertFalse(result.successful(), () -> sample[0]);
            var diagnostic = result.diagnostics().stream().filter(d -> d.code().equals(sample[1])).findFirst()
                    .orElseThrow(() -> new AssertionError(result.diagnostics().toString()));
            assertTrue(diagnostic.location().isPresent(), sample[0]);
        }
    }    @Test void cFunctionPrototypesAreIgnoredWhenTheirDefinitionsAreConverted() throws Exception {
        String source = "int add(int left, int right);\nint main(void) { return add(2, 3); }\nint add(int left, int right) { return left + right; }";
        var result = convert(Language.C, Language.JAVA, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("public static int add(int left, int right)"), generated);
        assertTrue(generated.contains("System.exit(add(2, 3))"), generated);
        var compilation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.JAVA, generated);
        assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());
    }    @Test void cHexadecimalAndOctalIntegerLiteralsConvertByTheirNumericValue() throws Exception {
        var result = convert(Language.C, Language.JAVA, "int values() { return 0x2A + 052; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("return (42 + 42);"), generated);
        var compilation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.JAVA, generated);
        assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

        var unsigned = convert(Language.C, Language.JAVA, "int values() { return 42u; }");
        assertFalse(unsigned.successful());
        assertTrue(unsigned.diagnostics().getFirst().message().contains("unsigned integer literals"), unsigned.diagnostics().toString());
    }    @Test void cPrototypesSupportCallsBeforeDefinitionsInGeneratedCpp() {
        String source = "int add(int left, int right); int main(void) { return add(2, 3); } int add(int left, int right) { return left + right; }";
        var conversion = convert(Language.C, Language.CPP, source);
        assertTrue(conversion.successful(), () -> conversion.diagnostics().toString());
        String generated = conversion.output().orElseThrow();
        assertTrue(generated.indexOf("int add(int left, int right);") < generated.indexOf("int main() {"), generated);
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, generated);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE) assertTrue(validation.successful(), () -> validation.diagnostics().toString());
        var roundTrip = convert(Language.CPP, Language.CPP, generated);
        assertTrue(roundTrip.successful(), () -> roundTrip.diagnostics().toString());
    }

    @Test void cToCppUsesCppEmptyParameterSyntax() {
        var result = convert(Language.C, Language.CPP, "int main(void) { return 0; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().contains("int main()"));
    }

    @Test void cIdentifiersThatAreCppKeywordsAreRejectedOnlyForCppOutput() {
        String source = "int value() { int class[2]; class[0] = 3; return class[0]; }";
        var cpp = convert(Language.C, Language.CPP, source);
        assertFalse(cpp.successful());
        var diagnostic = cpp.diagnostics().stream().filter(d -> d.code().equals("CB-CPP-IDENTIFIER-RESERVED")).findFirst().orElseThrow();
        assertTrue(diagnostic.message().contains("class"), diagnostic.message());
        assertTrue(diagnostic.location().isPresent());
        var c = convert(Language.C, Language.C, source);
        assertTrue(c.successful(), () -> c.diagnostics().toString());
        assertTrue(c.output().orElseThrow().contains("int class[2];"), c.output().orElseThrow());
    }

    @Test void cLongKeepsNativeCppLongWidthWhileJavaLongUsesLongLong() {
        var cToCpp = convert(Language.C, Language.CPP, "long echo(long value) { return value; }");
        assertTrue(cToCpp.successful(), () -> cToCpp.diagnostics().toString());
        String generated = cToCpp.output().orElseThrow();
        assertTrue(generated.contains("long echo(long value)"), generated);
        assertFalse(generated.contains("long long echo"), generated);
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, generated);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE) assertTrue(validation.successful(), () -> validation.diagnostics().toString());
        var roundTrip = convert(Language.CPP, Language.CPP, generated);
        assertTrue(roundTrip.successful(), () -> roundTrip.diagnostics().toString());
        assertTrue(roundTrip.output().orElseThrow().contains("long echo(long value)"), roundTrip.output().orElseThrow());

        var cRoundTrip = convert(Language.C, Language.C, "long echo(long value) { return value; }");
        assertTrue(cRoundTrip.successful(), () -> cRoundTrip.diagnostics().toString());
        assertTrue(cRoundTrip.output().orElseThrow().contains("long echo(long value)"), cRoundTrip.output().orElseThrow());

        var javaToCpp = convert(Language.JAVA, Language.CPP, "class Width { public static long echo(long value) { return value; } }");
        assertTrue(javaToCpp.successful(), () -> javaToCpp.diagnostics().toString());
        assertTrue(javaToCpp.output().orElseThrow().contains("long long echo(long long value)"), javaToCpp.output().orElseThrow());
    }
    @Test void cRuntimeSizedArraysAndLoopsConvertToCppVectorsAndCompile() {
        String source = "int sum(int count) { int values[count]; for (int i = 0; i < count; i++) { values[i] = i + 1; } return values[0] + values[count - 1]; }";
        var conversion = convert(Language.C, Language.CPP, source);
        assertTrue(conversion.successful(), () -> conversion.diagnostics().toString());
        String generated = conversion.output().orElseThrow();
        assertTrue(generated.contains("#include <vector>"), generated);
        assertTrue(generated.contains("std::vector<int> values(count)"), generated);
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, generated);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE) assertTrue(validation.successful(), () -> validation.diagnostics().toString());
    }

    @Test void excessiveSourceNestingReturnsADiagnosticInsteadOfOverflowingTheRequest() {
        String nested = "(".repeat(20_000) + "1" + ")".repeat(20_000);
        var result = convert(Language.C, Language.JAVA, "int value() { return " + nested + "; }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-PARSE-NESTING-LIMIT")));
    }
    @Test void unsupportedSyntaxReturnsLocatedDiagnostic() {
        var result = convert(Language.C, Language.C, "int main() { int *p; return 0; }");
        assertFalse(result.successful());
        assertEquals(Severity.ERROR, result.diagnostics().getFirst().severity());
        assertEquals("CB-C-POINTER-UNSUPPORTED", result.diagnostics().getFirst().code());
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

    @Test void javaInstanceMethodsAreRejectedInsteadOfFlattenedToStaticFunctions() {
        var result = convert(Language.JAVA, Language.C, "class Counter { int increment(int value) { return value + 1; } }");
        assertFalse(result.successful());
        assertEquals("CB-PARSE-JAVA-UNSUPPORTED", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().message().contains("Only static Java methods are supported"));
        assertTrue(result.diagnostics().getFirst().location().isPresent());
    }

    @Test void javaPrimitiveDataClassesBecomeCompilableCppClasses() throws Exception {
        var result = convert(Language.JAVA, Language.CPP,
                "public class Point { private int x; public double y = 2.5; public boolean visible; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("class Point"));
        assertTrue(generated.contains("private:"));
        assertTrue(generated.contains("int x{};"));
        assertTrue(generated.contains("double y = 2.5;"));
        assertTrue(generated.contains("bool visible{};"));
        compileCpp(generated);
    }
    @Test void javaConstructorsNestedClassesAndInstanceInitializersHaveExplicitDiagnostics() {
        var constructor = convert(Language.JAVA, Language.C, "class Counter { Counter() { } }");
        assertEquals("CB-JAVA-CONSTRUCTOR-UNSUPPORTED", constructor.diagnostics().getFirst().code());
        assertTrue(constructor.diagnostics().getFirst().location().isPresent());

        var nested = convert(Language.JAVA, Language.C,
                "class Outer { static class Inner { } static int value() { return 1; } }");
        assertEquals("CB-JAVA-NESTED-CLASS-UNSUPPORTED", nested.diagnostics().getFirst().code());
        assertTrue(nested.diagnostics().getFirst().location().isPresent());

        var initializer = convert(Language.JAVA, Language.C,
                "class Counter { { int value = 1; } static int next() { return 1; } }");
        assertEquals("CB-JAVA-CLASS-STATE-UNSUPPORTED", initializer.diagnostics().getFirst().code());
        assertTrue(initializer.diagnostics().getFirst().location().isPresent());
    }
    @Test void javaFieldsAndInitializerBlocksHaveSpecificLocatedDiagnostics() {
        for (String source : List.of(
                "class Counter { int value; static int next() { return 1; } }",
                "class Counter { static { int value = 0; } static int next() { return 1; } }")) {
            var result = convert(Language.JAVA, Language.C, source);
            assertFalse(result.successful());
            assertEquals("CB-JAVA-CLASS-STATE-UNSUPPORTED", result.diagnostics().getFirst().code(),
                    result.diagnostics().toString());
            assertTrue(result.diagnostics().getFirst().location().isPresent(), result.diagnostics().toString());
        }
    }
    @Test void cppClassesAndInheritanceHaveClearDiagnostics() {
        var classResult = convert(Language.CPP, Language.JAVA, "class Counter { int value; };");
        assertFalse(classResult.successful());
        assertEquals("CB-CPP-CLASS-UNSUPPORTED", classResult.diagnostics().getFirst().code());
        assertTrue(classResult.diagnostics().getFirst().location().isPresent());

        var inheritance = convert(Language.CPP, Language.JAVA, "class Counter : public Base { };");
        assertFalse(inheritance.successful());
        assertEquals("CB-CPP-INHERITANCE-UNSUPPORTED", inheritance.diagnostics().getFirst().code());

        var commentOnly = convert(Language.CPP, Language.CPP, "// class Example { }\nint value() { return 1; }");
        assertTrue(commentOnly.successful(), () -> commentOnly.diagnostics().toString());
    }
    @Test void javaGenericsAndCppTemplatesHaveExplicitDiagnostics() {
        var javaResult = convert(Language.JAVA, Language.CPP, "class Example { public static int size(java.util.List<String> values) { return 0; } }");
        assertFalse(javaResult.successful());
        assertEquals("CB-JAVA-GENERICS-UNSUPPORTED", javaResult.diagnostics().getFirst().code());
        assertTrue(javaResult.diagnostics().getFirst().location().isPresent());

        var cppResult = convert(Language.CPP, Language.JAVA, "template <typename T> T identity(T value) { return value; }");
        assertFalse(cppResult.successful());
        assertEquals("CB-CPP-TEMPLATE-UNSUPPORTED", cppResult.diagnostics().getFirst().code());

        var vectorResult = convert(Language.CPP, Language.JAVA, "int size() { std::vector<int> values; return 0; }");
        assertFalse(vectorResult.successful());
        assertEquals("CB-CPP-TEMPLATE-UNSUPPORTED", vectorResult.diagnostics().getFirst().code());

        var commentOnly = convert(Language.JAVA, Language.JAVA, "class Example { // List<String> remains a comment\n public static int value() { return 1; } }");
        assertTrue(commentOnly.successful(), () -> commentOnly.diagnostics().toString());
    }
    @Test void cppCommonUnsupportedSyntaxHasSpecificLocatedDiagnostics() {
        java.util.Map<String, String> cases = java.util.Map.ofEntries(
                java.util.Map.entry("int f() { auto value = 1; return value; }", "CB-CPP-AUTO-UNSUPPORTED"),
                java.util.Map.entry("int f(int* value) { return *value; }", "CB-CPP-POINTER-UNSUPPORTED"),
                java.util.Map.entry("enum Color { RED, BLUE }; int f() { return 0; }", "CB-CPP-ENUMS-UNSUPPORTED"),
                java.util.Map.entry("int f() { try { return 0; } catch (...) { return 1; } }", "CB-CPP-EXCEPTIONS-UNSUPPORTED"),
                java.util.Map.entry("template <typename T> T f(T value) { return value; }", "CB-CPP-TEMPLATE-UNSUPPORTED"),
                java.util.Map.entry("int f() { int* value = new int(1); return *value; }", "CB-CPP-MEMORY-UNSUPPORTED"),
                java.util.Map.entry("constexpr int f() { return 1; }", "CB-CPP-QUALIFIER-UNSUPPORTED"),
                java.util.Map.entry("int f(int value) { return static_cast<int>(value); }", "CB-CPP-CAST-UNSUPPORTED"),
                java.util.Map.entry("class Child : public Parent { };", "CB-CPP-INHERITANCE-UNSUPPORTED"));
        cases.forEach((source, expected) -> {
            var result = convert(Language.CPP, Language.JAVA, source);
            assertFalse(result.successful(), source);
            assertEquals(expected, result.diagnostics().getFirst().code(), result.diagnostics().toString());
            assertTrue(result.diagnostics().getFirst().location().isPresent(), result.diagnostics().toString());
        });
        var commentOnly = convert(Language.CPP, Language.CPP,
                "// auto, new, static_cast and constexpr are comment text\nint value() { return 1; }");
        assertTrue(commentOnly.successful(), () -> commentOnly.diagnostics().toString());
    }    @Test void javaInheritanceAndInterfacesHaveExplicitDiagnostics() {
        var inheritance = convert(Language.JAVA, Language.CPP, "class Child extends Parent { public static int value() { return 1; } }");
        assertFalse(inheritance.successful());
        assertEquals("CB-JAVA-INHERITANCE-UNSUPPORTED", inheritance.diagnostics().getFirst().code());

        var implemented = convert(Language.JAVA, Language.CPP, "class Child implements Runnable { public static int value() { return 1; } }");
        assertFalse(implemented.successful());
        assertEquals("CB-JAVA-INHERITANCE-UNSUPPORTED", implemented.diagnostics().getFirst().code());

        var interfaceResult = convert(Language.JAVA, Language.CPP, "interface Action { }");
        assertFalse(interfaceResult.successful());
        assertEquals("CB-JAVA-INTERFACE-UNSUPPORTED", interfaceResult.diagnostics().getFirst().code());
    }
    @Test void javaAllowsCommentsAroundItsSingleTopLevelClass() {
        var result = convert(Language.JAVA, Language.C,
                "// module note" + System.lineSeparator() + "class Example { public static int value() { return 7; } } /* trailing note */");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().contains("return 7;"));

        var extraCode = convert(Language.JAVA, Language.C,
                "class Example { public static int value() { return 7; } } int extra;");
        assertFalse(extraCode.successful());
    }
    @Test void javaUnsupportedKeywordChecksIgnoreCommentsAndStringLiterals() {
        var result = convert(Language.JAVA, Language.JAVA,
                "class Example { // class Child extends Parent" + System.lineSeparator() + " public static String value() { return " + (char)34 + "class Child implements Runnable; interface Action; enum Color" + (char)34 + "; } }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().contains("class Child implements Runnable; interface Action; enum Color"));
    }
    @Test void javaAndCppExceptionSyntaxHaveExplicitDiagnostics() {
        var javaResult = convert(Language.JAVA, Language.CPP, "class Example { public static int value() { try { return 1; } catch (Exception e) { return 0; } } }");
        assertFalse(javaResult.successful());
        assertEquals("CB-JAVA-EXCEPTIONS-UNSUPPORTED", javaResult.diagnostics().getFirst().code());

        var cppResult = convert(Language.CPP, Language.JAVA, "int value() { try { return 1; } catch (...) { return 0; } }");
        assertFalse(cppResult.successful());
        assertEquals("CB-CPP-EXCEPTIONS-UNSUPPORTED", cppResult.diagnostics().getFirst().code());

        var commentOnly = convert(Language.CPP, Language.CPP, "// try and catch are just comment text\nint value() { return 1; }");
        assertTrue(commentOnly.successful(), () -> commentOnly.diagnostics().toString());
    }
    @Test void nonPublicJavaMembersAreNotSilentlyMadePublic() {
        var result = convert(Language.JAVA, Language.CPP,
                "class Helpers { private static int hidden() { return 1; } public static int use() { return hidden(); } }");
        assertFalse(result.successful());
        assertEquals("CB-JAVA-ACCESS-UNSUPPORTED", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().location().isPresent());
    }
    @Test void finalJavaParametersAreNotSilentlyMadeMutable() {
        var result = convert(Language.JAVA, Language.CPP,
                "class Counter { public static int next(final int value) { value = 2; return value; } }");
        assertFalse(result.successful());
        assertEquals("CB-PARSE-JAVA-UNSUPPORTED", result.diagnostics().getFirst().code());
    }
    @Test void synchronizedJavaMethodsAreRejectedInsteadOfSilentlyFlattened() {
        var result = convert(Language.JAVA, Language.CPP,
                "class Counter { public static synchronized int next() { return 1; } }");
        assertFalse(result.successful());
        assertEquals("CB-JAVA-THREADING-UNSUPPORTED", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().location().isPresent());
    }
    @Test void cppUnsupportedKeywordInMacroDirectiveIsReportedByPreprocessor() {
        var result = convert(Language.CPP, Language.JAVA,
                "#define TRY try" + (char) 10 + "int value() { return 1; }");
        assertFalse(result.successful());
        assertEquals("CB-PREPROC-UNSUPPORTED-MACRO", result.diagnostics().getFirst().code());
    }    @Test void javaAndCppEnumsHaveExplicitDiagnostics() {
        var javaResult = convert(Language.JAVA, Language.CPP, "enum Color { RED, BLUE }");
        assertFalse(javaResult.successful());
        assertEquals("CB-JAVA-ENUMS-UNSUPPORTED", javaResult.diagnostics().getFirst().code());

        var cppResult = convert(Language.CPP, Language.JAVA, "enum Color { RED, BLUE };");
        assertFalse(cppResult.successful());
        assertEquals("CB-CPP-ENUMS-UNSUPPORTED", cppResult.diagnostics().getFirst().code());
    }
    @Test void javaCppUnsupportedSyntaxHasStableLocatedDiagnostics() {
        java.util.Map<String, String> cases = java.util.Map.ofEntries(
                java.util.Map.entry("class X { public static int f() { java.util.function.IntUnaryOperator op = x -> x + 1; return 0; } }", "CB-JAVA-LAMBDA-UNSUPPORTED"),
                java.util.Map.entry("class X { public static String f() { return null; } }", "CB-JAVA-NULL-UNSUPPORTED"),
                java.util.Map.entry("class X { public static int f() { var value = 1; return value; } }", "CB-JAVA-VAR-UNSUPPORTED"),
                java.util.Map.entry("class X { public static int f(int value) { switch(value) { default: return 0; } } }", "CB-JAVA-SWITCH-UNSUPPORTED"),
                java.util.Map.entry("class X { @Override public static int f() { return 0; } }", "CB-JAVA-ANNOTATION-UNSUPPORTED"),
                java.util.Map.entry("package demo; class X { public static int f() { return 0; } }", "CB-JAVA-IMPORT-UNSUPPORTED"),
                java.util.Map.entry("class X { public static int f(String[] values) { return 0; } }", "CB-JAVA-ARRAY-ELEMENT-UNSUPPORTED"),
                java.util.Map.entry("class X { public static int f(char[] values) { return 0; } }", "CB-JAVA-ARRAY-ELEMENT-UNSUPPORTED"),
                java.util.Map.entry("class X { public static int f(int[] values) { for (int value : values) { return value; } return 0; } }", "CB-JAVA-ENHANCED-FOR-UNSUPPORTED"),
                java.util.Map.entry("class X { public static int f(int value) { return (int) value; } }", "CB-JAVA-CAST-UNSUPPORTED"),
                java.util.Map.entry("class X { public static int f() throws Exception { return 0; } }", "CB-JAVA-EXCEPTIONS-UNSUPPORTED"));        cases.forEach((source, expected) -> {
            var result = convert(Language.JAVA, Language.CPP, source);
            assertFalse(result.successful(), source);
            assertEquals(expected, result.diagnostics().getFirst().code(), result.diagnostics().toString());
            assertTrue(result.diagnostics().getFirst().location().isPresent(), result.diagnostics().toString());
        });
        var literalText = convert(Language.JAVA, Language.CPP,
                "class X { public static String value() { return \"new null x -> y @Override\"; } }");
        assertTrue(literalText.successful(), () -> literalText.diagnostics().toString());
    }
    @Test void finalStaticJavaMethodsConvertToCpp() {
        var converted = convert(Language.JAVA, Language.CPP,
                "class Values { public static final int one() { return 1; } }");
        assertTrue(converted.successful(), () -> converted.diagnostics().toString());
        assertTrue(converted.output().orElseThrow().contains("int one()"), converted.output().orElseThrow());
    }

    @Test void javaLongValuesUsePortableSixtyFourBitCppTypes() {
        var converted = convert(Language.JAVA, Language.CPP,
                "class Values { public static long add(long left, long right) { return left + right + 1L; } }");
        assertTrue(converted.successful(), () -> converted.diagnostics().toString());
        String cpp = converted.output().orElseThrow();
        assertTrue(cpp.contains("long long add(long long left, long long right)"), cpp);
        assertTrue(cpp.contains("1LL"), cpp);
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, cpp);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE)
            assertTrue(validation.successful(), () -> validation.diagnostics().toString());

        var roundTrip = convert(Language.CPP, Language.JAVA, "long long add(long long left, long long right) { return left + right; }");
        assertTrue(roundTrip.successful(), () -> roundTrip.diagnostics().toString());
        assertTrue(roundTrip.output().orElseThrow().contains("long add(long left, long right)"), roundTrip.output().orElseThrow());
    }
    @Test void javaArraysUseZeroInitializedPortableCppVectors() {
        var converted = convert(Language.JAVA, Language.CPP,
                "class Values { public static int first(int size) { int[] values = new int[size]; return values[0]; } }");
        assertTrue(converted.successful(), () -> converted.diagnostics().toString());
        String cpp = converted.output().orElseThrow();
        assertTrue(cpp.contains("#include <vector>"), cpp);
        assertTrue(cpp.contains("std::vector<int> values(size);"), cpp);
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, cpp);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE)
            assertTrue(validation.successful(), () -> validation.diagnostics().toString());
    }

    @Test void javaArrayInitializerListsConvertToCppVectors() {
        var converted = convert(Language.JAVA, Language.CPP,
                "class Values { public static int sum() { int[] values = {2, 3, 5}; return values[0] + values[1] + values[2]; } }");
        assertTrue(converted.successful(), () -> converted.diagnostics().toString());
        String cpp = converted.output().orElseThrow();
        assertTrue(cpp.contains("std::vector<int> values(3);"), cpp);
        assertTrue(cpp.contains("values[0] = 2"), cpp);
        assertTrue(cpp.contains("values[1] = 3"), cpp);
        assertTrue(cpp.contains("values[2] = 5"), cpp);
    }

    @Test void explicitlyInitializedJavaStringArraysConvertToCpp() {
        var converted = convert(Language.JAVA, Language.CPP,
                "class Values { public static void show() { String[] values = {\"left\", \"right\"}; System.out.println(values[1]); } }");
        assertTrue(converted.successful(), () -> converted.diagnostics().toString());
        String cpp = converted.output().orElseThrow();
        assertTrue(cpp.contains("#include <string>"), cpp);
        assertTrue(cpp.contains("#include <vector>"), cpp);
        assertTrue(cpp.contains("std::vector<std::string> values(2);"), cpp);
        assertTrue(cpp.contains("(values[0] = \"left\");"), cpp);
        assertTrue(cpp.contains("(values[1] = \"right\");"), cpp);
        assertTrue(cpp.contains("std::cout << values[1] << std::endl;"), cpp);
    }
    @Test void explicitNewStringArrayInitializerConvertsToCpp() {
        var converted = convert(Language.JAVA, Language.CPP,
                "class Values { public static String get() { String[] values = new String[]{\"left\", \"right\"}; return values[0]; } }");
        assertTrue(converted.successful(), () -> converted.diagnostics().toString());
        String cpp = converted.output().orElseThrow();
        assertTrue(cpp.contains("std::vector<std::string> values(2);"), cpp);
        assertTrue(cpp.contains("(values[0] = \"left\");"), cpp);
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, cpp);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE)
            assertTrue(validation.successful(), () -> validation.diagnostics().toString());
    }

    @Test void uninitializedJavaStringArrayIsRejectedWithNullSemanticsDiagnostic() {
        var converted = convert(Language.JAVA, Language.CPP,
                "class Values { public static int size() { String[] values = new String[2]; return values.length; } }");
        assertFalse(converted.successful());
        assertTrue(converted.diagnostics().stream().anyMatch(d -> d.code().equals("CB-JAVA-ARRAY-ELEMENT-UNSUPPORTED")),
                () -> converted.diagnostics().toString());
    }
    @Test void javaArrayParametersGenerateMutableVectorReferencesInCpp() {
        var converted = convert(Language.JAVA, Language.CPP,
                "class Values { public static int first(int[] values) { int[] copy = new int[2]; copy[0] = values[0]; return copy[0]; } }");
        assertTrue(converted.successful(), () -> converted.diagnostics().toString());
        String cpp = converted.output().orElseThrow();
        assertTrue(cpp.contains("std::vector<int>& values"), cpp);
        assertTrue(cpp.contains("std::vector<int> copy(2);"), cpp);
        assertTrue(cpp.contains("values[0]"), cpp);
        var roundTrip = convert(Language.CPP, Language.JAVA, cpp);
        assertTrue(roundTrip.successful(), () -> roundTrip.diagnostics().toString());
        assertTrue(roundTrip.output().orElseThrow().contains("int[] values"), roundTrip.output().orElseThrow());
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, cpp);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE)
            assertTrue(validation.successful(), () -> validation.diagnostics().toString());
    }

    @Test void javaArraysAreRejectedWithStableDiagnostic() {
        var result = convert(Language.JAVA, Language.C, "class Example { int[] values; }");
        assertFalse(result.successful());
        assertEquals("CB-JAVA-CLASS-STATE-UNSUPPORTED", result.diagnostics().getFirst().code());
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

    @Test void narrowingNumericConversionsAreRejectedAcrossLanguageBoundaries() {
        for (String[] sample : List.of(
                new String[]{"c", "java", "int value() { return 3.5; }"},
                new String[]{"java", "c", "class Example { public static int value() { double amount = 3.5; return amount; } }"})) {
            var result = convert(Language.fromId(sample[0]), Language.fromId(sample[1]), sample[2]);
            assertFalse(result.successful());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-MISMATCH")),
                    () -> result.diagnostics().toString());
        }
    }    @Test void JavaArraySizesAndIndexesRejectLongValuesIncludingCallResults() {
        for (String source : List.of(
                "int value() { long index = 0; int values[2]; return values[index]; }",
                "long index() { return 0L; } int value() { int values[2]; return values[index()]; }",
                "long size() { return 2L; } int value() { int values[size()]; return 0; }")) {
            var result = convert(Language.C, Language.JAVA, source);
            assertFalse(result.successful());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-INTEGRAL") || d.code().equals("CB-TYPE-CALL-VALUE")),
                    () -> result.diagnostics().toString());
        }
    }
    @Test void floatingPointArraySizesAndIndexesAreRejectedEarly() {
        for (String source : List.of(
                "int value() { int values[2.5]; return 0; }",
                "int value() { int values[2]; return values[1.5]; }")) {
            var result = convert(Language.C, Language.JAVA, source);
            assertFalse(result.successful());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-INTEGRAL")),
                    () -> result.diagnostics().toString());
        }
    }    @Test void floatingPointLiteralsConvertWithTheirPrecisionSuffixes() throws Exception {
        for (String[] sample : List.of(new String[]{"double", "3.14", "3.14"}, new String[]{"float", "2.5f", "2.5f"}, new String[]{"double", "1.25e2", "125.0"})) {
            var result = convert(Language.C, Language.JAVA,
                    sample[0] + " value() { return " + sample[1] + "; }");
            assertTrue(result.successful(), () -> result.diagnostics().toString());
            String generated = result.output().orElseThrow();
            assertTrue(generated.contains("return " + sample[2] + ";"), generated);
            Path source = tempDir.resolve("TasCodeGenerated.java");
            Path classes = Files.createDirectory(tempDir.resolve("float-" + classesCounter++));
            Files.writeString(source, generated);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()), generated);
        }
    }    @Test void longIntegerLiteralsConvertAndOutOfRangeValuesGetLocatedDiagnostics() throws Exception {
        var result = convert(Language.C, Language.JAVA,
                "long large() { return 2147483648L; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("return 2147483648L;"), generated);
        Path source = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("large-integer-classes"));
        Files.writeString(source, generated);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()), generated);

        var overflow = convert(Language.C, Language.JAVA,
                "long tooLarge() { return 9223372036854775808; }");
        assertFalse(overflow.successful());
        assertEquals("CB-PARSE-C-UNSUPPORTED", overflow.diagnostics().getFirst().code());
        assertTrue(overflow.diagnostics().getFirst().location().isPresent());
    }    @Test void functionReturnTypesAreUsedWhenCheckingConditions() {
        var result = convert(Language.JAVA, Language.CPP,
                "class Example { public static String text() { return \"yes\"; } public static int check() { if (text()) return 1; return 0; } }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-CONDITION")),
                () -> result.diagnostics().toString());
    }    @Test void numericAssignmentAndUpdateConditionsConvertToJavaBooleanExpressions() {
        for (String condition : List.of("count++", "(count = 1)")) {
            var result = convert(Language.C, Language.JAVA,
                    "int check(int count) { if (" + condition + ") return 1; return 0; }");
            assertTrue(result.successful(), () -> condition + ": " + result.diagnostics());
            assertTrue(result.output().orElseThrow().contains("!= 0"), result.output().orElseThrow());
        }
    }
    @Test void nestedForLoopsGetConditionTypeChecks() {
        var result = convert(Language.C, Language.JAVA,
                "void check() { if (1) for (int i = 0; \"not a condition\"; i++) { } }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-CONDITION")),
                () -> result.diagnostics().toString());
    }
    @Test void scalarValuesCannotBeIndexedAsArrays() {
        var result = convert(Language.C, Language.JAVA,
                "int value() { int scalar = 5; return scalar[0]; }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-ARRAY-ACCESS")),
                () -> result.diagnostics().toString());
    }
    @Test void nestedAssignmentsAreTypeCheckedRecursively() {
        var result = convert(Language.C, Language.JAVA,
                "int value() { int number = 0; number = (number = \"wrong\"); return number; }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-MISMATCH")),
                () -> result.diagnostics().toString());
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

    @Test void builtInWebSamplesConvertForEveryLanguagePair() {
        String[][] samples = {
                {"c", "cpp", "#include <stdio.h>\nint add(int left, int right) { return left + right; }"},
                {"c", "java", "#include <stdio.h>\nint main() { int first = 2; int second = 3; printf(\"%d\\n\", first + second); return 0; }"},
                {"cpp", "c", "int add(int left, int right) { return left + right; }"},
                {"cpp", "java", "int add(int left, int right) { return left + right; }"},
                {"java", "c", "class Example { static int add(int left, int right) { return left + right; } }"},
                {"java", "cpp", "class Example { static int add(int left, int right) { return left + right; } }"},
                {"c", "c", "int add(int left, int right) { return left + right; }"},
                {"cpp", "cpp", "int add(int left, int right) { return left + right; }"},
                {"java", "java", "class Example { static int add(int left, int right) { return left + right; } }"}
        };
        for (String[] sample : samples) {
            var result = convert(Language.fromId(sample[0]), Language.fromId(sample[1]), sample[2]);
            assertTrue(result.successful(), () -> sample[0] + " -> " + sample[1] + ": " + result.diagnostics());
            assertTrue(result.output().isPresent() && !result.output().orElseThrow().isBlank());
        }
    }
    @Test void CTargetRejectsStringValuesButAllowsConsoleStringLiterals() {
        var comparison = convert(Language.C, Language.C, "bool same() { return \"a\" == \"a\"; }");
        assertTrue(comparison.diagnostics().stream().anyMatch(d -> d.code().equals("CB-UNSUP-STRING-C")));

        var output = convert(Language.JAVA, Language.C,
                "class Text { static void show() { System.out.println(\"hello\"); } }");
        assertTrue(output.successful(), () -> output.diagnostics().toString());
        assertTrue(output.output().orElseThrow().contains("puts(\"hello\")"));
    }
    @Test void supportedHeadersAreRemovedBeforeParsingAndRestoredForCLikeTargets() {
        var result = convert(Language.C, Language.C, "#include <stdio.h>\nint answer() { return 42; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().startsWith("#include <stdio.h>"));
    }

    @Test void loopControlOutsideLoopsIsRejectedWithLocations() {
        var breakOutside = convert(Language.C, Language.JAVA, "int value() { break; return 0; }");
        var breakDiagnostic = breakOutside.diagnostics().stream()
                .filter(d -> d.code().equals("CB-PARSE-C-UNSUPPORTED") && d.message().contains("break is only valid inside a loop")).findFirst().orElseThrow();
        assertTrue(breakDiagnostic.location().isPresent());

        var continueOutside = convert(Language.C, Language.JAVA, "int value() { continue; return 0; }");
        assertTrue(continueOutside.diagnostics().stream().anyMatch(d -> d.code().equals("CB-PARSE-C-UNSUPPORTED") && d.message().contains("continue is only valid inside a loop")));

        var insideLoop = convert(Language.C, Language.JAVA, "int value(int x) { while (x > 0) { break; } return 0; }");
        assertTrue(insideLoop.successful(), () -> insideLoop.diagnostics().toString());
    }
    @Test void cppOnlyHeadersAreRejectedInCInput() {
        var result = convert(Language.C, Language.C, "#include <iostream>\nint value() { return 1; }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-PREPROC-HEADER-LANGUAGE")));
    }
    @Test void simpleLiteralMacrosAreExpandedAndPreserveColumns() {
        var result = convert(Language.C, Language.C, "#define MAX 10\nint answer() { return MAX + 1; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().contains("return (10 + 1);"));
    }

    @Test void preprocessorAcceptsTrailingCommentsOnSimpleDirectives() {
        var result = convert(Language.C, Language.C,
                "#include <stdio.h> // standard output" + System.lineSeparator()
                        + "#define LIMIT 10 /* constant */" + System.lineSeparator()
                        + "int answer() { return LIMIT; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().startsWith("#include <stdio.h>"));
        assertTrue(result.output().orElseThrow().contains("return 10;"));
    }    @Test void unsupportedMacroExpansionReturnsAStableDiagnostic() {
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
        assertTrue(javaToC.output().orElseThrow().startsWith("#include <stdio.h>"));
    }
    @Test void crossLanguagePrintfFormatsAreCheckedConservatively() {
        var cDouble = convert(Language.C, Language.JAVA, "double show(double value) { printf(\"%lf\", value); return value; }");
        assertTrue(cDouble.successful(), () -> cDouble.diagnostics().toString());
        assertTrue(cDouble.output().orElseThrow().contains("System.out.printf(\"%f\", value)"), cDouble.output().orElseThrow());        var escapedPercent = convert(Language.C, Language.JAVA,
                "double show(double value) { printf(\"%%%5.2lf\", value); return value; }");
        assertTrue(escapedPercent.successful(), () -> escapedPercent.diagnostics().toString());
        assertTrue(escapedPercent.output().orElseThrow().contains("System.out.printf(\"%%%5.2f\", value)"), escapedPercent.output().orElseThrow());


        var unsupportedLength = convert(Language.C, Language.JAVA, "int show() { printf(\"%ld\", 42); return 0; }");
        assertFalse(unsupportedLength.successful());
        assertTrue(unsupportedLength.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-FORMAT-UNSUPPORTED")), unsupportedLength.diagnostics().toString());

        var wrongType = convert(Language.C, Language.JAVA, "int show() { printf(\"%d\", \"text\"); return 0; }");
        assertFalse(wrongType.successful());
        assertTrue(wrongType.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-FORMAT-ARG-TYPE")), wrongType.diagnostics().toString());

        var countMismatch = convert(Language.C, Language.JAVA, "int show() { printf(\"%d %d\", 1); return 0; }");
        assertFalse(countMismatch.successful());
        assertTrue(countMismatch.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-FORMAT-ARG-COUNT")), countMismatch.diagnostics().toString());

        var dynamicFormat = convert(Language.C, Language.JAVA, "int show(string format) { printf(format, 1); return 0; }");
        assertFalse(dynamicFormat.successful());
        assertTrue(dynamicFormat.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-FORMAT-LITERAL")), dynamicFormat.diagnostics().toString());

        var javaOnly = convert(Language.JAVA, Language.CPP, "class Printer { public static void show() { System.out.printf(\"%b\", true); } }");
        assertFalse(javaOnly.successful());
        assertTrue(javaOnly.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-FORMAT-UNSUPPORTED")), javaOnly.diagnostics().toString());
    }
    @Test void printlnIntegerAndCharacterValuesConvertWithNativeFormatting() {
        var result = convert(Language.JAVA, Language.C,
                "class Printer { public static void show(int value, char letter) { System.out.println(value); System.out.print(letter); } }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("printf(\"%d\\n\", value)"), generated);
        assertTrue(generated.contains("printf(\"%c\", letter)"), generated);

        var floatValue = convert(Language.JAVA, Language.C,
                "class Printer { public static void show(double value) { System.out.println(value); } }");
        assertFalse(floatValue.successful());
        assertTrue(floatValue.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-OUTPUT-TYPE")),
                floatValue.diagnostics().toString());
    }
    @Test void conditionalsAndLoopsSurviveTheSharedIrAndNestedCallsAreMapped() {
        String source = "int main() { int x = 0; if (x < 3) { printf(\"ok\"); } while (x < 3) { return x; } return 0; }";
        var result = convert(Language.C, Language.JAVA, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("if ((x < 3))"));
        assertTrue(generated.contains("while ((x < 3))"));
        assertTrue(generated.contains("System.out.printf(\"ok\")"));
    }
    @Test void logicalAndUnaryOperatorsPreservePrecedenceAcrossTargets() {
        String source = "int check(int x) { if (!(x <= 0) || x == 3 && x != 5) return -x; else return +x; }";
        for (Language target : Language.values()) {
            var result = convert(Language.C, target, source);
            assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
            String generated = result.output().orElseThrow();
            assertTrue(generated.contains("&&"), generated);
            assertTrue(generated.contains("||"), generated);
            assertTrue(generated.contains("!"), generated);
            assertTrue(generated.contains("-x"), generated);
        }
    }
    @Test void forLoopsAndUpdatesRoundTripAcrossAllLanguageTargets() {
        String source = "int sum() { int total = 0; for (int i = 0; i < 3; i++) { total = total + i; } return total; }";
        for (Language target : Language.values()) {
            var result = convert(Language.C, target, source);
            assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
            String generated = result.output().orElseThrow();
            assertTrue(generated.contains("for ("), generated);
            assertTrue(generated.contains("i++"), generated);
            assertTrue(generated.contains("total ="), generated);
        }
    }
    @Test void cToCppPreservesCommaSeparatedForLoopDeclarations() {
        String source = "int sum() { int total = 0; for (int i = 0, limit = 3; i < limit; i++) { total += i; } return total; }";
        var result = convert(Language.C, Language.CPP, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("for (int i = 0, limit = 3;"), generated);

    }    @Test void breakAndContinueAreGeneratedOnlyInsideLoops() {
        String source = "int main() { for (int i = 0; i < 5; i++) { if (i == 1) continue; if (i == 3) break; } return 0; }";
        for (Language target : Language.values()) {
            var result = convert(Language.C, target, source);
            assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
            assertTrue(result.output().orElseThrow().contains("continue;"));
            assertTrue(result.output().orElseThrow().contains("break;"));
        }
        for (String keyword : List.of("break", "continue")) {
            var invalid = convert(Language.C, Language.C, "int main() { " + keyword + "; return 0; }");
            assertFalse(invalid.successful());
            assertTrue(invalid.diagnostics().getFirst().message().contains("inside a loop"));
            assertTrue(invalid.diagnostics().getFirst().location().isPresent());
        }
    }
    @Test void doWhileLoopsRetainTheirPostTestConditionAcrossTargets() {
        String source = "int count() { int value = 0; do { value = value + 1; } while (value < 3); return value; }";
        for (Language target : Language.values()) {
            var result = convert(Language.C, target, source);
            assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
            String generated = result.output().orElseThrow();
            assertTrue(generated.contains("do {"), generated);
            assertTrue(generated.contains("while ((value < 3));"), generated);
        }
    }
    @Test void primitiveArrayDeclarationsAndIndexedWritesTranslateBetweenCLikeAndJava() throws Exception {
        String cSource = "int first() { int values[4]; values[1] = 7; return values[1]; }";
        for (Language target : Language.values()) {
            var result = convert(Language.C, target, cSource);
            assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
            String generated = result.output().orElseThrow();
            assertTrue(generated.contains("values[1]"), generated);
        }
        var javaResult = convert(Language.C, Language.JAVA, cSource);
        String javaSource = javaResult.output().orElseThrow();
        assertTrue(javaSource.contains("int[] values = new int[4]"), javaSource);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("array-classes"));
        Files.writeString(sourceFile, javaSource);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, javaSource);

        String javaInput = "class Example { public static int first() { int[] values = new int[4]; values[1] = 7; return values[1]; } }";
        var cResult = convert(Language.JAVA, Language.C, javaInput);
        assertTrue(cResult.successful(), () -> cResult.diagnostics().toString());
        assertTrue(cResult.output().orElseThrow().contains("int values[4]"), cResult.output().orElseThrow());
    }
    @Test void putsMapsToJavaConsoleOutputAndBack() {
        var cToJava = convert(Language.C, Language.JAVA, "int show() { puts(\"ready\"); return 0; }");
        assertTrue(cToJava.successful(), () -> cToJava.diagnostics().toString());
        assertTrue(cToJava.output().orElseThrow().contains("System.out.println(\"ready\")"));

        var javaToC = convert(Language.JAVA, Language.C,
                "class Console { public static int show() { System.out.println(\"ready\"); return 0; } }");
        assertTrue(javaToC.successful(), () -> javaToC.diagnostics().toString());
        assertTrue(javaToC.output().orElseThrow().contains("puts(\"ready\")"));
    }
    @Test void characterLiteralsAndCommonEscapesRoundTripAcrossLanguages() throws Exception {
        String source = "char newline() { return '\\n'; } char letter() { return 'A'; } char quote() { return '\\''; }";
        for (Language target : Language.values()) {
            var result = convert(Language.C, target, source);
            assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
            String generated = result.output().orElseThrow();
            assertTrue(generated.contains("'\\n'"), generated);
            assertTrue(generated.contains("'A'"), generated);
            assertTrue(generated.contains("'\\''"), generated);
        }
        var javaResult = convert(Language.C, Language.JAVA, source);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("character-classes"));
        Files.writeString(sourceFile, javaResult.output().orElseThrow());
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, javaResult.output().orElseThrow());
    }
    @Test void stringLiteralEscapesAreDecodedAndRegenerated() throws Exception {
        String source = "int main() { printf(\"line\\n\\\"quoted\\\"\"); return 0; }";
        for (Language target : Language.values()) {
            var result = convert(Language.C, target, source);
            assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
            assertTrue(result.output().orElseThrow().contains("line\\n\\\"quoted\\\""), result.output().orElseThrow());
        }
        var javaResult = convert(Language.C, Language.JAVA, source);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("string-classes"));
        Files.writeString(sourceFile, javaResult.output().orElseThrow());
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, javaResult.output().orElseThrow());
    }
    @Test void elseIfChainsConvertAndCompile() throws Exception {
        String source = "int classify(int value) { if (value < 0) return -1; else if (value == 0) return 0; else if (value < 10) return 1; else return 2; }";
        for (Language target : Language.values()) {
            var result = convert(Language.C, target, source);
            assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
            assertTrue(result.output().orElseThrow().contains("else if"), result.output().orElseThrow());
        }
        var javaResult = convert(Language.C, Language.JAVA, source);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("else-if-classes"));
        Files.writeString(sourceFile, javaResult.output().orElseThrow());
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, javaResult.output().orElseThrow());

    }
    @Test void javaStringAndCharacterEscapesConvertToCAndCpp() {
        String source = "class Literals { public static void show() { System.out.printf(\"line\\n\\\"quoted\\\" %c\", '\\''); } }";
        for (Language target : List.of(Language.C, Language.CPP)) {
            var result = convert(Language.JAVA, target, source);
            assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
            String generated = result.output().orElseThrow();
            assertTrue(generated.contains("line\\n\\\"quoted\\\""), generated);
            assertTrue(generated.contains("'\\''"), generated);
        }
    }

    @Test void unsupportedStringEscapeProducesSourceDiagnostic() {
        var result = convert(Language.C, Language.C, "int show() { printf(\"bad\\q\"); return 0; }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("unsupported string escape")));
    }
    @Test void booleanDeclarationsAndLogicalControlFlowConvertAcrossAllLanguages() throws Exception {
        String cSource = "bool ready(bool left, bool right) { if (left && !right) return true; else return false; }";
        String javaSource = "class Flags { public static boolean ready(boolean left, boolean right) { if (left && !right) return true; else return false; } }";
        for (Language from : Language.values()) {
            String source = from == Language.JAVA ? javaSource : cSource;
            for (Language target : Language.values()) {
                var result = convert(from, target, source);
                assertTrue(result.successful(), () -> from + " -> " + target + ": " + result.diagnostics());
                assertTrue(result.output().orElseThrow().contains("&&"));
            }
        }
        var javaResult = convert(Language.C, Language.JAVA, cSource);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("boolean-classes"));
        Files.writeString(sourceFile, javaResult.output().orElseThrow());
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, javaResult.output().orElseThrow());
    }
    @Test void validatedConversionReturnsOnlyCompilerAcceptedOutput() {
        var service = new com.tascode.codebridge.validation.ValidatedConversionService(
                CodeBridge.createPipeline(), new com.tascode.codebridge.validation.CompilerValidationService());
        var valid = service.convert(Language.C, Language.JAVA, "int value() { return 42; }");
        assertTrue(valid.successful(), () -> valid.toString());
        assertEquals(com.tascode.codebridge.validation.ValidationStatus.VALID,
                valid.validation().orElseThrow().status());

        var unsupported = service.convert(Language.C, Language.JAVA, "int value() { int *pointer; return 0; }");
        assertFalse(unsupported.successful());
        assertTrue(unsupported.validation().isEmpty());
    }
    @Test void symbolErrorsAreRejectedWithSourceLocations() {
        for (Language language : List.of(Language.C, Language.CPP)) {
            var unresolved = convert(language, language, "int value() { return missing; }");
            assertFalse(unresolved.successful());
            assertEquals("CB-SYMBOL-UNRESOLVED", unresolved.diagnostics().getFirst().code());
            assertTrue(unresolved.diagnostics().getFirst().location().isPresent());
        }
        var duplicateLocal = convert(Language.C, Language.C, "int value() { int item; int item; return 0; }");
        assertEquals("CB-SYMBOL-DUP-LOCAL", duplicateLocal.diagnostics().getFirst().code());
        var duplicateFunction = convert(Language.C, Language.C, "int value() { return 1; } int value() { return 2; }");
        assertEquals("CB-SYMBOL-DUP-FUNCTION", duplicateFunction.diagnostics().getFirst().code());
    }
    @Test void typeMismatchesAreRejectedWithLocatedDiagnostics() {
        var booleanReturn = convert(Language.C, Language.JAVA, "int value() { return true; }");
        assertFalse(booleanReturn.successful());
        assertTrue(booleanReturn.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-MISMATCH") && d.location().isPresent()));

        var missingReturn = convert(Language.C, Language.C, "int value() { return; }");
        assertEquals("CB-TYPE-RETURN-VALUE", missingReturn.diagnostics().getFirst().code());

        var voidReturn = convert(Language.C, Language.C, "void value() { return 1; }");
        assertEquals("CB-TYPE-RETURN-VOID", voidReturn.diagnostics().getFirst().code());

        var badIndex = convert(Language.C, Language.C, "int value() { int values[4]; return values[true]; }");
        assertEquals("CB-TYPE-INTEGRAL", badIndex.diagnostics().getFirst().code());
    }
    @Test void functionCallsAreCheckedForNamesArityAndLiteralTypes() {
        var unknown = convert(Language.C, Language.C, "int value() { return missingFunction(1); }");
        assertTrue(unknown.diagnostics().stream().anyMatch(d -> d.code().equals("CB-CALL-UNRESOLVED")));

        var wrongCount = convert(Language.C, Language.C, "int add(int left, int right) { return left + right; } int value() { return add(1); }");
        assertTrue(wrongCount.diagnostics().stream().anyMatch(d -> d.code().equals("CB-CALL-ARG-COUNT")));

        var wrongType = convert(Language.C, Language.JAVA, "bool enabled(bool flag) { return flag; } int value() { return enabled(1); }");
        assertTrue(wrongType.diagnostics().stream().anyMatch(d -> d.code().equals("CB-CALL-ARG-TYPE")));
    }
    @Test void cppDefaultStringsRemainEmptyAfterConversionToJava() throws Exception {
        var scalar = convert(Language.CPP, Language.JAVA,
                "bool empty() { std::string value; return value == \"\"; }");
        assertTrue(scalar.successful(), () -> scalar.diagnostics().toString());
        assertTrue(scalar.output().orElseThrow().contains("String value = \"\";"));

        var array = convert(Language.CPP, Language.JAVA,
                "bool empty() { std::string values[2]; return values[0] == \"\"; }");
        assertTrue(array.successful(), () -> array.diagnostics().toString());
        assertTrue(array.output().orElseThrow().contains("java.util.Arrays.fill(values, \"\")"));
        assertTrue(array.output().orElseThrow().contains("java.util.Objects.equals(values[0], \"\")"));
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("cpp-string-array-classes"));
        Files.writeString(sourceFile, array.output().orElseThrow());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString()), array.output().orElseThrow());
    }
    @Test void stringConcatenationWithFunctionCallsRetainsStringType() {
        var result = convert(Language.JAVA, Language.CPP,
                "class Text { static String first() { return \"A\"; } static String joined() { return first() + \"B\"; } }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.output().orElseThrow().contains("std::string joined()"));
    }
    @Test void unaryNegationPromotesNarrowIntegerValuesToInt() {
        var narrowing = convert(Language.C, Language.JAVA,
                "char negate(char value) { return -value; }");
        assertTrue(narrowing.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-MISMATCH")));

        var valid = convert(Language.C, Language.JAVA,
                "int negate(char value) { return -value; }");
        assertTrue(valid.successful(), () -> valid.diagnostics().toString());
    }
    @Test void narrowIntegerOperandsPromoteToIntInExpressions() {
        var narrowing = convert(Language.C, Language.JAVA,
                "char add(char left, char right) { return left + right; }");
        assertTrue(narrowing.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-MISMATCH")));
    }
    @Test void mappedConsoleCallResultsCannotBeUsedAsValues() {
        var cReturn = convert(Language.C, Language.JAVA, "int value() { return printf(\"%d\", 1); }");
        assertTrue(cReturn.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-RETURN-UNSUPPORTED")));

        var javaReturn = convert(Language.JAVA, Language.C,
                "class Output { static int value() { return System.out.printf(\"%d\", 1); } }");
        assertTrue(javaReturn.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-RETURN-UNSUPPORTED")));

        var standalone = convert(Language.C, Language.JAVA, "int value() { printf(\"%d\", 1); return 0; }");
        assertTrue(standalone.successful(), () -> standalone.diagnostics().toString());
    }
    @Test void functionCallResultsAreCheckedInInitializersAndAssignments() {
        var initializer = convert(Language.C, Language.JAVA,
                "double readValue() { return 1.5; } int result() { int value = readValue(); return value; }");
        assertTrue(initializer.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-CALL-VALUE")));

        var assignment = convert(Language.C, Language.JAVA,
                "double readValue() { return 1.5; } int result() { int value; value = readValue(); return value; }");
        assertTrue(assignment.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-CALL-VALUE")));
    }
    @Test void nestedCallReturnTypesAreCheckedAgainstTheEnclosingFunction() {
        var narrowing = convert(Language.C, Language.JAVA,
                "double value() { return 1.5; } int result() { return value() + 1; }");
        assertTrue(narrowing.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-CALL-RETURN")));

        var widening = convert(Language.C, Language.JAVA,
                "int value() { return 1; } double result() { return value() + 1; }");
        assertTrue(widening.successful(), () -> widening.diagnostics().toString());
    }
    @Test void functionCallTypesIncludeNestedExpressionsAndReturnTypes() {
        var valid = convert(Language.C, Language.JAVA,
                "int add(int left, int right) { return left + right; } int value(int x) { return add(x + 1, 2); }");
        assertTrue(valid.successful(), () -> valid.diagnostics().toString());

        var invalidReturn = convert(Language.C, Language.JAVA,
                "bool enabled() { return true; } int value() { return enabled(); }");
        assertTrue(invalidReturn.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-CALL-RETURN")));

        String invalidExpressionArgumentSource = "int take(int value) { return value; } int value() { return take(true + false); }";
        var invalidExpressionArgument = convert(Language.C, Language.JAVA, invalidExpressionArgumentSource);
        var argumentDiagnostic = invalidExpressionArgument.diagnostics().stream()
                .filter(d -> d.code().equals("CB-CALL-ARG-TYPE")).findFirst().orElseThrow();
        assertEquals(invalidExpressionArgumentSource.indexOf("true") + 1,
                argumentDiagnostic.location().orElseThrow().startColumn());
    }
    @Test void callAnalysisVisitsArraySizesAndLoopInitializersAndRespectsShadowing() {
        var arraySize = convert(Language.C, Language.C, "int size() { return 4; } int values() { int data[size()]; return 0; }");
        assertTrue(arraySize.successful(), () -> arraySize.diagnostics().toString());

        var loopInitializer = convert(Language.C, Language.JAVA,
                "int use(int x) { return x; } int run() { for (int index = use(true); index < 2; index++) { } return 0; }");
        assertTrue(loopInitializer.diagnostics().stream().anyMatch(d -> d.code().equals("CB-CALL-ARG-TYPE")));

        var shadowing = convert(Language.C, Language.C,
                "int take(int x) { return x; } int run(bool item) { { int item = 1; take(item); } take(item); return 0; }");
        assertTrue(shadowing.successful(), () -> shadowing.diagnostics().toString());
    }
    @Test void invalidBooleanConditionsAndOperatorOperandsAreRejected() {
        var numericJavaCondition = convert(Language.C, Language.JAVA, "int value(int flag) { if (flag) return 1; else return 0; }");
        assertTrue(numericJavaCondition.successful(), () -> numericJavaCondition.diagnostics().toString());
        assertTrue(numericJavaCondition.output().orElseThrow().contains("flag != 0"));

        var invalidLogical = convert(Language.C, Language.JAVA, "int value() { if (1 && true) return 1; else return 0; }");
        assertTrue(invalidLogical.successful(), () -> invalidLogical.diagnostics().toString());
        assertTrue(invalidLogical.output().orElseThrow().contains("1 != 0"));

        var invalidArithmetic = convert(Language.C, Language.C, "int value() { return true + false; }");
        assertTrue(invalidArithmetic.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-NUMERIC-OPERANDS")));

        var validNativeTruthValue = convert(Language.C, Language.C, "int value(int flag) { if (flag) return 1; else return 0; }");
        assertTrue(validNativeTruthValue.successful(), () -> validNativeTruthValue.diagnostics().toString());
    }
    @Test void cppParserUsesCppSpecificUnsupportedDiagnostic() {
        var result = convert(Language.CPP, Language.CPP, "int value() { int *pointer; return 0; }");
        assertFalse(result.successful());
        assertEquals("CB-CPP-POINTER-UNSUPPORTED", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().location().isPresent());
    }
    @Test void generatorsUseLanguageSpecificStableRootDiagnostics() {
        var root = new com.tascode.codebridge.ir.LiteralExpression(1,
                com.tascode.codebridge.diagnostic.SourceSpan.point(1, 1), 1);
        var c = new com.tascode.codebridge.language.c.CGenerator().generate(root);
        var cpp = new com.tascode.codebridge.language.cpp.CppGenerator().generate(root);
        var java = new com.tascode.codebridge.language.java.JavaGenerator().generate(root);
        assertEquals("CB-GEN-C-ROOT", c.diagnostics().getFirst().code());
        assertEquals("CB-GEN-CPP-ROOT", cpp.diagnostics().getFirst().code());
        assertEquals("CB-GEN-JAVA-ROOT", java.diagnostics().getFirst().code());
    }
    @Test void duplicateParametersHaveTheirOwnDiagnosticCode() {
        var result = convert(Language.C, Language.C, "int add(int value, int value) { return value; }");
        assertFalse(result.successful());
        assertEquals("CB-SYMBOL-DUP-PARAMETER", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().location().isPresent());
    }
    @Test void integralDivisionAndRemainderByConstantZeroAreRejected() {
        for (String expression : List.of("1 / 0", "5 % (2 - 2)")) {
            var result = convert(Language.C, Language.JAVA, "int value() { return " + expression + "; }");
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-DIVIDE-BY-ZERO")),
                    () -> result.diagnostics().toString());
        }
    }
    @Test void incrementAndDecrementRequireNumericLocals() {
        var stringUpdate = convert(Language.CPP, Language.JAVA,
                "string update(string value) { value++; return value; }");
        assertTrue(stringUpdate.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-NUMERIC-OPERANDS")));

        var booleanUpdate = convert(Language.C, Language.JAVA,
                "bool update(bool value) { value--; return value; }");
        assertTrue(booleanUpdate.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-NUMERIC-OPERANDS")));

        var numericUpdate = convert(Language.C, Language.JAVA,
                "int update(int value) { ++value; return value; }");
        assertTrue(numericUpdate.successful(), () -> numericUpdate.diagnostics().toString());
    }
    @Test void nonzeroLiteralLoopsCountAsNonCompletingForReturnAnalysis() {
        var whileLoop = convert(Language.C, Language.JAVA, "int run() { while (1) { } }");
        assertTrue(whileLoop.successful(), () -> whileLoop.diagnostics().toString());
        assertTrue(whileLoop.output().orElseThrow().contains("while ((1 != 0))"));

        var doLoop = convert(Language.C, Language.JAVA, "int run() { do { } while (1); }");
        assertTrue(doLoop.successful(), () -> doLoop.diagnostics().toString());
    }
    @Test void javaTargetRejectsFunctionsThatCanFallThrough() {
        var missingPath = convert(Language.C, Language.JAVA,
                "int classify(int value) { if (value > 0) return 1; else if (value == 0) return 0; }");
        assertTrue(missingPath.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-MISSING-RETURN")));

        var allPathsReturn = convert(Language.C, Language.JAVA,
                "int classify(int value) { if (value > 0) return 1; else return 0; }");
        assertTrue(allPathsReturn.successful(), () -> allPathsReturn.diagnostics().toString());

        var nativeSource = convert(Language.C, Language.C,
                "int classify(int value) { if (value > 0) return 1; }");
        assertTrue(nativeSource.successful(), () -> nativeSource.diagnostics().toString());
    }
    @Test void nonCompletingConstantTrueLoopsDoNotRequireReturnStatements() {
        var whileLoop = convert(Language.C, Language.JAVA, "int spin() { while (true) { } }");
        assertTrue(whileLoop.successful(), () -> whileLoop.diagnostics().toString());

        var forLoop = convert(Language.C, Language.JAVA, "int spin() { for (;;) { } }");
        assertTrue(forLoop.successful(), () -> forLoop.diagnostics().toString());

        var loopWithBreak = convert(Language.C, Language.JAVA, "int spin(bool stop) { while (true) { if (stop) break; } }");
        assertTrue(loopWithBreak.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-MISSING-RETURN")));
    }
    @Test void returnAnalysisScopesBreaksToTheirInnermostLoop() {
        var nestedBreak = convert(Language.C, Language.JAVA,
                "int spin() { while (true) { for (;;) { break; } } }");
        assertTrue(nestedBreak.successful(), () -> nestedBreak.diagnostics().toString());

        var continueOnly = convert(Language.C, Language.JAVA,
                "int spin() { do { continue; } while (true); }");
        assertTrue(continueOnly.successful(), () -> continueOnly.diagnostics().toString());

        var conditionalBreak = convert(Language.C, Language.JAVA,
                "int spin(bool stop) { for (;;) { if (stop) break; else continue; } }");
        assertTrue(conditionalBreak.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-MISSING-RETURN")));
    }
    @Test void nativeScalarConditionsAreNormalizedAndGeneratedJavaCompiles() throws Exception {
        String source = "int check(int count, bool enabled) { if (count && enabled) return 1; else if (!count) return 0; else return 2; }";
        var result = convert(Language.C, Language.JAVA, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("count != 0"), generated);
        assertTrue(generated.contains("count == 0"), generated);
        assertTrue(generated.contains("enabled"), generated);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("condition-classes"));
        Files.writeString(sourceFile, generated);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, generated);
    }
    @Test void numericLoopConditionsAreNormalizedIncludingForLocals() throws Exception {
        String source = "int count(int value) { while (value) { value--; } do { value++; } while (value); for (int index = 2; index; index--) { value = value + index; } return value; }";
        var result = convert(Language.C, Language.JAVA, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("while ((value != 0))"), generated);
        assertTrue(generated.contains("(index != 0)"), generated);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("loop-condition-classes"));
        Files.writeString(sourceFile, generated);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, generated);
    }
    @Test void cScanfWithMultipleDestinationsConvertsToCppAndCompiles() {
        String source = "#include <stdio.h>\nint main() { int a, b; scanf(\"%d %d\", &a, &b); printf(\"Sum = %d\", a + b); return 0; }";
        var result = convert(Language.C, Language.CPP, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("#include <stdio.h>"), generated);
        assertTrue(generated.contains("scanf(\"%d %d\", &a, &b)"), generated);
        var compilation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, generated);
        if (compilation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE)
            assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());
    }
    @Test void oneIntegerScanfStatementConvertsToJavaScanner() throws Exception {
        String source = "int readValue() { int value; scanf(\"%d\", &value); return value; }";
        var result = convert(Language.C, Language.JAVA, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("new java.util.Scanner(System.in)"), generated);
        assertTrue(generated.contains("(CODEBRIDGE_INPUT.hasNextInt() ? CODEBRIDGE_INPUT.nextInt() : value)"), generated);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("scanf-classes"));
        Files.writeString(sourceFile, generated);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, generated);

        var nativeRoundTrip = convert(Language.C, Language.C, source);
        assertTrue(nativeRoundTrip.successful(), () -> nativeRoundTrip.diagnostics().toString());
        assertTrue(nativeRoundTrip.output().orElseThrow().contains("scanf(\"%d\", &value)"));

        var unsupported = convert(Language.C, Language.JAVA, "int readValue() { int value; scanf(\"%s\", &value); return value; }");
        assertTrue(unsupported.diagnostics().stream().anyMatch(d -> d.code().equals("CB-INPUT-SCANF-UNSUPPORTED")));

        var nested = convert(Language.C, Language.JAVA, "int readValue() { int value; return scanf(\"%d\", &value); }");
        assertTrue(nested.diagnostics().stream().anyMatch(d -> d.code().equals("CB-INPUT-SCANF-UNSUPPORTED")));
    }
    @Test void floatScanfFormatsUseMatchingJavaScannerMethods() throws Exception {
        for (String[] sample : List.of(new String[]{"float", "%f", "nextFloat"}, new String[]{"double", "%lf", "nextDouble"})) {
            String source = "" + sample[0] + " readValue() { " + sample[0] + " value; scanf(\"" + sample[1] + "\", &value); return value; }";
            var result = convert(Language.C, Language.JAVA, source);
            assertTrue(result.successful(), () -> result.diagnostics().toString());
            String generated = result.output().orElseThrow();
            assertTrue(generated.contains("CODEBRIDGE_INPUT." + sample[2].replace("next", "hasNext") + "()") && generated.contains("CODEBRIDGE_INPUT." + sample[2].replace("next", "hasNext") + "()") && generated.contains("CODEBRIDGE_INPUT." + sample[2] + "()"), generated);
            assertTrue(generated.contains("useLocale(java.util.Locale.ROOT)"), generated);
            Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
            Path classes = Files.createDirectory(tempDir.resolve("scanf-" + sample[0] + "-classes"));
            Files.writeString(sourceFile, generated);
            int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
            assertEquals(0, exit, generated);
        }
        var mismatch = convert(Language.C, Language.JAVA, "int readValue() { int value; scanf(\"%f\", &value); return value; }");
        assertTrue(mismatch.diagnostics().stream().anyMatch(d -> d.code().equals("CB-INPUT-SCANF-TYPE")));
    }
    @Test void charScanfFormatUsesRawScannerCharacterRead() throws Exception {
        String source = "char readValue() { char value; scanf(\"%c\", &value); return value; }";
        var result = convert(Language.C, Language.JAVA, source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("codeBridgeReadCharOr(value)") && generated.contains("next == null ? previous : next.charAt(0)"), generated);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("scanf-char-classes"));
        Files.writeString(sourceFile, generated);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, generated);
    }
    @Test void cppConsoleCallsMapToAndFromJava() throws Exception {
        var cppToJava = convert(Language.CPP, Language.JAVA,
                "int show() { printf(\"%d\", 7); puts(\"ready\"); return 0; }");
        assertTrue(cppToJava.successful(), () -> cppToJava.diagnostics().toString());
        String generated = cppToJava.output().orElseThrow();
        assertTrue(generated.contains("System.out.printf(\"%d\", 7)"), generated);
        assertTrue(generated.contains("System.out.println(\"ready\")"), generated);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("cpp-console-classes"));
        Files.writeString(sourceFile, generated);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, generated);

        var javaToCpp = convert(Language.JAVA, Language.CPP,
                "class Console { public static int show() { System.out.printf(\"%d\", 7); System.out.println(\"ready\"); return 0; } }");
        assertTrue(javaToCpp.successful(), () -> javaToCpp.diagnostics().toString());
        assertTrue(javaToCpp.output().orElseThrow().contains("printf(\"%d\", 7)"));
        assertTrue(javaToCpp.output().orElseThrow().contains("std::cout << \"ready\" << std::endl"));
    }
    @Test void dockerSandboxCanCompareCAndGeneratedJavaWhenDockerIsAvailable() {
        String source = "#include <stdio.h>\nint main() { printf(\"42\\n\"); return 0; }";
        var conversion = convert(Language.C, Language.JAVA, source);
        assertTrue(conversion.successful(), () -> conversion.diagnostics().toString());
        var sandbox = new com.tascode.codebridge.validation.DockerSandboxExecutionService();
        var comparison = new com.tascode.codebridge.validation.OutputEquivalenceService(sandbox)
                .compare(Language.C, source, Language.JAVA, conversion.output().orElseThrow(), "");
        var sourceRun = comparison.sourceExecution().orElseThrow(() -> new AssertionError(comparison.message()));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                sourceRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.DOCKER_UNAVAILABLE
                        && sourceRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.IMAGE_UNAVAILABLE,
                sourceRun.message());
        var targetRun = comparison.targetExecution().orElseThrow(() -> new AssertionError(comparison.message()));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                targetRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.DOCKER_UNAVAILABLE
                        && targetRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.IMAGE_UNAVAILABLE,
                targetRun.message());
        assertTrue(sourceRun.completed(), sourceRun.message());
        assertTrue(targetRun.completed(), targetRun.message());
        assertEquals(java.util.Optional.of(true), comparison.matches(), comparison.message());
    }

    @Test void recursiveNestedControlFlowAndShadowingCompileAndCompareWhenDockerIsAvailable() {
        String source = "#include <stdio.h>\n"
                + "int factorial(int n) { if (n <= 1) return 1; return n * factorial(n - 1); }\n"
                + "int main() { int total = 0; for (int i = 1; i <= 3; i++) { "
                + "for (int j = 1; j <= 3; j++) { "
                + "if (i == j) total += factorial(i); else if (i < j) total += i * j - (i + j); else total += 1; "
                + "{ int total = i + j; total += 1; } } } printf(\"%d\\n\", total); return 0; }";
        var conversion = convert(Language.C, Language.CPP, source);
        assertTrue(conversion.successful(), () -> conversion.diagnostics().toString());
        String generated = conversion.output().orElseThrow();
        assertTrue(generated.contains("factorial"), generated);
        assertTrue(generated.contains("for ("), generated);
        assertTrue(generated.contains("else"), generated);

        var compiler = new com.tascode.codebridge.validation.CompilerValidationService();
        var sourceCompilation = compiler.validate(Language.C, source);
        org.junit.jupiter.api.Assumptions.assumeTrue(sourceCompilation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE,
                "C compiler is unavailable");
        assertTrue(sourceCompilation.successful(), sourceCompilation.diagnostics().toString());
        var targetCompilation = compiler.validate(Language.CPP, generated);
        org.junit.jupiter.api.Assumptions.assumeTrue(targetCompilation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE,
                "C++ compiler is unavailable");
        assertTrue(targetCompilation.successful(), targetCompilation.diagnostics().toString());

        var comparison = new com.tascode.codebridge.validation.OutputEquivalenceService(
                new com.tascode.codebridge.validation.DockerSandboxExecutionService())
                .compare(Language.C, source, Language.CPP, generated, "");
        var sourceRun = comparison.sourceExecution().orElseThrow(() -> new AssertionError(comparison.message()));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                sourceRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.DOCKER_UNAVAILABLE
                        && sourceRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.IMAGE_UNAVAILABLE,
                sourceRun.message());
        var targetRun = comparison.targetExecution().orElseThrow(() -> new AssertionError(comparison.message()));
        assertTrue(sourceRun.completed(), sourceRun.message());
        assertTrue(targetRun.completed(), targetRun.message());
        assertEquals("11\n", sourceRun.stdout());
        assertEquals(java.util.Optional.of(true), comparison.matches(), comparison.message());
    }
    @Test void dockerSandboxCanCompareCScanfProgramWithGeneratedCppWhenDockerIsAvailable() {
        String source = "#include <stdio.h>\nint main() { int a, b; scanf(\"%d %d\", &a, &b); printf(\"Sum = %d\", a + b); return 0; }";
        var conversion = convert(Language.C, Language.CPP, source);
        assertTrue(conversion.successful(), () -> conversion.diagnostics().toString());
        var comparison = new com.tascode.codebridge.validation.OutputEquivalenceService(
                new com.tascode.codebridge.validation.DockerSandboxExecutionService())
                .compare(Language.C, source, Language.CPP, conversion.output().orElseThrow(), "2 3\n");
        var sourceRun = comparison.sourceExecution().orElseThrow(() -> new AssertionError(comparison.message()));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                sourceRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.DOCKER_UNAVAILABLE
                        && sourceRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.IMAGE_UNAVAILABLE,
                sourceRun.message());
        var targetRun = comparison.targetExecution().orElseThrow(() -> new AssertionError(comparison.message()));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                targetRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.DOCKER_UNAVAILABLE
                        && targetRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.IMAGE_UNAVAILABLE,
                targetRun.message());
        assertTrue(sourceRun.completed(), sourceRun.message());
        assertTrue(targetRun.completed(), targetRun.message());
        assertEquals("Sum = 5", sourceRun.stdout());
        assertEquals(java.util.Optional.of(true), comparison.matches(), comparison.message());
    }    @Test void dockerSandboxCanCompareJavaAndGeneratedCppWhenDockerIsAvailable() {
        String source = "public class JavaExample { public static void main(String[] args) { int a = 2; int b = 3; System.out.printf(\"Sum = %d\", a + b); } }";
        var conversion = convert(Language.JAVA, Language.CPP, source);
        assertTrue(conversion.successful(), () -> conversion.diagnostics().toString());
        var comparison = new com.tascode.codebridge.validation.OutputEquivalenceService(
                new com.tascode.codebridge.validation.DockerSandboxExecutionService())
                .compare(Language.JAVA, source, Language.CPP, conversion.output().orElseThrow(), "");
        var sourceRun = comparison.sourceExecution().orElseThrow(() -> new AssertionError(comparison.message()));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                sourceRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.DOCKER_UNAVAILABLE
                        && sourceRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.IMAGE_UNAVAILABLE,
                sourceRun.message());
        var targetRun = comparison.targetExecution().orElseThrow(() -> new AssertionError(comparison.message()));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                targetRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.DOCKER_UNAVAILABLE
                        && targetRun.status() != com.tascode.codebridge.validation.SandboxExecutionStatus.IMAGE_UNAVAILABLE,
                targetRun.message());
        assertTrue(sourceRun.completed(), sourceRun.message());
        assertTrue(targetRun.completed(), targetRun.message());
        assertEquals("Sum = 5", sourceRun.stdout());
        assertEquals(java.util.Optional.of(true), comparison.matches(), comparison.message());
    }    @Test void localHttpApiServesHealthAndConversionRequests() throws Exception {
        try (var server = new com.tascode.codebridge.web.CodeBridgeHttpServer(new java.net.InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            var client = java.net.http.HttpClient.newHttpClient();
            var health = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/health")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"ok\""));
            String json = "{\"from\":\"c\",\"to\":\"java\",\"source\":\"int value() { return 42; }\","
                    + "\"validate\":false,\"verifyOutput\":false,\"clangPreflight\":false,\"clangLowering\":false,\"input\":\"\"}";
            var wrongContentType = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/convert"))
                    .header("Content-Type", "application/jsonp").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build();
            var wrongContentTypeResponse = client.send(wrongContentType, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(415, wrongContentTypeResponse.statusCode());
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/convert"))
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("public static int value()"), response.body());
            String validatedJson = "{\"from\":\"c\",\"to\":\"java\",\"source\":\"int value() { return 42; }\",\"validate\":true}";
            var validatedRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/convert"))
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(validatedJson)).build();
            var validatedResponse = client.send(validatedRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, validatedResponse.statusCode(), validatedResponse.body());
            assertTrue(validatedResponse.body().contains("\"validation\":\"VALID\""), validatedResponse.body());            for (String malformed : List.of(
                    "{\"from\":\"c\",\"to\":\"java\",\"source\":\"int value() { return 1; }\",\"validate\":\"yes\"}",
                    "{\"from\":\"c\",\"to\":\"java\",\"source\":\"int value() { return 1; }\",\"extra\":true}",
                    "{\"from\":\"c\",\"to\":\"java\"}")) {
                var badRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/convert"))
                        .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(malformed)).build();
                var badResponse = client.send(badRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(400, badResponse.statusCode(), badResponse.body());
            }
        } catch (java.io.IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Loopback HTTP is blocked in this test environment");
        }
    }
    @Test void cppOutputStreamsConvertToJavaAndRoundTripToCpp() throws Exception {
        String input = "#include <iostream>\nint show(int value) { std::cout << \"value=\" << value << std::endl; return 0; }";
        var java = convert(Language.CPP, Language.JAVA, input);
        assertTrue(java.successful(), () -> java.diagnostics().toString());
        String generated = java.output().orElseThrow();
        assertTrue(generated.contains("System.out.print(\"value=\")"), generated);
        assertTrue(generated.contains("System.out.print(value)"), generated);
        assertTrue(generated.contains("System.out.println()"), generated);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("cout-classes"));
        Files.writeString(sourceFile, generated);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString()), generated);

        var cpp = convert(Language.CPP, Language.CPP, input);
        assertTrue(cpp.successful(), () -> cpp.diagnostics().toString());
        assertTrue(cpp.output().orElseThrow().contains("#include <iostream>"), cpp.output().orElseThrow());
        assertTrue(cpp.output().orElseThrow().contains("std::cout << \"value=\" << value << std::endl"), cpp.output().orElseThrow());

        var c = convert(Language.CPP, Language.C, input);
        assertFalse(c.successful());
        assertTrue(c.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-COUT-TARGET")), c.diagnostics().toString());
        var invalidPlacement = convert(Language.CPP, Language.JAVA, "int show() { int value = std::cout << 1; return value; }");
        assertFalse(invalidPlacement.successful());
        assertTrue(invalidPlacement.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-COUT-CONTEXT")), invalidPlacement.diagnostics().toString());
        var unqualified = convert(Language.CPP, Language.JAVA,
                String.join("\n", "#include <iostream>", "using namespace std;", "int show() { cout << \"ok\" << endl; return 0; }"));
        assertTrue(unqualified.successful(), () -> unqualified.diagnostics().toString());
        assertTrue(unqualified.output().orElseThrow().contains("System.out.print(\"ok\")"), unqualified.output().orElseThrow());
        assertTrue(unqualified.output().orElseThrow().contains("System.out.println()"), unqualified.output().orElseThrow());
        var inputChain = convert(Language.CPP, Language.JAVA,
                String.join("\n", "#include <iostream>", "using namespace std;",
                        "int main() { int first, second; std::string word; char letter; std::cin >> first; cin >> second >> word >> letter; cout << first + second << word << letter << endl; return 0; }"));
        assertTrue(inputChain.successful(), () -> inputChain.diagnostics().toString());
        String inputJava = inputChain.output().orElseThrow();
        assertTrue(inputJava.contains("CODEBRIDGE_INPUT.nextInt()"), inputJava);
        assertTrue(inputJava.contains("CODEBRIDGE_INPUT.next()"), inputJava);
        assertTrue(inputJava.contains("CODEBRIDGE_INPUT.next().charAt(0)"), inputJava);
        assertTrue(inputJava.contains("System.out.println()"), inputJava);
        Path inputSource = tempDir.resolve("TasCodeGenerated.java");
        Path inputClasses = Files.createDirectory(tempDir.resolve("cin-classes"));
        Files.writeString(inputSource, inputJava);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", inputClasses.toString(), inputSource.toString()), inputJava);
    }
    @Test void javaPrintCallsConvertToNativeOutputAndRejectUnsupportedTypes() {
        var javaToC = convert(Language.JAVA, Language.C,
                "class Printer { static void show(int count, char letter) { System.out.print(\"ready\"); System.out.print(count); System.out.println(count); System.out.print(letter); System.out.println(); } }");
        assertTrue(javaToC.successful(), () -> javaToC.diagnostics().toString());
        String c = javaToC.output().orElseThrow();
        assertTrue(c.contains("printf(\"%s\", \"ready\")"), c);
        assertTrue(c.contains("puts(\"\")"), c);
        assertTrue(c.contains("printf(\"%d\\n\", count)"), c);
        var cCompile = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.C, c);
        if (cCompile.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE)
            assertTrue(cCompile.successful(), () -> cCompile.diagnostics().toString());

        var javaToCpp = convert(Language.JAVA, Language.CPP,
                "class Printer { static void show(int count, char letter) { System.out.print(\"value=\"); System.out.print(count); System.out.println(count); System.out.print(letter); System.out.println(); } }");
        assertTrue(javaToCpp.successful(), () -> javaToCpp.diagnostics().toString());
        String cpp = javaToCpp.output().orElseThrow();
        assertTrue(cpp.contains("std::cout << \"value=\""), cpp);
        assertTrue(cpp.contains("std::cout << count"), cpp);
        assertTrue(cpp.contains("std::cout << count << std::endl"), cpp);
        assertTrue(cpp.contains("std::cout << letter"), cpp);
        var cppCompile = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, cpp);
        if (cppCompile.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE)
            assertTrue(cppCompile.successful(), () -> cppCompile.diagnostics().toString());
        assertTrue(cpp.contains("#include <iostream>"), cpp);

        var arrayPrint = convert(Language.JAVA, Language.C,
                "class Printer { static void show() { int[] values = new int[2]; values[0] = 7; System.out.println(values[0]); } }");
        assertTrue(arrayPrint.successful(), () -> arrayPrint.diagnostics().toString());
        assertTrue(arrayPrint.output().orElseThrow().contains("printf(\"%d\\n\", values[0])"), arrayPrint.output().orElseThrow());

        var constantConcat = convert(Language.JAVA, Language.C,
                "class Printer { static void show() { System.out.print(\"hello \" + \"world\"); } }");
        assertTrue(constantConcat.successful(), () -> constantConcat.diagnostics().toString());
        assertTrue(constantConcat.output().orElseThrow().contains("printf(\"%s\", \"hello world\")"), constantConcat.output().orElseThrow());

        var mixedConcat = convert(Language.JAVA, Language.C,
                "class Printer { static void show(int value) { System.out.print(\"value=\" + value); } }");
        assertFalse(mixedConcat.successful());
        assertTrue(mixedConcat.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-OUTPUT-TYPE")),
                mixedConcat.diagnostics().toString());
        var unsupportedC = convert(Language.JAVA, Language.C,
                "class Printer { static void show(boolean value) { System.out.print(value); } }");
        assertFalse(unsupportedC.successful());
        assertTrue(unsupportedC.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-OUTPUT-TYPE")),
                unsupportedC.diagnostics().toString());

        var unsupportedCpp = convert(Language.JAVA, Language.CPP,
                "class Printer { static void show(boolean value) { System.out.print(value); } }");
        assertFalse(unsupportedCpp.successful());
        assertTrue(unsupportedCpp.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-OUTPUT-TYPE")),
                unsupportedCpp.diagnostics().toString());
    }
    @Test void javaAndCppStringsConvertWithinTheSupportedTypeSubset() throws Exception {
        String javaInput = "class Text { public static String join(String left, String right) { return left + right; } }";
        var toCpp = convert(Language.JAVA, Language.CPP, javaInput);
        assertTrue(toCpp.successful(), () -> toCpp.diagnostics().toString());
        assertTrue(toCpp.output().orElseThrow().contains("std::string join(std::string left, std::string right)"), toCpp.output().orElseThrow());
        assertTrue(toCpp.output().orElseThrow().contains("#include <string>"), toCpp.output().orElseThrow());

        var toJava = convert(Language.CPP, Language.JAVA, "#include <string>\nstd::string join(std::string left, std::string right) { std::string value = left + right; return value; }");
        assertTrue(toJava.successful(), () -> toJava.diagnostics().toString());
        String javaOutput = toJava.output().orElseThrow();
        assertTrue(javaOutput.contains("public static String join(String left, String right)"), javaOutput);
        var cppRoundTrip = convert(Language.CPP, Language.CPP, "#include <string>\nstd::string join(std::string left, std::string right) { return left + right; }");
        assertTrue(cppRoundTrip.successful(), () -> cppRoundTrip.diagnostics().toString());
        assertTrue(cppRoundTrip.output().orElseThrow().contains("std::string join(std::string left, std::string right)"), cppRoundTrip.output().orElseThrow());
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("string-classes"));
        Files.writeString(sourceFile, javaOutput);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString()), javaOutput);

        var toC = convert(Language.JAVA, Language.C, javaInput);
        assertFalse(toC.successful());
        assertTrue(toC.diagnostics().stream().anyMatch(d -> d.code().equals("CB-UNSUP-STRING-C")), toC.diagnostics().toString());
    }
    @Test void commonJavaStringPredicatesUseCppRuntimeHelpers() {
        var converted = convert(Language.JAVA, Language.CPP,
                "class Text { public static boolean match(String value, String prefix) { return value.startsWith(prefix) || value.endsWith(prefix) || value.contains(prefix); } public static String append(String value, String suffix) { return value.concat(suffix); } }");
        assertTrue(converted.successful(), () -> converted.diagnostics().toString());
        String cpp = converted.output().orElseThrow();
        assertTrue(cpp.contains("__codebridge_starts_with(value, prefix)"), cpp);
        assertTrue(cpp.contains("__codebridge_ends_with(value, prefix)"), cpp);
        assertTrue(cpp.contains("__codebridge_contains(value, prefix)"), cpp);
        assertTrue(cpp.contains("return (value + suffix);"), cpp);
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, cpp);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE)
            assertTrue(validation.successful(), () -> validation.diagnostics().toString());
    }
    @Test void javaPrimitiveStaticFieldsLowerToCppTranslationUnitState() {
        var converted = convert(Language.JAVA, Language.CPP,
                "class Counter { private static int total = 4; public static final int LIMIT = 10; public static int increment() { total = total + 1; return total; } }");
        assertTrue(converted.successful(), () -> converted.diagnostics().toString());
        String cpp = converted.output().orElseThrow();
        assertTrue(cpp.contains("static int total = 4;"), cpp);
        assertTrue(cpp.contains("const int LIMIT = 10;"), cpp);
        assertTrue(cpp.contains("int increment()"), cpp);
        var validation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, cpp);
        if (validation.status() != com.tascode.codebridge.validation.ValidationStatus.COMPILER_UNAVAILABLE)
            assertTrue(validation.successful(), () -> validation.diagnostics().toString());

        var instanceField = convert(Language.JAVA, Language.CPP,
                "class Counter { int total; public static int read() { return 0; } }");
        assertFalse(instanceField.successful());
        assertTrue(instanceField.diagnostics().stream().anyMatch(d -> d.code().equals("CB-JAVA-CLASS-STATE-UNSUPPORTED")),
                instanceField.diagnostics().toString());
        var computedInitializer = convert(Language.JAVA, Language.CPP,
                "class Counter { static int total = 2 + 3; public static int read() { return total; } }");
        assertFalse(computedInitializer.successful());
        assertTrue(computedInitializer.diagnostics().stream().anyMatch(d -> d.code().equals("CB-JAVA-STATIC-FIELD-INITIALIZER-UNSUPPORTED")),
                computedInitializer.diagnostics().toString());
        var outOfRange = convert(Language.JAVA, Language.CPP,
                "class Counter { static byte total = 200; public static int read() { return 0; } }");
        assertFalse(outOfRange.successful());
        assertTrue(outOfRange.diagnostics().stream().anyMatch(d -> d.code().equals("CB-JAVA-STATIC-FIELD-INITIALIZER-UNSUPPORTED")),
                outOfRange.diagnostics().toString());
    }
    @Test void cppStringArraysCarryTheHeaderAndGenerateJavaArrays() throws Exception {
        var result = convert(Language.CPP, Language.JAVA, "#include <string>\nint count() { std::string values[2]; values[0] = \"ok\"; return 2; }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        String generated = result.output().orElseThrow();
        assertTrue(generated.contains("String[] values = new String[2]"), generated);
        Path sourceFile = tempDir.resolve("TasCodeGenerated.java");
        Path classes = Files.createDirectory(tempDir.resolve("string-array-classes"));
        Files.writeString(sourceFile, generated);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sourceFile.toString()), generated);
    }
    @Test void crossLanguageStringEqualityUsesValueComparisonSafely() throws Exception {
        var cppToJava = convert(Language.CPP, Language.JAVA, "bool same(string left, string right) { return left == right; }");
        assertTrue(cppToJava.successful(), () -> cppToJava.diagnostics().toString());
        String generatedJava = cppToJava.output().orElseThrow();
        assertTrue(generatedJava.contains("java.util.Objects.equals(left, right)"), generatedJava);
        Path javaFile = tempDir.resolve("TasCodeGenerated.java");
        Path javaClasses = Files.createDirectory(tempDir.resolve("string-equality-java"));
        Files.writeString(javaFile, generatedJava);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", javaClasses.toString(), javaFile.toString()), generatedJava);

        var javaToCpp = convert(Language.JAVA, Language.CPP, "class Text { public static boolean same(String left, String right) { return left.equals(right); } }");
        assertTrue(javaToCpp.successful(), () -> javaToCpp.diagnostics().toString());
        assertTrue(javaToCpp.output().orElseThrow().contains("return (left == right);"), javaToCpp.output().orElseThrow());
        var stringMethods = convert(Language.JAVA, Language.CPP,
                "class Text { public static int length(String value) { return value.length(); } public static boolean empty(String value) { return value.isEmpty(); } }");
        assertTrue(stringMethods.successful(), () -> stringMethods.diagnostics().toString());
        String methodsCpp = stringMethods.output().orElseThrow();
        assertTrue(methodsCpp.contains("return value.length();"), methodsCpp);
        assertTrue(methodsCpp.contains("return value.empty();"), methodsCpp);
        var methodsValidation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, methodsCpp);
        assertEquals(com.tascode.codebridge.validation.ValidationStatus.VALID, methodsValidation.status(), methodsValidation.diagnostics().toString());
        var unequal = convert(Language.CPP, Language.JAVA, "bool different(string left, string right) { return left != right; }");
        assertTrue(unequal.successful(), () -> unequal.diagnostics().toString());
        assertTrue(unequal.output().orElseThrow().contains("!java.util.Objects.equals(left, right)"), unequal.output().orElseThrow());
        var invalidEquals = convert(Language.JAVA, Language.CPP, "class Text { public static boolean same(String left) { return left.equals(1); } }");
        assertFalse(invalidEquals.successful());
        assertTrue(invalidEquals.diagnostics().stream().anyMatch(d -> d.code().equals("CB-STRING-EQUALS-TYPE")), invalidEquals.diagnostics().toString());
        var cppValidation = new com.tascode.codebridge.validation.CompilerValidationService().validate(Language.CPP, javaToCpp.output().orElseThrow());
        assertEquals(com.tascode.codebridge.validation.ValidationStatus.VALID, cppValidation.status(), cppValidation.diagnostics().toString());

        var identityComparison = convert(Language.JAVA, Language.CPP, "class Text { public static boolean same(String left, String right) { return left == right; } }");
        assertFalse(identityComparison.successful());
        assertTrue(identityComparison.diagnostics().stream().anyMatch(d -> d.code().equals("CB-STRING-COMPARE-SEMANTICS")), identityComparison.diagnostics().toString());
    }
}
