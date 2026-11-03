package com.tascode.codebridge.web;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime settings. Defaults intentionally expose the local development server only, without authentication.
 * {@code authToken} is {@code null} when API authentication is disabled.
 */
public record ServerConfiguration(String bindAddress, int port, String authToken) {
    public static final String BIND_ADDRESS_ENV = "CODEBRIDGE_BIND_ADDRESS";
    public static final String PORT_ENV = "CODEBRIDGE_PORT";
    public static final String AUTH_TOKEN_ENV = "CODEBRIDGE_AUTH_TOKEN";
    public static final int MIN_AUTH_TOKEN_LENGTH = 16;
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;

    /** Configuration without API authentication. */
    public ServerConfiguration(String bindAddress, int port) {
        this(bindAddress, port, null);
    }

    public ServerConfiguration {
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (bindAddress.isBlank()) throw new IllegalArgumentException("Bind address must not be blank.");
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("Port must be between 0 and 65535.");
        if (authToken != null) validateToken(authToken);
    }

    static void validateToken(String token) {
        if (!token.equals(token.strip())) {
            throw new IllegalArgumentException(AUTH_TOKEN_ENV + " must not have surrounding whitespace.");
        }
        if (token.length() < MIN_AUTH_TOKEN_LENGTH) {
            throw new IllegalArgumentException(AUTH_TOKEN_ENV + " must be at least " + MIN_AUTH_TOKEN_LENGTH + " characters.");
        }
        if (!token.matches("[A-Za-z0-9._~+/-]+=*")) {
            throw new IllegalArgumentException(AUTH_TOKEN_ENV + " must use printable bearer-token characters (letters, digits, . _ ~ + / - and trailing =).");
        }
    }

    public boolean authenticationEnabled() {
        return authToken != null;
    }

    /** Never prints the secret. */
    @Override
    public String toString() {
        return "ServerConfiguration[bindAddress=" + bindAddress + ", port=" + port
                + ", authToken=" + (authToken == null ? "<none>" : "<redacted>") + "]";
    }

    /** Reads production/development environment variables; an optional CLI port takes precedence. */
    public static ServerConfiguration from(String[] arguments, Map<String, String> environment) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(environment, "environment");
        if (arguments.length > 1) throw new IllegalArgumentException("Expected at most one command-line argument: the server port.");

        String host = environment.getOrDefault(BIND_ADDRESS_ENV, DEFAULT_BIND_ADDRESS);
        String configuredPort = arguments.length == 1 ? arguments[0]
                : environment.getOrDefault(PORT_ENV, Integer.toString(DEFAULT_PORT));
        if (host.isBlank() || !host.equals(host.strip())) {
            throw new IllegalArgumentException(BIND_ADDRESS_ENV + " must be a non-empty address without surrounding whitespace.");
        }
        final int port;
        try {
            port = Integer.parseInt(configuredPort);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(PORT_ENV + " and the optional command-line port must be an integer.", invalid);
        }
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("Port must be between 0 and 65535.");
        try {
            InetAddress.getByName(host);
        } catch (UnknownHostException invalid) {
            throw new IllegalArgumentException(BIND_ADDRESS_ENV + " is not a resolvable bind address: " + host, invalid);
        }
        // An empty value (for example from "${VAR:-}" in compose files) means "not configured".
        String token = environment.get(AUTH_TOKEN_ENV);
        if (token != null && token.isEmpty()) token = null;
        return new ServerConfiguration(host, port, token);
    }

    public InetSocketAddress socketAddress() {
        return new InetSocketAddress(bindAddress, port);
    }
}
