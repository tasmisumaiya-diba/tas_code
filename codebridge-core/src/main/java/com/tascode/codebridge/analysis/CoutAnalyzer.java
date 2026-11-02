package com.tascode.codebridge.analysis;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import java.util.ArrayList;
import java.util.List;

/** Restricts the special stream-chain IR markers to standalone C++ cout statements. */
public final class CoutAnalyzer {
    public List<Diagnostic> analyze(Program program, Language source) {
        if (source != Language.CPP) return List.of();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (FunctionDeclaration function : program.functions()) visitStatement(function.body(), diagnostics);
        return List.copyOf(diagnostics);
    }
    private void visitStatement(Statement s, List<Diagnostic> out) {
        if (s instanceof BlockStatement b) b.statements().forEach(child -> visitStatement(child, out));
        else if (s instanceof ExpressionStatement e) visitExpression(e.expression(), true, false, out);
        else if (s instanceof VariableDeclaration v) v.initializer().ifPresent(e -> visitExpression(e, false, false, out));
        else if (s instanceof ArrayDeclaration a) visitExpression(a.size(), false, false, out);
        else if (s instanceof ReturnStatement r) r.value().ifPresent(e -> visitExpression(e, false, false, out));
        else if (s instanceof IfStatement i) { visitExpression(i.condition(), false, false, out); visitStatement(i.thenBranch(), out); i.elseBranch().ifPresent(x -> visitStatement(x, out)); }
        else if (s instanceof WhileStatement w) { visitExpression(w.condition(), false, false, out); visitStatement(w.body(), out); }
        else if (s instanceof DoWhileStatement d) { visitStatement(d.body(), out); visitExpression(d.condition(), false, false, out); }
        else if (s instanceof ForStatement f) { f.initializer().ifPresent(x -> visitStatement(x, out)); f.condition().ifPresent(x -> visitExpression(x, false, false, out)); f.update().ifPresent(x -> visitExpression(x, false, false, out)); visitStatement(f.body(), out); }
    }
    private void visitExpression(Expression e, boolean standalone, boolean insideCout, List<Diagnostic> out) {
        if (e instanceof CallExpression c) {
            if (c.callee().equals("__codebridge_cout")) {
                if (!standalone) out.add(Diagnostic.at(Severity.ERROR, "CB-IO-COUT-CONTEXT", "std::cout stream chains must be complete standalone statements.", c.sourceSpan()));
                c.arguments().forEach(x -> visitExpression(x, false, true, out));
            } else if (c.callee().equals("__codebridge_endl")) {
                if (!insideCout) out.add(Diagnostic.at(Severity.ERROR, "CB-IO-COUT-CONTEXT", "std::endl is supported only inside a std::cout stream chain.", c.sourceSpan()));
            } else c.arguments().forEach(x -> visitExpression(x, false, false, out));
        } else if (e instanceof BinaryExpression b) { visitExpression(b.left(), false, false, out); visitExpression(b.right(), false, false, out); }
        else if (e instanceof UnaryExpression u) visitExpression(u.operand(), false, false, out);
        else if (e instanceof AssignmentExpression a) { visitExpression(a.target(), false, false, out); visitExpression(a.value(), false, false, out); }
        else if (e instanceof ArrayAccessExpression a) { visitExpression(a.array(), false, false, out); visitExpression(a.index(), false, false, out); }
    }
}
