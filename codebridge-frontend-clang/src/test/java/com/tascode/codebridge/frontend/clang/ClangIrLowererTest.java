package com.tascode.codebridge.frontend.clang;

import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.ir.BinaryExpression;
import com.tascode.codebridge.ir.BlockStatement;
import com.tascode.codebridge.ir.FunctionDeclaration;
import com.tascode.codebridge.ir.ForStatement;
import com.tascode.codebridge.ir.IfStatement;
import com.tascode.codebridge.ir.Program;
import com.tascode.codebridge.ir.ReturnStatement;
import com.tascode.codebridge.language.Language;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClangIrLowererTest {
    private final ClangIrLowerer lowerer = new ClangIrLowerer();

    @Test void lowersClangFunctionAndTypedArithmeticIntoSharedIr() {
        String source = "int add(int a, int b) { return a + b; }";
        SemanticAstNode left = node("DeclRefExpr", "a", "int", source, "a", Map.of(), List.of());
        SemanticAstNode right = node("DeclRefExpr", "b", "int", source, "b", Map.of(), List.of());
        SemanticAstNode binary = node("BinaryOperator", "", "int", source, "a + b", Map.of(), List.of(left, right));
        SemanticAstNode returned = node("ReturnStmt", "", "", source, "return a + b;", Map.of(), List.of(binary));
        SemanticAstNode body = node("CompoundStmt", "", "", source, "{ return a + b; }", Map.of(), List.of(returned));
        SemanticAstNode parameterA = node("ParmDecl", "a", "int", source, "a", Map.of(), List.of());
        SemanticAstNode parameterB = node("ParmDecl", "b", "int", source, "b", Map.of(), List.of());
        SemanticAstNode function = node("FunctionDecl", "add", "int (int, int)", source, source,
                Map.of("definition", "true"), List.of(parameterA, parameterB, body));

        var result = lowerer.lower(model(function), source, List.of());

        assertTrue(result.successful(), result.diagnostics().toString());
        Program program = result.program();
        assertEquals(1, program.functions().size());
        FunctionDeclaration add = program.functions().getFirst();
        assertEquals("int", add.returnType());
        assertEquals(List.of("a", "b"), add.parameters().stream().map(p -> p.name()).toList());
        ReturnStatement returnStatement = (ReturnStatement) add.body().statements().getFirst();
        BinaryExpression expression = assertInstanceOf(BinaryExpression.class, returnStatement.value().orElseThrow());
        assertEquals("+", expression.operator());
        assertEquals(1, expression.sourceSpan().startLine());
    }

    @Test void pointerTypesProduceLocatedSemanticDiagnosticInsteadOfDroppingType() {
        String source = "int *identity(int *value) { return value; }";
        SemanticAstNode function = node("FunctionDecl", "identity", "int *(int *)", source, source,
                Map.of("definition", "true"), List.of());

        var result = lowerer.lower(model(function), source, List.of());

        assertFalse(result.successful());
        assertEquals("CB-CLANG-TYPE-UNSUPPORTED", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().location().isPresent());
        assertTrue(result.diagnostics().getFirst().message().contains("Pointer types"));
    }

    @Test void lowersConditionalStatementAndPreservesBranches() {
        String source = "int value(int input) { if (input > 0) return 1; else return 0; }";
        SemanticAstNode input = node("DeclRefExpr", "input", "int", source, "input", Map.of(), List.of());
        SemanticAstNode zero = node("IntegerLiteral", "", "int", source, "0", Map.of(), List.of());
        SemanticAstNode condition = node("BinaryOperator", "", "int", source, "input > 0", Map.of(), List.of(input, zero));
        SemanticAstNode one = node("IntegerLiteral", "", "int", source, "1", Map.of(), List.of());
        SemanticAstNode zeroReturn = node("IntegerLiteral", "", "int", source, "0", Map.of(), List.of());
        SemanticAstNode thenReturn = node("ReturnStmt", "", "", source, "return 1;", Map.of(), List.of(one));
        SemanticAstNode elseReturn = node("ReturnStmt", "", "", source, "return 0;", Map.of(), List.of(zeroReturn));
        SemanticAstNode conditional = node("IfStmt", "", "", source, "if (input > 0) return 1; else return 0;", Map.of(), List.of(condition, thenReturn, elseReturn));
        SemanticAstNode body = node("CompoundStmt", "", "", source, "{ if (input > 0) return 1; else return 0; }", Map.of(), List.of(conditional));
        SemanticAstNode parameter = node("ParmDecl", "input", "int", source, "input", Map.of(), List.of());
        SemanticAstNode function = node("FunctionDecl", "value", "int (int)", source, source,
                Map.of("definition", "true"), List.of(parameter, body));

        var result = lowerer.lower(model(function), source, List.of());

        assertTrue(result.successful(), result.diagnostics().toString());
        IfStatement lowered = assertInstanceOf(IfStatement.class, result.program().functions().getFirst().body().statements().getFirst());
        assertTrue(lowered.elseBranch().isPresent());
    }

    @Test void unsupportedSwitchIsDiagnosedAtItsAstLocation() {
        String source = "int value() { switch (1) {} return 0; }";
        SemanticAstNode switchNode = node("SwitchStmt", "", "", source, "switch (1) {}", Map.of(), List.of());
        SemanticAstNode body = node("CompoundStmt", "", "", source, "{ switch (1) {} return 0; }", Map.of(), List.of(switchNode));
        SemanticAstNode function = node("FunctionDecl", "value", "int ()", source, source,
                Map.of("definition", "true"), List.of(body));

        var result = lowerer.lower(model(function), source, List.of());

        assertFalse(result.successful());
        assertEquals("CB-CLANG-STATEMENT-UNSUPPORTED", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().location().isPresent());
    }

    @Test void lowersExplicitFourChildForLoop() {
        String source = "int count() { int i = 0; for (i = 0; i < 3; i++) { } return i; }";
        int loopStart = source.indexOf("for (");
        int initStart = source.indexOf("i = 0", loopStart);
        int conditionStart = source.indexOf("i < 3", loopStart);
        int updateStart = source.indexOf("i++", loopStart);
        SemanticAstNode initLeft = nodeAt("DeclRefExpr", "i", "int", initStart, initStart + 1, List.of());
        SemanticAstNode initRight = nodeAt("IntegerLiteral", "", "int", initStart + 4, initStart + 5, List.of());
        SemanticAstNode init = nodeAt("BinaryOperator", "", "int", initStart, initStart + 5, List.of(initLeft, initRight));
        SemanticAstNode conditionLeft = nodeAt("DeclRefExpr", "i", "int", conditionStart, conditionStart + 1, List.of());
        SemanticAstNode limit = nodeAt("IntegerLiteral", "", "int", conditionStart + 4, conditionStart + 5, List.of());
        SemanticAstNode condition = nodeAt("BinaryOperator", "", "int", conditionStart, conditionStart + 5, List.of(conditionLeft, limit));
        SemanticAstNode updateName = nodeAt("DeclRefExpr", "i", "int", updateStart, updateStart + 1, List.of());
        SemanticAstNode update = nodeAt("UnaryOperator", "", "int", updateStart, updateStart + 3, List.of(updateName));
        SemanticAstNode loopBody = node("CompoundStmt", "", "", source, "{ }", Map.of(), List.of());
        int loopEnd = source.indexOf(" { }", updateStart) + 4;
        SemanticAstNode loop = nodeAt("ForStmt", "", "", loopStart, loopEnd, List.of(init, condition, update, loopBody));
        SemanticAstNode initialValue = node("IntegerLiteral", "", "int", source, "0", Map.of(), List.of());
        SemanticAstNode variable = node("VarDecl", "i", "int", source, "int i = 0", Map.of(), List.of(initialValue));
        SemanticAstNode declaration = node("DeclStmt", "", "", source, "int i = 0;", Map.of(), List.of(variable));
        SemanticAstNode functionBody = node("CompoundStmt", "", "", source, "{ int i = 0; for (i = 0; i < 3; i++) { } return i; }", Map.of(), List.of(declaration, loop));
        SemanticAstNode function = node("FunctionDecl", "count", "int ()", source, source,
                Map.of("definition", "true"), List.of(functionBody));

        var result = lowerer.lower(model(function), source, List.of());

        assertTrue(result.successful(), result.diagnostics().toString());
        ForStatement lowered = assertInstanceOf(ForStatement.class, result.program().functions().getFirst().body().statements().get(1));
        assertTrue(lowered.initializer().isPresent());
        assertTrue(lowered.condition().isPresent());
        assertTrue(lowered.update().isPresent());
    }

    @Test void internalLinkageIsNotSilentlyChangedToExternalLinkage() {
        String source = "static int value() { return 1; }";
        SemanticAstNode literal = node("IntegerLiteral", "", "int", source, "1", Map.of(), List.of());
        SemanticAstNode returned = node("ReturnStmt", "", "", source, "return 1;", Map.of(), List.of(literal));
        SemanticAstNode body = node("CompoundStmt", "", "", source, "{ return 1; }", Map.of(), List.of(returned));
        SemanticAstNode function = node("FunctionDecl", "value", "int ()", source, source,
                Map.of("definition", "true", "linkage", "internal"), List.of(body));

        var result = lowerer.lower(model(function), source, List.of());

        assertFalse(result.successful());
        assertEquals("CB-CLANG-LINKAGE-UNSUPPORTED", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().location().isPresent());
    }

    private ClangSemanticModel model(SemanticAstNode... declarations) {
        return new ClangSemanticModel(Language.C, "c17", "test-clang", "x86_64-test", 64,
                List.of(declarations), Map.of());
    }

    private SemanticAstNode node(String kind, String name, String type, String source, String fragment,
                                 Map<String, String> attributes, List<SemanticAstNode> children) {
        int offset = source.indexOf(fragment);
        if (kind.equals("ParmDecl")) offset = source.indexOf(" " + name, source.indexOf('(')) + 1;
        if (kind.equals("DeclRefExpr")) offset = source.lastIndexOf(name);
        assertTrue(offset >= 0, "Fragment is in test source: " + fragment);
        int end = offset + fragment.length();
        String referencedName = kind.equals("DeclRefExpr") ? name : "";
        return new SemanticAstNode(kind, name, "", type, type, "", "test-symbol", referencedName,
                Optional.of(new SourceSpan(1, offset + 1, 1, end + 1)), attributes, children);
    }

    private SemanticAstNode nodeAt(String kind, String name, String type, int start, int end, List<SemanticAstNode> children) {
        return new SemanticAstNode(kind, name, "", type, type, "", "test-symbol", name,
                Optional.of(new SourceSpan(1, start + 1, 1, end + 1)), Map.of(), children);
    }
}
