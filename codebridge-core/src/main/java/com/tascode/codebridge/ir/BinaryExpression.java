package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
public record BinaryExpression(int irVersion,SourceSpan sourceSpan,String operator,Expression left,Expression right) implements Expression {
 public BinaryExpression { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(operator); Objects.requireNonNull(left); Objects.requireNonNull(right); if(operator.isBlank())throw new IllegalArgumentException("operator must not be blank"); IrVersions.requireMatching(irVersion,left); IrVersions.requireMatching(irVersion,right); }
}
