package com.tascode.codebridge;

import com.tascode.codebridge.web.ServerConfiguration;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigurationTest {
    @Test void defaultsToLoopbackForDevelopment() {
        var configuration = ServerConfiguration.from(new String[0], Map.of());

        assertEquals("127.0.0.1", configuration.bindAddress());
        assertEquals(8080, configuration.port());
        assertTrue(configuration.socketAddress().getAddress().isLoopbackAddress());
    }

    @Test void environmentConfiguresProductionBindAndPort() {
        var configuration = ServerConfiguration.from(new String[0], Map.of(
                ServerConfiguration.BIND_ADDRESS_ENV, "0.0.0.0",
                ServerConfiguration.PORT_ENV, "9000"));

        assertEquals("0.0.0.0", configuration.bindAddress());
        assertEquals(9000, configuration.port());
        assertFalse(configuration.socketAddress().getAddress().isLoopbackAddress());
    }

    @Test void commandLinePortOverridesEnvironmentPort() {
        var configuration = ServerConfiguration.from(new String[]{"9090"}, Map.of(
                ServerConfiguration.BIND_ADDRESS_ENV, "127.0.0.1",
                ServerConfiguration.PORT_ENV, "9000"));

        assertEquals(9090, configuration.port());
    }

    @Test void invalidPortIsRejectedBeforeBinding() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfiguration.from(new String[0],
                Map.of(ServerConfiguration.PORT_ENV, "70000")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfiguration.from(new String[0],
                Map.of(ServerConfiguration.PORT_ENV, "not-a-port")));
    }

    @Test void configuredLoopbackAddressServesHealthEndpoint() throws Exception {
        var configuration = ServerConfiguration.from(new String[0], Map.of(
                ServerConfiguration.BIND_ADDRESS_ENV, "127.0.0.1",
                ServerConfiguration.PORT_ENV, "0"));
        try (var server = new com.tascode.codebridge.web.CodeBridgeHttpServer(configuration.socketAddress())) {
            server.start();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/health")).GET().build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"ok\""), response.body());
        }
    }

    @Test void occupiedPortProducesActionableBindMessage() throws Exception {
        try (var occupied = new ServerSocket(0)) {
            var address = new java.net.InetSocketAddress("127.0.0.1", occupied.getLocalPort());
            var failure = assertThrows(java.net.BindException.class,
                    () -> new com.tascode.codebridge.web.CodeBridgeHttpServer(address));

            assertTrue(failure.getMessage().contains("127.0.0.1:" + occupied.getLocalPort()), failure.getMessage());
            assertTrue(failure.getMessage().contains("already be in use"), failure.getMessage());
        }
    }
}
