package com.tascode.codebridge.frontend.clang;

import com.tascode.codebridge.language.Language;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClangAstJsonDecoderTest {
    private final ClangAstJsonDecoder decoder = new ClangAstJsonDecoder();

    @Test void decodesTypedAstDeclarationsResolvedReferencesAndScopes() throws Exception {
        String json = """
                {"language":"C","standard":"c17","compilerVersion":"clang test","targetTriple":"x86_64-unknown-linux-gnu","pointerWidth":64,
                 "declarations":[{"kind":"FunctionDecl","name":"sum","displayName":"sum(int, int)","usr":"c:@F@sum#I#I#","type":{"spelling":"int (int, int)","canonical":"int (int, int)"},"location":{"line":1,"column":1,"endLine":3,"endColumn":1},"attributes":{"definition":true},"children":[{"kind":"ParmDecl","name":"left","type":{"spelling":"int","canonical":"int"},"location":{"line":1,"column":9},"children":[]},{"kind":"CompoundStmt","type":{"spelling":"","canonical":""},"location":{"line":1,"column":24},"children":[{"kind":"ReturnStmt","location":{"line":2,"column":3},"children":[{"kind":"BinaryOperator","type":{"spelling":"int","canonical":"int"},"valueCategory":"prvalue","attributes":{"opcode":"+"},"children":[{"kind":"DeclRefExpr","referenced":{"usr":"c:sum:left","name":"left"},"type":{"spelling":"int","canonical":"int"},"children":[]}]}]}]}]}],
                 "symbols":[{"id":"c:@F@sum#I#I#","kind":"FunctionDecl","name":"sum","scope":"","type":"int (int, int)","canonicalType":"int (int, int)","definition":true,"location":{"line":1,"column":1}}],
                 "diagnostics":[]}
                """;
        ClangSemanticModel model = decoder.decode(json).model();
        assertEquals(Language.C, model.language());
        assertEquals("c17", model.standard());
        assertEquals("clang test", model.compilerVersion());
        assertEquals(64, model.pointerWidth());
        assertEquals("x86_64-unknown-linux-gnu", model.targetTriple());
        assertEquals(64, model.pointerWidth());
        assertEquals("x86_64-unknown-linux-gnu", model.targetTriple());
        assertEquals(1, model.declarations().size());
        SemanticAstNode function = model.declarations().getFirst();
        assertEquals("int (int, int)", function.type());
        assertEquals(9, function.children().getFirst().location().orElseThrow().startColumn());
        SemanticAstNode reference = function.children().get(1).children().getFirst().children().getFirst().children().getFirst();
        assertEquals("c:sum:left", reference.referencedSymbolId());
        assertEquals("left", reference.referencedName());
        SemanticAstNode binary = function.children().get(1).children().getFirst().children().getFirst();
        assertEquals("+", binary.attributes().get("opcode"));
        assertEquals("true", function.attributes().get("definition"));
        assertTrue(model.symbols().get("c:@F@sum#I#I#").definition());
    }

    @Test void preservesCompilerErrorsAsLocatedStableDiagnostics() throws Exception {
        String json = """
                {"language":"CPP","standard":"c++23","compilerVersion":"clang test","targetTriple":"x86_64-unknown-linux-gnu","pointerWidth":64,"declarations":[],"symbols":[],
                 "diagnostics":[{"severity":"error","message":"expected ';'","location":{"line":4,"column":9}}]}
                """;
        var decoded = decoder.decode(json);
        assertEquals(Language.CPP, decoded.model().language());
        assertEquals("CB-CLANG-SEMANTIC-ERROR", decoded.diagnostics().getFirst().code());
        assertEquals(4, decoded.diagnostics().getFirst().location().orElseThrow().startLine());
    }

    @Test void rejectsMalformedFrontendDocuments() {
        assertThrows(java.io.IOException.class, () -> decoder.decode("{\"language\":\"C\"}"));
        assertThrows(java.io.IOException.class, () -> decoder.decode("{\"language\":\"Rust\",\"standard\":\"x\",\"declarations\":[],\"symbols\":[],\"diagnostics\":[]}"));
        assertThrows(java.io.IOException.class, () -> decoder.decode("{\"language\":\"C\",\"standard\":\"c17\",\"declarations\":{},\"symbols\":[],\"diagnostics\":[]}"));
    }
}
