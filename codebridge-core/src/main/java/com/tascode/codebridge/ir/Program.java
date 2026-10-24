package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;
public record Program(int irVersion, SourceSpan sourceSpan, List<FunctionDeclaration> functions, List<String> includes) implements IrNode {
 public Program(int irVersion, SourceSpan sourceSpan, List<FunctionDeclaration> functions) { this(irVersion, sourceSpan, functions, List.of()); }
 public Program { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); functions=List.copyOf(Objects.requireNonNull(functions)); includes=List.copyOf(Objects.requireNonNull(includes)); includes.forEach(h->{if(!h.matches("[A-Za-z0-9_.]+"))throw new IllegalArgumentException("Invalid include name");}); functions.forEach(f->IrVersions.requireMatching(irVersion,f)); }
}
