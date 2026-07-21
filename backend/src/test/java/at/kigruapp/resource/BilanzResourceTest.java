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

    @Test
    void matrixSumsPerChildTwoChildrenSameGroupDouble() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, defId, "100.00");
        ObjectId familyId = createFamily("Meier");
        createChild(familyId, "Anna", semesterId, groupId, null, null);
        createChild(familyId, "Ben", semesterId, groupId, null, null);

        given()
            .queryParam("year", 2020)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families.size()", is(1))
            .body("families[0].name", is("Meier"))
            .body("families[0].months[2].month", is(3))     // March
            .body("families[0].months[2].amount", is(200.00f))
            .body("families[0].months[2].active", is(true))
            .body("families[0].total", is(2400.00f));         // 200 * 12
    }

    @Test
    void matrixSumsTwoChildrenDifferentGroups() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupA = new ObjectId();
        ObjectId groupB = new ObjectId();
        setDefault(semesterId, groupA, defId, "100.00");
        setDefault(semesterId, groupB, defId, "150.00");
        ObjectId familyId = createFamily("Meier");
        createChild(familyId, "Anna", semesterId, groupA, null, null);
        createChild(familyId, "Ben", semesterId, groupB, null, null);

        given()
            .queryParam("year", 2020)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families[0].months[2].amount", is(250.00f));
    }

    @Test
    void matrixOverrideBeatsDefaultInCell() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, defId, "100.00");
        ObjectId familyId = createFamily("Meier");
        ObjectId childId = createChild(familyId, "Anna", semesterId, groupId, null, null);
        given().contentType(ContentType.JSON)
            .body("{\"personId\":\"" + childId + "\",\"year\":2020,\"month\":3,"
                + "\"definitionId\":\"" + defId + "\",\"amount\":500.00}")
            .when().put("/api/v1/bilanzen/overrides").then().statusCode(204);

        given()
            .queryParam("year", 2020)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families[0].months[2].amount", is(500.00f))   // March overridden
            .body("families[0].months[1].amount", is(100.00f));  // Feb still default
    }

    @Test
    void matrixEmptyFamilyShowsZeroRow() {
        fullCleanup();
        createSemester(2020);
        ObjectId familyId = createFamily("Leer");
        given()
            .queryParam("year", 2020)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families.size()", is(1))
            .body("families[0].months[2].amount", is(0))
            .body("families[0].months[2].active", is(false))
            .body("families[0].total", is(0));
    }

    @Test
    void matrixMonthToSemesterBorderNoDoubleCount() {
        fullCleanup();
        // Semester A: 2020-01-01 .. 2020-07-31 ; Semester B: 2020-08-01 .. 2020-12-31
        Semester a = new Semester(); a.start = utc(2020,1,1); a.end = utc(2020,7,31); a.createdAt = Instant.now(); a.persist();
        Semester b = new Semester(); b.start = utc(2020,8,1); b.end = utc(2020,12,31); b.createdAt = Instant.now(); b.persist();
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(a.id, groupId, defId, "100.00");
        setDefault(b.id, groupId, defId, "200.00");
        ObjectId familyId = createFamily("Meier");
        // child assigned in BOTH semesters
        ObjectId childId = createChild(familyId, "Anna", a.id, groupId, null, null);
        // add a second group assignment for semester B for the same child
        coll("semester_assignments").insertOne(new Document("_id", new ObjectId())
            .append("personId", childId).append("semesterId", b.id)
            .append("section", "group").append("definitionId", new ObjectId())
            .append("fieldInstanceId", groupId));

        given()
            .queryParam("year", 2020)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families[0].months[6].amount", is(100.00f))   // July -> semester A
            .body("families[0].months[7].amount", is(200.00f));  // August -> semester B
    }

    @Test
    void matrixMonthWithoutCoveringSemesterIsZero() {
        fullCleanup();
        // semester only covers Feb..Nov 2020
        Semester s = new Semester(); s.start = utc(2020,2,1); s.end = utc(2020,11,30); s.createdAt = Instant.now(); s.persist();
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(s.id, groupId, defId, "100.00");
        ObjectId familyId = createFamily("Meier");
        createChild(familyId, "Anna", s.id, groupId, null, null);

        given()
            .queryParam("year", 2020)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families[0].months[0].amount", is(0))     // January: gap -> 0
            .body("families[0].months[0].active", is(false))
            .body("families[0].months[1].amount", is(100.00f)); // February
    }

    @Test
    void childOutsideEntryExitContributesZeroAndIsNotEditable() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, defId, "100.00");
        ObjectId familyId = createFamily("Meier");
        // active only May..Aug 2020
        createChild(familyId, "Anna", semesterId, groupId, "2020-05-01", "2020-08-31");

        given()
            .queryParam("year", 2020)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families[0].months[2].amount", is(0))       // March: outside window
            .body("families[0].months[2].active", is(false))
            .body("families[0].months[2].editable", is(false))
            .body("families[0].months[4].amount", is(100.00f)) // May: inside
            .body("families[0].months[4].active", is(true));
    }

    @Test
    void entryMidMonthCountsFullMonthAndSetsEntryMarker() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, defId, "100.00");
        ObjectId familyId = createFamily("Meier");
        createChild(familyId, "Anna", semesterId, groupId, "2020-05-15", null);

        given()
            .queryParam("year", 2020)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families[0].months[4].amount", is(100.00f)) // May counts full
            .body("families[0].months[4].entryMarker", is(true))
            .body("families[0].months[4].exitMarker", is(false))
            .body("families[0].months[3].amount", is(0))       // April: before entry
            .body("families[0].months[3].active", is(false));
    }

    @Test
    void exitMonthSetsExitMarkerAndOpenEndHasNoMarker() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, defId, "100.00");
        ObjectId familyId = createFamily("Meier");
        createChild(familyId, "Anna", semesterId, groupId, "2020-02-01", "2020-09-20");

        given()
            .queryParam("year", 2020)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families[0].months[8].exitMarker", is(true))   // September exit
            .body("families[0].months[8].amount", is(100.00f))    // exit month counts full
            .body("families[0].months[1].entryMarker", is(true)); // February entry
    }

    @Test
    void futureMonthsAreGreyedAndExcludedFromTotal() {
        fullCleanup();
        int futureYear = YearMonth.now().getYear() + 1;
        ObjectId semesterId = createSemester(futureYear);
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, defId, "100.00");
        ObjectId familyId = createFamily("Meier");
        createChild(familyId, "Anna", semesterId, groupId, null, null);

        given()
            .queryParam("year", futureYear)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families[0].months[0].future", is(true))
            .body("families[0].months[0].editable", is(false))
            .body("families[0].total", is(0));   // all 12 months future -> excluded
    }

    @Test
    void mixedCurrencyFlaggedWhenTwoCurrenciesContribute() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId eur = createCurrency("EUR", "€");
        ObjectId usd = createCurrency("USD", "$");
        ObjectId defEur = createDefinition(eur, "Elternbeitrag");
        ObjectId defUsd = createDefinition(usd, "Ausflug");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, defEur, "100.00");
        setDefault(semesterId, groupId, defUsd, "50.00");
        ObjectId familyId = createFamily("Meier");
        createChild(familyId, "Anna", semesterId, groupId, null, null);

        given()
            .queryParam("year", 2020)
            .when().get("/api/v1/bilanzen")
            .then().statusCode(200)
            .body("families[0].months[2].mixedCurrency", is(true));
    }

    @Test
    void everyCellHonoursFlagInvariants() {
        fullCleanup();
        int nextYear = YearMonth.now().getYear() + 1;
        // Semester covering both this year and next year to exercise future + active cells.
        Semester s = new Semester();
        s.start = utc(YearMonth.now().getYear() - 1, 1, 1);
        s.end = utc(nextYear, 12, 31);
        s.createdAt = Instant.now();
        s.persist();
        ObjectId currencyId = createCurrency("EUR", "€");
        ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(s.id, groupId, defId, "100.00");
        ObjectId familyId = createFamily("Meier");
        createChild(familyId, "Anna", s.id, groupId, nextYear + "-03-10", nextYear + "-10-20");

        for (int year : new int[]{YearMonth.now().getYear(), nextYear}) {
            var months = given()
                .queryParam("year", year)
                .when().get("/api/v1/bilanzen")
                .then().statusCode(200)
                .extract().jsonPath().getList("families[0].months");

            for (int i = 0; i < months.size(); i++) {
                @SuppressWarnings("unchecked")
                var cell = (java.util.Map<String, Object>) months.get(i);
                boolean future = (boolean) cell.get("future");
                boolean active = (boolean) cell.get("active");
                boolean editable = (boolean) cell.get("editable");
                boolean entryMarker = (boolean) cell.get("entryMarker");
                boolean exitMarker = (boolean) cell.get("exitMarker");
                boolean mixed = (boolean) cell.get("mixedCurrency");
                Number amount = (Number) cell.get("amount");

                // editable == active && !future
                assertEquals(active && !future, editable, "editable invariant at month " + (i + 1));
                // active=false => no markers, no mixed, zero amount
                if (!active) {
                    assertFalse(entryMarker, "inactive entryMarker at month " + (i + 1));
                    assertFalse(exitMarker, "inactive exitMarker at month " + (i + 1));
                    assertFalse(mixed, "inactive mixed at month " + (i + 1));
                    assertEquals(0, amount.doubleValue(), 0.0001, "inactive amount at month " + (i + 1));
                }
            }
        }
    }
}
