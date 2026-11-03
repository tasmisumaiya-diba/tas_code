package com.tascode.codebridge;

import com.tascode.codebridge.web.CodeBridgeHttpServer;
import com.tascode.codebridge.web.ServerConfiguration;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeBridgeHttpAuthTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String TOKEN = "correct-horse-battery-staple";
    private static final String BODY = "{\"from\":\"c\",\"to\":\"java\",\"source\":\"int main(void){return 0;}\"}";

    @Test void apiIsOpenWhenNoTokenIsConfigured() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            assertEquals(200, post(server, "/api/convert", null).statusCode());
            var health = CLIENT.send(HttpRequest.newBuilder(uri(server, "/api/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(health.body().contains("\"authenticationRequired\":false"), health.body());
        }
    }

    @Test void apiRejectsMissingOrWrongTokenAndAcceptsTheRightOne() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0), TOKEN)) {
            server.start();
            for (String path : new String[]{"/api/convert", "/api/analyze"}) {
                var missing = post(server, path, null);
                assertEquals(401, missing.statusCode(), path);
                assertEquals("Bearer realm=\"codebridge\"", missing.headers().firstValue("WWW-Authenticate").orElseThrow());
                assertEquals(401, post(server, path, "Bearer wrong-token-value-123").statusCode(), path);
                assertEquals(401, post(server, path, "Basic " + TOKEN).statusCode(), path);
                assertEquals(401, post(server, path, "Bearer ").statusCode(), path);
            }
            assertEquals(200, post(server, "/api/convert", "Bearer " + TOKEN).statusCode());
            assertEquals(200, post(server, "/api/convert", "bearer " + TOKEN).statusCode());
            assertNotEquals(401, post(server, "/api/analyze", "Bearer " + TOKEN).statusCode());
        }
    }

    @Test void healthCheckAndUiStayPublicSoContainerProbesAndLoginWork() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0), TOKEN)) {
            server.start();
            for (String path : new String[]{"/api/health", "/"}) {
                var response = CLIENT.send(HttpRequest.newBuilder(uri(server, path)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                if (path.equals("/api/health")) assertTrue(response.body().contains("\"authenticationRequired\":true"));
            }
            var ui = CLIENT.send(HttpRequest.newBuilder(uri(server, "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(ui.body().contains("id=\"api-token\""));
            assertFalse(ui.body().contains(TOKEN));
        }
    }

    @Test void configurationReadsTokenFromEnvironmentAndNeverPrintsIt() {
        var configuration = ServerConfiguration.from(new String[0], Map.of(ServerConfiguration.AUTH_TOKEN_ENV, TOKEN));
        assertTrue(configuration.authenticationEnabled());
        assertEquals(TOKEN, configuration.authToken());
        assertFalse(configuration.toString().contains(TOKEN));

        var disabled = ServerConfiguration.from(new String[0], Map.of());
        assertFalse(disabled.authenticationEnabled());
        assertFalse(ServerConfiguration.from(new String[0], Map.of(ServerConfiguration.AUTH_TOKEN_ENV, "")).authenticationEnabled());
    }

    @Test void configurationRejectsWeakOrPaddedTokens() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfiguration.from(new String[0],
                Map.of(ServerConfiguration.AUTH_TOKEN_ENV, "short")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfiguration.from(new String[0],
                Map.of(ServerConfiguration.AUTH_TOKEN_ENV, " " + TOKEN)));
        assertThrows(IllegalArgumentException.class, () -> ServerConfiguration.from(new String[0],
                Map.of(ServerConfiguration.AUTH_TOKEN_ENV, "non-ascii-টোকেন-value")));
    }

    @Test void directServerConfigurationRejectsWeakTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0), "short"));
    }

    private static HttpResponse<String> post(CodeBridgeHttpServer server, String path, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(uri(server, path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY));
        if (authorization != null) request.header("Authorization", authorization);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(CodeBridgeHttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
