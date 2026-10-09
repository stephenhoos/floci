package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/** Protects the real emulator independently of the AWS authentication it emulates. */
@ApplicationScoped
public class RequestSecurityFilter {

    public static final String API_KEY_HEADER = "X-Floci-Api-Key";
    private final Provider<EmulatorConfig> configProvider;

    @Inject
    public RequestSecurityFilter(Provider<EmulatorConfig> configProvider) {
        this.configProvider = configProvider;
    }

    void register(@Observes Router router) {
        // Raw streaming and WebSocket routes bypass JAX-RS filters. Guard the shared router
        // before any service route so those transports have the same access boundary.
        router.route().order(Integer.MIN_VALUE).handler(this::filterRoute);
    }

    private void filterRoute(RoutingContext context) {
        Response decision = check(context.request().method().name(), RequestHost.of(context.request()),
                context.normalizedPath(), context.request().query(), context.request()::getHeader);
        if (decision == null) {
            if ("websocket".equalsIgnoreCase(context.request().getHeader("Upgrade"))) {
                context.request().headers().remove(API_KEY_HEADER);
            }
            context.next();
            return;
        }
        context.response().setStatusCode(decision.getStatus());
        decision.getStringHeaders().forEach((name, values) ->
                values.forEach(value -> context.response().headers().add(name, value)));
        if (decision.getEntity() instanceof AwsErrorResponse error) {
            context.response().end(new JsonObject().put("__type", error.type()).put("message", error.message()).encode());
        } else {
            context.response().end();
        }
    }

    public void filter(ContainerRequestContext request) {
        Response decision = check(request.getMethod(), RequestHost.of(request),
                request.getUriInfo().getPath(), request.getUriInfo().getRequestUri().getRawQuery(), request::getHeaderString);
        if (decision != null) {
            request.abortWith(decision);
        }
    }

    private Response check(String method, String authority, String path, String query, Function<String, String> headers) {
        EmulatorConfig config = configProvider.get();
        EmulatorConfig.SecurityConfig security = config.security();
        if (security.browserRequestProtection() && isBrowser(headers)) {
            String host = hostname(authority);
            if (!allowedHost(host, config)) {
                return deny("Browser host is not approved for this emulator.");
            }
            String origin = headers.apply("Origin");
            if (origin != null && !allowedOrigin(origin, config)) {
                return deny("Browser origin is not approved for this emulator.");
            }
            if ("cross-site".equalsIgnoreCase(headers.apply("Sec-Fetch-Site")) && origin == null) {
                return deny("Cross-site browser requests require an approved origin.");
            }
        }
        Optional<String> key = security.apiKey();
        if (key.isPresent() && !(isHealthRequest(method, path) && (query == null || query.isEmpty())
                && headers.apply("X-Amz-Target") == null && headers.apply("Smithy-Protocol") == null)) {
            // Browsers omit credentials on preflights. Answer here so OPTIONS never dispatches
            // to an integration or management handler without the configured key.
            String origin = headers.apply("Origin");
            if ("OPTIONS".equals(method) && origin != null
                    && headers.apply("Access-Control-Request-Method") != null
                    && allowedHost(hostname(authority), config) && allowedOrigin(origin, config)) {
                return Response.noContent().header("Access-Control-Allow-Origin", origin)
                        .header("Access-Control-Allow-Methods", "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS")
                        .header("Access-Control-Allow-Headers", headers.apply("Access-Control-Request-Headers"))
                        .header("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers").build();
            }
            String supplied = headers.apply(API_KEY_HEADER);
            if (supplied == null || !MessageDigest.isEqual(key.get().getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8))) {
                return deny("A valid emulator API key is required.");
            }
        }
        return null;
    }

    private static boolean isHealthRequest(String method, String path) {
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return ("GET".equals(method) || "HEAD".equals(method))
                && ("_floci/health".equals(path) || "_localstack/health".equals(path));
    }

    private static boolean isBrowser(Function<String, String> headers) {
        String agent = headers.apply("User-Agent");
        return headers.apply("Origin") != null || headers.apply("Sec-Fetch-Site") != null
                || (agent != null && agent.contains("Mozilla/"));
    }

    static boolean allowedHost(String host, EmulatorConfig config) {
        if (host == null) {
            return false;
        }
        return localHost(host)
                || config.security().allowedBrowserHosts().orElse(List.of()).stream()
                        .anyMatch(allowed -> host.equalsIgnoreCase(allowed))
                || config.hostname().filter(name -> host.equalsIgnoreCase(name)).isPresent()
                || host.equals(hostname(URI.create(config.baseUrl()).getAuthority()));
    }

    static boolean allowedOrigin(String origin, EmulatorConfig config) {
        try {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                return false;
            }
            String host = hostname(uri.getAuthority());
            return localHost(host) || config.security().extraCorsAllowedOrigins().orElse(List.of()).stream()
                    .anyMatch(allowed -> origin.equalsIgnoreCase(allowed));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean localHost(String host) {
        return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                || host.equals("localhost.floci.io") || host.endsWith(".localhost.floci.io")
                || host.equals("host.docker.internal") || host.endsWith(".localhost")
                || host.equals("localhost.localstack.cloud") || host.endsWith(".localhost.localstack.cloud"));
    }

    private static String hostname(String authority) {
        if (authority == null) {
            return null;
        }
        try {
            URI uri = URI.create("http://" + authority);
            if (uri.getUserInfo() != null || !uri.getRawPath().isEmpty()
                    || uri.getQuery() != null || uri.getFragment() != null || uri.getHost() == null) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Response deny(String message) {
        return Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new AwsErrorResponse("AccessDeniedException", message)).build();
    }
}
