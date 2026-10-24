package com.tascode.codebridge;

import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageModule;
import com.tascode.codebridge.pipeline.ConversionPipeline;
import com.tascode.codebridge.pipeline.ConversionResult;
import com.tascode.codebridge.rules.ConversionRule;
import com.tascode.codebridge.rules.ConversionRuleProvider;
import java.util.List;
import java.util.ServiceLoader;

/** Public entry points for the CodeBridge conversion engine. */
public final class CodeBridge {
    public static final String VERSION = "0.1.0-SNAPSHOT";
    private CodeBridge() { }
    public static String version() { return VERSION; }

    /** Discovers the language modules present on the application's classpath. */
    public static ConversionPipeline createPipeline() {
        List<LanguageModule> modules = ServiceLoader.load(LanguageModule.class).stream().map(ServiceLoader.Provider::get).toList();
        List<ConversionRule> rules = ServiceLoader.load(ConversionRuleProvider.class).stream()
                .map(ServiceLoader.Provider::get).flatMap(provider -> provider.rules().stream()).toList();
        return new ConversionPipeline(modules, rules);
    }

    public static ConversionPipeline createPipeline(List<LanguageModule> modules, List<ConversionRule> rules) {
        return new ConversionPipeline(modules, rules);
    }

    public static ConversionResult convert(Language from, Language to, String source) {
        return createPipeline().convert(from, to, source);
    }
}
