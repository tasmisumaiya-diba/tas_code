package com.tascode.codebridge.language.cpp;

import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageGenerator;
import com.tascode.codebridge.language.subset.SubsetFunctionGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Generates C++ for the supported shared IR and declares functions before their uses. */
public final class CppGenerator implements LanguageGenerator {
    private final SubsetFunctionGenerator delegate = new SubsetFunctionGenerator(Language.CPP);

    @Override public Language language() { return Language.CPP; }

    @Override public GenerationResult generate(IrNode root) {
        GenerationResult generated = delegate.generate(root);
        if (!generated.successful() || !(root instanceof Program program) || program.functions().isEmpty()) return generated;
        String source = generated.source();
        if (source.contains("__codebridge_java_string_length")) source = "#include <cstddef>\n" + source;
        if (source.contains("std::sqrt") || source.contains("std::pow") || source.contains("std::floor")
                || source.contains("std::ceil")) source = "#include <cmath>\n" + source;
        if (source.contains("std::min") || source.contains("std::max")) source = "#include <algorithm>\n" + source;
        int insertion = 0;
        while (source.startsWith("#include ", insertion)) {
            int newline = source.indexOf('\n', insertion);
            if (newline < 0) break;
            insertion = newline + 1;
        }
        StringBuilder output = new StringBuilder(source.substring(0, insertion));
        if (insertion > 0) output.append('\n');
        List<String> helpers = runtimeHelpers(program);
        for (String helper : helpers) output.append(helper).append('\n');
        if (!helpers.isEmpty()) output.append('\n');
        boolean hasForwardDeclarations = program.functions().stream().anyMatch(function -> !function.name().equals("main"));
        for (FunctionDeclaration function : program.functions()) {
            // main is the program entry point and cannot be called as an ordinary helper.
            if (!function.name().equals("main")) output.append(prototype(function)).append('\n');
        }
        if (hasForwardDeclarations) output.append('\n');
        int body = insertion;
        if (body < source.length() && source.charAt(body) == '\n') body++;
        output.append(source.substring(body));
        return new GenerationResult(output.toString(), generated.diagnostics());
    }

    private List<String> runtimeHelpers(Program program) {
        List<String> helpers = new ArrayList<>();
        if (containsCall(program, "__codebridge_starts_with")) helpers.add("static bool __codebridge_starts_with(const std::string& value, const std::string& prefix) { return value.size() >= prefix.size() && value.compare(0, prefix.size(), prefix) == 0; }");
        if (containsCall(program, "__codebridge_ends_with")) helpers.add("static bool __codebridge_ends_with(const std::string& value, const std::string& suffix) { return value.size() >= suffix.size() && value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0; }");
        if (containsCall(program, "__codebridge_contains")) helpers.add("static bool __codebridge_contains(const std::string& value, const std::string& part) { return value.find(part) != std::string::npos; }");
        if (containsCall(program, "__codebridge_java_string_length")) helpers.add("static std::size_t __codebridge_java_string_length(const std::string& value) { std::size_t units = 0; for (std::size_t i = 0; i < value.size();) { unsigned char lead = static_cast<unsigned char>(value[i]); if (lead < 0x80) { ++units; ++i; } else if ((lead & 0xE0) == 0xC0 && i + 1 < value.size()) { ++units; i += 2; } else if ((lead & 0xF0) == 0xE0 && i + 2 < value.size()) { ++units; i += 3; } else if ((lead & 0xF8) == 0xF0 && i + 3 < value.size()) { units += 2; i += 4; } else { ++units; ++i; } } return units; }");
        return helpers;
    }

    private boolean containsCall(Program program, String name) {
        return program.functions().stream().anyMatch(function -> containsCall(function.body(), name));
    }

    private boolean containsCall(Statement statement, String name) {
        if (statement instanceof BlockStatement block) return block.statements().stream().anyMatch(child -> containsCall(child, name));
        if (statement instanceof VariableDeclaration variable) return variable.initializer().map(value -> containsCall(value, name)).orElse(false);
        if (statement instanceof ArrayDeclaration array) return containsCall(array.size(), name);
        if (statement instanceof ReturnStatement returned) return returned.value().map(value -> containsCall(value, name)).orElse(false);
        if (statement instanceof ExpressionStatement expression) return containsCall(expression.expression(), name);
        if (statement instanceof IfStatement conditional) return containsCall(conditional.condition(), name) || containsCall(conditional.thenBranch(), name) || conditional.elseBranch().map(child -> containsCall(child, name)).orElse(false);
        if (statement instanceof WhileStatement loop) return containsCall(loop.condition(), name) || containsCall(loop.body(), name);
        if (statement instanceof DoWhileStatement loop) return containsCall(loop.body(), name) || containsCall(loop.condition(), name);
        if (statement instanceof ForStatement loop) return loop.initializer().map(child -> containsCall(child, name)).orElse(false) || loop.condition().map(value -> containsCall(value, name)).orElse(false) || loop.update().map(value -> containsCall(value, name)).orElse(false) || containsCall(loop.body(), name);
        return false;
    }

    private boolean containsCall(Expression expression, String name) {
        if (expression instanceof CallExpression call) return call.callee().equals(name) || call.arguments().stream().anyMatch(argument -> containsCall(argument, name));
        if (expression instanceof BinaryExpression binary) return containsCall(binary.left(), name) || containsCall(binary.right(), name);
        if (expression instanceof UnaryExpression unary) return containsCall(unary.operand(), name);
        if (expression instanceof AssignmentExpression assignment) return containsCall(assignment.target(), name) || containsCall(assignment.value(), name);
        if (expression instanceof ArrayAccessExpression access) return containsCall(access.array(), name) || containsCall(access.index(), name);
        return false;
    }

    private String prototype(FunctionDeclaration function) {
        String parameters = function.parameters().stream().map(this::parameter).collect(Collectors.joining(", "));
        return typeName(function.returnType()) + " " + function.name() + "(" + parameters + ");";
    }

    private String parameter(Parameter parameter) {
        String type = parameter.type();
        String rendered = type.endsWith("[]")
                ? "std::vector<" + typeName(type.substring(0, type.length() - 2)) + ">&"
                : typeName(type);
        return rendered + " " + parameter.name();
    }

    private String typeName(String type) {
        return switch (type) {
            case "c-long" -> "long";
            case "byte" -> "std::int8_t";
            case "long" -> "long long";
            case "string", "String" -> "std::string";
            case "_Bool" -> "bool";
            default -> type;
        };
    }
}
