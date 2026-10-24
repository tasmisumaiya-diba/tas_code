package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
import java.util.Optional;
public record VariableDeclaration(int irVersion,SourceSpan sourceSpan,String name,String type,Optional<Expression> initializer) implements Statement {
 public VariableDeclaration { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(name); Objects.requireNonNull(type); initializer=Objects.requireNonNull(initializer); if(name.isBlank()||type.isBlank())throw new IllegalArgumentException("name and type must not be blank"); initializer.ifPresent(e->IrVersions.requireMatching(irVersion,e)); }
}
