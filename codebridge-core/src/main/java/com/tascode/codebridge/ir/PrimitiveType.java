package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Built-in scalar type plus target-dependent width and character encoding facts when known. */
public record PrimitiveType(int irVersion, SourceSpan sourceSpan, Kind kind, String sourceSpelling,
                            int bitWidth, Signedness signedness, String characterEncoding) implements IrType {
    public enum Kind { BOOLEAN, INTEGER, FLOATING, CHARACTER }
    public enum Signedness { SIGNED, UNSIGNED, NOT_APPLICABLE, TARGET_DEFINED }
    public PrimitiveType {
        IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(kind);
        Objects.requireNonNull(sourceSpelling); Objects.requireNonNull(signedness); Objects.requireNonNull(characterEncoding);
        if (sourceSpelling.isBlank() || bitWidth < 0) throw new IllegalArgumentException("Primitive spelling and non-negative width are required");
        if (kind == Kind.BOOLEAN && (signedness != Signedness.NOT_APPLICABLE || bitWidth > 1))
            throw new IllegalArgumentException("Boolean types are not signed integers and use at most one value bit");
        if (kind != Kind.CHARACTER && !characterEncoding.isEmpty()) throw new IllegalArgumentException("Only character types have a character encoding");
        if (kind == Kind.CHARACTER && signedness == Signedness.NOT_APPLICABLE) throw new IllegalArgumentException("Character signedness must be explicit or target-defined");
    }
}
