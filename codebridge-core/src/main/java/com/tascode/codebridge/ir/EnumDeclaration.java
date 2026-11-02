package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;

/** A C enum definition with enumerator values resolved to their C integer values. */
public record EnumDeclaration(int irVersion, SourceSpan sourceSpan, String name, List<Enumerator> enumerators)
        implements IrNode {
    public EnumDeclaration {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan);
        Objects.requireNonNull(name);
        enumerators = List.copyOf(Objects.requireNonNull(enumerators));
        if (enumerators.isEmpty()) throw new IllegalArgumentException("An enum must declare at least one enumerator");
        enumerators.forEach(item -> IrVersions.requireMatching(irVersion, item));
    }

    public record Enumerator(int irVersion, SourceSpan sourceSpan, String name, int value) implements IrNode {
        public Enumerator {
            IrVersions.requireSupported(irVersion);
            Objects.requireNonNull(sourceSpan);
            Objects.requireNonNull(name);
            if (name.isBlank()) throw new IllegalArgumentException("Enumerator name is required");
        }
    }
}
