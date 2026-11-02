package com.tascode.codebridge.analysis;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import java.util.ArrayList;
import java.util.List;

/** Rejects string values for backends that cannot represent them in this subset. */
public final class StringFeatureAnalyzer {
    public List<Diagnostic> analyze(Program program, Language target) {
        if (target != Language.C || !containsString(program)) return List.of();
        return List.of(Diagnostic.at(Severity.ERROR, "CB-UNSUP-STRING-C", "String values are supported only when converting between Java and C++ in the current subset.", program.sourceSpan()));
    }
    private boolean containsString(Program p) {
        for (FunctionDeclaration f : p.functions()) {
            if (stringType(f.returnType()) || f.parameters().stream().anyMatch(x -> stringType(x.type())) || containsString(f.body())) return true;
        }
        return false;
    }
    private boolean containsString(Statement s) {
        if (s instanceof BlockStatement b) return b.statements().stream().anyMatch(this::containsString);
        if (s instanceof VariableDeclaration v) return stringType(v.type()) || v.initializer().map(this::containsString).orElse(false);
        if (s instanceof ArrayDeclaration a) return stringType(a.elementType());
        if (s instanceof ReturnStatement r) return r.value().map(this::containsString).orElse(false);
        if (s instanceof ExpressionStatement e) return containsString(e.expression());
        if (s instanceof IfStatement i) return containsString(i.condition()) || containsString(i.thenBranch()) || i.elseBranch().map(this::containsString).orElse(false);
        if (s instanceof WhileStatement w) return containsString(w.condition()) || containsString(w.body());
        if (s instanceof DoWhileStatement d) return containsString(d.body()) || containsString(d.condition());
        if (s instanceof ForStatement f) return f.initializer().map(this::containsString).orElse(false) || f.condition().map(this::containsString).orElse(false) || f.update().map(this::containsString).orElse(false) || containsString(f.body());
        return false;
    }
    private boolean containsString(Expression e) {
        if (e instanceof LiteralExpression literal) return literal.value() instanceof String;
        if (e instanceof BinaryExpression binary) return containsString(binary.left()) || containsString(binary.right());
        if (e instanceof UnaryExpression unary) return containsString(unary.operand());
        if (e instanceof AssignmentExpression assignment) return containsString(assignment.target()) || containsString(assignment.value());
        if (e instanceof ArrayAccessExpression access) return containsString(access.array()) || containsString(access.index());
        if (e instanceof CallExpression call) {
            if (List.of("printf", "puts", "scanf", "System.out.printf", "System.out.println", "System.out.print").contains(call.callee())) return false;
            return call.arguments().stream().anyMatch(this::containsString);
        }
        return false;
    }
    private boolean stringType(String type) { return type.equals("string") || type.equals("String"); }
}
