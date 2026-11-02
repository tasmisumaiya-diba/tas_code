package com.tascode.codebridge.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tascode.codebridge.CodeBridge;
import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.pipeline.ConversionPipeline;
import com.tascode.codebridge.pipeline.ConversionResult;
import com.tascode.codebridge.validation.CompilationResult;
import com.tascode.codebridge.validation.CompilerValidationService;
import com.tascode.codebridge.frontend.clang.ClangSemanticFrontend;
import com.tascode.codebridge.frontend.clang.ClangIrLowerer;
import com.tascode.codebridge.frontend.clang.ClangSemanticModel;
import com.tascode.codebridge.validation.DockerSandboxExecutionService;
import com.tascode.codebridge.validation.OutputComparisonResult;
import com.tascode.codebridge.validation.OutputEquivalenceService;
import com.tascode.codebridge.validation.SandboxExecutionResult;
import com.tascode.codebridge.validation.ValidatedConversionService;
import com.tascode.codebridge.preprocess.CPreprocessor;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local HTTP API and static UI for CodeBridge. */
public final class CodeBridgeHttpServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 1_000_000;
    private static final SecureRandom CSP_NONCE_GENERATOR = new SecureRandom();
    private final HttpServer server;
    private final ConversionPipeline pipeline;
    private final ValidatedConversionService validated;
    private final CompilerValidationService compilerValidator;
    private final OutputEquivalenceService equivalence;
    private final ClangSemanticFrontend clangFrontend = new ClangSemanticFrontend();
    private final ClangIrLowerer clangIrLowerer = new ClangIrLowerer();

    public CodeBridgeHttpServer(InetSocketAddress address) throws IOException {
        this(address, CodeBridge.createPipeline(), new CompilerValidationService());
    }

    CodeBridgeHttpServer(InetSocketAddress address, ConversionPipeline pipeline, CompilerValidationService validator) throws IOException {
        this.server = createBoundServer(address);
        this.pipeline = pipeline;
        this.validated = new ValidatedConversionService(pipeline, validator);
        this.compilerValidator = validator;
        this.equivalence = new OutputEquivalenceService(new DockerSandboxExecutionService());
        server.createContext("/api/health", this::health);
        server.createContext("/api/convert", this::convert);
        server.createContext("/api/analyze", this::analyze);
        server.createContext("/", this::index);
        server.setExecutor(new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), task -> {
                    Thread thread = new Thread(task, "codebridge-http");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.CallerRunsPolicy()));
    }

    private static HttpServer createBoundServer(InetSocketAddress address) throws IOException {
        try {
            return HttpServer.create(address, 32);
        } catch (BindException occupied) {
            BindException actionable = new BindException("Cannot start CodeBridge at " + address.getHostString() + ":"
                    + address.getPort() + "; the address may already be in use or unavailable.");
            actionable.initCause(occupied);
            throw actionable;
        }
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }

    public static void main(String[] args) throws IOException {
        ServerConfiguration configuration = ServerConfiguration.from(args, System.getenv());
        CodeBridgeHttpServer app = new CodeBridgeHttpServer(configuration.socketAddress());
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        app.start();
        if (!configuration.socketAddress().getAddress().isLoopbackAddress()) {
            System.err.println("WARNING: CodeBridge is listening on a non-loopback address. "
                    + "The HTTP server has no authentication or TLS; restrict access with a trusted network/firewall and reverse proxy.");
        }
        System.out.println("CodeBridge listening on " + configuration.bindAddress() + ":" + app.port());
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/api/health")) { respond(exchange, 404, "{\"error\":\"not found\"}"); return; }
        if (!exchange.getRequestMethod().equals("GET")) { respond(exchange, 405, "{\"error\":\"method not allowed\"}"); return; }
        respond(exchange, 200, "{\"status\":\"ok\",\"version\":" + quote(CodeBridge.version()) + "}");
    }

    private void index(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().equals("/")) {
            respond(exchange, 404, "{\"error\":\"not found\"}"); return;
        }
        try (var stream = getClass().getResourceAsStream("/web/index.html")) {
            if (stream == null) { respond(exchange, 404, "UI not packaged"); return; }
            String nonce = createNonce();
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("<style>", "<style nonce=\"" + nonce + "\">")
                    .replace("<script>", "<script nonce=\"" + nonce + "\">");
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            setCommonSecurityHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce
                    + "'; style-src 'nonce-" + nonce + "'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }
    }

    private void analyze(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/api/analyze")) { respond(exchange, 404, "{\"error\":\"not found\"}"); return; }
        if (!exchange.getRequestMethod().equals("POST")) { respond(exchange, 405, "{\"error\":\"method not allowed\"}"); return; }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            respond(exchange, 415, "{\"error\":\"content type must be application/json\"}"); return;
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) { respond(exchange, 413, "{\"error\":\"request too large\"}"); return; }
        try {
            Map<String, Object> request = JsonObject.parse(new String(bytes, StandardCharsets.UTF_8));
            Language language = Language.fromId(requiredString(request, "language"));
            String source = requiredString(request, "source");
            if (language != Language.C && language != Language.CPP) throw new IllegalArgumentException("language must be c or cpp");
            for (String key : request.keySet()) if (!List.of("language", "source").contains(key)) throw new IllegalArgumentException("Unknown field: " + key);
            var result = clangFrontend.analyze(language, source);
            int status = result.successful() ? 200 : result.diagnostics().stream().anyMatch(d -> d.code().startsWith("CB-CLANG-DOCKER") || d.code().equals("CB-CLANG-IMAGE-UNAVAILABLE") || d.code().equals("CB-CLANG-TIMEOUT")) ? 503 : 422;
            respond(exchange, status, clangFrontend.encode(result));
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, "{\"error\":" + quote(exception.getMessage()) + "}");
        }
    }
    private void convert(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/api/convert")) { respond(exchange, 404, "{\"error\":\"not found\"}"); return; }
        if (!exchange.getRequestMethod().equals("POST")) {
            respond(exchange, 405, "{\"error\":\"method not allowed\"}"); return; }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            respond(exchange, 415, "{\"error\":\"content type must be application/json\"}"); return;
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) { respond(exchange, 413, "{\"error\":\"request too large\"}"); return; }
        try {
            Map<String, Object> request = JsonObject.parse(new String(bytes, StandardCharsets.UTF_8));
            Language from = Language.fromId(requiredString(request, "from"));
            Language to = Language.fromId(requiredString(request, "to"));
            String source = requiredString(request, "source");
            for (String key : request.keySet()) {
                if (!List.of("from", "to", "source", "validate", "verifyOutput", "input", "clangPreflight", "clangLowering").contains(key)) {
                    throw new IllegalArgumentException("Unknown field: " + key);
                }
            }
            boolean validate = optionalBoolean(request, "validate", false);
            boolean verifyOutput = optionalBoolean(request, "verifyOutput", false);
            boolean clangPreflight = optionalBoolean(request, "clangPreflight", false);
            boolean clangLowering = optionalBoolean(request, "clangLowering", false);
            String input = optionalString(request, "input", "");
            if ((clangPreflight || clangLowering) && from != Language.C && from != Language.CPP) {
                throw new IllegalArgumentException("Clang analysis and AST conversion can only be used with C or C++ source.");
            }
            ConversionResult conversion;
            CompilationResult compilation = null;
            List<Diagnostic> clangDiagnostics = List.of();
            boolean clangAccepted = true;
            ClangSemanticModel clangModel = null;
            if (clangPreflight || clangLowering) {
                var semantic = clangFrontend.analyze(from, source);
                clangDiagnostics = semantic.diagnostics();
                clangAccepted = semantic.successful();
                clangModel = semantic.model().orElse(null);
            }
            if (!clangAccepted) {
                conversion = new ConversionResult(from, to, java.util.Optional.empty(), clangDiagnostics);
            } else if (clangLowering) {
                var preprocessed = new CPreprocessor().process(from, source);
                if (!preprocessed.successful()) {
                    conversion = new ConversionResult(from, to, java.util.Optional.empty(), preprocessed.diagnostics());
                } else {
                    var lowering = clangIrLowerer.lower(clangModel, preprocessed.source(), preprocessed.includes());
                    if (!lowering.successful()) {
                        conversion = new ConversionResult(from, to, java.util.Optional.empty(), lowering.diagnostics());
                    } else {
                        conversion = pipeline.convertIr(from, to, lowering.program());
                        if (validate && conversion.successful()) {
                            compilation = compilerValidator.validate(to, conversion.output().orElseThrow());
                        }
                    }
                }
            } else if (validate) {
                var validatedResult = validated.convert(from, to, source);
                conversion = validatedResult.conversion();
                compilation = validatedResult.validation().orElse(null);
            } else {
                conversion = pipeline.convert(from, to, source);
            }
            if (clangAccepted && !clangDiagnostics.isEmpty()) {
                List<Diagnostic> combined = new ArrayList<>(clangDiagnostics);
                combined.addAll(conversion.diagnostics());
                conversion = new ConversionResult(from, to, conversion.output(), combined);
            }
            OutputComparisonResult comparison = null;
            if (verifyOutput) {
                comparison = conversion.successful()
                        ? equivalence.compare(from, source, to, conversion.output().orElseThrow(), input)
                        : new OutputComparisonResult(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                                "Conversion must succeed before output comparison can run.");
            }
            respond(exchange, 200, encode(conversion, compilation, comparison));
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, "{\"error\":" + quote(exception.getMessage()) + "}");
        } catch (RuntimeException exception) {
            respond(exchange, 500, "{\"error\":\"conversion failed\"}");
        }
    }
    private static String requiredString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String string) || string.isBlank()) throw new IllegalArgumentException("Field '" + key + "' is required.");
        return string;
    }

    private static String optionalString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof String string) return string;
        throw new IllegalArgumentException("Field '" + key + "' must be a string.");
    }
    private static boolean optionalBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean bool) return bool;
        throw new IllegalArgumentException("Field '" + key + "' must be a boolean.");
    }
    private static String encode(ConversionResult result, CompilationResult validation, OutputComparisonResult comparison) {
        StringBuilder json = new StringBuilder("{\"from\":").append(quote(result.from().id()))
                .append(",\"to\":").append(quote(result.to().id())).append(",\"success\":").append(result.successful() && (validation == null || validation.successful()))
                .append(",\"output\":").append(result.output().map(CodeBridgeHttpServer::quote).orElse("null"))
                .append(",\"diagnostics\":[");
        List<Diagnostic> diagnostics = new ArrayList<>(result.diagnostics());
        if (validation != null) diagnostics.addAll(validation.diagnostics());
        for (int i = 0; i < diagnostics.size(); i++) {
            if (i > 0) json.append(',');
            Diagnostic diagnostic = diagnostics.get(i);
            json.append("{\"severity\":").append(quote(diagnostic.severity().name()))
                    .append(",\"code\":").append(quote(diagnostic.code()))
                    .append(",\"message\":").append(quote(diagnostic.message()));
            diagnostic.location().ifPresent(location -> json.append(",\"location\":{\"startLine\":").append(location.startLine())
                    .append(",\"startColumn\":").append(location.startColumn()).append('}'));
            json.append('}');
        }
        json.append(']');
        if (validation != null) json.append(",\"validation\":").append(quote(validation.status().name()));
        if (comparison != null) json.append(",\"outputComparison\":").append(comparisonJson(comparison));
        return json.append('}').toString();
    }

    private static String comparisonJson(OutputComparisonResult comparison) {
        return "{\"matches\":" + comparison.matches().map(Object::toString).orElse("null")
                + ",\"message\":" + quote(comparison.message())
                + ",\"source\":" + comparison.sourceExecution().map(CodeBridgeHttpServer::executionJson).orElse("null")
                + ",\"target\":" + comparison.targetExecution().map(CodeBridgeHttpServer::executionJson).orElse("null") + "}";
    }

    private static String executionJson(SandboxExecutionResult execution) {
        return "{\"language\":" + quote(execution.language().id())
                + ",\"status\":" + quote(execution.status().name())
                + ",\"exitCode\":" + execution.exitCode().map(Object::toString).orElse("null")
                + ",\"stdout\":" + quote(execution.stdout())
                + ",\"stderr\":" + quote(execution.stderr())
                + ",\"message\":" + quote(execution.message()) + "}";
    }
    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) switch (c) {
            case '"' -> result.append("\\\""); case '\\' -> result.append("\\\\"); case '\b' -> result.append("\\b");
            case '\f' -> result.append("\\f"); case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r");
            case '\t' -> result.append("\\t"); default -> { if (c < 0x20) result.append(String.format("\\u%04x", (int)c)); else result.append(c); }
        }
        return result.append('"').toString();
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        setCommonSecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static void setCommonSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private static String createNonce() {
        byte[] random = new byte[16];
        CSP_NONCE_GENERATOR.nextBytes(random);
        return Base64.getEncoder().withoutPadding().encodeToString(random);
    }

    private static final class JsonObject {
        private final String input; private int at;
        private JsonObject(String input) { this.input = input; }
        static Map<String, Object> parse(String input) {
            JsonObject parser = new JsonObject(input); parser.space(); parser.expect('{');
            Map<String, Object> values = new LinkedHashMap<>(); parser.space();
            if (parser.take('}')) return values;
            do {
                parser.space(); String key = parser.string(); parser.space(); parser.expect(':'); parser.space();
                Object value = parser.peek() == '"' ? parser.string() : parser.bool();
                if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate JSON field: " + key);
                parser.space(); if (parser.take('}')) break; parser.expect(',');
            } while (true);
            parser.space(); if (parser.at != input.length()) throw new IllegalArgumentException("Unexpected text after JSON object.");
            return values;
        }
        private Object bool() { if (input.startsWith("true", at)) { at += 4; return true; } if (input.startsWith("false", at)) { at += 5; return false; } throw new IllegalArgumentException("Expected a string or boolean JSON value."); }
        private String string() {
            expect('"'); StringBuilder value = new StringBuilder();
            while (at < input.length()) {
                char c = input.charAt(at++); if (c == '"') return value.toString();
                if (c != '\\') { if (c < 0x20) throw new IllegalArgumentException("Unescaped control character in JSON string."); value.append(c); continue; }
                if (at >= input.length()) break;
                char escape = input.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> value.append(escape); case 'b' -> value.append('\b'); case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n'); case 'r' -> value.append('\r'); case 't' -> value.append('\t');
                    case 'u' -> { if (at + 4 > input.length()) throw new IllegalArgumentException("Invalid Unicode escape."); try { value.append((char)Integer.parseInt(input.substring(at, at + 4), 16)); } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid Unicode escape."); } at += 4; }
                    default -> throw new IllegalArgumentException("Invalid JSON escape.");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string.");
        }
        private char peek() { return at < input.length() ? input.charAt(at) : 0; }
        private void space() { while (at < input.length() && Character.isWhitespace(input.charAt(at))) at++; }
        private boolean take(char expected) { if (peek() != expected) return false; at++; return true; }
        private void expect(char expected) { if (!take(expected)) throw new IllegalArgumentException("Malformed JSON request."); }
    }
}
