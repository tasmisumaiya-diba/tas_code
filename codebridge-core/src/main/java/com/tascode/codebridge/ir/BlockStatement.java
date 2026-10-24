package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;
public record BlockStatement(int irVersion,SourceSpan sourceSpan,List<Statement> statements) implements Statement {
 public BlockStatement { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); statements=List.copyOf(Objects.requireNonNull(statements)); statements.forEach(s->IrVersions.requireMatching(irVersion,s)); }
}
