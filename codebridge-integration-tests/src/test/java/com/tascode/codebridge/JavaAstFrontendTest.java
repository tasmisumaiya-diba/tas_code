package com.tascode.codebridge;

import com.tascode.codebridge.language.java.JavaAstFrontend;
import com.tascode.codebridge.language.java.JavaFrontendModel;
import com.tascode.codebridge.language.java.JavaParser;
import com.tascode.codebridge.ir.TypeReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaAstFrontendTest {
    private final JavaAstFrontend frontend = new JavaAstFrontend();

    @Test
    void parsesModernJavaGrammarAndIndexesNestedGenericTypeShapes() {
        String source = "package sample; import java.util.List; import java.util.Map; " +
                "class Box<T extends Number> { private Map<String, List<? extends T[]>>[] values; " +
                "public <U extends Comparable<U>> U choose(U value) throws IllegalArgumentException { return value; } }";
        JavaAstFrontend.Result result = frontend.parse(source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        JavaFrontendModel model = result.model().orElseThrow();
        assertEquals(21, model.languageLevel());
        assertEquals("sample", model.packageName().orElseThrow());
        assertEquals(2, model.imports().size());
        JavaFrontendModel.TypeModel type = model.types().getFirst();
        assertEquals("T", type.typeParameters().getFirst().name());
        JavaFrontendModel.MemberModel field = type.members().stream().filter(m -> m.kind().equals("FIELD")).findFirst().orElseThrow();
        TypeReference fieldType = field.type().orElseThrow();
        assertEquals("Map", fieldType.name());
        assertEquals(1, fieldType.arrayDimensions());
        assertEquals(2, fieldType.typeArguments().size());
        TypeReference wildcard = fieldType.typeArguments().get(1).typeArguments().getFirst();
        assertEquals(TypeReference.BoundKind.EXTENDS, wildcard.boundKind());
        assertEquals(1, wildcard.bound().orElseThrow().arrayDimensions());
        JavaFrontendModel.MemberModel method = type.members().stream().filter(m -> m.kind().equals("METHOD")).findFirst().orElseThrow();
        assertEquals("choose", method.name());
        assertEquals("U", method.parameterTypes().getFirst().name());
        assertEquals("IllegalArgumentException", method.thrownTypes().getFirst().name());
    }

    @Test
    void parsesSwitchExpressionsAndPatternMatchingWithoutLoweringThemYet() {
        String source = "class Modern { Object value(Object input) { return switch (input) { case String text -> text; default -> null; }; } }";
        JavaAstFrontend.Result result = frontend.parse(source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.model().orElseThrow().syntaxKinds().contains("SWITCH_EXPRESSION"));
        assertTrue(result.model().orElseThrow().syntaxKinds().contains("BINDING_PATTERN"));
    }

    @Test
    void resolvesSymbolsAndAttributesExpressionTypes() {
        JavaAstFrontend.Result result = frontend.parse("public class SemanticSample { public static String echo(String value) { return value; } }");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        JavaFrontendModel model = result.model().orElseThrow();
        assertTrue(model.resolvedSymbols().stream().anyMatch(symbol -> symbol.name().equals("echo")
                && symbol.elementKind().equals("METHOD") && symbol.qualifiedName().contains("SemanticSample.echo")),
                model.resolvedSymbols().toString());
        assertTrue(model.resolvedSymbols().stream().anyMatch(symbol -> symbol.name().equals("value")
                && symbol.elementKind().equals("PARAMETER") && symbol.type().equals("java.lang.String")),
                model.resolvedSymbols().toString());
        assertTrue(model.attributedTypes().stream().anyMatch(type -> type.syntaxKind().equals("METHOD_INVOCATION")
                || type.type().equals("java.lang.String")), model.attributedTypes().toString());
    }

    @Test
    void reportsUnresolvedSymbolsAndTypeErrorsDuringAttribution() {
        JavaAstFrontend.Result unresolved = frontend.parse("class Bad { static int value() { return missingName; } }");
        assertFalse(unresolved.successful());
        assertEquals("CB-JAVA-SYMBOL-UNRESOLVED", unresolved.diagnostics().getFirst().code());
        assertTrue(unresolved.diagnostics().getFirst().location().isPresent());

        JavaAstFrontend.Result mismatch = frontend.parse("class Bad { static int value() { return \"text\"; } }");
        assertFalse(mismatch.successful());
        assertEquals("CB-JAVA-TYPE-ERROR", mismatch.diagnostics().getFirst().code());
        assertTrue(mismatch.diagnostics().getFirst().location().isPresent());

        var conversionParse = new JavaParser().parse("class Bad { public static int value() { return missingName; } }");
        assertTrue(conversionParse.successful(), () -> conversionParse.diagnostics().toString());
        assertEquals("CB-JAVA-SYMBOL-UNRESOLVED", conversionParse.semanticDiagnostics().getFirst().code());
        var conversion = com.tascode.codebridge.CodeBridge.convert(com.tascode.codebridge.language.Language.JAVA,
                com.tascode.codebridge.language.Language.CPP, "class Bad { public static int value() { return missingName; } }");
        assertFalse(conversion.successful());
        assertEquals("CB-SYMBOL-UNRESOLVED", conversion.diagnostics().getFirst().code());
    }

    @Test
    void conversionParserUsesFrontendSyntaxDiagnostics() {
        var result = new JavaParser().parse("class Broken { int value( { } }");
        assertFalse(result.successful());
        assertEquals("CB-JAVA-SYNTAX", result.diagnostics().getFirst().code());
    }

    @Test
    void reportsLocatedStableDiagnosticForMalformedJavaSyntax() {
        JavaAstFrontend.Result result = frontend.parse("class Broken { void run( { } }");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.code().equals("CB-JAVA-SYNTAX")));
        assertTrue(result.diagnostics().getFirst().location().isPresent());
    }
}
