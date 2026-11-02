package com.tascode.codebridge.analysis;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import java.util.*;

/** Performs conservative type checks for the primitive function IR subset. */
public final class TypeAnalyzer {
    private static final Set<String> NUMERIC = Set.of("byte", "int", "long", "short", "char", "float", "double");

    public List<Diagnostic> analyze(Program program, Language from, Language to) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (FunctionDeclaration function : program.functions()) {
            Deque<Map<String, String>> scopes = new ArrayDeque<>();
            Map<String, String> translationUnitNames = program.globals().stream().collect(java.util.stream.Collectors.toMap(GlobalVariableDeclaration::name, global -> normalize(global.type()), (a, b) -> a, HashMap::new));
            program.enums().forEach(enumeration -> enumeration.enumerators().forEach(enumerator -> translationUnitNames.put(enumerator.name(), "int")));
            scopes.push(translationUnitNames);
            scopes.push(new HashMap<>());
            for (Parameter parameter : function.parameters()) scopes.peek().put(parameter.name(), normalize(parameter.type()));
            analyzeBlock(function.body(), function.returnType(), from, to, scopes, diagnostics, false);
            if (!normalize(function.returnType()).equals("void") && (from == Language.JAVA || to == Language.JAVA)
                    && !cannotCompleteNormally(function.body())) {
                error("CB-TYPE-MISSING-RETURN", "Function '" + function.name() + "' may finish without returning a value.",
                        function.body().sourceSpan(), diagnostics);
            }
        }
        return List.copyOf(diagnostics);
    }

    private boolean cannotCompleteNormally(Statement statement) {
        if (statement instanceof ReturnStatement) return true;
        if (statement instanceof BlockStatement block) {
            for (Statement child : block.statements()) if (cannotCompleteNormally(child)) return true;
            return false;
        }
        if (statement instanceof IfStatement conditional) {
            return conditional.elseBranch().isPresent() && cannotCompleteNormally(conditional.thenBranch())
                    && cannotCompleteNormally(conditional.elseBranch().orElseThrow());
        }
        if (statement instanceof DoWhileStatement loop) {
            return cannotCompleteNormally(loop.body()) || isTrue(loop.condition()) && !containsLoopBreak(loop.body());
        }
        if (statement instanceof WhileStatement loop) return isTrue(loop.condition()) && !containsLoopBreak(loop.body());
        if (statement instanceof ForStatement loop) {
            return loop.condition().map(this::isTrue).orElse(true) && !containsLoopBreak(loop.body());
        }
        return false;
    }

    private boolean isTrue(Expression expression) {
        if (expression instanceof LiteralExpression literal) {
            if (Boolean.TRUE.equals(literal.value())) return true;
            if (literal.value() instanceof Number number) return number.doubleValue() != 0.0;
            if (literal.value() instanceof Character character) return character != 0;
            return false;
        }
        Double constant = constantNumber(expression);
        if (constant != null) return constant != 0.0;
        if (expression instanceof BinaryExpression binary) {
            Double left = constantNumber(binary.left()), right = constantNumber(binary.right());
            if (left != null && right != null) return switch (binary.operator()) {
                case "!=" -> !left.equals(right); case "==" -> left.equals(right);
                case ">" -> left > right; case ">=" -> left >= right;
                case "<" -> left < right; case "<=" -> left <= right;
                default -> false;
            };
            if (binary.operator().equals("&&")) return isTrue(binary.left()) && isTrue(binary.right());
            if (binary.operator().equals("||")) return isTrue(binary.left()) || isTrue(binary.right());
        }
        return false;
    }

    private Double constantNumber(Expression expression) {
        if (expression instanceof LiteralExpression literal) {
            if (literal.value() instanceof Number number) return number.doubleValue();
            if (literal.value() instanceof Character character) return (double) character;
            if (literal.value() instanceof Boolean bool) return bool ? 1.0 : 0.0;
        } else if (expression instanceof UnaryExpression unary) {
            Double value = constantNumber(unary.operand());
            if (value == null) return null;
            return switch (unary.operator()) { case "+" -> value; case "-" -> -value; case "!" -> value == 0.0 ? 1.0 : 0.0; default -> null; };
        } else if (expression instanceof BinaryExpression binary) {
            Double left = constantNumber(binary.left()), right = constantNumber(binary.right());
            if (left == null || right == null) return null;
            return switch (binary.operator()) {
                case "+" -> left + right; case "-" -> left - right; case "*" -> left * right;
                case "/" -> right == 0.0 ? null : left / right;
                case "%" -> right == 0.0 ? null : left % right;
                default -> null;
            };
        }
        return null;
    }
    private boolean containsLoopBreak(Statement statement) {
        if (statement instanceof BreakStatement) return true;
        if (statement instanceof BlockStatement block) return block.statements().stream().anyMatch(this::containsLoopBreak);
        if (statement instanceof IfStatement conditional) {
            return containsLoopBreak(conditional.thenBranch())
                    || conditional.elseBranch().map(this::containsLoopBreak).orElse(false);
        }
        // A break nested in another loop does not exit the loop being analyzed.
        return false;
    }
    private void analyzeBlock(BlockStatement block, String returnType, Language from, Language to,
                              Deque<Map<String, String>> scopes, List<Diagnostic> diagnostics, boolean nested) {
        if (nested) scopes.push(new HashMap<>());
        for (Statement statement : block.statements()) analyzeStatement(statement, returnType, from, to, scopes, diagnostics);
        if (nested) scopes.pop();
    }

    private void analyzeStatement(Statement statement, String returnType, Language from, Language to,
                                  Deque<Map<String, String>> scopes, List<Diagnostic> diagnostics) {
        if (statement instanceof BlockStatement block) analyzeBlock(block, returnType, from, to, scopes, diagnostics, true);
        else if (statement instanceof VariableDeclaration variable) {
            String expected = normalize(variable.type());
            variable.initializer().ifPresent(value -> { checkExpression(value, from, to, scopes, diagnostics); checkCompatible(expected, typeOf(value, scopes), value, value.sourceSpan(), from, to, diagnostics, "initializer"); });
            scopes.peek().put(variable.name(), expected);
        } else if (statement instanceof ArrayDeclaration array) {
            scopes.peek().put(array.name(), normalize(array.elementType()) + "[]");
            checkExpression(array.size(), from, to, scopes, diagnostics);
            checkIntegral(typeOf(array.size(), scopes), array.size().sourceSpan(), from, to, diagnostics, "array size");
        } else if (statement instanceof ReturnStatement returned) {
            String expected = normalize(returnType);
            if (returned.value().isEmpty() && !expected.equals("void")) {
                error("CB-TYPE-RETURN-VALUE", "Function returning '" + expected + "' must return a value.", returned.sourceSpan(), diagnostics);
            } else if (returned.value().isPresent() && expected.equals("void")) {
                error("CB-TYPE-RETURN-VOID", "A void function cannot return a value.", returned.sourceSpan(), diagnostics);
            } else returned.value().ifPresent(value -> { checkExpression(value, from, to, scopes, diagnostics); checkCompatible(expected, typeOf(value, scopes), value, value.sourceSpan(), from, to, diagnostics, "return value"); });
        } else if (statement instanceof ExpressionStatement expression) checkExpression(expression.expression(), from, to, scopes, diagnostics);
        else if (statement instanceof IfStatement conditional) {
            checkExpression(conditional.condition(), from, to, scopes, diagnostics);
            analyzeStatement(conditional.thenBranch(), returnType, from, to, scopes, diagnostics);
            conditional.elseBranch().ifPresent(branch -> analyzeStatement(branch, returnType, from, to, scopes, diagnostics));
        } else if (statement instanceof WhileStatement loop) {
            checkExpression(loop.condition(), from, to, scopes, diagnostics); analyzeStatement(loop.body(), returnType, from, to, scopes, diagnostics);
        } else if (statement instanceof DoWhileStatement loop) {
            analyzeStatement(loop.body(), returnType, from, to, scopes, diagnostics); checkExpression(loop.condition(), from, to, scopes, diagnostics);
        } else if (statement instanceof ForStatement loop) {
            scopes.push(new HashMap<>());
            loop.initializer().ifPresent(initializer -> { if (initializer instanceof BlockStatement declarations) declarations.statements().forEach(declaration -> analyzeStatement(declaration, returnType, from, to, scopes, diagnostics)); else analyzeStatement(initializer, returnType, from, to, scopes, diagnostics); });
            loop.condition().ifPresent(condition -> checkExpression(condition, from, to, scopes, diagnostics));
            loop.update().ifPresent(update -> checkExpression(update, from, to, scopes, diagnostics));
            analyzeStatement(loop.body(), returnType, from, to, scopes, diagnostics);
            scopes.pop();
        }
    }

    private void checkExpression(Expression expression, Language from, Language to,
                                 Deque<Map<String, String>> scopes, List<Diagnostic> diagnostics) {
        if (expression instanceof AssignmentExpression assignment) {
            String expected = typeOf(assignment.target(), scopes), actual = typeOf(assignment.value(), scopes);
            checkCompatible(expected, actual, assignment.value(), assignment.sourceSpan(), from, to, diagnostics, "assignment");
            checkExpression(assignment.target(), from, to, scopes, diagnostics);
            checkExpression(assignment.value(), from, to, scopes, diagnostics);
        } else if (expression instanceof BinaryExpression binary) {
            checkExpression(binary.left(), from, to, scopes, diagnostics);
            checkExpression(binary.right(), from, to, scopes, diagnostics);            if (Set.of("/", "%").contains(binary.operator())
                    && Set.of("byte", "int", "long", "short", "char").contains(typeOf(binary.left(), scopes))
                    && Set.of("byte", "int", "long", "short", "char").contains(typeOf(binary.right(), scopes))
                    && Double.valueOf(0.0).equals(constantNumber(binary.right()))) {
                error("CB-TYPE-DIVIDE-BY-ZERO", "Integral division or remainder by a constant zero is not safe to convert.",
                        binary.sourceSpan(), diagnostics);
            }
        } else if (expression instanceof UnaryExpression unary) {
            checkExpression(unary.operand(), from, to, scopes, diagnostics);
        } else if (expression instanceof UpdateExpression update) {
            String targetType = typeOf(update.target(), scopes);
            if (!NUMERIC.contains(targetType) && !(targetType.equals("bool") && from == Language.C && to == Language.C)) {
                error("CB-TYPE-NUMERIC-OPERANDS", "Increment and decrement require a numeric variable, but found type '" + targetType + "'.",
                        update.sourceSpan(), diagnostics);
            }
            checkExpression(update.target(), from, to, scopes, diagnostics);        } else if (expression instanceof ArrayAccessExpression access) {
            String arrayType = typeOf(access.array(), scopes);
            if (!arrayType.equals("unknown") && !arrayType.endsWith("[]")) {
                error("CB-TYPE-ARRAY-ACCESS", "Indexing requires an array value, but found type: " + arrayType + ".", access.sourceSpan(), diagnostics);
            }
            checkIntegral(typeOf(access.index(), scopes), access.index().sourceSpan(), from, to, diagnostics, "array index");
            checkExpression(access.array(), from, to, scopes, diagnostics);
            checkExpression(access.index(), from, to, scopes, diagnostics);
        } else if (expression instanceof CallExpression call) {
            call.arguments().forEach(argument -> checkExpression(argument, from, to, scopes, diagnostics));
        }
    }
    private String typeOf(Expression expression, Deque<Map<String, String>> scopes) {
        if (expression instanceof LiteralExpression literal) {
            Object value = literal.value();
            if (value instanceof Boolean) return "bool";
            if (value instanceof Character) return "char";
            if (value instanceof Long) return "long";
            if (value instanceof Float) return "float";
            if (value instanceof Double) return "double";
            if (value instanceof Number) return "int";
            if (value instanceof String) return "string";
        } else if (expression instanceof NameExpression name) {
            for (Map<String, String> scope : scopes) if (scope.containsKey(name.name())) return scope.get(name.name());
        } else if (expression instanceof ArrayAccessExpression access) {
            String array = typeOf(access.array(), scopes);
            return array.endsWith("[]") ? array.substring(0, array.length() - 2) : "unknown";
        } else if (expression instanceof AssignmentExpression assignment) return typeOf(assignment.target(), scopes);
        else if (expression instanceof UpdateExpression update) return typeOf(update.target(), scopes);
        else if (expression instanceof UnaryExpression unary) {
            if (unary.operator().equals("!")) return "bool";
            String operand = typeOf(unary.operand(), scopes);
            return Set.of("byte", "short", "char").contains(operand) ? "int" : operand;
        }
        else if (expression instanceof BinaryExpression binary) {
            if (Set.of("==", "!=", "<", ">", "<=", ">=", "&&", "||").contains(binary.operator())) return "bool";
            String left = typeOf(binary.left(), scopes), right = typeOf(binary.right(), scopes);
            if (NUMERIC.contains(left) && NUMERIC.contains(right)) return wider(left, right);
            if (binary.operator().equals("+") && left.equals("string") && right.equals("string")) return "string";
            return left.equals("unknown") || right.equals("unknown") ? "unknown" : "unknown";
        }
        return "unknown";
    }

    private String wider(String a, String b) {
        List<String> order = List.of("byte", "short", "char", "int", "long", "float", "double");
        String promoted = order.get(Math.max(order.indexOf(a), order.indexOf(b)));
        return Set.of("short", "char").contains(promoted) ? "int" : promoted;
    }

    private void checkCompatible(String expected, String actual, Expression value, com.tascode.codebridge.diagnostic.SourceSpan span,
                                 Language from, Language to, List<Diagnostic> diagnostics, String context) {
        if (expected.equals("unknown") || actual.equals("unknown") || expected.equals(actual)) return;
        boolean expectedNumeric = NUMERIC.contains(expected), actualNumeric = NUMERIC.contains(actual);
        if (expectedNumeric && actualNumeric) {
            Double constant = value == null ? null : constantNumber(value);
            if (from == Language.JAVA && expected.equals("byte") && actual.equals("int")
                    && constant != null && constant >= -128 && constant <= 127 && constant == Math.rint(constant)) return;
            if (from != Language.JAVA && to != Language.JAVA || javaWidening(actual, expected)) return;
            error("CB-TYPE-MISMATCH", "Narrowing numeric conversions are not supported safely.", span, diagnostics);
            return;
        }
        if ((expected.equals("bool") && actualNumeric) || (actual.equals("bool") && expectedNumeric)) {
            if (from != Language.JAVA && to != Language.JAVA) return;
        }
        error("CB-TYPE-MISMATCH", "Incompatible " + context + ": expected '" + expected + "' but found '" + actual + "'.", span, diagnostics);
    }

    private boolean javaWidening(String actual, String expected) {
        return switch (actual) {
            case "byte" -> Set.of("short", "int", "long", "float", "double").contains(expected);
            case "short", "char" -> Set.of("int", "long", "float", "double").contains(expected);
            case "int" -> Set.of("long", "float", "double").contains(expected);
            case "long" -> Set.of("float", "double").contains(expected);
            case "float" -> expected.equals("double");
            default -> false;
        };
    }
    private void checkIntegral(String actual, com.tascode.codebridge.diagnostic.SourceSpan span, Language from, Language to,
                               List<Diagnostic> diagnostics, String context) {
        if (actual.equals("unknown")) return;
        boolean integral = Set.of("byte", "int", "short", "char").contains(actual)
                || actual.equals("long") && from != Language.JAVA && to != Language.JAVA;
        if (!integral) error("CB-TYPE-INTEGRAL", "An int-compatible integral value is required for the " + context + ".", span, diagnostics);
    }
    private void checkNumeric(String actual, com.tascode.codebridge.diagnostic.SourceSpan span,
                              List<Diagnostic> diagnostics, String context) {
        if (!actual.equals("unknown") && !NUMERIC.contains(actual)) {
            error("CB-TYPE-NUMERIC-REQUIRED", "A numeric value is required for the " + context + ".", span, diagnostics);
        }
    }

    private String normalize(String type) { if (type.endsWith("[]")) return normalize(type.substring(0, type.length() - 2)) + "[]"; return switch (type) { case "_Bool", "boolean" -> "bool"; case "c-long" -> "long"; case "String" -> "string"; default -> type; }; }

    private void error(String code, String message, com.tascode.codebridge.diagnostic.SourceSpan span,
                       List<Diagnostic> diagnostics) {
        diagnostics.add(Diagnostic.at(Severity.ERROR, code, message, span));
    }
}
