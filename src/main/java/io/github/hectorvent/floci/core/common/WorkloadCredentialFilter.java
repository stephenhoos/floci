package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.apigateway.ApiGatewayExecuteController;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontServingController;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/** Keeps the emulator's independent credential out of API Gateway events and authorizers. */
@Provider
@Priority(Priorities.AUTHORIZATION + 100)
public class WorkloadCredentialFilter implements ContainerRequestFilter {
    @Context
    ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext request) {
        if (resourceInfo != null && (resourceInfo.getResourceClass() == ApiGatewayExecuteController.class
                || resourceInfo.getResourceClass() == CloudFrontServingController.class)) {
            request.getHeaders().keySet().stream()
                    .filter(RequestSecurityFilter.API_KEY_HEADER::equalsIgnoreCase).toList()
                    .forEach(request.getHeaders()::remove);
        }
    }
}
