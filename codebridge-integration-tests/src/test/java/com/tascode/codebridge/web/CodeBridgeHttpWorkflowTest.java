package com.tascode.codebridge.web;

import com.tascode.codebridge.CodeBridge;
import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.validation.CompilationResult;
import com.tascode.codebridge.validation.CompilerValidationService;
import com.tascode.codebridge.validation.DockerSandboxExecutionService;
import com.tascode.codebridge.validation.OutputEquivalenceService;
import com.tascode.codebridge.validation.ValidationStatus;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeBridgeHttpWorkflowTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test void containerHealthProbeAcceptsAHealthyServer() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            HealthProbe.main(new String[]{Integer.toString(server.port())});
        }
    }

    @Test void validationWarningsAreReturnedAndRemainVisibleToTheUi() throws Exception {
        var validator = (com.tascode.codebridge.validation.CompilationValidator) (language, source) ->
                new CompilationResult(language, ValidationStatus.VALID, List.of(Diagnostic.withoutLocation(
                        Severity.WARNING, "CB-TEST-COMPILER-WARNING", "Representative compiler warning.")));
        var equivalence = new OutputEquivalenceService(new DockerSandboxExecutionService());
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0), CodeBridge.createPipeline(),
                validator, equivalence, null)) {
            server.start();
            var response = post(server, "{\"from\":\"c\",\"to\":\"java\",\"source\":\"int value() { return 42; }\",\"validate\":true}");
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"success\":true"), response.body());
            assertTrue(response.body().contains("\"validation\":\"VALID\""), response.body());
            assertTrue(response.body().contains("\"severity\":\"WARNING\""), response.body());
            assertTrue(response.body().contains("CB-TEST-COMPILER-WARNING"), response.body());
        }
    }

    @Test void failedOutputComparisonIsReturnedAsInconclusiveInsteadOfSuccess() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            var response = post(server, "{\"from\":\"c\",\"to\":\"java\",\"source\":\"int add(int a, int b) { return a + b; }\",\"verifyOutput\":true}");
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"success\":true"), response.body());
            assertTrue(response.body().contains("\"outputComparison\":{\"matches\":null"), response.body());
            assertTrue(response.body().contains("Source program could not be executed:"), response.body());
        }
    }

    @Test void malformedApiJsonReturnsAnActionableBadRequest() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            var response = post(server, "{\"from\":\"c\",\"to\":\"java\",\"source\":");
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"error\":"), response.body());
        }
    }

    private static HttpResponse<String> post(CodeBridgeHttpServer server, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/convert"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
