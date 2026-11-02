package com.tascode.codebridge.analysis;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import java.util.*;

/** Checks local function calls against the supported program and output-call signatures. */
public final class FunctionCallAnalyzer {
    private static final Set<String> NUMERIC = Set.of("byte", "int", "long", "short", "char", "float", "double");

    public List<Diagnostic> analyze(Program program, Language sourceLanguage, Language targetLanguage) {
        Map<String, FunctionDeclaration> functions = new HashMap<>();
        for (FunctionDeclaration function : program.functions()) functions.putIfAbsent(function.name(), function);
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (FunctionDeclaration function : program.functions()) {
            Map<String, String> locals = new HashMap<>();
            program.globals().forEach(global -> locals.put(global.name(), normalize(global.type())));
            function.parameters().forEach(parameter -> locals.put(parameter.name(), normalize(parameter.type())));
            collectLocals(function.body(), locals);
            visitBlock(function.body(), functions, locals, sourceLanguage, targetLanguage, diagnostics);
            checkCallReturns(function.body(), normalize(function.returnType()), functions, locals, sourceLanguage, targetLanguage, diagnostics);
        }
        return List.copyOf(diagnostics);
    }

    private void checkCallReturns(Statement statement, String expected, Map<String, FunctionDeclaration> functions,
                                  Map<String, String> locals, Language source, Language target, List<Diagnostic> diagnostics) {
        if (statement instanceof ReturnStatement returned) {
            returned.value().filter(CallExpression.class::isInstance).map(CallExpression.class::cast)
                    .filter(call -> functions.containsKey(call.callee())).ifPresent(call -> {
                        String actual = normalize(functions.get(call.callee()).returnType());
                        if (!compatible(expected, actual, source, target)) {
                            error("CB-TYPE-CALL-RETURN", "Function '" + call.callee() + "' returns '" + actual
                                    + "', which is incompatible with the enclosing return type '" + expected + "'.", call, diagnostics);
                        }
                    });
            returned.value().filter(value -> !(value instanceof CallExpression)).filter(this::containsFunctionCall).ifPresent(value -> {
                String actual = typeOf(value, locals, functions);
                if (!compatible(expected, actual, source, target)) errorAt("CB-TYPE-CALL-RETURN",
                        "A nested function call produces type '" + actual
                                + "', which is incompatible with the enclosing return type '" + expected + "'.",
                        value.sourceSpan(), diagnostics);
            });
        } else if (statement instanceof BlockStatement block) {
            block.statements().forEach(child -> checkCallReturns(child, expected, functions, locals, source, target, diagnostics));
        } else if (statement instanceof IfStatement conditional) {
            checkCallReturns(conditional.thenBranch(), expected, functions, locals, source, target, diagnostics);
            conditional.elseBranch().ifPresent(branch -> checkCallReturns(branch, expected, functions, locals, source, target, diagnostics));
        } else if (statement instanceof WhileStatement loop) checkCallReturns(loop.body(), expected, functions, locals, source, target, diagnostics);
        else if (statement instanceof DoWhileStatement loop) checkCallReturns(loop.body(), expected, functions, locals, source, target, diagnostics);
        else if (statement instanceof ForStatement loop) checkCallReturns(loop.body(), expected, functions, locals, source, target, diagnostics);
    }
    private void checkCallIntegral(Expression value, Map<String, FunctionDeclaration> functions,
                                   Map<String, String> locals, Language source, Language target,
                                   List<Diagnostic> diagnostics, String context) {
        if (!containsFunctionCall(value)) return;
        String actual = typeOf(value, locals, functions);
        boolean integral = Set.of("byte", "int", "short", "char").contains(actual)
                || actual.equals("long") && source != Language.JAVA && target != Language.JAVA;
        if (!actual.equals("unknown") && !integral) {
            errorAt("CB-TYPE-INTEGRAL", "A function call used for the " + context + " must produce an int-compatible integral value, but found '" + actual + "'.",
                    value.sourceSpan(), diagnostics);
        }
    }
    private void checkCallValueCompatibility(String expected, Expression value, Map<String, FunctionDeclaration> functions,
                                             Map<String, String> locals, Language source, Language target,
                                             List<Diagnostic> diagnostics, String context) {
        if (!containsFunctionCall(value)) return;
        String actual = typeOf(value, locals, functions);
        if (!actual.equals("unknown") && !compatible(expected, actual, source, target)) {
            errorAt("CB-TYPE-CALL-VALUE", "A function call in this " + context + " produces type '" + actual
                    + "', which is incompatible with the required type '" + expected + "'.", value.sourceSpan(), diagnostics);
        }
    }
    private boolean containsFunctionCall(Expression expression) {
        if (expression instanceof CallExpression) return true;
        if (expression instanceof BinaryExpression binary) return containsFunctionCall(binary.left()) || containsFunctionCall(binary.right());
        if (expression instanceof UnaryExpression unary) return containsFunctionCall(unary.operand());
        if (expression instanceof AssignmentExpression assignment) return containsFunctionCall(assignment.value());
        if (expression instanceof ArrayAccessExpression access) return containsFunctionCall(access.array()) || containsFunctionCall(access.index());
        return false;
    }
    private void collectLocals(BlockStatement block, Map<String, String> locals) {
        for (Statement statement : block.statements()) {
            if (statement instanceof VariableDeclaration variable) locals.putIfAbsent(variable.name(), normalize(variable.type()));
            else if (statement instanceof ArrayDeclaration array) locals.putIfAbsent(array.name(), normalize(array.elementType()) + "[]");
        }
    }

    private Map<String, String> childLocals(BlockStatement block, Map<String, String> parent) {
        Map<String, String> child = new HashMap<>(parent);
        for (Statement statement : block.statements()) {
            if (statement instanceof VariableDeclaration variable) child.put(variable.name(), normalize(variable.type()));
            else if (statement instanceof ArrayDeclaration array) child.put(array.name(), normalize(array.elementType()) + "[]");
        }
        return child;
    }
    private void visitBlock(BlockStatement block, Map<String, FunctionDeclaration> functions, Map<String, String> locals,
                            Language source, Language target, List<Diagnostic> diagnostics) {
        for (Statement statement : block.statements()) {
            if (statement instanceof BlockStatement nested) visitBlock(nested, functions, childLocals(nested, locals), source, target, diagnostics);
            else if (statement instanceof VariableDeclaration variable) variable.initializer().ifPresent(value -> {
                visitExpression(value, functions, locals, source, target, diagnostics);
                checkCallValueCompatibility(normalize(variable.type()), value, functions, locals, source, target, diagnostics, "initializer");
            });
            else if (statement instanceof ArrayDeclaration array) {
                visitExpression(array.size(), functions, locals, source, target, diagnostics);
                checkCallIntegral(array.size(), functions, locals, source, target, diagnostics, "array size");
            }
            else if (statement instanceof ReturnStatement returned) returned.value().ifPresent(value -> visitExpression(value, functions, locals, source, target, diagnostics));
            else if (statement instanceof ExpressionStatement expression) visitExpression(expression.expression(), functions, locals, source, target, diagnostics);
            else if (statement instanceof IfStatement conditional) {
                visitExpression(conditional.condition(), functions, locals, source, target, diagnostics);
                visitScopedStatement(conditional.thenBranch(), functions, locals, source, target, diagnostics);
                conditional.elseBranch().ifPresent(branch -> visitScopedStatement(branch, functions, locals, source, target, diagnostics));
            } else if (statement instanceof WhileStatement loop) {
                visitExpression(loop.condition(), functions, locals, source, target, diagnostics);
                visitScopedStatement(loop.body(), functions, locals, source, target, diagnostics);
            } else if (statement instanceof DoWhileStatement loop) {
                visitScopedStatement(loop.body(), functions, locals, source, target, diagnostics);
                visitExpression(loop.condition(), functions, locals, source, target, diagnostics);
            } else if (statement instanceof ForStatement loop) {
                Map<String, String> loopLocals = new HashMap<>(locals);
                loop.initializer().ifPresent(initializer -> {
                    if (initializer instanceof VariableDeclaration variable) loopLocals.put(variable.name(), normalize(variable.type()));
                    else if (initializer instanceof ArrayDeclaration array) loopLocals.put(array.name(), normalize(array.elementType()) + "[]");
                    visitStatement(initializer, functions, loopLocals, source, target, diagnostics);
                });
                loop.condition().ifPresent(condition -> visitExpression(condition, functions, loopLocals, source, target, diagnostics));
                loop.update().ifPresent(update -> visitExpression(update, functions, loopLocals, source, target, diagnostics));
                visitScopedStatement(loop.body(), functions, loopLocals, source, target, diagnostics);
            }
        }
    }

    private void visitScopedStatement(Statement statement, Map<String, FunctionDeclaration> functions, Map<String, String> locals,
                                     Language source, Language target, List<Diagnostic> diagnostics) {
        if (statement instanceof BlockStatement block) {
            visitBlock(block, functions, childLocals(block, locals), source, target, diagnostics);
        } else visitStatement(statement, functions, locals, source, target, diagnostics);
    }

    private void visitStatement(Statement statement, Map<String, FunctionDeclaration> functions, Map<String, String> locals,
                                Language source, Language target, List<Diagnostic> diagnostics) {
        if (statement instanceof BlockStatement block) visitBlock(block, functions, childLocals(block, locals), source, target, diagnostics);
        else if (statement instanceof VariableDeclaration variable) variable.initializer().ifPresent(value -> {
            visitExpression(value, functions, locals, source, target, diagnostics);
            checkCallValueCompatibility(normalize(variable.type()), value, functions, locals, source, target, diagnostics, "initializer");
        });
        else if (statement instanceof ArrayDeclaration array) {
                visitExpression(array.size(), functions, locals, source, target, diagnostics);
                checkCallIntegral(array.size(), functions, locals, source, target, diagnostics, "array size");
            }
        else if (statement instanceof ExpressionStatement expression) visitExpression(expression.expression(), functions, locals, source, target, diagnostics);
        else if (statement instanceof ReturnStatement returned) returned.value().ifPresent(value -> visitExpression(value, functions, locals, source, target, diagnostics));
        else if (statement instanceof IfStatement conditional) {
            visitExpression(conditional.condition(), functions, locals, source, target, diagnostics);
            visitScopedStatement(conditional.thenBranch(), functions, locals, source, target, diagnostics);
            conditional.elseBranch().ifPresent(branch -> visitScopedStatement(branch, functions, locals, source, target, diagnostics));
        } else if (statement instanceof WhileStatement loop) {
            visitExpression(loop.condition(), functions, locals, source, target, diagnostics); visitScopedStatement(loop.body(), functions, locals, source, target, diagnostics);
        } else if (statement instanceof DoWhileStatement loop) {
            visitScopedStatement(loop.body(), functions, locals, source, target, diagnostics); visitExpression(loop.condition(), functions, locals, source, target, diagnostics);
        } else if (statement instanceof ForStatement loop) {
            Map<String, String> loopLocals = new HashMap<>(locals);
            loop.initializer().ifPresent(initializer -> {
                if (initializer instanceof VariableDeclaration variable) loopLocals.put(variable.name(), normalize(variable.type()));
                else if (initializer instanceof ArrayDeclaration array) loopLocals.put(array.name(), normalize(array.elementType()) + "[]");
                visitStatement(initializer, functions, loopLocals, source, target, diagnostics);
            });
            loop.condition().ifPresent(condition -> visitExpression(condition, functions, loopLocals, source, target, diagnostics));
            loop.update().ifPresent(update -> visitExpression(update, functions, loopLocals, source, target, diagnostics));
            visitScopedStatement(loop.body(), functions, loopLocals, source, target, diagnostics);
        }
    }
    private void visitExpression(Expression expression, Map<String, FunctionDeclaration> functions, Map<String, String> locals,
                                 Language source, Language target, List<Diagnostic> diagnostics) {
        if (expression instanceof CallExpression call) {
            if (source == Language.CPP && call.callee().equals("__codebridge_endl")) return;
            if (source == Language.CPP && call.callee().equals("__codebridge_cin")) {
                if (target != Language.JAVA) error("CB-INPUT-CIN-TARGET", "C++ stream input converts only to Java in the current subset.", call, diagnostics);
                return;
            }
            if (source == Language.CPP && target == Language.C && call.callee().equals("__codebridge_cout")) {
                error("CB-IO-COUT-TARGET", "C++ stream output cannot be converted to C by the supported subset.", call, diagnostics);
                return;
            }
            FunctionDeclaration function = functions.get(call.callee());
            if (function != null) {
                if (function.parameters().size() != call.arguments().size()) {
                    error("CB-CALL-ARG-COUNT", "Function '" + call.callee() + "' expects " + function.parameters().size()
                            + " argument(s), but received " + call.arguments().size() + ".", call, diagnostics);
                } else {
                    for (int i = 0; i < call.arguments().size(); i++) {
                        String expected = normalize(function.parameters().get(i).type());
                        String actual = typeOf(call.arguments().get(i), locals, functions);
                        if (!compatible(expected, actual, source, target)) {
                            errorAt("CB-CALL-ARG-TYPE", "Argument " + (i + 1) + " of '" + call.callee() + "' expects '"
                                    + expected + "' but found '" + actual + "'.", call.arguments().get(i).sourceSpan(), diagnostics);
                        }
                    }
                }
            } else if (source == Language.JAVA && isStringEquals(call, locals)) {
                if (call.arguments().size() != 1) error("CB-STRING-EQUALS-ARITY", "String.equals requires exactly one argument.", call, diagnostics);
                else if (!compatible("string", typeOf(call.arguments().getFirst(), locals, functions), source, target)) errorAt("CB-STRING-EQUALS-TYPE", "String.equals requires a string argument.", call.arguments().getFirst().sourceSpan(), diagnostics);
            } else if (source == Language.JAVA && target == Language.CPP && isStringOneArgumentMethod(call, locals)) {
                if (call.arguments().size() != 1) error("CB-STRING-METHOD-ARITY", "String startsWith, endsWith, contains, and concat require exactly one argument.", call, diagnostics);
                else if (!compatible("string", typeOf(call.arguments().getFirst(), locals, functions), source, target)) errorAt("CB-STRING-METHOD-TYPE", "String methods require a String argument.", call.arguments().getFirst().sourceSpan(), diagnostics);
            } else if (source == Language.JAVA && target == Language.CPP && isStringZeroArgumentMethod(call, locals)) {
                if (!call.arguments().isEmpty()) error("CB-STRING-METHOD-ARITY", "String.length() and String.isEmpty() take no arguments.", call, diagnostics);
            } else if (!isSupportedOutputCall(call.callee(), source)) {
                error("CB-CALL-UNRESOLVED", "Function '" + call.callee() + "' is not declared or supported.", call, diagnostics);
            } else checkOutputCall(call, source, target, locals, functions, diagnostics);
            call.arguments().forEach(argument -> visitExpression(argument, functions, locals, source, target, diagnostics));
        } else if (expression instanceof BinaryExpression binary) {
            visitExpression(binary.left(), functions, locals, source, target, diagnostics);
            visitExpression(binary.right(), functions, locals, source, target, diagnostics);
        } else if (expression instanceof UnaryExpression unary) visitExpression(unary.operand(), functions, locals, source, target, diagnostics);
        else if (expression instanceof AssignmentExpression assignment) {
            checkCallValueCompatibility(typeOf(assignment.target(), locals, functions), assignment.value(), functions, locals, source, target, diagnostics, "assignment");
            visitExpression(assignment.target(), functions, locals, source, target, diagnostics);
            visitExpression(assignment.value(), functions, locals, source, target, diagnostics);
        } else if (expression instanceof ArrayAccessExpression access) {
            checkCallIntegral(access.index(), functions, locals, source, target, diagnostics, "array index");
            visitExpression(access.array(), functions, locals, source, target, diagnostics);
            visitExpression(access.index(), functions, locals, source, target, diagnostics);
        }
    }

    private boolean isStringOneArgumentMethod(CallExpression call, Map<String, String> locals) {
        int marker = call.callee().lastIndexOf(".");
        if (marker <= 0) return false;
        String method = call.callee().substring(marker + 1);
        return Set.of("startsWith", "endsWith", "contains", "concat").contains(method)
                && locals.getOrDefault(call.callee().substring(0, marker), "unknown").equals("string");
    }
    private boolean isStringZeroArgumentMethod(CallExpression call, Map<String, String> locals) {
        int marker = call.callee().lastIndexOf('.');
        if (marker <= 0) return false;
        String method = call.callee().substring(marker + 1);
        String receiver = call.callee().substring(0, marker);
        return (method.equals("length") || method.equals("isEmpty"))
                && locals.getOrDefault(receiver, "unknown").equals("string");
    }    private boolean isStringEquals(CallExpression call, Map<String, String> locals) {
        int marker = call.callee().lastIndexOf(".equals");
        return marker > 0 && marker + 7 == call.callee().length() && locals.getOrDefault(call.callee().substring(0, marker), "unknown").equals("string");
    }
    private boolean isSupportedOutputCall(String name, Language source) {
        return source == Language.JAVA
                ? name.equals("System.out.printf") || name.equals("System.out.println") || name.equals("System.out.print")
                : name.equals("printf") || name.equals("puts") || name.equals("scanf") || (source == Language.CPP && name.equals("__codebridge_cout"));
    }

    private void checkOutputCall(CallExpression call, Language source, Language target, Map<String, String> locals,
                                 Map<String, FunctionDeclaration> functions, List<Diagnostic> diagnostics) {
                if (call.callee().equals("scanf") || call.callee().equals("__codebridge_cout")) return;
        if (call.callee().equals("System.out.print")) {
            if (call.arguments().size() != 1) {
                error("CB-CALL-ARG-COUNT", "Output call 'System.out.print' expects exactly one argument.", call, diagnostics);
            } else if (source != target) {
                String actual = typeOf(call.arguments().getFirst(), locals, functions);
                if (containsMixedStringConcat(call.arguments().getFirst(), locals, functions)) {
                    errorAt("CB-IO-OUTPUT-TYPE", "String concatenation with non-string values is not supported for native print.",
                            call.arguments().getFirst().sourceSpan(), diagnostics);
                    return;
                }                boolean supported = Set.of("string", "byte", "int", "short", "char").contains(actual);
                if (!supported) errorAt("CB-IO-OUTPUT-TYPE",
                        "Java print supports strings, integers, and characters only.",
                        call.arguments().getFirst().sourceSpan(), diagnostics);
            }
            return;
        }
        if (call.callee().equals("System.out.println")) {
            if (call.arguments().isEmpty()) return;
            if (call.arguments().size() != 1) {
                error("CB-CALL-ARG-COUNT", "Output call System.out.println expects one argument or no arguments.", call, diagnostics);
            } else if (source != target) {
                String actual = typeOf(call.arguments().getFirst(), locals, functions);
                if (containsMixedStringConcat(call.arguments().getFirst(), locals, functions)) {
                    errorAt("CB-IO-OUTPUT-TYPE", "String concatenation with non-string values is not supported for native println.",
                            call.arguments().getFirst().sourceSpan(), diagnostics);
                    return;
                }                if (!Set.of("string", "byte", "int", "short", "char").contains(actual)) {
                    errorAt("CB-IO-OUTPUT-TYPE", "Cross-language println conversion supports strings, integers, and characters only.",
                            call.arguments().getFirst().sourceSpan(), diagnostics);
                }
            }
            return;
        }

boolean puts = call.callee().equals("puts");
        if (puts) {
            if (call.arguments().size() != 1) error("CB-CALL-ARG-COUNT", "Output call '" + call.callee() + "' expects exactly one argument.", call, diagnostics);
            else if (source != target) {
                String actual = typeOf(call.arguments().getFirst(), locals, functions);
                if (!actual.equals("string") && !actual.equals("unknown")) errorAt("CB-IO-OUTPUT-TYPE",
                        "Cross-language puts/println conversion requires a string argument.", call.arguments().getFirst().sourceSpan(), diagnostics);
            }
            return;
        }
        if (call.arguments().isEmpty()) {
            error("CB-CALL-ARG-COUNT", "Formatted output call '" + call.callee() + "' requires a format string.", call, diagnostics);
            return;
        }
        if (source == target) return;
        if (!(call.arguments().getFirst() instanceof LiteralExpression literal) || !(literal.value() instanceof String format)) {
            error("CB-IO-FORMAT-LITERAL", "Cross-language formatted output requires a literal format string so conversions can be checked.", call, diagnostics);
            return;
        }
        List<Character> conversions = new ArrayList<>();
        for (int i = 0; i < format.length(); i++) {
            if (format.charAt(i) != '%') continue;
            if (++i >= format.length()) { unsupportedFormat(call, literal, diagnostics); return; }
            if (format.charAt(i) == '%') continue;
            while (i < format.length() && "-#+ 0".indexOf(format.charAt(i)) >= 0) i++;
            while (i < format.length() && Character.isDigit(format.charAt(i))) i++;
            if (i < format.length() && format.charAt(i) == '.') { i++; while (i < format.length() && Character.isDigit(format.charAt(i))) i++; }
            if (i < format.length() && format.charAt(i) == 'l' && source != Language.JAVA && target == Language.JAVA
                    && i + 1 < format.length() && "feg".indexOf(format.charAt(i + 1)) >= 0) i++;
            if (i >= format.length() || !"dfegcsoxX".contains(String.valueOf(format.charAt(i)))) {
                unsupportedFormat(call, literal, diagnostics); return;
            }
            conversions.add(format.charAt(i));
        }
        int supplied = call.arguments().size() - 1;
        if (supplied != conversions.size()) {
            error("CB-IO-FORMAT-ARG-COUNT", "Format string consumes " + conversions.size() + " value(s), but the call supplies " + supplied + ".", call, diagnostics);
            return;
        }
        for (int i = 0; i < conversions.size(); i++) {
            char conversion = conversions.get(i);
            String actual = typeOf(call.arguments().get(i + 1), locals, functions);
            boolean valid = switch (conversion) {
                case 'd', 'o', 'x', 'X' -> Set.of("byte", "int", "short", "long", "char").contains(actual);
                case 'f', 'e', 'g' -> Set.of("byte", "int", "short", "long", "char", "float", "double").contains(actual);
                case 'c' -> Set.of("byte", "char", "int", "short", "long").contains(actual);
                case 's' -> actual.equals("string");
                default -> false;
            };
            if (!valid && !actual.equals("unknown")) errorAt("CB-IO-FORMAT-ARG-TYPE",
                    "Format conversion %" + conversion + " is incompatible with argument type '" + actual + "'.",
                    call.arguments().get(i + 1).sourceSpan(), diagnostics);
        }
    }

    private boolean containsMixedStringConcat(Expression expression, Map<String, String> locals,
                                              Map<String, FunctionDeclaration> functions) {
        if (expression instanceof BinaryExpression binary) {
            if (binary.operator().equals("+")) {
                boolean leftString = typeOf(binary.left(), locals, functions).equals("string");
                boolean rightString = typeOf(binary.right(), locals, functions).equals("string");
                if (leftString != rightString) return true;
            }
            return containsMixedStringConcat(binary.left(), locals, functions)
                    || containsMixedStringConcat(binary.right(), locals, functions);
        }
        if (expression instanceof UnaryExpression unary) return containsMixedStringConcat(unary.operand(), locals, functions);
        return false;
    }
    private void unsupportedFormat(CallExpression call, LiteralExpression format, List<Diagnostic> diagnostics) {
        diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-IO-FORMAT-UNSUPPORTED",
                "This printf format uses a conversion that cannot be preserved between the selected languages.", format.sourceSpan()));
    }
    private String typeOf(Expression expression, Map<String, String> locals, Map<String, FunctionDeclaration> functions) {
        if (expression instanceof LiteralExpression literal) {
            if (literal.value() instanceof Boolean) return "bool";
            if (literal.value() instanceof Character) return "char";
            if (literal.value() instanceof Long) return "long";
            if (literal.value() instanceof Float) return "float";
            if (literal.value() instanceof Double) return "double";
            if (literal.value() instanceof Number) return "int";
            if (literal.value() instanceof String) return "string";
        } else if (expression instanceof NameExpression name) return locals.getOrDefault(name.name(), "unknown");
        else if (expression instanceof ArrayAccessExpression access) {
            String array = typeOf(access.array(), locals, functions);
            return array.endsWith("[]") ? array.substring(0, array.length() - 2) : "unknown";
        } else if (expression instanceof BinaryExpression binary) {
            if (Set.of("==", "!=", "<", ">", "<=", ">=", "&&", "||").contains(binary.operator())) return "bool";
            String left = typeOf(binary.left(), locals, functions);
            String right = typeOf(binary.right(), locals, functions);
            if (NUMERIC.contains(left) && NUMERIC.contains(right)) return wider(left, right);
            if (binary.operator().equals("+") && left.equals("string") && right.equals("string")) return "string";
            if (!left.equals("unknown") && !right.equals("unknown")) return "invalid";
        } else if (expression instanceof UnaryExpression unary) {
            if (unary.operator().equals("!")) return "bool";
            String operand = typeOf(unary.operand(), locals, functions);
            return Set.of("byte", "short", "char").contains(operand) ? "int" : operand;
        } else if (expression instanceof AssignmentExpression assignment) return typeOf(assignment.target(), locals, functions);
        else if (expression instanceof UpdateExpression update) return typeOf(update.target(), locals, functions);
        else if (expression instanceof CallExpression call && functions.containsKey(call.callee())) {
            return normalize(functions.get(call.callee()).returnType());
        }
        return "unknown";
    }
    private String wider(String first, String second) {
        List<String> order = List.of("byte", "short", "char", "int", "long", "float", "double");
        String promoted = order.get(Math.max(order.indexOf(first), order.indexOf(second)));
        return Set.of("byte", "short", "char").contains(promoted) ? "int" : promoted;
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
    }    private boolean compatible(String expected, String actual, Language source, Language target) {
        if (actual.equals("unknown") || expected.equals(actual)) return true;
        if (NUMERIC.contains(expected) && NUMERIC.contains(actual)) {
            return source != Language.JAVA && target != Language.JAVA || javaWidening(actual, expected);
        }
        return ((expected.equals("bool") && NUMERIC.contains(actual)) || (actual.equals("bool") && NUMERIC.contains(expected)))
                && source != Language.JAVA && target != Language.JAVA;
    }

    private String normalize(String type) { if (type.endsWith("[]")) return normalize(type.substring(0, type.length() - 2)) + "[]"; return switch (type) { case "_Bool", "boolean" -> "bool"; case "c-long" -> "long"; case "String" -> "string"; default -> type; }; }
    private void errorAt(String code, String message, com.tascode.codebridge.diagnostic.SourceSpan span,
                         List<Diagnostic> diagnostics) {
        diagnostics.add(Diagnostic.at(Severity.ERROR, code, message, span));
    }
    private void error(String code, String message, CallExpression call, List<Diagnostic> diagnostics) {
        diagnostics.add(Diagnostic.at(Severity.ERROR, code, message, call.sourceSpan()));
    }
}
