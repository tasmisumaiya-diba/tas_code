package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
/** Explicitly unresolved compiler type; backends must not silently treat it as a concrete type. */
public record UnknownType(int irVersion, SourceSpan sourceSpan, String reason) implements IrType {
    public UnknownType { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(reason); }
}
