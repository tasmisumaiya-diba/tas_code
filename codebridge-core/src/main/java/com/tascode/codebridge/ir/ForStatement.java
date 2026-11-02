package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/** C-style for loop. An absent condition has the language-defined meaning of true. */
public record ForStatement(int irVersion, SourceSpan sourceSpan, Optional<Statement> initializer,
                           Optional<Expression> condition, Optional<Expression> update, Statement body)
        implements Statement {
    public ForStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        initializer = Objects.requireNonNull(initializer, "initializer");
        condition = Objects.requireNonNull(condition, "condition");
        update = Objects.requireNonNull(update, "update");
        Objects.requireNonNull(body, "body");
        initializer.ifPresent(statement -> IrVersions.requireMatching(irVersion, statement));
        condition.ifPresent(expression -> IrVersions.requireMatching(irVersion, expression));
        update.ifPresent(expression -> IrVersions.requireMatching(irVersion, expression));
        IrVersions.requireMatching(irVersion, body);
    }
}