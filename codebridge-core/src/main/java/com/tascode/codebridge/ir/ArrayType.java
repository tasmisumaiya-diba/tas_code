package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Array shape and runtime bounds behavior; nested arrays represent multidimensional arrays. */
public record ArrayType(int irVersion, SourceSpan sourceSpan, IrType elementType, LengthKind lengthKind,
                        long fixedLength, BoundsBehavior boundsBehavior, boolean mutable) implements IrType {
    public enum LengthKind { FIXED, RUNTIME, UNKNOWN }
    public enum BoundsBehavior { CHECKED, UNCHECKED, UNSPECIFIED }
    public ArrayType {
        IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(elementType);
        Objects.requireNonNull(lengthKind); Objects.requireNonNull(boundsBehavior);
        IrVersions.requireMatching(irVersion, elementType);
        if (fixedLength < 0 || (lengthKind != LengthKind.FIXED && fixedLength != 0))
            throw new IllegalArgumentException("Only fixed arrays may carry a non-negative fixed length");
    }
}
