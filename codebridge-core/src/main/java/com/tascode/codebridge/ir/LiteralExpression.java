package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
public record LiteralExpression(int irVersion,SourceSpan sourceSpan,Object value) implements Expression {
 public LiteralExpression { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(value); if(!(value instanceof String||value instanceof Number||value instanceof Boolean||value instanceof Character))throw new IllegalArgumentException("Unsupported literal value type"); }
}
