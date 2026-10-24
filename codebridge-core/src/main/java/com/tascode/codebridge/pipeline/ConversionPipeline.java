package com.tascode.codebridge.pipeline;

import com.tascode.codebridge.capability.IrFeatureAnalyzer;
import com.tascode.codebridge.analysis.SymbolAnalyzer;
import com.tascode.codebridge.analysis.TypeAnalyzer;
import com.tascode.codebridge.analysis.FunctionCallAnalyzer;
import com.tascode.codebridge.analysis.ConditionTypeAnalyzer;
import com.tascode.codebridge.analysis.ScanfAnalyzer;
import com.tascode.codebridge.analysis.CoutAnalyzer;
import com.tascode.codebridge.analysis.StringFeatureAnalyzer;
import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageModule;
import com.tascode.codebridge.language.LanguageParser;
import com.tascode.codebridge.language.LanguageGenerator;
import com.tascode.codebridge.ir.IrNode;
import com.tascode.codebridge.ir.Program;
import com.tascode.codebridge.preprocess.CPreprocessor;
import com.tascode.codebridge.preprocess.PreprocessResult;
import com.tascode.codebridge.rules.ConversionRule;
import com.tascode.codebridge.rules.RuleContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Runs deterministic parse, capability-check, transform, and generation stages. */
public final class ConversionPipeline {
    private final Map<Language, LanguageModule> modules;
    private final List<ConversionRule> rules;
    private final IrFeatureAnalyzer featureAnalyzer = new IrFeatureAnalyzer();
    private final SymbolAnalyzer symbolAnalyzer = new SymbolAnalyzer();
    private final TypeAnalyzer typeAnalyzer = new TypeAnalyzer();
    private final FunctionCallAnalyzer functionCallAnalyzer = new FunctionCallAnalyzer();
    private final ConditionTypeAnalyzer conditionTypeAnalyzer = new ConditionTypeAnalyzer();
    private final ScanfAnalyzer scanfAnalyzer = new ScanfAnalyzer();
    private final CoutAnalyzer coutAnalyzer = new CoutAnalyzer();
    private final StringFeatureAnalyzer stringFeatureAnalyzer = new StringFeatureAnalyzer();
    private final CPreprocessor preprocessor = new CPreprocessor();

    public ConversionPipeline(List<LanguageModule> modules, List<ConversionRule> rules) {
        Objects.requireNonNull(modules); Objects.requireNonNull(rules);
        this.modules = modules.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(LanguageModule::language, UnaryOperator.identity()));
        this.rules = rules.stream().sorted(Comparator.comparingInt(ConversionRule::priority).reversed()).toList();
    }

    public ConversionResult convert(Language from, Language to, String source) {
        Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(source);
        List<Diagnostic> diagnostics = new ArrayList<>();
        LanguageModule sourceModule = modules.get(from), targetModule = modules.get(to);
        if (sourceModule == null || targetModule == null) {
            diagnostics.add(Diagnostic.withoutLocation(Severity.ERROR, "CB-LANG-NOT-REGISTERED", "A parser or generator for the selected language is not registered."));
            return new ConversionResult(from, to, Optional.empty(), diagnostics);
        }
        PreprocessResult preprocessed = preprocessor.process(from, source);
        diagnostics.addAll(preprocessed.diagnostics());
        if (!preprocessed.successful()) return new ConversionResult(from, to, Optional.empty(), diagnostics);
        LanguageParser.ParseResult parsed = sourceModule.parser().parse(preprocessed.source());
        diagnostics.addAll(parsed.diagnostics());
        if (!parsed.successful()) return new ConversionResult(from, to, Optional.empty(), diagnostics);
        IrNode root = parsed.root();
        if (root instanceof Program program && !preprocessed.includes().isEmpty()) {
            root = new Program(program.irVersion(), program.sourceSpan(), program.functions(), preprocessed.includes());
        }
        if (root instanceof Program program) {
            diagnostics.addAll(symbolAnalyzer.analyze(program));
            diagnostics.addAll(typeAnalyzer.analyze(program, from, to));
            diagnostics.addAll(functionCallAnalyzer.analyze(program, from, to));
            diagnostics.addAll(conditionTypeAnalyzer.analyze(program, from, to));
            diagnostics.addAll(scanfAnalyzer.analyze(program, from, to));
            diagnostics.addAll(coutAnalyzer.analyze(program, from));
            diagnostics.addAll(stringFeatureAnalyzer.analyze(program, to));
        }
        if (!diagnostics.isEmpty()) return new ConversionResult(from, to, Optional.empty(), diagnostics);
        for (String feature : featureAnalyzer.requiredFeatures(root)) {
            if (!targetModule.capabilities().supports(feature)) {
                diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-UNSUP-FEATURE",
                        "The target language does not support the required feature '" + feature + "'.", root.sourceSpan()));
            }
        }
        if (!diagnostics.isEmpty()) return new ConversionResult(from, to, Optional.empty(), diagnostics);
        RuleContext context = new RuleContext(from, to);
        try {
            for (ConversionRule rule : rules) if (rule.appliesTo(from, to, root)) root = Objects.requireNonNull(rule.rewrite(root, context), "Rule returned null IR");
        } catch (RuntimeException exception) {
            diagnostics.add(Diagnostic.withoutLocation(Severity.ERROR, "CB-RULE-FAILED", "A conversion rule could not transform this program: " + exception.getMessage()));
            return new ConversionResult(from, to, Optional.empty(), diagnostics);
        }
        LanguageGenerator.GenerationResult generated = targetModule.generator().generate(root);
        diagnostics.addAll(generated.diagnostics());
        return new ConversionResult(from, to, generated.successful() ? Optional.of(generated.source()) : Optional.empty(), diagnostics);
    }
}
