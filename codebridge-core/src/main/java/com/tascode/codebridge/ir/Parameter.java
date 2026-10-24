package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
public record Parameter(int irVersion,SourceSpan sourceSpan,String type,String name) implements IrNode {
 public Parameter { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(type); Objects.requireNonNull(name); if(type.isBlank()||name.isBlank())throw new IllegalArgumentException("Parameter type and name are required"); }
}
