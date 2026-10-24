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

/** Rewrites known function callees from a classpath TSV mapping table. */
public final class StandardLibraryCallRule implements ConversionRule {
    private final Map<MappingKey, String> mappings;

    public StandardLibraryCallRule() {
        this.mappings = loadMappings();
    }

    @Override
    public boolean appliesTo(Language from, Language to, IrNode node) {
        return node instanceof Program && mappings.keySet().stream().anyMatch(key -> key.from == from && key.to == to);
    }

    @Override public int priority() { return 100; }

    @Override
    public IrNode rewrite(IrNode node, RuleContext context) {
        if (!(node instanceof Program program)) return node;
        List<FunctionDeclaration> functions = program.functions().stream().map(function -> new FunctionDeclaration(
                function.irVersion(), function.sourceSpan(), function.returnType(), function.name(), function.parameters(),
                (BlockStatement) rewriteStatement(function.body(), context))).toList();
        return new Program(program.irVersion(), program.sourceSpan(), functions, program.includes());
    }

    private Statement rewriteStatement(Statement statement, RuleContext context) {
        if (statement instanceof BlockStatement block) {
            List<Statement> children = block.statements().stream().map(child -> rewriteStatement(child, context)).toList();
            return new BlockStatement(block.irVersion(), block.sourceSpan(), children);
        }
        if (statement instanceof VariableDeclaration variable) {
            return new VariableDeclaration(variable.irVersion(), variable.sourceSpan(), variable.name(), variable.type(),
                    variable.initializer().map(value -> rewriteExpression(value, context)));
        }
        if (statement instanceof ReturnStatement returned) {
            return new ReturnStatement(returned.irVersion(), returned.sourceSpan(),
                    returned.value().map(value -> rewriteExpression(value, context)));
        }
        if (statement instanceof ExpressionStatement expression) {
            Expression rewritten = rewriteExpression(expression.expression(), context);
            return new ExpressionStatement(expression.irVersion(), expression.sourceSpan(), rewritten);
        }
        return statement;
    }

    private Expression rewriteExpression(Expression expression, RuleContext context) {
        if (expression instanceof BinaryExpression binary) {
            return new BinaryExpression(binary.irVersion(), binary.sourceSpan(), binary.operator(),
                    rewriteExpression(binary.left(), context), rewriteExpression(binary.right(), context));
        }
        if (expression instanceof CallExpression call) {
            List<Expression> arguments = call.arguments().stream().map(argument -> rewriteExpression(argument, context)).toList();
            String callee = mappings.getOrDefault(new MappingKey(context.from(), context.to(), call.callee()), call.callee());
            return new CallExpression(call.irVersion(), call.sourceSpan(), callee, arguments);
        }
        return expression;
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