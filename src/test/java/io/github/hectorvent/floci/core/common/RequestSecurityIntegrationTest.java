package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(RequestSecurityIntegrationTest.ApiKeyProfile.class)
class RequestSecurityIntegrationTest {
    private static final String KEY = "synthetic-integration-api-key-1234567890";

    public static class ApiKeyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.security.api-key", KEY);
        }
    }

    @Test
    void managementRequestsRequireTheIndependentKey() {
        given().header("X-Amz-Target", "AmazonSSM.DescribeParameters")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261009/us-east-1/ssm/aws4_request")
                .contentType("application/x-amz-json-1.1").body("{}".getBytes(StandardCharsets.UTF_8))
                .when().post("/").then().statusCode(403);
        given().header("X-Amz-Target", "AmazonSSM.DescribeParameters")
                .header(RequestSecurityFilter.API_KEY_HEADER, KEY)
                .contentType("application/x-amz-json-1.1").body("{}".getBytes(StandardCharsets.UTF_8))
                .when().post("/").then().statusCode(200);
    }

    @Test
    void browserFormAndRebindingCallsAreBlockedBeforeDispatch() {
        given().header("Origin", "https://attacker.example")
                .header(RequestSecurityFilter.API_KEY_HEADER, KEY)
                .contentType("application/x-www-form-urlencoded").formParam("Action", "ListQueues")
                .when().post("/").then().statusCode(403);
        given().header("Host", "attacker.example").header("Sec-Fetch-Site", "same-origin")
                .header(RequestSecurityFilter.API_KEY_HEADER, KEY)
                .when().get("/_floci/health").then().statusCode(403);
    }

    @Test
    void healthIsPublicButDestructiveManagementIsProtected() {
        given().when().get("/_floci/health").then().statusCode(200);
        given().when().post("/_floci/state/reset").then().statusCode(403);
        given().when().post("/_localstack/state/nuke").then().statusCode(403);
        given().when().get("/v2/").then().statusCode(403);
        given().when().get("/codeartifact/npm/example/repository/").then().statusCode(403);
        given().when().get("/ws/example/stage").then().statusCode(403);
    }

    @Test
    void approvedBrowserPreflightDoesNotNeedOrDispatchWithApiKey() {
        given().header("Origin", "http://localhost:4500")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", RequestSecurityFilter.API_KEY_HEADER)
                .when().options("/_floci/state/reset").then().statusCode(204);
        given().header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "POST")
                .when().options("/_floci/state/reset").then().statusCode(403);
    }
    @Test
    void healthExemptionCannotCarryRpcSignals() {
        given().header("X-Amz-Target", "AmazonSSM.DescribeParameters")
                .when().get("/_floci/health").then().statusCode(403);
        given().queryParam("Action", "ListQueues")
                .when().get("/_floci/health").then().statusCode(403);
    }

}
