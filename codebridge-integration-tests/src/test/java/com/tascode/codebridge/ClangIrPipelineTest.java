package com.tascode.codebridge;

import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.frontend.clang.ClangIrLowerer;
import com.tascode.codebridge.frontend.clang.ClangSemanticModel;
import com.tascode.codebridge.frontend.clang.SemanticAstNode;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.validation.CompilerValidationService;
import com.tascode.codebridge.validation.ValidationStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.*;

class ClangIrPipelineTest {
    @Test void loweredClangIrRunsThroughExistingSharedConversionPipeline() {
        String source = "int add(int a, int b) { return a + b; }";
        SemanticAstNode left = node("DeclRefExpr", "a", "int", source, "a", List.of());
        SemanticAstNode right = node("DeclRefExpr", "b", "int", source, "b", List.of());
        SemanticAstNode sum = node("BinaryOperator", "", "int", source, "a + b", List.of(left, right));
        SemanticAstNode returned = node("ReturnStmt", "", "", source, "return a + b;", List.of(sum));
        SemanticAstNode body = node("CompoundStmt", "", "", source, "{ return a + b; }", List.of(returned));
        SemanticAstNode paramA = node("ParmDecl", "a", "int", source, "a", List.of());
        SemanticAstNode paramB = node("ParmDecl", "b", "int", source, "b", List.of());
        SemanticAstNode function = new SemanticAstNode("FunctionDecl", "add", "add(int, int)", "int (int, int)",
                "int (int, int)", "", "", "", Optional.of(span(source, 0, source.length())),
                Map.of("definition", "true"), List.of(paramA, paramB, body));
        var lowered = new ClangIrLowerer().lower(new ClangSemanticModel(Language.C, "c17", "clang-test", "x86_64-test", 64,
                List.of(function), Map.of()), source, List.of());
        assertTrue(lowered.successful(), lowered.diagnostics().toString());

        var cpp = CodeBridge.createPipeline().convertIr(Language.C, Language.CPP, lowered.program());
        var java = CodeBridge.createPipeline().convertIr(Language.C, Language.JAVA, lowered.program());

        assertTrue(cpp.successful(), cpp.diagnostics().toString());
        assertTrue(cpp.output().orElseThrow().contains("int add(int a, int b)"));
        var compilation = new CompilerValidationService().validate(Language.CPP, cpp.output().orElseThrow());
        Assumptions.assumeTrue(compilation.status() != ValidationStatus.COMPILER_UNAVAILABLE,
                "C++ compiler is unavailable in this environment");
        assertTrue(compilation.successful(), compilation.diagnostics().toString());
        assertTrue(java.successful(), java.diagnostics().toString());
        assertTrue(java.output().orElseThrow().contains("public static int add(int a, int b)"));
    }

    private SemanticAstNode node(String kind, String name, String type, String source, String fragment, List<SemanticAstNode> children) {
        int offset;
        if (kind.equals("ParmDecl")) offset = source.indexOf(" " + name, source.indexOf('(')) + 1;
        else if (kind.equals("DeclRefExpr")) offset = source.lastIndexOf(name);
        else offset = source.indexOf(fragment);
        assertTrue(offset >= 0, fragment);
        return new SemanticAstNode(kind, name, "", type, type, "", "test-symbol",
                kind.equals("DeclRefExpr") ? name : "", Optional.of(span(source, offset, offset + fragment.length())), Map.of(), children);
    }

    private SourceSpan span(String source, int start, int end) {
        return new SourceSpan(1, start + 1, 1, end + 1);
    }
}
