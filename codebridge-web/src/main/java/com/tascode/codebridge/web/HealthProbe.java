package com.tascode.codebridge.web;

import java.net.HttpURLConnection;
import java.net.URI;

/** Container health-check entry point; health deliberately does not require API authentication. */
public final class HealthProbe {
    private HealthProbe() { }

    public static void main(String[] arguments) throws Exception {
        int port = arguments.length == 0 ? ServerConfiguration.DEFAULT_PORT : Integer.parseInt(arguments[0]);
        if (arguments.length > 1 || port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Expected at most one valid health-check port.");
        }

        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/health").toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        connection.setRequestMethod("GET");
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("CodeBridge health route is not ready.");
            String body = new String(connection.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!body.contains("\"status\":\"ok\"")) throw new IllegalStateException("CodeBridge health response was invalid.");
        } finally {
            connection.disconnect();
        }
    }
}
