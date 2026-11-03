package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
/** Null literal type; it is not interchangeable with a nullable reference type. */
public record NullType(int irVersion, SourceSpan sourceSpan) implements IrType {
    public NullType { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); }
}
