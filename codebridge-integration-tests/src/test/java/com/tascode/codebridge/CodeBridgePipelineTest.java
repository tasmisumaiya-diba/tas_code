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

    @Test void javaInstanceMethodsAreRejectedInsteadOfFlattenedToStaticFunctions() {
        var result = convert(Language.JAVA, Language.C, "class Counter { int increment(int value) { return value + 1; } }");
        assertFalse(result.successful());
        assertEquals("CB-PARSE-JAVA-UNSUPPORTED", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().message().contains("Only static Java methods are supported"));
        assertTrue(result.diagnostics().getFirst().location().isPresent());
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
    @Test void javaInheritanceAndInterfacesHaveExplicitDiagnostics() {
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
    @Test void javaAndCppEnumsHaveExplicitDiagnostics() {
        var javaResult = convert(Language.JAVA, Language.CPP, "enum Color { RED, BLUE }");
        assertFalse(javaResult.successful());
        assertEquals("CB-JAVA-ENUMS-UNSUPPORTED", javaResult.diagnostics().getFirst().code());

        var cppResult = convert(Language.CPP, Language.JAVA, "enum Color { RED, BLUE };");
        assertFalse(cppResult.successful());
        assertEquals("CB-CPP-ENUMS-UNSUPPORTED", cppResult.diagnostics().getFirst().code());
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

    @Test void nestedAssignmentsAreTypeCheckedRecursively() {
        var result = convert(Language.C, Language.JAVA,
                "int value() { int number = 0; number = (number = \"wrong\"); return number; }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-TYPE-MISMATCH")),
                () -> result.diagnostics().toString());
    }    @Test void everyLanguagePairConvertsTheSharedFunctionSubset() {
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
        assertTrue(javaToC.output().orElseThrow().startsWith("#include <stdio.h>"));
    }
    @Test void crossLanguagePrintfFormatsAreCheckedConservatively() {
        var cDouble = convert(Language.C, Language.JAVA, "double show(double value) { printf(\"%lf\", value); return value; }");
        assertTrue(cDouble.successful(), () -> cDouble.diagnostics().toString());
        assertTrue(cDouble.output().orElseThrow().contains("System.out.printf(\"%f\", value)"), cDouble.output().orElseThrow());


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
    @Test void printlnToPutsRejectsNonStringArguments() {
        var result = convert(Language.JAVA, Language.C, "class Printer { public static void show() { System.out.println(42); } }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("CB-IO-OUTPUT-TYPE")), result.diagnostics().toString());
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
    @Test void breakAndContinueAreGeneratedOnlyInsideLoops() {
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
    }    @Test void elseIfChainsConvertAndCompile() throws Exception {
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
        assertEquals("CB-TYPE-NUMERIC-REQUIRED", badIndex.diagnostics().getFirst().code());
    }
    @Test void functionCallsAreCheckedForNamesArityAndLiteralTypes() {
        var unknown = convert(Language.C, Language.C, "int value() { return missingFunction(1); }");
        assertTrue(unknown.diagnostics().stream().anyMatch(d -> d.code().equals("CB-CALL-UNRESOLVED")));

        var wrongCount = convert(Language.C, Language.C, "int add(int left, int right) { return left + right; } int value() { return add(1); }");
        assertTrue(wrongCount.diagnostics().stream().anyMatch(d -> d.code().equals("CB-CALL-ARG-COUNT")));

        var wrongType = convert(Language.C, Language.JAVA, "bool enabled(bool flag) { return flag; } int value() { return enabled(1); }");
        assertTrue(wrongType.diagnostics().stream().anyMatch(d -> d.code().equals("CB-CALL-ARG-TYPE")));
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
        assertEquals("CB-PARSE-CPP-UNSUPPORTED", result.diagnostics().getFirst().code());
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
        assertTrue(javaToCpp.output().orElseThrow().contains("puts(\"ready\")"));
    }
    @Test void localHttpApiServesHealthAndConversionRequests() throws Exception {
        try (var server = new com.tascode.codebridge.web.CodeBridgeHttpServer(new java.net.InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            var client = java.net.http.HttpClient.newHttpClient();
            var health = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/health")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"ok\""));
            String json = "{\"from\":\"c\",\"to\":\"java\",\"source\":\"int value() { return 42; }\"}";
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
