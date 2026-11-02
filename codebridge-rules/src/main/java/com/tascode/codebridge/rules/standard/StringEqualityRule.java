package com.tascode.codebridge.rules.standard;

import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.rules.ConversionRule;
import com.tascode.codebridge.rules.RuleContext;
import java.util.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Preserves value-based string equality across Java and C++. */
public final class StringEqualityRule implements ConversionRule {
    private static final Map<String, String> JAVA_CPP_STRING_MAPPINGS = loadRuntimeMappings();
    private Language from;
    private Language to;
    @Override public boolean appliesTo(Language from, Language to, IrNode node) {
        return node instanceof Program && ((from == Language.CPP && to == Language.JAVA) || (from == Language.JAVA && to == Language.CPP));
    }
    @Override public int priority() { return 120; }
    @Override public synchronized IrNode rewrite(IrNode node, RuleContext context) {
        Program program = (Program) node;
        from = context.from(); to = context.to();
        List<FunctionDeclaration> functions = new ArrayList<>();
        for (FunctionDeclaration function : program.functions()) {
            Map<String, String> types = new HashMap<>();
            function.parameters().forEach(p -> types.put(p.name(), normalize(p.type())));
            functions.add(new FunctionDeclaration(function.irVersion(), function.sourceSpan(), function.returnType(), function.name(),
                    function.parameters(), rewriteBlock(function.body(), types)));
        }
        return new Program(program.irVersion(), program.sourceSpan(), functions, program.includes(), program.globals(), program.enums(), program.classes());
    }
    private BlockStatement rewriteBlock(BlockStatement block, Map<String, String> parent) {
        Map<String, String> types = new HashMap<>(parent);
        List<Statement> statements = new ArrayList<>();
        for (Statement s : block.statements()) {
            statements.add(rewriteStatement(s, types));
            if (from == Language.CPP && to == Language.JAVA && s instanceof ArrayDeclaration array && normalize(array.elementType()).equals("string")) {
                LiteralExpression empty = new LiteralExpression(array.irVersion(), array.sourceSpan(), "");
                NameExpression name = new NameExpression(array.irVersion(), array.sourceSpan(), array.name());
                CallExpression fill = new CallExpression(array.irVersion(), array.sourceSpan(), "java.util.Arrays.fill", List.of(name, empty));
                statements.add(new ExpressionStatement(array.irVersion(), array.sourceSpan(), fill));
            }
        }
        return new BlockStatement(block.irVersion(), block.sourceSpan(), statements);
    }
    private Statement rewriteStatement(Statement s, Map<String, String> types) {
        if (s instanceof BlockStatement b) return rewriteBlock(b, types);
        if (s instanceof VariableDeclaration v) {
            Expression initializer = v.initializer().map(e -> rewriteExpression(e, types)).orElse(null);
            if (from == Language.CPP && to == Language.JAVA && initializer == null && normalize(v.type()).equals("string")) {
                initializer = new LiteralExpression(v.irVersion(), v.sourceSpan(), "");
            }
            types.put(v.name(), normalize(v.type()));
            return new VariableDeclaration(v.irVersion(), v.sourceSpan(), v.name(), v.type(), Optional.ofNullable(initializer));
        }
        if (s instanceof ArrayDeclaration a) { types.put(a.name(), normalize(a.elementType()) + "[]"); return a; }
        if (s instanceof ExpressionStatement e) return new ExpressionStatement(e.irVersion(), e.sourceSpan(), rewriteExpression(e.expression(), types));
        if (s instanceof ReturnStatement r) return new ReturnStatement(r.irVersion(), r.sourceSpan(), r.value().map(e -> rewriteExpression(e, types)));
        if (s instanceof IfStatement i) return new IfStatement(i.irVersion(), i.sourceSpan(), rewriteExpression(i.condition(), types), rewriteStatement(i.thenBranch(), new HashMap<>(types)), i.elseBranch().map(x -> rewriteStatement(x, new HashMap<>(types))));
        if (s instanceof WhileStatement w) return new WhileStatement(w.irVersion(), w.sourceSpan(), rewriteExpression(w.condition(), types), rewriteStatement(w.body(), new HashMap<>(types)));
        if (s instanceof DoWhileStatement d) return new DoWhileStatement(d.irVersion(), d.sourceSpan(), rewriteStatement(d.body(), new HashMap<>(types)), rewriteExpression(d.condition(), types));
        if (s instanceof ForStatement f) { Map<String,String> loopTypes=new HashMap<>(types); return new ForStatement(f.irVersion(),f.sourceSpan(),f.initializer().map(x->rewriteStatement(x,loopTypes)),f.condition().map(x->rewriteExpression(x,loopTypes)),f.update().map(x->rewriteExpression(x,loopTypes)),rewriteStatement(f.body(),loopTypes)); }
        return s;
    }
    private Expression rewriteExpression(Expression e, Map<String, String> types) {
        if (e instanceof BinaryExpression b) {
            Expression left=rewriteExpression(b.left(),types), right=rewriteExpression(b.right(),types);
            BinaryExpression rewritten=new BinaryExpression(b.irVersion(),b.sourceSpan(),b.operator(),left,right);
            if (from==Language.CPP && to==Language.JAVA && Set.of("==","!=").contains(b.operator()) && typeOf(left,types).equals("string") && typeOf(right,types).equals("string")) {
                CallExpression equals=new CallExpression(b.irVersion(),b.sourceSpan(),"java.util.Objects.equals",List.of(left,right));
                return b.operator().equals("==")?equals:new UnaryExpression(b.irVersion(),b.sourceSpan(),"!",equals);
            }
            return rewritten;
        }
        if (e instanceof CallExpression c) {
            if (from == Language.JAVA && to == Language.CPP) {
                int separator = c.callee().lastIndexOf('.');
                if (separator > 0) {
                    String receiver = c.callee().substring(0, separator);
                    String method = c.callee().substring(separator + 1);
                    String mapping = JAVA_CPP_STRING_MAPPINGS.get(method + "/" + c.arguments().size());
                    if (mapping != null && types.getOrDefault(receiver, "unknown").equals("string")) {
                        if (mapping.equals("==") && c.arguments().size() == 1) {
                            Expression argument = rewriteExpression(c.arguments().getFirst(), types);
                            if (typeOf(argument, types).equals("string"))
                                return new BinaryExpression(c.irVersion(), c.sourceSpan(), "==",
                                        new NameExpression(c.irVersion(), c.sourceSpan(), receiver), argument);
                        } else if (Set.of("empty", "length").contains(mapping)) {
                            return new CallExpression(c.irVersion(), c.sourceSpan(), receiver + "." + mapping, List.of());
                        } else if (mapping.equals("+") && c.arguments().size() == 1) {
                            Expression argument = rewriteExpression(c.arguments().getFirst(), types);
                            if (typeOf(argument, types).equals("string"))
                                return new BinaryExpression(c.irVersion(), c.sourceSpan(), "+",
                                        new NameExpression(c.irVersion(), c.sourceSpan(), receiver), argument);
                        } else if (mapping.startsWith("HELPER:") && c.arguments().size() == 1) {
                            Expression argument = rewriteExpression(c.arguments().getFirst(), types);
                            if (typeOf(argument, types).equals("string")) return new CallExpression(c.irVersion(), c.sourceSpan(),
                                    mapping.substring("HELPER:".length()), List.of(new NameExpression(c.irVersion(), c.sourceSpan(), receiver), argument));
                        }
                    }
                }
            }
            return new CallExpression(c.irVersion(),c.sourceSpan(),c.callee(),c.arguments().stream().map(x->rewriteExpression(x,types)).toList());
        }
        if (e instanceof AssignmentExpression a) return new AssignmentExpression(a.irVersion(),a.sourceSpan(),rewriteExpression(a.target(),types),rewriteExpression(a.value(),types));
        if (e instanceof UnaryExpression u) return new UnaryExpression(u.irVersion(),u.sourceSpan(),u.operator(),rewriteExpression(u.operand(),types));
        if (e instanceof ArrayAccessExpression a) return new ArrayAccessExpression(a.irVersion(),a.sourceSpan(),rewriteExpression(a.array(),types),rewriteExpression(a.index(),types));
        return e;
    }
    private String typeOf(Expression e, Map<String,String> types) {
        if(e instanceof LiteralExpression l && l.value() instanceof String)return "string";
        if(e instanceof NameExpression n)return types.getOrDefault(n.name(),"unknown");
        if(e instanceof ArrayAccessExpression a){String array=typeOf(a.array(),types);return array.endsWith("[]")?array.substring(0,array.length()-2):"unknown";}
        if(e instanceof BinaryExpression b && b.operator().equals("+") && typeOf(b.left(),types).equals("string") && typeOf(b.right(),types).equals("string"))return "string";
        return "unknown";
    }
    private static Map<String, String> loadRuntimeMappings() {
        InputStream resource = StringEqualityRule.class.getResourceAsStream("/java-cpp-runtime-mappings.tsv");
        if (resource == null) throw new IllegalStateException("Missing java-cpp-runtime-mappings.tsv");
        Map<String, String> mappings = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t", -1);
                if (fields.length != 4 || !fields[0].equals("String") || fields[1].isBlank()
                        || !fields[2].matches("\\d+") || fields[3].isBlank())
                    throw new IllegalStateException("Invalid Java-to-C++ runtime mapping at line " + lineNumber);
                if (mappings.putIfAbsent(fields[1] + "/" + fields[2], fields[3]) != null)
                    throw new IllegalStateException("Duplicate Java-to-C++ runtime mapping at line " + lineNumber);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read Java-to-C++ runtime mappings", exception);
        }
        return Map.copyOf(mappings);
    }
    private String normalize(String type) { if (type.endsWith("[]")) return normalize(type.substring(0, type.length() - 2)) + "[]"; return switch (type) { case "String" -> "string"; case "c-long" -> "long"; default -> type; }; }
}
