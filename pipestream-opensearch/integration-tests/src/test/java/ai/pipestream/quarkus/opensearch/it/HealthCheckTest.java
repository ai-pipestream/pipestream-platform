package ai.pipestream.quarkus.opensearch.it;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for OpenSearch health check endpoint.
 * Verifies that the health check reports OpenSearch status correctly.
 */
@QuarkusTest
public class HealthCheckTest {

    @Test
    void testHealthEndpointReturnsUp() {
        given()
            .when()
                .get("/q/health")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void testReadyEndpointReturnsUp() {
        given()
            .when()
                .get("/q/health/ready")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void testOpenSearchHealthCheckIncluded() {
        given()
            .when()
                .get("/q/health/ready")
            .then()
                .statusCode(200)
                .body("checks.name", hasItem("OpenSearch connection health check"))
                .body("checks.find { it.name == 'OpenSearch connection health check' }.status", 
                      equalTo("UP"));
    }

    @Test
    void testOpenSearchHealthCheckHasClusterInfo() {
        given()
            .when()
                .get("/q/health/ready")
            .then()
                .statusCode(200)
                .body("checks.find { it.name == 'OpenSearch connection health check' }.data.clusterStatus", 
                      anyOf(equalTo("green"), equalTo("yellow")))
                .body("checks.find { it.name == 'OpenSearch connection health check' }.data.hosts", 
                      notNullValue());
    }
}
