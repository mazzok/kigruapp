package at.kigruapp.resource;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import com.mongodb.client.MongoClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RequiredHoursResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        RequiredHours.deleteAll();
        Semester.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").deleteMany(new org.bson.Document());
    }

    private String persistSemester() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        s.createdAt = Instant.now();
        s.persist();
        return s.id.toHexString();
    }

    @Test
    void getReturnsEmptyDtoWhenNoneSaved() {
        String semesterId = persistSemester();
        given().when().get("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("defaultMinutesPerMonth", is(0))
            .body("tiers.size()", is(0));
    }

    @Test
    void putThenGetRoundTrips() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":2,\"percent\":75},{\"fromChild\":3,\"percent\":100}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("defaultMinutesPerMonth", is(480))
            .body("tiers.size()", is(2));

        given().when().get("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("defaultMinutesPerMonth", is(480))
            .body("tiers[0].fromChild", is(2))
            .body("tiers[1].percent", is(100));
    }

    @Test
    void putUpsertsInsteadOfDuplicating() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(200);
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":600,\"tiers\":[]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(200);

        given().when().get("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200).body("defaultMinutesPerMonth", is(600));
    }

    @Test
    void putRejectsNonPositiveDefault() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":0,\"tiers\":[]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }

    @Test
    void putRejectsNonAscendingTiers() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":3,\"percent\":0},{\"fromChild\":2,\"percent\":75}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }

    @Test
    void putRejectsTierFromChildBelowTwo() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":1,\"percent\":25}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }

    @Test
    void putRejectsNegativeTierPercent() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":2,\"percent\":-1}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }

    @Test
    void savesGroupRatesAndOrder() {
        String semesterId = new org.bson.types.ObjectId().toHexString();
        String groupId = seedGroup("Käfergruppe", "#43a047");

        given().contentType("application/json")
                .body("""
                      {"semesterId":"%s","defaultMinutesPerMonth":480,"allGroups":false,
                       "order":"LEAST_EXPENSIVE_FIRST",
                       "groupRates":[{"groupInstanceId":"%s","minutesPerMonth":300}],
                       "tiers":[{"fromChild":2,"percent":25}]}
                      """.formatted(semesterId, groupId))
                .when().put("/api/v1/required-hours?semesterId=" + semesterId)
                .then().statusCode(200)
                .body("allGroups", equalTo(false))
                .body("order", equalTo("LEAST_EXPENSIVE_FIRST"))
                .body("groupRates[0].groupInstanceId", equalTo(groupId))
                .body("groupRates[0].minutesPerMonth", equalTo(300))
                .body("tiers[0].percent", equalTo(25));
    }

    @Test
    void rejectsMissingGroupRateWhenPerGroup() {
        String semesterId = new org.bson.types.ObjectId().toHexString();
        seedGroup("Käfergruppe", "#43a047");
        seedGroup("Bärengruppe", "#fb8c00");
        String onlyOne = seedGroup("Mäusegruppe", "#8e24aa");

        given().contentType("application/json")
                .body("""
                      {"semesterId":"%s","defaultMinutesPerMonth":480,"allGroups":false,
                       "order":"MOST_EXPENSIVE_FIRST",
                       "groupRates":[{"groupInstanceId":"%s","minutesPerMonth":300}],
                       "tiers":[]}
                      """.formatted(semesterId, onlyOne))
                .when().put("/api/v1/required-hours?semesterId=" + semesterId)
                .then().statusCode(400);
    }

    @Test
    void rejectsPercentOutsideRange() {
        String semesterId = new org.bson.types.ObjectId().toHexString();

        given().contentType("application/json")
                .body("""
                      {"semesterId":"%s","defaultMinutesPerMonth":480,"allGroups":true,
                       "order":"MOST_EXPENSIVE_FIRST","groupRates":[],
                       "tiers":[{"fromChild":2,"percent":120}]}
                      """.formatted(semesterId))
                .when().put("/api/v1/required-hours?semesterId=" + semesterId)
                .then().statusCode(400);
    }

    @Test
    void keepsGroupRatesWhenSwitchingBackToAllGroups() {
        String semesterId = new org.bson.types.ObjectId().toHexString();
        String groupId = seedGroup("Käfergruppe", "#43a047");

        given().contentType("application/json")
                .body("""
                      {"semesterId":"%s","defaultMinutesPerMonth":480,"allGroups":false,
                       "order":"MOST_EXPENSIVE_FIRST",
                       "groupRates":[{"groupInstanceId":"%s","minutesPerMonth":300}],"tiers":[]}
                      """.formatted(semesterId, groupId))
                .when().put("/api/v1/required-hours?semesterId=" + semesterId)
                .then().statusCode(200);

        given().contentType("application/json")
                .body("""
                      {"semesterId":"%s","defaultMinutesPerMonth":480,"allGroups":true,
                       "order":"MOST_EXPENSIVE_FIRST","groupRates":[],"tiers":[]}
                      """.formatted(semesterId))
                .when().put("/api/v1/required-hours?semesterId=" + semesterId)
                .then().statusCode(200)
                .body("groupRates[0].minutesPerMonth", equalTo(300));
    }

    private String seedGroup(String label, String color) {
        var db = mongoClient.getDatabase(databaseName);
        org.bson.types.ObjectId definitionId = db.getCollection("field_definitions")
                .find(new org.bson.Document("fieldName", "group").append("outdatedAt", null))
                .first() != null
                ? db.getCollection("field_definitions")
                    .find(new org.bson.Document("fieldName", "group").append("outdatedAt", null))
                    .first().getObjectId("_id")
                : insertGroupDefinition(db);
        org.bson.types.ObjectId id = new org.bson.types.ObjectId();
        db.getCollection("field_instances").insertOne(new org.bson.Document("_id", id)
                .append("definitionId", definitionId)
                .append("value", new org.bson.Document("label", label).append("color", color)));
        return id.toHexString();
    }

    private org.bson.types.ObjectId insertGroupDefinition(com.mongodb.client.MongoDatabase db) {
        org.bson.types.ObjectId definitionId = new org.bson.types.ObjectId();
        db.getCollection("field_definitions").insertOne(new org.bson.Document("_id", definitionId)
                .append("fieldName", "group")
                .append("label", new org.bson.Document("de", "Gruppen"))
                .append("outdatedAt", null));
        return definitionId;
    }
}
