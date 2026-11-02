package com.tascode.codebridge;

import com.tascode.codebridge.web.CodeBridgeHttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeBridgeHttpSecurityTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test void uiUsesNonceCspAndDoesNotEnableCrossOriginAccess() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            var response = CLIENT.send(HttpRequest.newBuilder(uri(server, "/")).GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            String policy = response.headers().firstValue("Content-Security-Policy").orElseThrow();
            var nonceMatcher = Pattern.compile("script-src 'nonce-([^']+)'").matcher(policy);
            assertTrue(nonceMatcher.find(), policy);
            String nonce = nonceMatcher.group(1);
            assertTrue(policy.contains("style-src 'nonce-" + nonce + "'"), policy);
            assertTrue(policy.contains("frame-ancestors 'none'"), policy);
            assertTrue(response.body().contains("<script nonce=\"" + nonce + "\">"));
            assertTrue(response.body().contains("<style nonce=\"" + nonce + "\">"));
            assertTrue(response.body().contains("<summary>Advanced options</summary>"));
            String basicControls = response.body().substring(response.body().indexOf("<div class=\"controls\">"),
                    response.body().indexOf("<details class=\"advanced-options\">"));
            assertFalse(basicControls.contains("id=\"validate\""));
            assertFalse(basicControls.contains("id=\"clang-preflight\""));
            assertFalse(basicControls.contains("id=\"clang-lowering\""));
            assertFalse(basicControls.contains("id=\"verify-output\""));
            assertTrue(response.body().contains("id=\"program-input\""));
            assertCommonHeaders(response);
            assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
    }

    @Test void apiResponsesAreNotCachedAndMalformedMediaTypeIsRejected() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            var health = CLIENT.send(HttpRequest.newBuilder(uri(server, "/api/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertEquals("no-store", health.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
                    health.headers().firstValue("Content-Security-Policy").orElseThrow());
            assertCommonHeaders(health);

            var invalidRequest = HttpRequest.newBuilder(uri(server, "/api/convert"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            var rejected = CLIENT.send(invalidRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(415, rejected.statusCode());
            assertEquals("no-store", rejected.headers().firstValue("Cache-Control").orElseThrow());
            assertCommonHeaders(rejected);
        }
    }

    @Test void apiRoutesRejectUnregisteredPathSuffixes() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            var health = CLIENT.send(HttpRequest.newBuilder(uri(server, "/api/health/details")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var convert = CLIENT.send(HttpRequest.newBuilder(uri(server, "/api/convert/extra"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(404, health.statusCode());
            assertEquals(404, convert.statusCode());
            assertTrue(health.body().contains("not found"));
            assertTrue(convert.body().contains("not found"));
            assertCommonHeaders(health);
            assertCommonHeaders(convert);
        }
    }

    @Test void apiRejectsDeclaredOversizeBodiesBeforeParsing() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            byte[] body = " ".repeat(1_000_001).getBytes(StandardCharsets.UTF_8);
            var response = CLIENT.send(HttpRequest.newBuilder(uri(server, "/api/convert"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                            .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(413, response.statusCode());
            assertTrue(response.body().contains("request too large"));
            assertCommonHeaders(response);
        }
    }

    @Test void apiRejectsMalformedUtf8InsteadOfSilentlyReplacingSourceBytes() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            byte[] malformedJson = {'{', '"', 'x', '"', ':', '"', (byte) 0xC3, '"', '}'};
            for (String path : new String[]{"/api/convert", "/api/analyze"}) {
                var response = CLIENT.send(HttpRequest.newBuilder(uri(server, path))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(malformedJson))
                                .build(), HttpResponse.BodyHandlers.ofString());

                assertEquals(400, response.statusCode(), path);
                assertTrue(response.body().contains("valid UTF-8"), response.body());
                assertCommonHeaders(response);
            }
        }
    }

    private static URI uri(CodeBridgeHttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private static void assertCommonHeaders(HttpResponse<?> response) {
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElseThrow());
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
        assertEquals("camera=(), microphone=(), geolocation=()",
                response.headers().firstValue("Permissions-Policy").orElseThrow());
    }
}
