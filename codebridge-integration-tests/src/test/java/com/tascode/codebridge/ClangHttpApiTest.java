package com.tascode.codebridge;

import com.tascode.codebridge.web.CodeBridgeHttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClangHttpApiTest {
    @Test void clangLoweringOptionRejectsNonNativeSourceWithActionableBadRequest() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            String requestBody = "{\"from\":\"java\",\"to\":\"cpp\",\"source\":\"class Example {}\",\"clangLowering\":true}";
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/convert"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("only be used with C or C++"), response.body());
        }
    }

    @Test void unsupportedConversionIsReturnedAsFailureWithoutGeneratedCode() throws Exception {
        try (var server = new CodeBridgeHttpServer(new InetSocketAddress("127.0.0.1", 0))) {
            server.start();
            String requestBody = "{\"from\":\"cpp\",\"to\":\"java\",\"source\":\"class Counter : public Base { };\"}";
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/convert"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"success\":false"), response.body());
            assertTrue(response.body().contains("\"output\":null"), response.body());
            assertTrue(response.body().contains("CB-CPP-INHERITANCE-UNSUPPORTED"), response.body());
        }
    }
}
