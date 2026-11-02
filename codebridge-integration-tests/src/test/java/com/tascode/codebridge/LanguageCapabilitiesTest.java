package com.tascode.codebridge;

import com.tascode.codebridge.capability.IrFeatureAnalyzer;
import com.tascode.codebridge.capability.LanguageCapabilities;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageModule;
import com.tascode.codebridge.language.c.CLanguageModule;
import com.tascode.codebridge.language.cpp.CppLanguageModule;
import com.tascode.codebridge.language.java.JavaLanguageModule;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageCapabilitiesTest {
    @Test
    void backendCapabilitiesAreLoadedFromTheirOwnDataFiles() {
        var c = new CLanguageModule().capabilities();
        var cpp = new CppLanguageModule().capabilities();
        var java = new JavaLanguageModule().capabilities();

        assertTrue(c.supports("functions"));
        assertFalse(c.supports("strings"));
        assertTrue(cpp.supports("strings"));
        assertTrue(java.supports("strings"));
        assertFalse(cpp.supportedFeatures().isEmpty());
        assertFalse(java.supportedFeatures().isEmpty());
    }

    @Test
    void irFeatureScanRequiresStringsForStringValuesButNotConsoleFormatLiterals() {
        var javaParse = new JavaLanguageModule().parser().parse(
                "class Example { static String echo(String value) { return value; } }");
        assertTrue(javaParse.successful());
        assertTrue(new IrFeatureAnalyzer().requiredFeatures(javaParse.root()).contains("strings"));

        var cParse = new CLanguageModule().parser().parse("void show() { printf(\"ready\"); }");
        assertTrue(cParse.successful());
        assertFalse(new IrFeatureAnalyzer().requiredFeatures(cParse.root()).contains("strings"));
    }

    @Test
    void pipelineRejectsAStringFeatureMissingFromTheTargetDeclaration() {
        var cpp = new CppLanguageModule();
        LanguageModule cppWithoutStrings = new LanguageModule() {
            @Override public Language language() { return Language.CPP; }
            @Override public com.tascode.codebridge.language.LanguageParser parser() { return cpp.parser(); }
            @Override public com.tascode.codebridge.language.LanguageGenerator generator() { return cpp.generator(); }
            @Override public LanguageCapabilities capabilities() {
                return new LanguageCapabilities(Set.of("functions", "variables", "calls", "return", "blocks"));
            }
        };
        var pipeline = CodeBridge.createPipeline(List.of(new JavaLanguageModule(), cppWithoutStrings), List.of());

        var result = pipeline.convert(Language.JAVA, Language.CPP,
                "class Example { static String echo(String value) { return value; } }");

        assertFalse(result.successful());
        assertEquals("CB-UNSUP-FEATURE", result.diagnostics().getFirst().code());
        assertTrue(result.diagnostics().getFirst().message().contains("strings"));
    }
}
