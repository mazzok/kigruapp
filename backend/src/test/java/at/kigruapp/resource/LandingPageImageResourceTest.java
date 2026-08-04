package at.kigruapp.resource;

import at.kigruapp.entity.LandingPageImage;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class LandingPageImageResourceTest {

    private static final byte[] ONE_PIXEL_PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01,
    };

    @BeforeEach
    void cleanup() {
        LandingPageImage.deleteAll();
    }

    @Test
    void uploadStoresImageAndReturnsUrl() {
        given()
                .contentType("image/png")
                .body(ONE_PIXEL_PNG)
                .when().post("/api/v1/landing-page/images")
                .then().statusCode(200)
                .body("id", matchesPattern("[0-9a-f]{24}"))
                .body("url", matchesPattern("/api/v1/landing-page/images/[0-9a-f]{24}"));

        assertEquals(1, LandingPageImage.count());
    }

    @Test
    void uploadRejectsEmptyBody() {
        given()
                .contentType("image/png")
                .body(new byte[0])
                .when().post("/api/v1/landing-page/images")
                .then().statusCode(400);
    }

    @Test
    void uploadRejectsOversizedImage() {
        byte[] tooLarge = new byte[LandingPageImageResource.MAX_BYTES + 1];

        given()
                .contentType("image/png")
                .body(tooLarge)
                .when().post("/api/v1/landing-page/images")
                .then().statusCode(400);
    }

    @Test
    void getServesUploadedImageWithContentType() {
        String url = given()
                .contentType("image/png")
                .body(ONE_PIXEL_PNG)
                .when().post("/api/v1/landing-page/images")
                .then().statusCode(200)
                .extract().path("url");

        byte[] body = given()
                .when().get(url)
                .then().statusCode(200)
                .contentType("image/png")
                .extract().asByteArray();

        assertArrayEquals(ONE_PIXEL_PNG, body);
    }

    @Test
    void getReturns404ForUnknownId() {
        given()
                .when().get("/api/v1/landing-page/images/000000000000000000000000")
                .then().statusCode(404);
    }

    @Test
    void getReturns404ForMalformedId() {
        given()
                .when().get("/api/v1/landing-page/images/not-an-id")
                .then().statusCode(404);
    }
}
