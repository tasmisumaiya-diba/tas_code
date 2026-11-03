package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
public record VoidType(int irVersion, SourceSpan sourceSpan) implements IrType {
    public VoidType { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); }
}
