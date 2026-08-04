package at.kigruapp.resource;

import at.kigruapp.entity.LandingPage;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LandingPageResourceTest {

    @BeforeEach
    void cleanup() {
        LandingPage.deleteAll();
    }

    private String put(String bodyHtml) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"bodyHtml\":" + jsonString(bodyHtml) + "}")
                .when().put("/api/v1/landing-page")
                .then().statusCode(200)
                .extract().path("bodyHtml");
    }

    /** Minimaler JSON-String-Escaper für die Testfixtures. */
    private String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void getReturnsEmptyContentWhenNothingSavedYet() {
        given()
                .when().get("/api/v1/landing-page")
                .then().statusCode(200)
                .body("bodyHtml", is(""));
    }

    @Test
    void putThenGetReturnsSavedContent() {
        put("<p>Willkommen</p>");

        given()
                .when().get("/api/v1/landing-page")
                .then().statusCode(200)
                .body("bodyHtml", is("<p>Willkommen</p>"));
    }

    @Test
    void putOverwritesSingletonInsteadOfCreatingSecondRow() {
        put("<p>eins</p>");
        put("<p>zwei</p>");

        assertEquals(1, LandingPage.count());
        given()
                .when().get("/api/v1/landing-page")
                .then().body("bodyHtml", is("<p>zwei</p>"));
    }

    @Test
    void sanitizerDropsScriptAndIframeButKeepsTablesImagesAndStyles() {
        String saved = put("<script>alert(1)</script>"
                + "<iframe src=\"https://evil.example\"></iframe>"
                + "<table><tbody><tr><td style=\"color:red\">Zelle</td></tr></tbody></table>"
                + "<img src=\"https://example.org/logo.png\" alt=\"Logo\">");

        assertFalse(saved.contains("<script"), saved);
        assertFalse(saved.contains("<iframe"), saved);
        assertTrue(saved.contains("<table"), saved);
        assertTrue(saved.contains("style="), saved);
        assertTrue(saved.contains("https://example.org/logo.png"), saved);
    }

    @Test
    void sanitizerDropsDataUriImageButKeepsHttpsImage() {
        String saved = put("<img src=\"data:image/png;base64,AAAA\">"
                + "<img src=\"https://example.org/ok.png\">");

        assertFalse(saved.contains("data:image"), saved);
        assertTrue(saved.contains("https://example.org/ok.png"), saved);
    }

    @Test
    void sanitizerKeepsRelativeImageUrlFromUploadEndpoint() {
        String saved = put("<img src=\"/api/v1/landing-page/images/507f1f77bcf86cd799439011\">");

        assertTrue(saved.contains("/api/v1/landing-page/images/507f1f77bcf86cd799439011"), saved);
    }

    @Test
    void sanitizerKeepsPlaceholderTokensIntact() {
        String saved = put("<p>Hallo {{person.firstName}}, Bilanz {{stunden.bilanz}}</p>");

        assertTrue(saved.contains("{{person.firstName}}"), saved);
        assertTrue(saved.contains("{{stunden.bilanz}}"), saved);
        assertFalse(saved.contains("<!--"), saved);
    }

    @Test
    void putRejectsNullBody() {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().put("/api/v1/landing-page")
                .then().statusCode(400);
    }
}
