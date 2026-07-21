package at.kigruapp.resource;

import at.kigruapp.entity.*;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class BilanzResourceTest {

    @Inject MongoClient mongoClient;
    @ConfigProperty(name = "quarkus.mongodb.database") String databaseName;

    private com.mongodb.client.MongoCollection<Document> coll(String name) {
        return mongoClient.getDatabase(databaseName).getCollection(name);
    }

    private void fullCleanup() {
        BilanzOverride.deleteAll();
        KostenValue.deleteAll();
        KostenDefinition.deleteAll();
        Currency.deleteAll();
        Semester.deleteAll();
        Person.deleteAll();
        Family.deleteAll();
        coll("semester_assignments").deleteMany(new Document());
        coll("field_instances").deleteMany(new Document());
        coll("field_definitions").deleteMany(new Document());
    }

    private Instant utc(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    // Semester covering the whole given year.
    private ObjectId createSemester(int year) {
        Semester s = new Semester();
        s.start = utc(year, 1, 1);
        s.end = utc(year, 12, 31);
        s.createdAt = Instant.now();
        s.persist();
        return s.id;
    }

    private ObjectId createCurrency(String code, String symbol) {
        Currency c = new Currency();
        c.code = code; c.symbol = symbol;
        c.persist();
        return c.id;
    }

    private ObjectId createDefinition(ObjectId currencyId, String label) {
        KostenDefinition d = new KostenDefinition();
        d.label = label; d.currencyId = currencyId; d.active = true;
        d.persist();
        return d.id;
    }

    private void setDefault(ObjectId semesterId, ObjectId groupId, ObjectId defId, String amount) {
        KostenValue v = new KostenValue();
        v.semesterId = semesterId; v.groupId = groupId; v.definitionId = defId;
        v.amount = new BigDecimal(amount);
        v.persist();
    }

    // Creates a CHILD person in the family with a "group" assignment for the semester.
    private ObjectId createChild(ObjectId familyId, String firstName, ObjectId semesterId,
                                 ObjectId groupId, String entryDate, String exitDate) {
        // personType=CHILD basic property
        ObjectId typeDefId = new ObjectId();
        coll("field_definitions").insertOne(new Document("_id", typeDefId).append("fieldName", "personType"));
        ObjectId typeInstId = new ObjectId();
        coll("field_instances").insertOne(new Document("_id", typeInstId)
                .append("definitionId", typeDefId).append("value", "CHILD"));
        // firstName basic property
        ObjectId nameDefId = new ObjectId();
        coll("field_definitions").insertOne(new Document("_id", nameDefId).append("fieldName", "firstName"));
        ObjectId nameInstId = new ObjectId();
        coll("field_instances").insertOne(new Document("_id", nameInstId)
                .append("definitionId", nameDefId).append("value", firstName));

        Person p = new Person();
        p.familyId = familyId;
        p.basicProperties = List.of(new FieldRef(typeDefId, typeInstId), new FieldRef(nameDefId, nameInstId));
        p.persist();

        Document a = new Document("_id", new ObjectId())
                .append("personId", p.id)
                .append("semesterId", semesterId)
                .append("section", "group")
                .append("definitionId", new ObjectId())
                .append("fieldInstanceId", groupId);
        if (entryDate != null) a.append("entryDate", entryDate);
        if (exitDate != null) a.append("exitDate", exitDate);
        coll("semester_assignments").insertOne(a);
        return p.id;
    }

    private ObjectId createFamily(String name) {
        Family f = new Family();
        f.name = name; f.createdAt = Instant.now();
        f.persist();
        return f.id;
    }

    @BeforeEach
    void cleanup() {
        BilanzOverride.deleteAll();
    }

    @Test
    void putOverrideCreatesRow() {
        String personId = new ObjectId().toString();
        String definitionId = new ObjectId().toString();

        given()
            .contentType(ContentType.JSON)
            .body("{\"personId\":\"" + personId + "\",\"year\":2026,\"month\":3,"
                + "\"definitionId\":\"" + definitionId + "\",\"amount\":123.45}")
            .when().put("/api/v1/bilanzen/overrides")
            .then().statusCode(204);

        BilanzOverride o = BilanzOverride.findByKeys(
            new ObjectId(personId), 2026, 3, new ObjectId(definitionId));
        assertNotNull(o);
        assertEquals(0, new BigDecimal("123.45").compareTo(o.amount));
    }

    @Test
    void putOverrideOverwritesExistingRow() {
        String personId = new ObjectId().toString();
        String definitionId = new ObjectId().toString();
        String body = "{\"personId\":\"" + personId + "\",\"year\":2026,\"month\":3,"
            + "\"definitionId\":\"" + definitionId + "\",\"amount\":%s}";

        given().contentType(ContentType.JSON).body(String.format(body, "100.00"))
            .when().put("/api/v1/bilanzen/overrides").then().statusCode(204);
        given().contentType(ContentType.JSON).body(String.format(body, "250.00"))
            .when().put("/api/v1/bilanzen/overrides").then().statusCode(204);

        assertEquals(1, BilanzOverride.count());
        BilanzOverride o = BilanzOverride.findByKeys(
            new ObjectId(personId), 2026, 3, new ObjectId(definitionId));
        assertEquals(0, new BigDecimal("250.00").compareTo(o.amount));
    }

    @Test
    void putOverrideRejectsInvalidMonth() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"personId\":\"" + new ObjectId() + "\",\"year\":2026,\"month\":13,"
                + "\"definitionId\":\"" + new ObjectId() + "\",\"amount\":1}")
            .when().put("/api/v1/bilanzen/overrides")
            .then().statusCode(400);
    }

    @Test
    void cellReturnsDefaultAmountLine() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, defId, "340.00");
        ObjectId familyId = createFamily("Meier");
        createChild(familyId, "Anna", semesterId, groupId, null, null);

        given()
            .queryParam("familyId", familyId.toString())
            .queryParam("year", 2020).queryParam("month", 3)
            .when().get("/api/v1/bilanzen/cell")
            .then().statusCode(200)
            .body("lines.size()", is(1))
            .body("lines[0].childName", is("Anna"))
            .body("lines[0].defaultAmount", is(340.00f))
            .body("lines[0].effectiveAmount", is(340.00f))
            .body("sum", is(340.00f))
            .body("mixedCurrency", is(false));
    }

    @Test
    void cellOverrideTakesPrecedenceOverDefault() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, defId, "340.00");
        ObjectId familyId = createFamily("Meier");
        ObjectId childId = createChild(familyId, "Anna", semesterId, groupId, null, null);

        given().contentType(ContentType.JSON)
            .body("{\"personId\":\"" + childId + "\",\"year\":2020,\"month\":3,"
                + "\"definitionId\":\"" + defId + "\",\"amount\":500.00}")
            .when().put("/api/v1/bilanzen/overrides").then().statusCode(204);

        given()
            .queryParam("familyId", familyId.toString())
            .queryParam("year", 2020).queryParam("month", 3)
            .when().get("/api/v1/bilanzen/cell")
            .then().statusCode(200)
            .body("lines[0].defaultAmount", is(340.00f))
            .body("lines[0].effectiveAmount", is(500.00f))
            .body("sum", is(500.00f));
    }

    @Test
    void cellIsEmptyWhenNoCoveringSemester() {
        fullCleanup();
        ObjectId familyId = createFamily("Meier");
        given()
            .queryParam("familyId", familyId.toString())
            .queryParam("year", 2020).queryParam("month", 3)
            .when().get("/api/v1/bilanzen/cell")
            .then().statusCode(200)
            .body("lines.size()", is(0))
            .body("sum", is(0));
    }

    @Test
    void cellExcludesInactiveDefinition() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, defId, "340.00");
        // deactivate
        KostenDefinition d = KostenDefinition.findById(defId);
        d.active = false; d.update();
        ObjectId familyId = createFamily("Meier");
        createChild(familyId, "Anna", semesterId, groupId, null, null);

        given()
            .queryParam("familyId", familyId.toString())
            .queryParam("year", 2020).queryParam("month", 3)
            .when().get("/api/v1/bilanzen/cell")
            .then().statusCode(200)
            .body("lines.size()", is(0));
    }
}
