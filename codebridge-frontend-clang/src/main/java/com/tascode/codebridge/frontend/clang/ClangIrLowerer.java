package com.tascode.codebridge.frontend.clang;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.ir.AssignmentExpression;
import com.tascode.codebridge.ir.BinaryExpression;
import com.tascode.codebridge.ir.BreakStatement;
import com.tascode.codebridge.ir.BlockStatement;
import com.tascode.codebridge.ir.CallExpression;
import com.tascode.codebridge.ir.Expression;
import com.tascode.codebridge.ir.ExpressionStatement;
import com.tascode.codebridge.ir.ForStatement;
import com.tascode.codebridge.ir.FunctionDeclaration;
import com.tascode.codebridge.ir.IfStatement;
import com.tascode.codebridge.ir.IrVersions;
import com.tascode.codebridge.ir.LiteralExpression;
import com.tascode.codebridge.ir.NameExpression;
import com.tascode.codebridge.ir.Parameter;
import com.tascode.codebridge.ir.Program;
import com.tascode.codebridge.ir.ReturnStatement;
import com.tascode.codebridge.ir.Statement;
import com.tascode.codebridge.ir.ContinueStatement;
import com.tascode.codebridge.ir.DoWhileStatement;
import com.tascode.codebridge.ir.UnaryExpression;
import com.tascode.codebridge.ir.UpdateExpression;
import com.tascode.codebridge.ir.VariableDeclaration;
import com.tascode.codebridge.ir.WhileStatement;
import com.tascode.codebridge.language.Language;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lowers a conservative, explicit subset of Clang's semantic AST into the shared IR. */
public final class ClangIrLowerer {
    private static final int IR_VERSION = IrVersions.CURRENT;
    private static final Set<String> SCALAR_TYPES = Set.of("void", "_Bool", "bool", "char", "short", "int", "long", "long long", "float", "double");
    private static final Set<String> BINARY_OPERATORS = Set.of("+", "-", "*", "/", "%", "<<", ">>", "<", "<=", ">", ">=", "==", "!=", "&", "|", "^", "&&", "||", "=");
    private static final Set<String> EXPRESSION_WRAPPERS = Set.of("UnexposedExpr", "ImplicitCastExpr", "CStyleCastExpr", "ConstantExpr", "ParenExpr", "FullExpr");
    private static final Pattern INTEGER_SUFFIX = Pattern.compile("(?i)(?:u|l|ll)+$");
    private static final Pattern FLOATING_LITERAL = Pattern.compile("(?i)(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:e[+-]?[0-9]+)?[f]?");
    private static final List<String> OPERATORS = List.of("<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "+", "-", "*", "/", "%", "<", ">", "&", "|", "^", "=");

    public LoweringResult lower(ClangSemanticModel model, String source, List<String> includes) {
        if (model == null) return failure("CB-CLANG-IR-MODEL-MISSING", "Clang did not provide a semantic model to lower.");
        if (source == null || source.isBlank()) return failure("CB-CLANG-IR-SOURCE-EMPTY", "Source is empty; Clang AST lowering requires the original source text.");
        if (model.language() != Language.C && model.language() != Language.CPP) return failure("CB-CLANG-IR-LANGUAGE", "Clang IR lowering accepts C or C++ ASTs only.");
        LoweringContext context = new LoweringContext(source, model);
        Set<String> definitions = new HashSet<>();
        for (SemanticAstNode declaration : model.declarations()) {
            if (declaration.kind().equals("FunctionDecl") && isDefinition(declaration)) definitions.add(signature(declaration));
        }

        List<FunctionDeclaration> functions = new ArrayList<>();
        Set<String> functionNames = new HashSet<>();
        for (SemanticAstNode declaration : model.declarations()) {
            if (declaration.kind().equals("FunctionDecl")) {
                if (!isDefinition(declaration)) {
                    if (!definitions.contains(signature(declaration))) {
                        context.fail("CB-CLANG-DECLARATION-ONLY", "Function declaration '" + declaration.name() + "' has no definition in this source unit; external function bodies cannot be synthesized.", declaration);
                    }
                    continue;
                }
                try {
                    if (!functionNames.add(declaration.name())) {
                        throw context.failure("CB-CLANG-OVERLOAD-UNSUPPORTED", "Function overloading and duplicate function names need symbol-identity handling in the shared IR.", declaration);
                    }
                    functions.add(context.function(declaration));
                } catch (LoweringFailure failure) {
                    context.diagnostics.add(failure.diagnostic);
                } catch (IllegalArgumentException invalidIr) {
                    context.fail("CB-CLANG-IR-INVARIANT", "Clang AST shape violated a shared IR invariant: " + invalidIr.getMessage(), declaration);
                }
            } else {
                context.fail("CB-CLANG-DECLARATION-UNSUPPORTED", describeTopLevel(declaration), declaration);
            }
        }
        if (functions.isEmpty() && context.diagnostics.isEmpty()) {
            context.fail("CB-CLANG-NO-FUNCTIONS", "No free function definitions were found to lower into the current shared IR.", model.declarations().isEmpty() ? null : model.declarations().get(0));
        }
        if (context.hasErrors()) return new LoweringResult(null, context.diagnostics);
        SourceSpan programSpan = context.programSpan();
        return new LoweringResult(new Program(IR_VERSION, programSpan, functions, includes == null ? List.of() : includes), context.diagnostics);
    }

    private static String signature(SemanticAstNode declaration) {
        return declaration.displayName().isBlank() ? declaration.name() + declaration.type() : declaration.displayName();
    }

    private static boolean isDefinition(SemanticAstNode declaration) {
        return Boolean.parseBoolean(declaration.attributes().getOrDefault("definition", "false"));
    }

    private String describeTopLevel(SemanticAstNode node) {
        String feature = switch (node.kind()) {
            case "VarDecl" -> "Global variables and static storage are not represented by this Clang lowering stage.";
            case "StructDecl", "UnionDecl", "ClassDecl", "CXXRecordDecl" -> "Records and classes require aggregate layout and object-lifetime IR before they can be converted safely.";
            case "EnumDecl", "EnumConstantDecl" -> "Clang enum lowering is not part of this stage; use the existing restricted C enum path where applicable.";
            case "TypedefDecl", "TypeAliasDecl" -> "Type aliases are not represented by this stage's Clang-to-IR type model.";
            case "Namespace" -> "Namespace scopes are not represented by this stage's shared symbol model.";
            default -> "This top-level Clang declaration is not represented by the current shared IR subset.";
        };
        return "Clang AST declaration '" + node.kind() + "'" + named(node.name()) + " is unsupported. " + feature;
    }

    private String named(String name) { return name == null || name.isBlank() ? "" : " '" + name + "'"; }

    private LoweringResult failure(String code, String message) {
        return new LoweringResult(null, List.of(Diagnostic.withoutLocation(Severity.ERROR, code, message)));
    }

    public record LoweringResult(Program program, List<Diagnostic> diagnostics) {
        public LoweringResult { diagnostics = List.copyOf(diagnostics); }
        public boolean successful() { return program != null && diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR); }
    }

    private static final class LoweringContext {
        private final String source;
        private final String[] lines;
        private final ClangSemanticModel model;
        private final List<Diagnostic> diagnostics = new ArrayList<>();

        private LoweringContext(String source, ClangSemanticModel model) {
            this.source = source;
            this.lines = source.split("\\R", -1);
            this.model = model;
        }

        private FunctionDeclaration function(SemanticAstNode node) {
            if (node.attributes().getOrDefault("linkage", "").equals("internal")) {
                throw failure("CB-CLANG-LINKAGE-UNSUPPORTED", "Internal-linkage functions (for example C static functions or anonymous-namespace functions) cannot be represented without translation-unit linkage metadata.", node);
            }
            if (Boolean.parseBoolean(node.attributes().getOrDefault("inline", "false"))) {
                throw failure("CB-CLANG-INLINE-UNSUPPORTED", "Inline function linkage and one-definition-rule behavior are not represented in the current shared IR.", node);
            }
            String returnType = functionReturnType(node);
            if (!SCALAR_TYPES.contains(returnType)) throw failure("CB-CLANG-TYPE-UNSUPPORTED", typeMessage(returnType), node);
            List<Parameter> parameters = new ArrayList<>();
            SemanticAstNode body = null;
            for (SemanticAstNode child : node.children()) {
                if (child.kind().equals("ParmDecl")) {
                    String type = scalarType(child);
                    if (child.name().isBlank()) throw failure("CB-CLANG-PARAMETER-NAME-MISSING", "A function parameter without a source name cannot be represented by the current shared IR.", child);
                    parameters.add(new Parameter(IR_VERSION, span(child), type, child.name()));
                } else if (child.kind().equals("CompoundStmt")) body = child;
                else throw failure("CB-CLANG-FUNCTION-CHILD-UNSUPPORTED", "Function AST contains unsupported child '" + child.kind() + "' (for example a template or declaration attribute).", child);
            }
            if (body == null) throw failure("CB-CLANG-FUNCTION-BODY-MISSING", "A function definition has no compound body in Clang's AST.", node);
            List<Statement> statements = new ArrayList<>();
            for (SemanticAstNode child : body.children()) statements.addAll(statement(child));
            BlockStatement block = new BlockStatement(IR_VERSION, span(body), statements);
            return new FunctionDeclaration(IR_VERSION, span(node), returnType, node.name(), parameters, block);
        }

        private String functionReturnType(SemanticAstNode node) {
            String type = cleanType(node.canonicalType().isBlank() ? node.type() : node.canonicalType());
            int parameters = type.indexOf('(');
            if (parameters < 0) throw failure("CB-CLANG-FUNCTION-TYPE", "Clang did not provide a recognizable function type for '" + node.name() + "'.", node);
            String result = type.substring(0, parameters).strip();
            if (!SCALAR_TYPES.contains(result)) throw failure("CB-CLANG-TYPE-UNSUPPORTED", typeMessage(result), node);
            return result;
        }

        private String scalarType(SemanticAstNode node) {
            String type = cleanType(node.canonicalType().isBlank() ? node.type() : node.canonicalType());
            if (!SCALAR_TYPES.contains(type) || type.equals("void")) throw failure("CB-CLANG-TYPE-UNSUPPORTED", typeMessage(type), node);
            return type;
        }

        private String cleanType(String type) { return type.strip().replaceAll("\\s+", " "); }

        private String typeMessage(String type) {
            String semantic = type.contains("*") ? "Pointer types and pointer arithmetic require explicit pointee, ownership, and lifetime IR."
                    : type.contains("&") ? "C++ references and rvalue references require alias and lifetime semantics that are not represented in this IR stage."
                    : type.contains("[") ? "Array and pointer decay types require shape-preserving array IR and are not lowered by this stage."
                    : "Only plain scalar types (void, bool, char, short, int, long, float, and double) are lowered by this stage.";
            return "Clang resolved type '" + type + "'. " + semantic;
        }

        private List<Statement> statement(SemanticAstNode node) {
            return switch (node.kind()) {
                case "CompoundStmt" -> List.of(nestedBlock(node));
                case "ReturnStmt" -> List.of(returnStatement(node));
                case "DeclStmt" -> declarations(node);
                case "NullStmt" -> List.of();
                case "IfStmt" -> List.of(ifStatement(node));
                case "WhileStmt" -> List.of(whileStatement(node));
                case "DoStmt" -> List.of(doWhileStatement(node));
                case "ForStmt" -> List.of(forStatement(node));
                case "BreakStmt" -> List.of(new BreakStatement(IR_VERSION, span(node)));
                case "ContinueStmt" -> List.of(new ContinueStatement(IR_VERSION, span(node)));
                case "CallExpr", "BinaryOperator", "UnaryOperator" -> List.of(new ExpressionStatement(IR_VERSION, span(node), expression(node)));
                case "CXXOperatorCallExpr", "CXXMemberCallExpr", "CXXNewExpr", "CXXDeleteExpr" -> throw failure("CB-CLANG-STATEMENT-UNSUPPORTED", "C++ construct '" + node.kind() + "' has call/operator or object-lifetime semantics not represented in the current IR.", node);
                default -> throw failure("CB-CLANG-STATEMENT-UNSUPPORTED", "Clang statement '" + node.kind() + "' is not represented by this lowering stage. The AST node was not discarded.", node);
            };
        }

        private IfStatement ifStatement(SemanticAstNode node) {
            if (node.children().size() < 2 || node.children().size() > 3 || hasIfInitializer(node)) {
                throw failure("CB-CLANG-IF-SHAPE-UNSUPPORTED", "If statements with initializer/condition-variable forms or ambiguous child structure are not supported by this lowering stage.", node);
            }
            Expression condition = expression(node.children().get(0));
            Statement thenBranch = branch(node.children().get(1));
            Optional<Statement> elseBranch = node.children().size() == 3 ? Optional.of(branch(node.children().get(2))) : Optional.empty();
            return new IfStatement(IR_VERSION, span(node), condition, thenBranch, elseBranch);
        }

        private boolean hasIfInitializer(SemanticAstNode node) {
            String text = slice(node);
            int open = text.indexOf('('), close = text.indexOf(')', Math.max(0, open + 1));
            return open >= 0 && close > open && text.substring(open + 1, close).contains(";");
        }

        private WhileStatement whileStatement(SemanticAstNode node) {
            if (node.children().size() != 2) throw failure("CB-CLANG-WHILE-SHAPE-UNSUPPORTED", "While loop has an unsupported init/condition-variable form.", node);
            return new WhileStatement(IR_VERSION, span(node), expression(node.children().get(0)), branch(node.children().get(1)));
        }

        private DoWhileStatement doWhileStatement(SemanticAstNode node) {
            if (node.children().size() != 2) throw failure("CB-CLANG-DO-WHILE-SHAPE-UNSUPPORTED", "Do-while loop has an unexpected AST shape.", node);
            return new DoWhileStatement(IR_VERSION, span(node), branch(node.children().get(0)), expression(node.children().get(1)));
        }

        private ForStatement forStatement(SemanticAstNode node) {
            // libclang omits null cursor children. Accept only the unambiguous
            // four-child form with all three header clauses present.
            if (node.children().size() != 4) {
                throw failure("CB-CLANG-FOR-SHAPE-UNSUPPORTED", "Only for loops with explicit initializer, condition, increment, and body AST children are supported; omitted clauses and condition declarations are rejected.", node);
            }
            SemanticAstNode initializerNode = node.children().get(0);
            Optional<Statement> initializer;
            if (initializerNode.kind().equals("DeclStmt")) {
                List<Statement> declarations = declarations(initializerNode);
                if (declarations.size() != 1) throw failure("CB-CLANG-FOR-INITIALIZER-UNSUPPORTED", "A for-loop declaration initializer must declare exactly one local variable.", initializerNode);
                initializer = Optional.of(declarations.getFirst());
            } else {
                initializer = Optional.of(new ExpressionStatement(IR_VERSION, span(initializerNode), expression(initializerNode)));
            }
            Expression condition = expression(node.children().get(1));
            Expression update = expression(node.children().get(2));
            return new ForStatement(IR_VERSION, span(node), initializer, Optional.of(condition), Optional.of(update), branch(node.children().get(3)));
        }

        private Statement branch(SemanticAstNode node) {
            List<Statement> statements = statement(node);
            if (statements.size() == 1) return statements.getFirst();
            return new BlockStatement(IR_VERSION, span(node), statements);
        }

        private BlockStatement nestedBlock(SemanticAstNode node) {
            List<Statement> children = new ArrayList<>();
            for (SemanticAstNode child : node.children()) children.addAll(statement(child));
            return new BlockStatement(IR_VERSION, span(node), children);
        }

        private ReturnStatement returnStatement(SemanticAstNode node) {
            if (node.children().isEmpty()) return new ReturnStatement(IR_VERSION, span(node), Optional.empty());
            if (node.children().size() != 1) throw failure("CB-CLANG-RETURN-UNSUPPORTED", "Return statement has an unexpected number of semantic expressions.", node);
            return new ReturnStatement(IR_VERSION, span(node), Optional.of(expression(node.children().get(0))));
        }

        private List<Statement> declarations(SemanticAstNode node) {
            List<Statement> result = new ArrayList<>();
            for (SemanticAstNode child : node.children()) {
                if (!child.kind().equals("VarDecl")) throw failure("CB-CLANG-DECLARATOR-UNSUPPORTED", "Declaration statement contains unsupported AST child '" + child.kind() + "'.", child);
                String type = scalarType(child);
                String declarationText = slice(child);
                if (declarationText.matches("(?s).*\\b(?:static|register|extern|thread_local)\\b.*")) {
                    throw failure("CB-CLANG-STORAGE-UNSUPPORTED", "Local storage duration or linkage cannot be dropped during conversion.", child);
                }
                if (child.name().isBlank()) throw failure("CB-CLANG-VARIABLE-NAME-MISSING", "A local variable without a name cannot be represented by the shared IR.", child);
                Optional<Expression> initializer = child.children().isEmpty() ? Optional.empty()
                        : child.children().size() == 1 ? Optional.of(expression(child.children().get(0)))
                        : throwFailure("CB-CLANG-INITIALIZER-UNSUPPORTED", "Variable declaration has multiple initializer AST nodes.", child);
                result.add(new VariableDeclaration(IR_VERSION, span(child), child.name(), type, initializer));
            }
            return result;
        }

        private Optional<Expression> throwFailure(String code, String message, SemanticAstNode node) {
            throw failure(code, message, node);
        }

        private Expression expression(SemanticAstNode raw) {
            SemanticAstNode node = unwrap(raw);
            SourceSpan location = span(node);
            return switch (node.kind()) {
                case "IntegerLiteral" -> new LiteralExpression(IR_VERSION, location, integer(node));
                case "FloatingLiteral" -> new LiteralExpression(IR_VERSION, location, floating(node));
                case "CXXBoolLiteralExpr" -> new LiteralExpression(IR_VERSION, location, slice(node).equals("true"));
                case "DeclRefExpr" -> name(node);
                case "BinaryOperator" -> binary(node);
                case "UnaryOperator" -> unary(node);
                case "CallExpr" -> call(node);
                default -> throw failure("CB-CLANG-EXPRESSION-UNSUPPORTED", "Clang expression '" + node.kind() + "' is not represented by this lowering stage. The AST node was not discarded.", node);
            };
        }

        private SemanticAstNode unwrap(SemanticAstNode node) {
            if (!EXPRESSION_WRAPPERS.contains(node.kind())) return node;
            if (node.children().size() != 1) throw failure("CB-CLANG-EXPRESSION-WRAPPER", "Clang expression wrapper '" + node.kind() + "' does not contain exactly one expression.", node);
            SemanticAstNode child = node.children().get(0);
            if (node.kind().equals("ImplicitCastExpr") || node.kind().equals("CStyleCastExpr")) {
                String outer = cleanType(node.canonicalType().isBlank() ? node.type() : node.canonicalType());
                String inner = cleanType(child.canonicalType().isBlank() ? child.type() : child.canonicalType());
                if (!outer.equals(inner)) throw failure("CB-CLANG-CAST-UNSUPPORTED", "Clang inserted a conversion from '" + inner + "' to '" + outer + "'. Its value-conversion semantics are not represented in the current IR.", node);
            }
            return unwrap(child);
        }

        private Expression name(SemanticAstNode node) {
            if (node.referencedName().isBlank()) throw failure("CB-CLANG-REFERENCE-UNRESOLVED", "Clang did not resolve the referenced declaration for this name.", node);
            SemanticSymbol symbol = model.symbols().get(node.referencedSymbolId());
            if (symbol != null && symbol.kind().equals("VarDecl") && symbol.scope().isBlank()) {
                throw failure("CB-CLANG-GLOBAL-UNSUPPORTED", "Reference to global variable '" + symbol.name() + "' requires global-state IR, which this stage does not provide.", node);
            }
            if (symbol != null && (symbol.kind().equals("FunctionDecl") || symbol.kind().equals("CXXMethod"))) {
                throw failure("CB-CLANG-FUNCTION-VALUE-UNSUPPORTED", "Function references as values (including function pointers) are not represented by this IR stage.", node);
            }
            return new NameExpression(IR_VERSION, span(node), node.referencedName());
        }

        private Expression binary(SemanticAstNode node) {
            if (node.children().size() != 2) throw failure("CB-CLANG-BINARY-OPERAND-COUNT", "Clang binary operator does not have exactly two operand nodes.", node);
            SemanticAstNode leftNode = unwrap(node.children().get(0));
            SemanticAstNode rightNode = unwrap(node.children().get(1));
            String operator = between(leftNode, rightNode).strip();
            if (!BINARY_OPERATORS.contains(operator)) throw failure("CB-CLANG-OPERATOR-UNSUPPORTED", "Binary operator spelling '" + operator + "' is not supported by the shared expression IR.", node);
            Expression left = expression(node.children().get(0));
            Expression right = expression(node.children().get(1));
            if (operator.equals("=")) return new AssignmentExpression(IR_VERSION, span(node), left, right);
            return new BinaryExpression(IR_VERSION, span(node), operator, left, right);
        }

        private Expression unary(SemanticAstNode node) {
            if (node.children().size() != 1) throw failure("CB-CLANG-UNARY-OPERAND-COUNT", "Clang unary operator does not have exactly one operand node.", node);
            SemanticAstNode operandNode = unwrap(node.children().get(0));
            SourceSpan whole = span(node), operandSpan = span(operandNode);
            String prefix = sourceBetween(whole.startLine(), whole.startColumn(), operandSpan.startLine(), operandSpan.startColumn()).strip();
            String postfix = sourceBetween(operandSpan.endLine(), operandSpan.endColumn(), whole.endLine(), whole.endColumn()).strip();
            String operator = prefix.isEmpty() ? postfix : prefix;
            boolean isPrefix = !prefix.isEmpty();
            Expression operand = expression(node.children().get(0));
            if (operator.equals("++") || operator.equals("--")) {
                if (!(operand instanceof NameExpression name)) throw failure("CB-CLANG-UPDATE-UNSUPPORTED", "Increment/decrement of a non-local lvalue is not supported.", node);
                return new UpdateExpression(IR_VERSION, whole, name, operator, isPrefix);
            }
            if (!Set.of("!", "-", "+", "~").contains(operator)) throw failure("CB-CLANG-UNARY-OPERATOR-UNSUPPORTED", "Unary operator '" + operator + "' is not supported by the shared IR.", node);
            return new UnaryExpression(IR_VERSION, whole, operator, operand);
        }

        private Expression call(SemanticAstNode node) {
            if (node.children().isEmpty()) throw failure("CB-CLANG-CALL-CALLEE-MISSING", "Call expression has no callee AST node.", node);
            SemanticAstNode callee = findReference(node.children().get(0));
            if (callee == null || callee.referencedName().isBlank()) throw failure("CB-CLANG-CALL-CALLEE-UNSUPPORTED", "Indirect calls and member calls require function-pointer or object-dispatch IR.", node.children().get(0));
            List<Expression> arguments = new ArrayList<>();
            for (int i = 1; i < node.children().size(); i++) arguments.add(expression(node.children().get(i)));
            return new CallExpression(IR_VERSION, span(node), callee.referencedName(), arguments);
        }

        private SemanticAstNode findReference(SemanticAstNode node) {
            if (node.kind().equals("DeclRefExpr") && !node.referencedName().isBlank()) return node;
            for (SemanticAstNode child : node.children()) {
                SemanticAstNode found = findReference(child);
                if (found != null) return found;
            }
            return null;
        }

        private BigInteger integer(SemanticAstNode node) {
            String token = slice(node).replaceAll("'", "").replace("_", "");
            token = INTEGER_SUFFIX.matcher(token).replaceFirst("");
            try {
                int radix = token.startsWith("0x") || token.startsWith("0X") ? 16
                        : token.length() > 1 && token.startsWith("0") ? 8 : 10;
                String digits = radix == 16 ? token.substring(2) : radix == 8 ? token.substring(1) : token;
                BigInteger value = new BigInteger(digits, radix);
                String type = cleanType(node.canonicalType().isBlank() ? node.type() : node.canonicalType());
                int bits = switch (type) { case "char", "_Bool", "bool" -> 8; case "short" -> 16; case "int" -> 32; default -> 64; };
                if (value.bitLength() > bits - 1) throw new NumberFormatException("out of signed range");
                return value;
            } catch (NumberFormatException invalid) {
                throw failure("CB-CLANG-LITERAL-UNSUPPORTED", "Integer literal '" + slice(node).strip() + "' is outside the representable signed range of its Clang type.", node);
            }
        }

        private Double floating(SemanticAstNode node) {
            String token = slice(node).strip().replaceAll("[fF]$", "");
            if (!FLOATING_LITERAL.matcher(token).matches()) throw failure("CB-CLANG-LITERAL-UNSUPPORTED", "Floating literal spelling '" + slice(node).strip() + "' cannot be preserved by the current numeric IR.", node);
            try {
                double value = Double.parseDouble(token);
                if (!Double.isFinite(value)) throw new NumberFormatException("not finite");
                return value;
            } catch (NumberFormatException invalid) {
                throw failure("CB-CLANG-LITERAL-UNSUPPORTED", "Floating literal is outside the supported finite numeric range.", node);
            }
        }

        private String between(SemanticAstNode left, SemanticAstNode right) {
            SourceSpan a = span(left), b = span(right);
            if (offset(a.endLine(), a.endColumn()) > offset(b.startLine(), b.startColumn())) {
                throw failure("CB-CLANG-SOURCE-RANGE-INVALID", "Clang operand source ranges overlap; the operator cannot be recovered without guessing.", left);
            }
            String gap = sourceBetween(a.endLine(), a.endColumn(), b.startLine(), b.startColumn()).replaceAll("\\s+", "");
            if (OPERATORS.contains(gap)) return gap;
            for (String candidate : OPERATORS) if (gap.endsWith(candidate) && gap.length() == candidate.length()) return candidate;
            throw failure("CB-CLANG-OPERATOR-SPELLING-MISSING", "Could not recover the operator token between Clang's semantic operands.", left);
        }

        private String slice(SemanticAstNode node) {
            SourceSpan location = span(node);
            int start = offset(location.startLine(), location.startColumn());
            int end = offset(location.endLine(), location.endColumn());
            if (end <= start) end = Math.min(source.length(), start + 1);
            return source.substring(start, Math.min(end, source.length()));
        }

        private String sourceBetween(int startLine, int startColumn, int endLine, int endColumn) {
            int start = offset(startLine, startColumn), end = offset(endLine, endColumn);
            return end <= start ? "" : source.substring(start, Math.min(end, source.length()));
        }

        private int offset(int line, int column) {
            int current = 1, index = 0;
            while (current < line && index < source.length()) if (source.charAt(index++) == '\n') current++;
            return Math.min(source.length(), index + Math.max(0, column - 1));
        }

        private SourceSpan span(SemanticAstNode node) { return node.location().orElse(SourceSpan.point(1, 1)); }

        private SourceSpan programSpan() {
            int endLine = Math.max(1, lines.length);
            int endColumn = lines.length == 0 ? 1 : Math.max(1, lines[lines.length - 1].length() + 1);
            return new SourceSpan(1, 1, endLine, endColumn);
        }

        private boolean hasErrors() { return diagnostics.stream().anyMatch(value -> value.severity() == Severity.ERROR); }

        private void fail(String code, String message, SemanticAstNode node) {
            diagnostics.add(node == null ? Diagnostic.withoutLocation(Severity.ERROR, code, message)
                    : Diagnostic.at(Severity.ERROR, code, message, span(node)));
        }

        private LoweringFailure failure(String code, String message, SemanticAstNode node) {
            return new LoweringFailure(Diagnostic.at(Severity.ERROR, code, message, span(node)));
        }
    }

    private static final class LoweringFailure extends RuntimeException {
        private final Diagnostic diagnostic;
        private LoweringFailure(Diagnostic diagnostic) { super(diagnostic.message()); this.diagnostic = diagnostic; }
    }
}
