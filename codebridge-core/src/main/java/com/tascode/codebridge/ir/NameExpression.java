package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
public record NameExpression(int irVersion,SourceSpan sourceSpan,String name) implements Expression {
 public NameExpression { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(name); if(name.isBlank())throw new IllegalArgumentException("name must not be blank"); }
}
