package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;

/** Callable signature, including variadic and calling-convention facts when known. */
public record FunctionType(int irVersion, SourceSpan sourceSpan, List<IrType> parameterTypes,
                           IrType returnType, boolean variadic, String callingConvention) implements IrType {
    public FunctionType {
        IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan);
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes)); Objects.requireNonNull(returnType);
        Objects.requireNonNull(callingConvention); if (callingConvention.isBlank()) throw new IllegalArgumentException("Calling convention is required");
        parameterTypes.forEach(type -> IrVersions.requireMatching(irVersion, type)); IrVersions.requireMatching(irVersion, returnType);
    }
}
