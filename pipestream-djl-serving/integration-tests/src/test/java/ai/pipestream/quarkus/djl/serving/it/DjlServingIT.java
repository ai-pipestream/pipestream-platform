package ai.pipestream.quarkus.djl.serving.it;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.is;

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
        RestAssured.given()
                .when().get("/djl-serving-it/ping")
                .then()
                .statusCode(200)
                .body(containsString("Healthy")); // DJL Serving ping returns JSON with "Healthy" status
    }
}
