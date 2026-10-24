package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
public record ExpressionStatement(int irVersion,SourceSpan sourceSpan,Expression expression) implements Statement {
 public ExpressionStatement { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(expression); IrVersions.requireMatching(irVersion,expression); }
}
