package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;

public record Program(int irVersion, SourceSpan sourceSpan, List<FunctionDeclaration> functions,
                      List<String> includes, List<GlobalVariableDeclaration> globals, List<EnumDeclaration> enums,
                      List<ClassDeclaration> classes) implements IrNode {
    public Program(int irVersion, SourceSpan sourceSpan, List<FunctionDeclaration> functions) {
        this(irVersion, sourceSpan, functions, List.of(), List.of(), List.of(), List.of());
    }
    public Program(int irVersion, SourceSpan sourceSpan, List<FunctionDeclaration> functions, List<String> includes) {
        this(irVersion, sourceSpan, functions, includes, List.of(), List.of(), List.of());
    }
    public Program(int irVersion, SourceSpan sourceSpan, List<FunctionDeclaration> functions, List<String> includes, List<GlobalVariableDeclaration> globals) {
        this(irVersion, sourceSpan, functions, includes, globals, List.of(), List.of());
    }
    public Program(int irVersion, SourceSpan sourceSpan, List<FunctionDeclaration> functions, List<String> includes,
                   List<GlobalVariableDeclaration> globals, List<EnumDeclaration> enums) {
        this(irVersion, sourceSpan, functions, includes, globals, enums, List.of());
    }
    public Program {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan);
        functions = List.copyOf(Objects.requireNonNull(functions));
        includes = List.copyOf(Objects.requireNonNull(includes));
        globals = List.copyOf(Objects.requireNonNull(globals));
        enums = List.copyOf(Objects.requireNonNull(enums));
        classes = List.copyOf(Objects.requireNonNull(classes));
        includes.forEach(header -> { if (!header.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("Invalid include name"); });
        functions.forEach(function -> IrVersions.requireMatching(irVersion, function));
        globals.forEach(global -> IrVersions.requireMatching(irVersion, global));
        enums.forEach(enumeration -> IrVersions.requireMatching(irVersion, enumeration));
        classes.forEach(type -> IrVersions.requireMatching(irVersion, type));
    }
}
