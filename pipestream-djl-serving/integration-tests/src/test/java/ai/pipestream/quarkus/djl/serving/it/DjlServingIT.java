package ai.pipestream.quarkus.djl.serving.it;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class DjlServingIT {

    @Test
    public void testDevServiceActive() {
        String url = RestAssured.given()
                .when().get("/djl-serving-it/url")
                .then()
                .statusCode(200)
                .extract().asString();

        System.out.println("Captured DJL Serving URL: " + url);
        assertNotNull(url);
        assertFalse(url.isBlank(), "DJL Serving URL should not be blank");
    }

    @Test
    public void testModelNameExposed() {
        String modelName = RestAssured.given()
                .when().get("/djl-serving-it/model-name")
                .then()
                .statusCode(200)
                .extract().asString();

        System.out.println("Model name: " + modelName);
        assertNotNull(modelName);
        assertFalse(modelName.isBlank(), "Model name should not be blank");
    }

    @Test
    public void testPingDjlServing() {
        RestAssured.given()
                .when().get("/djl-serving-it/ping")
                .then()
                .statusCode(200)
                .body(notNullValue());
    }

    @Test
    public void testModelRegisteredAndPredicts() {
        // This verifies the dev service registered the all-MiniLM-L6-v2 model.
        // A 404 means the model was not registered; a 200 means it is ready.
        Response response = RestAssured.given()
                .queryParam("text", "Semantic embeddings are useful for search.")
                .when().get("/djl-serving-it/predict")
                .then()
                .statusCode(200)
                .body(notNullValue())
                .extract().response();

        String body = response.asString();
        System.out.println("Predict response: " + body);

        // The all-MiniLM-L6-v2 model produces 384-dimensional embeddings.
        // The response should contain numeric values — a rough check is that
        // the body contains at least 384 comma-separated numbers.
        long commaCount = body.chars().filter(c -> c == ',').count();
        assertTrue(commaCount >= 383,
                "Expected at least 383 commas (384-dim embedding) but got " + commaCount
                + ". Response: " + body);
    }
}
