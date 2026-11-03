package com.tascode.codebridge.rules.standard;

import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.rules.ConversionRule;
import com.tascode.codebridge.rules.RuleContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rewrites known function callees from a classpath TSV mapping table. */
public final class StandardLibraryCallRule implements ConversionRule {
    private final Map<MappingKey, String> mappings;

    public StandardLibraryCallRule() {
        this.mappings = loadMappings();
    }

    @Override
    public boolean appliesTo(Language from, Language to, IrNode node) {
        return node instanceof Program && ((from == Language.C && to == Language.CPP)
                || mappings.keySet().stream().anyMatch(key -> key.from == from && key.to == to));
    }

    @Override public int priority() { return 100; }

    @Override
    public IrNode rewrite(IrNode node, RuleContext context) {
        if (!(node instanceof Program program)) return node;
        List<FunctionDeclaration> functions = program.functions().stream().map(function -> {
            Map<String, String> locals = new HashMap<>();
            function.parameters().forEach(parameter -> locals.put(parameter.name(), parameter.type()));
            return new FunctionDeclaration(function.irVersion(), function.sourceSpan(), function.returnType(), function.name(),
                    function.parameters(), (BlockStatement) rewriteStatement(function.body(), context, locals));
        }).toList();
        return new Program(program.irVersion(), program.sourceSpan(), functions, program.includes(), program.globals(), program.enums(), program.classes());
    }

    private Statement rewriteStatement(Statement statement, RuleContext context, Map<String, String> locals) {
        if (statement instanceof BlockStatement block) {
            Map<String, String> scope = new HashMap<>(locals);
            List<Statement> children = new ArrayList<>();
            for (Statement child : block.statements()) children.add(rewriteStatement(child, context, scope));
            return new BlockStatement(block.irVersion(), block.sourceSpan(), children);
        }
        if (statement instanceof ArrayDeclaration array) {
            locals.put(array.name(), "array:" + array.elementType());
            return new ArrayDeclaration(array.irVersion(), array.sourceSpan(), array.elementType(), array.name(),
                    rewriteExpression(array.size(), context, locals));
        }
        if (statement instanceof VariableDeclaration variable) {
            Expression initializer = variable.initializer().map(value -> rewriteExpression(value, context, locals)).orElse(null);
            locals.put(variable.name(), variable.type());
            return new VariableDeclaration(variable.irVersion(), variable.sourceSpan(), variable.name(), variable.type(),
                    java.util.Optional.ofNullable(initializer));
        }
        if (statement instanceof ReturnStatement returned) {
            return new ReturnStatement(returned.irVersion(), returned.sourceSpan(),
                    returned.value().map(value -> rewriteExpression(value, context, locals)));
        }
        if (statement instanceof ExpressionStatement expression) {
            if (context.from() == Language.C && context.to() == Language.JAVA
                    && expression.expression() instanceof CallExpression call && call.callee().equals("printf")) {
                CallExpression println = simpleIntegerPrintfLine(call, locals);
                if (println != null) return new ExpressionStatement(expression.irVersion(), expression.sourceSpan(), println);
            }
            if (context.from() == Language.C && context.to() == Language.CPP
                    && expression.expression() instanceof CallExpression call && call.callee().equals("printf")) {
                CallExpression stream = simpleIntegerPrintf(call, locals);
                if (stream != null) return new ExpressionStatement(expression.irVersion(), expression.sourceSpan(), stream);
            }
            return new ExpressionStatement(expression.irVersion(), expression.sourceSpan(), rewriteExpression(expression.expression(), context, locals));
        }
        if (statement instanceof ForStatement loop) {
            Map<String, String> loopLocals = new HashMap<>(locals);
            return new ForStatement(loop.irVersion(), loop.sourceSpan(),
                    loop.initializer().map(initializer -> rewriteStatement(initializer, context, loopLocals)),
                    loop.condition().map(condition -> rewriteExpression(condition, context, loopLocals)),
                    loop.update().map(update -> rewriteExpression(update, context, loopLocals)),
                    rewriteStatement(loop.body(), context, loopLocals));
        }
        if (statement instanceof IfStatement conditional) {
            return new IfStatement(conditional.irVersion(), conditional.sourceSpan(),
                    rewriteExpression(conditional.condition(), context, locals),
                    rewriteStatement(conditional.thenBranch(), context, new HashMap<>(locals)),
                    conditional.elseBranch().map(branch -> rewriteStatement(branch, context, new HashMap<>(locals))));
        }
        if (statement instanceof DoWhileStatement loop) {
            return new DoWhileStatement(loop.irVersion(), loop.sourceSpan(), rewriteStatement(loop.body(), context, new HashMap<>(locals)),
                    rewriteExpression(loop.condition(), context, locals));
        }
        if (statement instanceof WhileStatement loop) {
            return new WhileStatement(loop.irVersion(), loop.sourceSpan(), rewriteExpression(loop.condition(), context, locals),
                    rewriteStatement(loop.body(), context, new HashMap<>(locals)));
        }
        return statement;
    }

    /** Rewrites only basic, semantically matching printf conversions; other formats stay as printf. */
    private CallExpression simpleIntegerPrintf(CallExpression call, Map<String, String> locals) {
        if (call.arguments().isEmpty() || !(call.arguments().getFirst() instanceof LiteralExpression literal)
                || !(literal.value() instanceof String format)) return null;
        if (call.arguments().stream().skip(1).anyMatch(argument -> !isSideEffectFree(argument))) return null;
        List<Expression> streamItems = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int argument = 1;
        for (int i = 0; i < format.length(); i++) {
            char current = format.charAt(i);
            if (current != '%') { text.append(current); continue; }
            if (i + 1 >= format.length()) return null;
            char conversion = format.charAt(++i);
            if (conversion == '%') { text.append('%'); continue; }
            if (argument >= call.arguments().size()) return null;
            Expression value = call.arguments().get(argument++);
            String valueType = typeOf(value, locals);
            if (conversion == 'd' && !Set.of("int", "short").contains(valueType)) return null;
            if (conversion == 's' && (!(value instanceof LiteralExpression stringLiteral)
                    || !(stringLiteral.value() instanceof String))) return null;
            if (conversion == 'c' && !valueType.equals("char")) return null;
            if (conversion != 'd' && conversion != 's' && conversion != 'c') return null;
            appendTextItems(streamItems, literal, text);
            streamItems.add(value);
        }
        if (argument != call.arguments().size()) return null;
        appendTextItems(streamItems, literal, text);
        return new CallExpression(call.irVersion(), call.sourceSpan(), "__codebridge_cout", streamItems);
    }

    /** Maps the common single integer line-print to Java's idiomatic println without changing return-value uses. */
    private CallExpression simpleIntegerPrintfLine(CallExpression call, Map<String, String> locals) {
        if (call.arguments().size() != 2 || !(call.arguments().getFirst() instanceof LiteralExpression formatLiteral)
                || !"%d\n".equals(formatLiteral.value())) return null;
        Expression value = call.arguments().get(1);
        if (!Set.of("int", "short").contains(typeOf(value, locals))) return null;
        return new CallExpression(call.irVersion(), call.sourceSpan(), "System.out.println", List.of(value));
    }

    private void appendTextItems(List<Expression> items, LiteralExpression format, StringBuilder text) {
        String value = text.toString();
        text.setLength(0);
        StringBuilder segment = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\n') {
                if (!segment.isEmpty()) items.add(new LiteralExpression(format.irVersion(), format.sourceSpan(), segment.toString()));
                segment.setLength(0);
                items.add(new LiteralExpression(format.irVersion(), format.sourceSpan(), '\n'));
            } else segment.append(character);
        }
        if (!segment.isEmpty()) items.add(new LiteralExpression(format.irVersion(), format.sourceSpan(), segment.toString()));
    }

    private boolean isSideEffectFree(Expression expression) {
        if (expression instanceof NameExpression || expression instanceof LiteralExpression) return true;
        if (expression instanceof BinaryExpression binary)
            return isSideEffectFree(binary.left()) && isSideEffectFree(binary.right());
        if (expression instanceof UnaryExpression unary)
            return isSideEffectFree(unary.operand()) && !Set.of("++", "--").contains(unary.operator());
        if (expression instanceof ArrayAccessExpression access)
            return isSideEffectFree(access.array()) && isSideEffectFree(access.index());
        return false;
    }

    private Expression rewriteExpression(Expression expression, RuleContext context, Map<String, String> locals) {
        if (expression instanceof BinaryExpression binary) {
            Expression left = rewriteExpression(binary.left(), context, locals);
            Expression right = rewriteExpression(binary.right(), context, locals);
            if (binary.operator().equals("+") && left instanceof LiteralExpression leftLiteral
                    && right instanceof LiteralExpression rightLiteral
                    && leftLiteral.value() instanceof String leftText && rightLiteral.value() instanceof String rightText) {
                return new LiteralExpression(binary.irVersion(), binary.sourceSpan(), leftText + rightText);
            }
            return new BinaryExpression(binary.irVersion(), binary.sourceSpan(), binary.operator(), left, right);
        }
        if (expression instanceof ArrayAccessExpression access) {
            return new ArrayAccessExpression(access.irVersion(), access.sourceSpan(),
                    rewriteExpression(access.array(), context, locals), rewriteExpression(access.index(), context, locals));
        }
        if (expression instanceof AssignmentExpression assignment) {
            return new AssignmentExpression(assignment.irVersion(), assignment.sourceSpan(),
                    rewriteExpression(assignment.target(), context, locals), rewriteExpression(assignment.value(), context, locals));
        }
        if (expression instanceof UpdateExpression) return expression;
        if (expression instanceof UnaryExpression unary) {
            return new UnaryExpression(unary.irVersion(), unary.sourceSpan(), unary.operator(), rewriteExpression(unary.operand(), context, locals));
        }
        if (!(expression instanceof CallExpression call)) return expression;

        List<Expression> arguments = new ArrayList<>();
        for (int i = 0; i < call.arguments().size(); i++) {
            Expression argument = rewriteExpression(call.arguments().get(i), context, locals);
            if (i == 0 && context.from() != Language.JAVA && context.to() == Language.JAVA
                    && call.callee().equals("printf") && argument instanceof LiteralExpression literal
                    && literal.value() instanceof String format) {
                argument = new LiteralExpression(literal.irVersion(), literal.sourceSpan(), normalizeJavaFloatFormat(format));
            }
            arguments.add(argument);
        }
        String callee = mappings.getOrDefault(new MappingKey(context.from(), context.to(), call.callee()), call.callee());
        boolean javaToC = context.from() == Language.JAVA && context.to() == Language.C;
        boolean javaToCpp = context.from() == Language.JAVA && context.to() == Language.CPP;
        if ((javaToC || javaToCpp) && (call.callee().equals("System.out.print") || call.callee().equals("System.out.println"))) {
            boolean println = call.callee().equals("System.out.println");
            if (arguments.isEmpty() && println) {
                if (javaToC) return new CallExpression(call.irVersion(), call.sourceSpan(), "puts",
                        List.of(new LiteralExpression(call.irVersion(), call.sourceSpan(), "")));
                return cout(call, List.of(endl(call)));
            }
            String valueType = typeOf(call.arguments().getFirst(), locals);
            if (javaToCpp) {
                List<Expression> streamValues = new ArrayList<>(arguments);
                if (println) streamValues.add(endl(call));
                if (valueType.equals("bool"))
                    return new CallExpression(call.irVersion(), call.sourceSpan(), "__codebridge_cout_bool", streamValues);
                return cout(call, streamValues);
            }
            if (valueType.equals("string")) {
                if (println) return new CallExpression(call.irVersion(), call.sourceSpan(), "puts", arguments);
                LiteralExpression format = new LiteralExpression(call.irVersion(), call.sourceSpan(), "%s");
                return new CallExpression(call.irVersion(), call.sourceSpan(), "printf", List.of(format, arguments.getFirst()));
            }
            String conversion = switch (valueType) {
                case "int", "short" -> "%d";
                case "char" -> "%c";
                default -> throw new IllegalStateException("Unsupported Java print type reached rewrite: " + valueType);
            };
            LiteralExpression format = new LiteralExpression(call.irVersion(), call.sourceSpan(), conversion + (println ? "\n" : ""));
            return new CallExpression(call.irVersion(), call.sourceSpan(), "printf", List.of(format, arguments.getFirst()));
        }
        return new CallExpression(call.irVersion(), call.sourceSpan(), callee, arguments);
    }

    private CallExpression cout(CallExpression original, List<Expression> values) {
        return new CallExpression(original.irVersion(), original.sourceSpan(), "__codebridge_cout", values);
    }

    private CallExpression endl(CallExpression original) {
        return new CallExpression(original.irVersion(), original.sourceSpan(), "__codebridge_endl", List.of());
    }

    private String typeOf(Expression expression, Map<String, String> locals) {
        if (expression instanceof NameExpression name) return locals.getOrDefault(name.name(), "unknown");
        if (expression instanceof LiteralExpression literal) {
            if (literal.value() instanceof String) return "string";
            if (literal.value() instanceof Character) return "char";
            if (literal.value() instanceof Byte || literal.value() instanceof Short) return "short";
            if (literal.value() instanceof Number) return "int";
        }
        if (expression instanceof ArrayAccessExpression access && access.array() instanceof NameExpression arrayName) {
            String arrayType = locals.getOrDefault(arrayName.name(), "unknown");
            return arrayType.startsWith("array:") ? arrayType.substring("array:".length()) : "unknown";
        }
        if (expression instanceof UnaryExpression unary) return typeOf(unary.operand(), locals);
        if (expression instanceof BinaryExpression binary) {
            String left = typeOf(binary.left(), locals), right = typeOf(binary.right(), locals);
            if (left.equals("string") || right.equals("string")) return "string";
            if (left.equals("double") || right.equals("double")) return "double";
            if (left.equals("float") || right.equals("float")) return "float";
            if (left.equals("long") || right.equals("long")) return "long";
            if (Set.of("int", "short", "char").contains(left) && Set.of("int", "short", "char").contains(right)) return "int";
        }
        return "unknown";
    }
    private String normalizeJavaFloatFormat(String format) {
        StringBuilder normalized = new StringBuilder(format.length());
        for (int i = 0; i < format.length();) {
            if (format.charAt(i) != '%') {
                normalized.append(format.charAt(i++));
                continue;
            }
            normalized.append('%');
            i++;
            if (i < format.length() && format.charAt(i) == '%') {
                normalized.append('%');
                i++;
                continue;
            }
            while (i < format.length() && "-#+ 0".indexOf(format.charAt(i)) >= 0) normalized.append(format.charAt(i++));
            while (i < format.length() && Character.isDigit(format.charAt(i))) normalized.append(format.charAt(i++));
            if (i < format.length() && format.charAt(i) == '.') {
                normalized.append(format.charAt(i++));
                while (i < format.length() && Character.isDigit(format.charAt(i))) normalized.append(format.charAt(i++));
            }
            if (i + 1 < format.length() && format.charAt(i) == 'l' && "feg".indexOf(format.charAt(i + 1)) >= 0) i++;
            if (i < format.length()) normalized.append(format.charAt(i++));
        }
        return normalized.toString();
    }
    private static Map<MappingKey, String> loadMappings() {
        InputStream resource = StandardLibraryCallRule.class.getResourceAsStream("/standard-library-mappings.tsv");
        if (resource == null) throw new IllegalStateException("Missing standard-library-mappings.tsv");
        Map<MappingKey, String> mappings = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t", -1);
                if (fields.length != 4 || java.util.Arrays.stream(fields).anyMatch(String::isBlank)) {
                    throw new IllegalStateException("Invalid standard-library mapping at line " + lineNumber);
                }
                MappingKey key = new MappingKey(Language.fromId(fields[0]), Language.fromId(fields[1]), fields[2]);
                if (mappings.putIfAbsent(key, fields[3]) != null) {
                    throw new IllegalStateException("Duplicate standard-library mapping at line " + lineNumber);
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not read standard-library mappings", exception);
        }
        return Map.copyOf(mappings);
    }

    private record MappingKey(Language from, Language to, String callee) { }
}
