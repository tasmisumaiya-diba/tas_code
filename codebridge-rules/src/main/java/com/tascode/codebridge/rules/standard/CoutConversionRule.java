package com.tascode.codebridge.rules.standard;

import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.rules.ConversionRule;
import com.tascode.codebridge.rules.RuleContext;
import java.util.ArrayList;
import java.util.List;

/** Converts supported C++ cout chains into ordered Java console calls. */
public final class CoutConversionRule implements ConversionRule {
    @Override public boolean appliesTo(Language from, Language to, IrNode node) {
        return from == Language.CPP && to == Language.JAVA && node instanceof Program;
    }
    @Override public int priority() { return 110; }
    @Override public IrNode rewrite(IrNode node, RuleContext context) {
        Program program = (Program) node;
        List<FunctionDeclaration> functions = program.functions().stream().map(f -> new FunctionDeclaration(
                f.irVersion(), f.sourceSpan(), f.returnType(), f.name(), f.parameters(), (BlockStatement) rewriteStatement(f.body()))).toList();
        return new Program(program.irVersion(), program.sourceSpan(), functions, program.includes(), program.globals(), program.enums(), program.classes());
    }
    private Statement rewriteStatement(Statement statement) {
        if (statement instanceof BlockStatement block) return new BlockStatement(block.irVersion(), block.sourceSpan(), block.statements().stream().map(this::rewriteStatement).toList());
        if (statement instanceof ExpressionStatement expr && expr.expression() instanceof CallExpression call && call.callee().equals("__codebridge_cout")) {
            List<Statement> output = new ArrayList<>();
            for (Expression item : call.arguments()) {
                CallExpression mapped = item instanceof CallExpression endl && endl.callee().equals("__codebridge_endl")
                        ? new CallExpression(endl.irVersion(), endl.sourceSpan(), "System.out.println", List.of())
                        : new CallExpression(item.irVersion(), item.sourceSpan(), "System.out.print", List.of(item));
                output.add(new ExpressionStatement(item.irVersion(), item.sourceSpan(), mapped));
            }
            return new BlockStatement(expr.irVersion(), expr.sourceSpan(), output);
        }
        if (statement instanceof IfStatement s) return new IfStatement(s.irVersion(), s.sourceSpan(), s.condition(), rewriteStatement(s.thenBranch()), s.elseBranch().map(this::rewriteStatement));
        if (statement instanceof WhileStatement s) return new WhileStatement(s.irVersion(), s.sourceSpan(), s.condition(), rewriteStatement(s.body()));
        if (statement instanceof DoWhileStatement s) return new DoWhileStatement(s.irVersion(), s.sourceSpan(), rewriteStatement(s.body()), s.condition());
        if (statement instanceof ForStatement s) return new ForStatement(s.irVersion(), s.sourceSpan(), s.initializer().map(this::rewriteStatement), s.condition(), s.update(), rewriteStatement(s.body()));
        return statement;
    }
}
