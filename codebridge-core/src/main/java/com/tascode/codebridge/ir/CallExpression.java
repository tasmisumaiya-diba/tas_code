package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;

/** A deterministic function or method invocation, with a qualified callee name. */
public record CallExpression(int irVersion, SourceSpan sourceSpan, String callee, List<Expression> arguments)
        implements Expression {
    public CallExpression {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(callee, "callee");
        if (callee.isBlank()) throw new IllegalArgumentException("callee must not be blank");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        arguments.forEach(argument -> IrVersions.requireMatching(irVersion, argument));
    }
}