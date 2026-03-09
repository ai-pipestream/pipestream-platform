package ai.pipestream.quarkus.djl.serving.it;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
public class DjlServingIT {

    @Test
    public void testDevServiceActive() {
        // Verify that the URL is captured
        String url = RestAssured.given()
                .when().get("/djl-serving-it/url")
                .then()
                .statusCode(200)
                .extract().asString();

        System.out.println("Captured DJL Serving URL: " + url);
    }

    @Test
    public void testPingDjlServing() {
        // Verify we can reach the actual container through the injected client
        // Upstream deepjavalibrary/djl-serving /ping returns "{}" with 200
        RestAssured.given()
                .when().get("/djl-serving-it/ping")
                .then()
                .statusCode(200)
                .body(notNullValue());
    }
}
