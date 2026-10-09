package io.github.hectorvent.floci.core.common;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenedHttpClientTest {
    private HttpServer server;
    private URI base;

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
    }

    @Test
    void localOidcPostAndStringBodyHandlerKeepWorking() throws Exception {
        try (ScreenedHttpClient client = new ScreenedHttpClient(true)) {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/echo"))
                    .POST(HttpRequest.BodyPublishers.ofString("code=abc&client_id=local")).build();
            assertEquals("code=abc&client_id=local", client.send(request, HttpResponse.BodyHandlers.ofString()).body());
        }
    }

    @Test
    void inputStreamAndAsyncDiscardHandlersKeepWorking() throws Exception {
        try (ScreenedHttpClient client = new ScreenedHttpClient(true)) {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/echo"))
                    .POST(HttpRequest.BodyPublishers.ofString("payload")).build();
            try (InputStream body = client.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()) {
                assertEquals("payload", new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertEquals(200, client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).get().statusCode());
        }
    }

    @Test
    void redirectsAreNotFollowedIntoMetadata() throws Exception {
        try (ScreenedHttpClient client = new ScreenedHttpClient(true)) {
            assertEquals(302, client.send(HttpRequest.newBuilder(base.resolve("/redirect")).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test
    void metadataIsRejectedEvenWhenPrivateIntegrationsAreAllowed() {
        try (ScreenedHttpClient client = new ScreenedHttpClient(true)) {
            for (String uri : new String[]{"http://169.254.169.254/", "http://[fd00:ec2::254]/"}) {
                assertThrows(IOException.class, () -> client.send(HttpRequest.newBuilder(URI.create(uri)).build(),
                        HttpResponse.BodyHandlers.discarding()));
            }
        }
    }

    @Test
    void strictModeRejectsPrivateTargets() {
        try (ScreenedHttpClient client = new ScreenedHttpClient(false)) {
            assertThrows(IOException.class, () -> client.send(HttpRequest.newBuilder(base.resolve("/echo")).build(),
                    HttpResponse.BodyHandlers.discarding()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void secondDnsAnswerIsScreenedAtConnectionTime(String scheme) {
        AtomicInteger lookups = new AtomicInteger();
        DnsResolver resolver = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                return new InetAddress[]{InetAddress.getByName(
                        lookups.incrementAndGet() == 1 ? "127.0.0.1" : "169.254.169.254")};
            }
            @Override public String resolveCanonicalHostname(String host) { return host; }
        };
        try (ScreenedHttpClient client = new ScreenedHttpClient(true, resolver, null)) {
            URI target = URI.create(scheme + "://rebinding.test:" + server.getAddress().getPort() + "/echo");
            assertThrows(IOException.class, () -> client.send(HttpRequest.newBuilder(target).build(),
                    HttpResponse.BodyHandlers.discarding()));
            assertTrue(lookups.get() >= 2);
        }
    }

    @Test
    void oversizedResponsesAreRejected() {
        server.createContext("/large", exchange -> {
            byte[] body = new byte[ScreenedHttpClient.MAX_BODY_BYTES + 1];
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        try (ScreenedHttpClient client = new ScreenedHttpClient(true)) {
            assertThrows(IOException.class, () -> client.send(HttpRequest.newBuilder(base.resolve("/large")).build(),
                    HttpResponse.BodyHandlers.ofByteArray()));
        }
    }
}
