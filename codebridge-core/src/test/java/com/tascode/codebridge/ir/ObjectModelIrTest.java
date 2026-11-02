package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ObjectModelIrTest {
    private static final SourceSpan SPAN = SourceSpan.point(1, 1);

    @Test void classIrRetainsFieldsConstructorsOverloadsAndInheritance() {
        TypeReference integer = new TypeReference(1, SPAN, "int");
        Parameter parameter = new Parameter(1, SPAN, "int", "value");
        BlockStatement body = new BlockStatement(1, SPAN, List.of());
        ConstructorDeclaration constructor = new ConstructorDeclaration(1, SPAN, "Counter", List.of(parameter),
                List.of(), Set.of("public"), body);
        MethodDeclaration methodInt = new MethodDeclaration(1, SPAN, "add", integer, List.of(parameter), List.of(),
                List.of(), Set.of("public"), Optional.of(body));
        MethodDeclaration methodLong = new MethodDeclaration(1, SPAN, "add", new TypeReference(1, SPAN, "long"),
                List.of(new Parameter(1, SPAN, "long", "value")), List.of(), List.of(), Set.of("public"), Optional.of(body));
        ClassDeclaration type = new ClassDeclaration(1, SPAN, "Counter", ClassDeclaration.Kind.CLASS,
                Set.of(), "public", List.of(), Optional.of(new TypeReference(1, SPAN, "BaseCounter")),
                List.of(new TypeReference(1, SPAN, "Countable")),
                List.of(new FieldDeclaration(1, SPAN, "value", integer, Set.of("private"), Optional.empty())),
                List.of(constructor), List.of(methodInt, methodLong), List.of());
        Program program = new Program(1, SPAN, List.of(), List.of(), List.of(), List.of(), List.of(type));

        assertEquals("BaseCounter", program.classes().getFirst().superclass().orElseThrow().name());
        assertEquals(2, program.classes().getFirst().methods().size());
        assertEquals("private", program.classes().getFirst().fields().getFirst().modifiers().iterator().next());
        assertEquals("long", program.classes().getFirst().methods().get(1).returnType().name());
    }

    @Test void abstractMethodsCannotCarryBodies() {
        assertThrows(IllegalArgumentException.class, () -> new MethodDeclaration(1, SPAN, "run",
                new TypeReference(1, SPAN, "void"), List.of(), List.of(), List.of(), Set.of("abstract"),
                Optional.of(new BlockStatement(1, SPAN, List.of()))));
    }
}
