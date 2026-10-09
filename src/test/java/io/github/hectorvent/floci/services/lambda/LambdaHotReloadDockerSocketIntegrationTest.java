package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Hot-reload enabled with no allow-list, as in the test configuration: directories that can hold
 * the Docker socket and ordinary code directories are both refused over the wire.
 */
@QuarkusTest
class LambdaHotReloadDockerSocketIntegrationTest {

    private static int counter;

    private static ValidatableResponse createFunction(String s3Key) {
        return createFunction("hot-reload-socket-guard-" + (++counter), s3Key);
    }

    private static ValidatableResponse createFunction(String name, String s3Key) {
        return given()
                .contentType("application/json")
                .body("""
                        {
                            "FunctionName": "%s",
                            "Runtime": "python3.12",
                            "Role": "arn:aws:iam::000000000000:role/r",
                            "Handler": "handler.handler",
                            "Code": { "S3Bucket": "hot-reload", "S3Key": "%s" }
                        }
                        """.formatted(name, s3Key))
                .when()
                .post("/2015-03-31/functions")
                .then();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/var/run", "/run", "/var/run/docker.sock", "/proc/1/root/var/run"})
    void createFunctionRejectsDirectoriesThatCanHoldTheDockerSocket(String s3Key) {
        createFunction(s3Key)
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", containsString("ALLOWED_PATHS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tmp/floci-hot-reload", "/home/ci/code"})
    void createFunctionRequiresAnAllowListForOrdinaryCodeDirectories(String s3Key) {
        String name = "hot-reload-socket-guard-accepted-" + (++counter);
        createFunction(name, s3Key).statusCode(400)
                .body("message", containsString("ALLOWED_PATHS"));
    }

}
