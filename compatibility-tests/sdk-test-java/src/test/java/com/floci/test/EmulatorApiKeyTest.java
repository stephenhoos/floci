package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmulatorApiKeyTest {
    private static final String HEADER = "X-Floci-Api-Key";
    private static final String KEY = System.getenv("FLOCI_TEST_API_KEY");
    private static final URI ENDPOINT = URI.create(System.getenv().getOrDefault("FLOCI_ENDPOINT", "http://localhost:4566"));

    @BeforeEach
    void configuredKey() {
        Assumptions.assumeTrue(KEY != null, "Run against a server with FLOCI_SECURITY_API_KEY and set FLOCI_TEST_API_KEY");
    }

    private ClientOverrideConfiguration configuration(boolean withKey) {
        ClientOverrideConfiguration.Builder configuration = ClientOverrideConfiguration.builder();
        if (withKey) {
            configuration.addExecutionInterceptor(new ExecutionInterceptor() {
                @Override
                public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context,
                                                       ExecutionAttributes attributes) {
                    return context.httpRequest().toBuilder().putHeader(HEADER, KEY).build();
                }
            });
        }
        return configuration.build();
    }

    private SsmClient client(boolean withKey) {
        return SsmClient.builder().endpointOverride(ENDPOINT).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(configuration(withKey)).build();
    }

    @Test
    void signedSdkRequestsWithApiKeyKeepTheirWireProtocol() {
        try (SsmClient ssm = client(true)) {
            ssm.putParameter(request -> request.name("/security/sdk-roundtrip").value("ok").type("String").overwrite(true));
            assertEquals("ok", ssm.getParameter(request -> request.name("/security/sdk-roundtrip")).parameter().value());
            ssm.deleteParameter(request -> request.name("/security/sdk-roundtrip"));
        }
    }

    @Test
    void emulatedTestCredentialsDoNotBypassApiKey() {
        try (SsmClient ssm = client(false)) {
            assertEquals(403, assertThrows(SsmException.class,
                    () -> ssm.describeParameters(request -> request.maxResults(1))).statusCode());
        }
    }

    @Test
    void s3SignatureValidationStillAcceptsTheSignedApiKeyHeader() {
        try (S3Client s3 = S3Client.builder().endpointOverride(ENDPOINT).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true).overrideConfiguration(configuration(true)).build()) {
            String bucket = "security-api-key-" + Long.toString(System.nanoTime(), 36);
            s3.createBucket(request -> request.bucket(bucket));
            try {
                s3.headBucket(request -> request.bucket(bucket));
            } finally {
                s3.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

}
