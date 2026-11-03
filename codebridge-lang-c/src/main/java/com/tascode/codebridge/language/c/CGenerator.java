package com.tascode.codebridge.language.c;

import com.tascode.codebridge.ir.FunctionDeclaration;
import com.tascode.codebridge.ir.IrNode;
import com.tascode.codebridge.ir.Parameter;
import com.tascode.codebridge.ir.Program;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageGenerator;
import com.tascode.codebridge.language.LanguageGenerator.GenerationResult;
import com.tascode.codebridge.language.subset.SubsetFunctionGenerator;
import java.util.stream.Collectors;

/** Generates C and emits declarations so self and mutually recursive functions are valid C. */
public final class CGenerator implements LanguageGenerator {
    private final SubsetFunctionGenerator delegate = new SubsetFunctionGenerator(Language.C);

    @Override public Language language() { return Language.C; }

    @Override public GenerationResult generate(IrNode root) {
        GenerationResult generated = delegate.generate(root);
        if (!generated.successful() || !(root instanceof Program program) || program.functions().isEmpty()) return generated;

        String source = generated.source();
        int insertion = 0;
        while (source.startsWith("#include ", insertion)) {
            int newline = source.indexOf('\n', insertion);
            if (newline < 0) break;
            insertion = newline + 1;
        }
        StringBuilder output = new StringBuilder(source.substring(0, insertion));
        if (insertion > 0) output.append('\n');
        boolean declarations = program.functions().stream().anyMatch(function -> !function.name().equals("main"));
        for (FunctionDeclaration function : program.functions()) {
            if (!function.name().equals("main")) output.append(prototype(function)).append('\n');
        }
        if (declarations) output.append('\n');
        int body = insertion;
        if (body < source.length() && source.charAt(body) == '\n') body++;
        output.append(source.substring(body));
        return new GenerationResult(output.toString(), generated.diagnostics());
    }

    private String prototype(FunctionDeclaration function) {
        String parameters = function.parameters().isEmpty() ? "void" : function.parameters().stream()
                .map(this::parameter).collect(Collectors.joining(", "));
        return cType(function.returnType()) + " " + function.name() + "(" + parameters + ");";
    }

    private String parameter(Parameter parameter) {
        return cType(parameter.type()) + " " + parameter.name();
    }

    private String cType(String type) {
        return switch (type) {
            case "c-long" -> "long";
            case "bool" -> "_Bool";
            case "string", "String" -> "char*";
            default -> type;
        };
    }
}
