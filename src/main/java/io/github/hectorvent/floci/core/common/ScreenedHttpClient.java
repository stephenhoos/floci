package io.github.hectorvent.floci.core.common;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

/** Bounded HTTP/1 client whose DNS screening applies to the addresses actually connected to. */
public final class ScreenedHttpClient extends HttpClient {

    public static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> TRANSPORT_HEADERS = Set.of("host", "connection", "content-length", "upgrade");
    private final CloseableHttpClient client;
    private final DnsResolver resolver;
    private final SSLContext tlsContext;

    public ScreenedHttpClient(boolean allowPrivateTargets) {
        this(allowPrivateTargets, SystemDefaultDnsResolver.INSTANCE, null);
    }

    public ScreenedHttpClient(boolean allowPrivateTargets, DnsResolver delegate, SSLContext sslContext) {
        resolver = new ScreeningResolver(delegate, allowPrivateTargets);
        tlsContext = sslContext;
        PoolingHttpClientConnectionManagerBuilder connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                        .setSocketTimeout(Timeout.ofSeconds(30)).build());
        if (sslContext != null) {
            connections.setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(sslContext).buildClassic());
        }
        client = HttpClients.custom().setConnectionManager(connections.build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableContentCompression().build();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        URI uri = request.uri();
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("Unsupported outbound URI.");
        }
        // Literal addresses may bypass a transport's DNS resolver; screen them here as well.
        resolver.resolve(uri.getHost());
        HttpUriRequestBase outgoing = new HttpUriRequestBase(request.method(), uri);
        request.headers().map().forEach((name, values) -> {
            if (!TRANSPORT_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> outgoing.addHeader(name, value));
            }
        });
        if (request.bodyPublisher().isPresent()) {
            outgoing.setEntity(new ByteArrayEntity(requestBody(request.bodyPublisher().get()),
                    ContentType.APPLICATION_OCTET_STREAM));
        }
        Duration timeout = request.timeout().orElse(Duration.ofSeconds(30));
        outgoing.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(timeout))
                .setConnectionRequestTimeout(Timeout.of(CONNECT_TIMEOUT))
                .setRedirectsEnabled(false).setContentCompressionEnabled(false).build());
        RawResponse raw = client.execute(outgoing, response -> {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (Header header : response.getHeaders()) {
                headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
            }
            byte[] body = new byte[0];
            if (response.getEntity() != null) {
                try (InputStream input = response.getEntity().getContent()) {
                    body = input.readNBytes(MAX_BODY_BYTES + 1);
                    if (body.length > MAX_BODY_BYTES) {
                        throw new ResponseTooLargeException();
                    }
                }
            }
            return new RawResponse(response.getCode(), HttpHeaders.of(headers, (name, value) -> true), body);
        });
        HttpResponse.BodySubscriber<T> subscriber = handler.apply(new HttpResponse.ResponseInfo() {
            @Override public int statusCode() { return raw.status(); }
            @Override public HttpHeaders headers() { return raw.headers(); }
            @Override public Version version() { return Version.HTTP_1_1; }
        });
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long count) { }
            @Override public void cancel() { }
        });
        if (raw.body().length > 0) {
            subscriber.onNext(List.of(ByteBuffer.wrap(raw.body())));
        }
        subscriber.onComplete();
        return new ScreenedResponse<>(request, raw.status(), raw.headers(), await(subscriber.getBody().toCompletableFuture()));
    }

    private static byte[] requestBody(HttpRequest.BodyPublisher publisher) throws IOException, InterruptedException {
        CompletableFuture<byte[]> bytes = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private final ByteArrayOutputStream output = new ByteArrayOutputStream();
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription supplied) {
                subscription = supplied;
                supplied.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ByteBuffer buffer) {
                if (buffer.remaining() > MAX_BODY_BYTES - output.size()) {
                    subscription.cancel();
                    bytes.completeExceptionally(new IOException("Outbound request exceeds its size limit."));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                output.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) { bytes.completeExceptionally(error); }
            @Override public void onComplete() { bytes.complete(output.toByteArray()); }
        });
        return await(bytes);
    }

    private static <T> T await(CompletableFuture<T> future) throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw new IOException("Outbound body processing failed.", e.getCause());
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(request, handler);
            } catch (IOException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                          HttpResponse.PushPromiseHandler<T> pushHandler) {
        return sendAsync(request, handler);
    }

    @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
    @Override public Optional<Duration> connectTimeout() { return Optional.of(CONNECT_TIMEOUT); }
    @Override public Redirect followRedirects() { return Redirect.NEVER; }
    @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
    @Override public SSLContext sslContext() {
        try {
            return tlsContext == null ? SSLContext.getDefault() : tlsContext;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override public SSLParameters sslParameters() { return sslContext().getDefaultSSLParameters(); }
    @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
    @Override public Version version() { return Version.HTTP_1_1; }
    @Override public Optional<Executor> executor() { return Optional.empty(); }
    @Override public void close() {
        try {
            client.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static final class ResponseTooLargeException extends IOException {
        public ResponseTooLargeException() {
            super("Outbound response exceeds its size limit.");
        }
    }

    private record RawResponse(int status, HttpHeaders headers, byte[] body) { }

    private record ScreenedResponse<T>(HttpRequest request, int statusCode, HttpHeaders headers, T body)
            implements HttpResponse<T> {
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public Version version() { return Version.HTTP_1_1; }
    }

    private record ScreeningResolver(DnsResolver delegate, boolean allowPrivateTargets) implements DnsResolver {
        @Override public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = delegate.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException("Outbound host has no addresses.");
            }
            for (InetAddress address : addresses) {
                if (SsrfProtection.isMetadataAddress(address)
                        || (!allowPrivateTargets && SsrfProtection.isBlockedAddress(address))) {
                    throw new UnknownHostException("Outbound target resolves to a blocked address.");
                }
            }
            return addresses.clone();
        }
        @Override public String resolveCanonicalHostname(String host) { return host; }
    }
}
