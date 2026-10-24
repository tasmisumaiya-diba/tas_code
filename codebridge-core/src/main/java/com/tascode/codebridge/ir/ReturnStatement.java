package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
import java.util.Optional;
public record ReturnStatement(int irVersion,SourceSpan sourceSpan,Optional<Expression> value) implements Statement {
 public ReturnStatement { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); value=Objects.requireNonNull(value); value.ifPresent(e->IrVersions.requireMatching(irVersion,e)); }
}
