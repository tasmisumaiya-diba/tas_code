package com.tascode.codebridge.frontend.clang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.language.Language;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Decodes the compact JSON document emitted by the bundled libclang exporter. */
public final class ClangAstJsonDecoder {
    private final ObjectMapper mapper = new ObjectMapper();

    public DecodedFrontendDocument decode(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isObject()) throw new IOException("Clang AST JSON root must be an object.");
        Language language;
        try { language = Language.valueOf(requiredText(root, "language")); }
        catch (IllegalArgumentException invalidLanguage) { throw new IOException("Clang AST JSON has an unknown language.", invalidLanguage); }
        String standard = requiredText(root, "standard");
        List<SemanticAstNode> declarations = new ArrayList<>();
        for (JsonNode node : requiredArray(root, "declarations")) declarations.add(node(node));
        Map<String, SemanticSymbol> symbols = new LinkedHashMap<>();
        for (JsonNode symbol : requiredArray(root, "symbols")) {
            String id = text(symbol, "id", "");
            if (!id.isBlank()) symbols.put(id, new SemanticSymbol(id, text(symbol, "kind", ""),
                    text(symbol, "name", ""), text(symbol, "scope", ""), text(symbol, "type", ""),
                    text(symbol, "canonicalType", ""), symbol.path("definition").asBoolean(false), span(symbol.path("location"))));
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (JsonNode diagnostic : requiredArray(root, "diagnostics")) {
            Severity severity = "error".equalsIgnoreCase(text(diagnostic, "severity", "error")) ? Severity.ERROR
                    : "warning".equalsIgnoreCase(text(diagnostic, "severity", "")) ? Severity.WARNING : Severity.INFO;
            String code = severity == Severity.ERROR ? "CB-CLANG-SEMANTIC-ERROR" : "CB-CLANG-SEMANTIC-WARNING";
            Optional<SourceSpan> location = span(diagnostic.path("location"));
            diagnostics.add(location.map(value -> Diagnostic.at(severity, code, text(diagnostic, "message", "Clang reported a diagnostic."), value))
                    .orElseGet(() -> Diagnostic.withoutLocation(severity, code, text(diagnostic, "message", "Clang reported a diagnostic."))));
        }
        return new DecodedFrontendDocument(new ClangSemanticModel(language, standard, text(root, "compilerVersion", ""), text(root, "targetTriple", ""), root.path("pointerWidth").asInt(0), declarations, symbols), diagnostics);
    }

    private SemanticAstNode node(JsonNode json) throws IOException {
        if (json == null || !json.isObject()) throw new IOException("Clang AST node must be an object.");
        if (!json.path("children").isArray()) throw new IOException("Clang AST node children must be an array.");
        String kind = requiredText(json, "kind");
        JsonNode type = json.path("type");
        Map<String, String> attributes = new LinkedHashMap<>();
        flattenAttributes(json.path("attributes"), "", attributes);
        json.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!List.of("kind", "name", "displayName", "usr", "type", "referenced", "location", "range", "children", "attributes").contains(key)) {
                JsonNode value = entry.getValue();
                if (value.isValueNode()) attributes.put(key, value.asText());
            }
        });
        List<SemanticAstNode> children = new ArrayList<>();
        for (JsonNode child : json.path("children")) children.add(node(child));
        JsonNode referenced = json.path("referenced");
        return new SemanticAstNode(kind, text(json, "name", ""),
                text(json, "displayName", ""), text(type, "spelling", ""), text(type, "canonical", ""),
                text(json, "valueCategory", ""), text(referenced, "usr", ""), text(referenced, "name", ""),
                span(json.path("location")), attributes, children);
    }

    private void flattenAttributes(JsonNode node, String prefix, Map<String, String> result) {
        if (!node.isObject()) return;
        node.fields().forEachRemaining(entry -> {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) flattenAttributes(value, key, result);
            else if (value.isValueNode()) result.put(key, value.asText());
        });
    }
    private Optional<SourceSpan> span(JsonNode node) {
        if (node.isMissingNode() || !node.hasNonNull("line") || !node.hasNonNull("column")) return Optional.empty();
        int line = Math.max(1, node.path("line").asInt(1));
        int column = Math.max(1, node.path("column").asInt(1));
        int endLine = Math.max(line, node.path("endLine").asInt(line));
        int endColumn = Math.max(1, node.path("endColumn").asInt(column));
        return Optional.of(new SourceSpan(line, column, endLine, endColumn));
    }

    private JsonNode requiredArray(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isArray()) throw new IOException("Clang AST JSON field '" + field + "' must be an array.");
        return value;
    }
    private String requiredText(JsonNode node, String field) throws IOException {
        if (!node.hasNonNull(field) || node.path(field).asText().isBlank()) throw new IOException("Clang AST JSON is missing '" + field + "'.");
        return node.path(field).asText();
    }
    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }

    public record DecodedFrontendDocument(ClangSemanticModel model, List<Diagnostic> diagnostics) {
        public DecodedFrontendDocument { diagnostics = List.copyOf(diagnostics); }
    }
}

