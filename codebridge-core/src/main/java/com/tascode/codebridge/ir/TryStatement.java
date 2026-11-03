package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exception handling with optional resources, catch clauses, and a finally block.
 * At least one catch clause, a finally block, or a resource is required.
 */
public record TryStatement(int irVersion, SourceSpan sourceSpan, List<Statement> resources, BlockStatement body,
                           List<Catch> catches, Optional<BlockStatement> finallyBlock) implements Statement {
    public TryStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        Objects.requireNonNull(body, "body");
        catches = List.copyOf(Objects.requireNonNull(catches, "catches"));
        finallyBlock = Objects.requireNonNull(finallyBlock, "finallyBlock");
        if (resources.isEmpty() && catches.isEmpty() && finallyBlock.isEmpty())
            throw new IllegalArgumentException("A try statement needs a resource, a catch clause, or a finally block");
        for (Statement resource : resources)
            if (!(resource instanceof VariableDeclaration || resource instanceof ExpressionStatement))
                throw new IllegalArgumentException("Try resources must be variable declarations or expressions");
        resources.forEach(resource -> IrVersions.requireMatching(irVersion, resource));
        IrVersions.requireMatching(irVersion, body);
        catches.forEach(clause -> IrVersions.requireMatching(irVersion, clause));
        finallyBlock.ifPresent(block -> IrVersions.requireMatching(irVersion, block));
    }

    /** A catch clause; several exception types model a Java multi-catch. */
    public record Catch(int irVersion, SourceSpan sourceSpan, List<IrType> exceptionTypes, String name,
                        BlockStatement body) implements IrNode {
        public Catch {
            IrVersions.requireSupported(irVersion);
            Objects.requireNonNull(sourceSpan, "sourceSpan");
            exceptionTypes = List.copyOf(Objects.requireNonNull(exceptionTypes, "exceptionTypes"));
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
            if (exceptionTypes.isEmpty()) throw new IllegalArgumentException("A catch clause requires at least one exception type");
            if (name.isBlank()) throw new IllegalArgumentException("Catch parameter name is required");
            exceptionTypes.forEach(type -> IrVersions.requireMatching(irVersion, type));
            IrVersions.requireMatching(irVersion, body);
        }
    }
}