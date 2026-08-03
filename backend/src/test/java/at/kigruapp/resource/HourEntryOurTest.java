package at.kigruapp.resource;

import at.kigruapp.entity.AliquotConfig;
import at.kigruapp.entity.Family;
import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class HourEntryOurTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        HourEntry.deleteAll();
        Person.deleteAll();
        Family.deleteAll();
        Semester.deleteAll();
        RequiredHours.deleteAll();
        AliquotConfig.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").drop();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
    }

    private String persistSemester() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z"); // 6 Monate
        s.createdAt = Instant.now();
        s.persist();
        return s.id.toHexString();
    }

    private ObjectId persistFamily() {
        Family f = new Family();
        f.name = "Muster";
        f.createdAt = Instant.now();
        f.persist();
        return f.id;
    }

    private Person persistPerson(ObjectId familyId) {
        Person p = new Person();
        p.familyId = familyId;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
    }

    private void persistEntry(ObjectId personId, String semesterId, String date, int minutes) {
        HourEntry e = new HourEntry();
        e.personId = personId;
        e.semesterId = new ObjectId(semesterId);
        e.roleLabel = "Kochen";
        e.date = date;
        e.minutes = minutes;
        e.comment = "";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.persist();
    }

    private void persistConfig(String semesterId, int def) {
        RequiredHours c = new RequiredHours();
        c.semesterId = new ObjectId(semesterId);
        c.defaultMinutesPerMonth = def;
        c.persist();
    }

    private void persistAliquot(String semesterId, String mode) {
        AliquotConfig c = new AliquotConfig();
        c.semesterId = new ObjectId(semesterId);
        c.stundenMode = mode;
        c.persist();
    }

    private void placeChild(ObjectId childPersonId, String semesterId, String entryDate, String exitDate) {
        Document a = new Document("_id", new ObjectId())
                .append("personId", childPersonId)
                .append("semesterId", new ObjectId(semesterId))
                .append("section", "group")
                .append("definitionId", new ObjectId())
                .append("fieldInstanceId", new ObjectId());
        if (entryDate != null) a.append("entryDate", entryDate);
        if (exitDate != null) a.append("exitDate", exitDate);
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").insertOne(a);
    }

    @Test
    void ourReturnsFamilyScopedEntriesAndMonthlyRows() {
        ObjectId famId = persistFamily();
        Person me = persistPerson(famId);     // single non-admin person -> current user
        Person partner = persistPerson(famId);
        String semesterId = persistSemester();
        persistConfig(semesterId, 480);        // 1 Monatssatz-Basis (kein Kind platziert -> familyMonthly 0)

        persistEntry(me.id, semesterId, "2026-10-05", 120);
        persistEntry(partner.id, semesterId, "2026-11-03", 90);

        given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("familyId", is(famId.toHexString()))
            .body("monthsInSemester", is(6))
            .body("months.size()", is(6))                       // alle Semester-Monate
            .body("months.find { it.month == '2026-10' }.istMinutes", is(120))
            .body("months.find { it.month == '2026-11' }.istMinutes", is(90))
            .body("months.find { it.month == '2026-09' }.istMinutes", is(0))
            .body("istMinutes", is(210))
            .body("entries.size()", is(2));                     // beide Elternteile sichtbar
    }

    @Test
    void ourExcludesOtherFamilies() {
        ObjectId myFam = persistFamily();
        Person me = persistPerson(myFam);
        String semesterId = persistSemester();
        persistConfig(semesterId, 480);

        ObjectId otherFam = persistFamily();
        ObjectId stranger = new ObjectId();
        persistEntry(stranger, semesterId, "2026-10-05", 999); // gehört nicht zur Familie
        // Achtung: stranger ist keine Person in myFam -> darf nicht auftauchen.
        persistEntry(me.id, semesterId, "2026-10-06", 60);

        given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("istMinutes", is(60))
            .body("entries.size()", is(1));
    }

    @Test
    void ourIncludesOutOfWindowMonthsInTotal() {
        ObjectId famId = persistFamily();
        Person me = persistPerson(famId);
        String semesterId = persistSemester(); // 2026-09-01 .. 2027-02-28
        persistConfig(semesterId, 480);

        persistEntry(me.id, semesterId, "2026-10-05", 120);   // innerhalb Semesterfenster
        persistEntry(me.id, semesterId, "2027-05-10", 75);    // außerhalb Semesterfenster, aber neuestes Semester

        given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("months.find { it.month == '2027-05' }.istMinutes", is(75))
            .body("istMinutes", is(195));

        // Invariante: istMinutes == Summe aller months[].istMinutes
        java.util.List<Integer> monthIst = given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
            .then().statusCode(200)
            .extract().path("months.istMinutes");
        int sum = monthIst.stream().mapToInt(Integer::intValue).sum();
        org.junit.jupiter.api.Assertions.assertEquals(195, sum);
    }

    @Test
    void ourPerDayAliquotProratesTotalAndEntryMonthSoll() {
        ObjectId famId = persistFamily();
        Person me = persistPerson(famId);      // erste Person -> aktueller Benutzer
        Person child = persistPerson(famId);
        String semesterId = persistSemester(); // 2026-09 .. 2027-02, 6 Monate
        persistConfig(semesterId, 480);        // 1 Kind -> 480/Monat
        persistAliquot(semesterId, "PER_DAY");
        // Eintritt Mitte November: Sep/Okt 0, Nov 15/30=0.5 -> 240, Dez/Jan/Feb je 480 -> total 1680
        placeChild(child.id, semesterId, "2026-11-16", null);

        given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("familyMonthlyMinutes", is(480))
            .body("sollMinutes", is(1680))                                  // < NONE-Wert 480*6=2880
            .body("sollMinutes", lessThan(2880))
            .body("months.find { it.month == '2026-11' }.sollMinutes", is(240)) // Eintrittsmonat pro-rata
            .body("months.find { it.month == '2026-09' }.sollMinutes", is(0))   // vor Eintritt
            .body("months.find { it.month == '2026-12' }.sollMinutes", is(480));// voller Monat
    }

    /** Gruppe als field_instance anlegen und ihre Id zurückgeben. */
    private ObjectId persistGroup(String label, String color) {
        ObjectId definitionId = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_definitions")
                .insertOne(new Document("_id", definitionId)
                        .append("fieldName", "group")
                        .append("label", new Document("de", "Gruppen"))
                        .append("outdatedAt", null));
        ObjectId instanceId = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", instanceId)
                        .append("definitionId", definitionId)
                        .append("value", new Document("label", label).append("color", color)));
        return instanceId;
    }

    /** Wie placeChild, aber mit vorgegebener Gruppe. */
    private void placeChildInGroup(ObjectId childPersonId, String semesterId, ObjectId groupInstanceId,
                                   String entryDate, String exitDate) {
        Document a = new Document("_id", new ObjectId())
                .append("personId", childPersonId)
                .append("semesterId", new ObjectId(semesterId))
                .append("section", "group")
                .append("definitionId", new ObjectId())
                .append("fieldInstanceId", groupInstanceId);
        if (entryDate != null) a.append("entryDate", entryDate);
        if (exitDate != null) a.append("exitDate", exitDate);
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").insertOne(a);
    }

    private void persistPerGroupConfig(String semesterId, ObjectId groupA, int minutesA,
                                       ObjectId groupB, int minutesB, int tierPercent) {
        RequiredHours c = new RequiredHours();
        c.semesterId = new ObjectId(semesterId);
        c.defaultMinutesPerMonth = 480;
        c.allGroups = false;
        c.order = RequiredHours.MOST_EXPENSIVE_FIRST;
        RequiredHours.GroupRate a = new RequiredHours.GroupRate();
        a.groupInstanceId = groupA;
        a.minutesPerMonth = minutesA;
        RequiredHours.GroupRate b = new RequiredHours.GroupRate();
        b.groupInstanceId = groupB;
        b.minutesPerMonth = minutesB;
        c.groupRates = new java.util.ArrayList<>(java.util.List.of(a, b));
        RequiredHours.Tier tier = new RequiredHours.Tier();
        tier.fromChild = 2;
        tier.percent = tierPercent;
        c.tiers = new java.util.ArrayList<>(java.util.List.of(tier));
        c.persist();
    }

    /** Familie mit zwei Kindern in zwei Gruppen, Sätze 480 und 300, ab 2. Kind 25 % Rabatt. */
    private String seedTwoChildFamilyWithPerGroupRates() {
        String semesterId = persistSemester();
        ObjectId familyId = persistFamily();
        Person parent = persistPerson(familyId);
        Person childA = persistPerson(familyId);
        Person childB = persistPerson(familyId);
        ObjectId kaefer = persistGroup("Käfergruppe", "#43a047");
        ObjectId baeren = persistGroup("Bärengruppe", "#fb8c00");
        placeChildInGroup(childA.id, semesterId, kaefer, null, null);
        placeChildInGroup(childB.id, semesterId, baeren, null, null);
        persistPerGroupConfig(semesterId, kaefer, 480, baeren, 300, 25);
        persistAliquot(semesterId, "NONE");
        return semesterId;
    }

    @Test
    void ourReturnsChildBreakdown() {
        String semesterId = seedTwoChildFamilyWithPerGroupRates();

        given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
                .then().statusCode(200)
                .body("allGroups", equalTo(false))
                .body("children.size()", equalTo(2))
                .body("children.groupLabel", hasItems("Käfergruppe", "Bärengruppe"))
                .body("children.find { it.groupLabel == 'Käfergruppe' }.baseMinutesPerMonth", equalTo(480))
                .body("children.find { it.groupLabel == 'Bärengruppe' }.baseMinutesPerMonth", equalTo(300))
                .body("months[0].children.size()", equalTo(2));
    }

    @Test
    void sumOfChildSollEqualsFamilySoll() {
        String semesterId = seedTwoChildFamilyWithPerGroupRates();

        var response = given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
                .then().statusCode(200).extract().jsonPath();

        int soll = response.getInt("sollMinutes");
        int sumChildren = response.getList("children.sollMinutes", Integer.class)
                .stream().mapToInt(Integer::intValue).sum();
        org.junit.jupiter.api.Assertions.assertEquals(soll, sumChildren);
    }

    @Test
    void sumOfMonthSharesEqualsMonthSoll() {
        String semesterId = seedTwoChildFamilyWithPerGroupRates();

        var response = given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
                .then().statusCode(200).extract().jsonPath();

        int months = response.getList("months").size();
        for (int i = 0; i < months; i++) {
            int monthSoll = response.getInt("months[" + i + "].sollMinutes");
            int sum = response.getList("months[" + i + "].children.minutes", Integer.class)
                    .stream().mapToInt(Integer::intValue).sum();
            org.junit.jupiter.api.Assertions.assertEquals(monthSoll, sum,
                    "Monat " + response.getString("months[" + i + "].month"));
        }
    }

    @Test
    void ourDefaultsToNewestSemesterWhenSemesterIdBlank() {
        ObjectId famId = persistFamily();
        Person me = persistPerson(famId);
        String semesterId = persistSemester(); // neuestes (einziges) Semester
        persistConfig(semesterId, 480);

        persistEntry(me.id, semesterId, "2026-10-05", 45);

        given().when().get("/api/v1/hour-entries/our")
            .then().statusCode(200)
            .body("istMinutes", is(45))
            .body("entries.size()", is(1));
    }
}
