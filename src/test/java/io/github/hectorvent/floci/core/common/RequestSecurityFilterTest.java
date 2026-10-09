package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestSecurityFilterTest {
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ContainerRequestContext request = mock(ContainerRequestContext.class, RETURNS_DEEP_STUBS);
    private final RequestSecurityFilter filter = new RequestSecurityFilter(() -> config);

    @BeforeEach
    void setup() {
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(config.security().browserRequestProtection()).thenReturn(true);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeaderString("Host")).thenReturn("localhost:4566");
        when(request.getUriInfo().getRequestUri()).thenReturn(URI.create("http://localhost:4566/"));
        when(request.getUriInfo().getPath()).thenReturn("");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://attacker.example", "null", "http://localhost.attacker.example",
            "http://localhost.floci.io.attacker.example", "http://user@localhost:3000"})
    void untrustedOriginsCannotSubmitManagementCalls(String origin) {
        when(request.getHeaderString("Origin")).thenReturn(origin);
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"attacker.example:4566", "localhost.attacker.example", "localhost@attacker.example"})
    void rebindingHostsAreRejectedEvenForSameOriginRequests(String host) {
        when(request.getHeaderString("Host")).thenReturn(host);
        when(request.getHeaderString("Sec-Fetch-Site")).thenReturn("same-origin");
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
    }

    @Test
    void olderBrowserWithoutFetchMetadataStillGetsHostProtection() {
        when(request.getHeaderString("User-Agent")).thenReturn("Mozilla/5.0");
        when(request.getHeaderString("Host")).thenReturn("attacker.example");
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
    }

    @Test
    void crossSiteNavigationWithoutOriginIsRejected() {
        when(request.getHeaderString("Sec-Fetch-Site")).thenReturn("cross-site");
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
    }

    @Test
    void localConsoleAndSdkCallsRemainUsable() {
        when(request.getHeaderString("Origin")).thenReturn("http://localhost:4500");
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    @Test
    void explicitCorsOriginAndBrowserHostAreRespected() {
        when(config.security().extraCorsAllowedOrigins()).thenReturn(Optional.of(List.of("https://dev.example")));
        when(config.security().allowedBrowserHosts()).thenReturn(Optional.of(List.of("emulator.example")));
        when(request.getHeaderString("Host")).thenReturn("emulator.example");
        when(request.getHeaderString("Origin")).thenReturn("https://dev.example");
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    @Test
    void sdkVirtualHostRequestsKeepTheirExistingRouting() {
        when(request.getHeaderString("Host")).thenReturn("bucket.custom.test");
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong", "local-emulator-secret"})
    void apiKeyCannotBeReplacedByEmulatedAwsCredentials(String supplied) {
        when(config.security().apiKey()).thenReturn(Optional.of("a".repeat(32)));
        when(request.getHeaderString("Authorization")).thenReturn("AWS4-HMAC-SHA256 Credential=test/20261009/us-east-1/iam/aws4_request");
        when(request.getHeaderString(RequestSecurityFilter.API_KEY_HEADER)).thenReturn(supplied);
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
    }

    @Test
    void validApiKeyAllowsNativeRequest() {
        when(config.security().apiKey()).thenReturn(Optional.of("a".repeat(32)));
        when(request.getHeaderString(RequestSecurityFilter.API_KEY_HEADER)).thenReturn("a".repeat(32));
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    @Test
    void onlyReadOnlyHealthBypassesConfiguredApiKey() {
        when(config.security().apiKey()).thenReturn(Optional.of("a".repeat(32)));
        when(request.getMethod()).thenReturn("GET");
        when(request.getUriInfo().getPath()).thenReturn("_floci/health");
        filter.filter(request);
        verify(request, never()).abortWith(any());
        when(request.getMethod()).thenReturn("POST");
        filter.filter(request);
        verify(request).abortWith(any(Response.class));
    }
}
