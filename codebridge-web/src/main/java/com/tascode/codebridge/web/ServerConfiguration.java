package com.tascode.codebridge.web;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Objects;

/** Runtime bind settings. Defaults intentionally expose the local development server only. */
public record ServerConfiguration(String bindAddress, int port) {
    public static final String BIND_ADDRESS_ENV = "CODEBRIDGE_BIND_ADDRESS";
    public static final String PORT_ENV = "CODEBRIDGE_PORT";
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;

    public ServerConfiguration {
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (bindAddress.isBlank()) throw new IllegalArgumentException("Bind address must not be blank.");
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("Port must be between 0 and 65535.");
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
        return new ServerConfiguration(host, port);
    }

    public InetSocketAddress socketAddress() {
        return new InetSocketAddress(bindAddress, port);
    }
}
