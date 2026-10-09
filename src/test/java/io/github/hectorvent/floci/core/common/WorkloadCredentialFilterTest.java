package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.apigateway.ApiGatewayExecuteController;
import io.github.hectorvent.floci.services.s3.S3Controller;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkloadCredentialFilterTest {
    @Test
    void gatewayWorkloadsCannotReadIndependentApiKey() {
        MultivaluedMap<String, String> headers = filteredHeaders(ApiGatewayExecuteController.class);
        assertNull(headers.getFirst(RequestSecurityFilter.API_KEY_HEADER));
        assertNull(headers.getFirst("x-floci-api-key"));
        assertEquals("application/json", headers.getFirst("Content-Type"));
    }

    @Test
    void s3KeepsSignedHeaderForItsSignatureValidator() {
        assertEquals("synthetic-key", filteredHeaders(S3Controller.class).getFirst(RequestSecurityFilter.API_KEY_HEADER));
    }

    private MultivaluedMap<String, String> filteredHeaders(Class<?> resource) {
        WorkloadCredentialFilter filter = new WorkloadCredentialFilter();
        filter.resourceInfo = mock(ResourceInfo.class);
        doReturn(resource).when(filter.resourceInfo).getResourceClass();
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle(RequestSecurityFilter.API_KEY_HEADER, "synthetic-key");
        headers.putSingle("x-floci-api-key", "synthetic-key");
        headers.putSingle("Content-Type", "application/json");
        when(request.getHeaders()).thenReturn(headers);
        filter.filter(request);
        return headers;
    }
}
