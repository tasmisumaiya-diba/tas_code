package com.tascode.codebridge.rules.standard;

import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.rules.ConversionRule;
import com.tascode.codebridge.rules.RuleContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts supported C++ cout chains into ordered Java console calls. */
public final class CoutConversionRule implements ConversionRule {
    @Override public boolean appliesTo(Language from, Language to, IrNode node) {
        return from == Language.CPP && to == Language.JAVA && node instanceof Program;
    }
    @Override public int priority() { return 110; }
    @Override public IrNode rewrite(IrNode node, RuleContext context) {
        Program program = (Program) node;
        List<FunctionDeclaration> functions = program.functions().stream().map(f -> {
            Map<String, String> locals = new HashMap<>();
            f.parameters().forEach(parameter -> locals.put(parameter.name(), parameter.type()));
            return new FunctionDeclaration(f.irVersion(), f.sourceSpan(), f.returnType(), f.name(), f.parameters(),
                    (BlockStatement) rewriteStatement(f.body(), locals));
        }).toList();
        return new Program(program.irVersion(), program.sourceSpan(), functions, program.includes(), program.globals(), program.enums(), program.classes());
    }
    private Statement rewriteStatement(Statement statement, Map<String, String> locals) {
        if (statement instanceof BlockStatement block) {
            Map<String, String> scope = new HashMap<>(locals);
            List<Statement> children = new ArrayList<>();
            for (Statement child : block.statements()) children.add(rewriteStatement(child, scope));
            return new BlockStatement(block.irVersion(), block.sourceSpan(), children);
        }
        if (statement instanceof VariableDeclaration variable) {
            locals.put(variable.name(), variable.type());
            return statement;
        }
        if (statement instanceof ArrayDeclaration array) {
            locals.put(array.name(), "array:" + array.elementType());
            return statement;
        }
        if (statement instanceof ExpressionStatement expr && expr.expression() instanceof CallExpression call && call.callee().equals("__codebridge_cout")) {
            if (singleValueLine(call, locals)) {
                Expression value = call.arguments().getFirst();
                return new ExpressionStatement(expr.irVersion(), expr.sourceSpan(),
                        new CallExpression(call.irVersion(), call.sourceSpan(), "System.out.println", List.of(value)));
            }
            if (stringLine(call, locals)) {
                List<Expression> values = call.arguments().subList(0, call.arguments().size() - 1);
                Expression joined = values.getFirst();
                for (Expression value : values.subList(1, values.size()))
                    joined = new BinaryExpression(call.irVersion(), value.sourceSpan(), "+", joined, value);
                return new ExpressionStatement(expr.irVersion(), expr.sourceSpan(),
                        new CallExpression(call.irVersion(), call.sourceSpan(), "System.out.println", List.of(joined)));
            }
            List<Statement> output = new ArrayList<>();
            for (Expression item : call.arguments()) {
                CallExpression mapped = item instanceof CallExpression endl && endl.callee().equals("__codebridge_endl")
                        ? new CallExpression(endl.irVersion(), endl.sourceSpan(), "System.out.println", List.of())
                        : new CallExpression(item.irVersion(), item.sourceSpan(), "System.out.print", List.of(item));
                output.add(new ExpressionStatement(item.irVersion(), item.sourceSpan(), mapped));
            }
            return new BlockStatement(expr.irVersion(), expr.sourceSpan(), output);
        }
        if (statement instanceof IfStatement s) return new IfStatement(s.irVersion(), s.sourceSpan(), s.condition(),
                rewriteStatement(s.thenBranch(), new HashMap<>(locals)),
                s.elseBranch().map(branch -> rewriteStatement(branch, new HashMap<>(locals))));
        if (statement instanceof WhileStatement s) return new WhileStatement(s.irVersion(), s.sourceSpan(), s.condition(),
                rewriteStatement(s.body(), new HashMap<>(locals)));
        if (statement instanceof DoWhileStatement s) return new DoWhileStatement(s.irVersion(), s.sourceSpan(),
                rewriteStatement(s.body(), new HashMap<>(locals)), s.condition());
        if (statement instanceof ForStatement s) {
            Map<String, String> loopLocals = new HashMap<>(locals);
            return new ForStatement(s.irVersion(), s.sourceSpan(), s.initializer().map(initializer -> rewriteStatement(initializer, loopLocals)),
                    s.condition(), s.update(), rewriteStatement(s.body(), loopLocals));
        }
        return statement;
    }

    private boolean singleValueLine(CallExpression stream, Map<String, String> locals) {
        if (stream.arguments().size() != 2 || !(stream.arguments().get(1) instanceof CallExpression endl)
                || !endl.callee().equals("__codebridge_endl")) return false;
        return Set.of("int", "short", "long", "char", "string").contains(typeOf(stream.arguments().getFirst(), locals));
    }

    /** Java string concatenation preserves order for supported, side-effect-safe cout operands. */
    private boolean stringLine(CallExpression stream, Map<String, String> locals) {
        if (stream.arguments().size() < 3
                || !(stream.arguments().getLast() instanceof CallExpression endl)
                || !endl.callee().equals("__codebridge_endl")
                || !typeOf(stream.arguments().getFirst(), locals).equals("string")) return false;
        return stream.arguments().subList(1, stream.arguments().size() - 1).stream()
                .allMatch(value -> Set.of("int", "short", "long", "char", "string").contains(typeOf(value, locals)));
    }

    private String typeOf(Expression expression, Map<String, String> locals) {
        if (expression instanceof NameExpression name) return locals.getOrDefault(name.name(), "unknown");
        if (expression instanceof LiteralExpression literal) {
            if (literal.value() instanceof String) return "string";
            if (literal.value() instanceof Character) return "char";
            if (literal.value() instanceof Byte || literal.value() instanceof Short) return "short";
            if (literal.value() instanceof Integer) return "int";
            if (literal.value() instanceof Long) return "long";
            return "unknown";
        }
        if (expression instanceof BinaryExpression binary) {
            String left = typeOf(binary.left(), locals), right = typeOf(binary.right(), locals);
            if (binary.operator().equals("+") && left.equals("string") && right.equals("string")) return "string";
            if (Set.of("int", "short", "long", "char").contains(left)
                    && Set.of("int", "short", "long", "char").contains(right))
                return left.equals("long") || right.equals("long") ? "long" : "int";
        }
        return "unknown";
    }
}
