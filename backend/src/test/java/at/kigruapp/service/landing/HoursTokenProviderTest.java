package at.kigruapp.service.landing;

import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class HoursTokenProviderTest {

    @Inject
    HoursTokenProvider provider;

    @BeforeEach
    void cleanup() {
        Person.deleteAll();
        Semester.deleteAll();
        HourEntry.deleteAll();
        RequiredHours.deleteAll();
    }

    private Semester persistSemester() {
        Semester semester = new Semester();
        semester.start = ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
        semester.end = ZonedDateTime.of(2026, 10, 31, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
        semester.createdAt = Instant.now();
        semester.persist();
        return semester;
    }

    private Person persistPerson() {
        Person person = new Person();
        person.familyId = new ObjectId();
        person.persist();
        return person;
    }

    private void persistEntry(ObjectId personId, ObjectId semesterId, int minutes) {
        HourEntry entry = new HourEntry();
        entry.personId = personId;
        entry.semesterId = semesterId;
        entry.date = "2026-09-10";
        entry.minutes = minutes;
        entry.createdAt = Instant.now();
        entry.persist();
    }

    @Test
    void placeholdersCoverAllThreeHourTokens() {
        List<String> tokens = provider.placeholders().stream().map(LandingPlaceholder::token).toList();

        assertTrue(tokens.contains("{{stunden.geleistet}}"), tokens.toString());
        assertTrue(tokens.contains("{{stunden.soll}}"), tokens.toString());
        assertTrue(tokens.contains("{{stunden.bilanz}}"), tokens.toString());
    }

    @Test
    void placeholdersAreGroupedAsStunden() {
        assertTrue(provider.placeholders().stream().allMatch(p -> "stunden".equals(p.group())));
    }

    @Test
    void geleisteteMinutenWerdenAlsStundenMitKommaFormatiert() {
        Semester semester = persistSemester();
        Person person = persistPerson();
        persistEntry(person.id, semester.id, 150);

        Map<String, String> values = provider.values(person);

        assertEquals("2,5", values.get("{{stunden.geleistet}}"));
    }

    @Test
    void bilanzIstGeleistetMinusSollUndZeigtVorzeichen() {
        Semester semester = persistSemester();
        Person person = persistPerson();
        persistEntry(person.id, semester.id, 60);
        // Ohne RequiredHours ist soll = 0, die Bilanz also positiv.

        assertEquals("1,0", provider.values(person).get("{{stunden.bilanz}}"));
    }

    @Test
    void ohneSemesterLiefertLeereWerteStattFehler() {
        Person person = persistPerson();

        Map<String, String> values = provider.values(person);

        assertEquals("", values.get("{{stunden.geleistet}}"));
        assertEquals("", values.get("{{stunden.soll}}"));
        assertEquals("", values.get("{{stunden.bilanz}}"));
    }

    @Test
    void stundenTokensStimmenMitDemOurEndpunktUeberein() {
        Semester semester = persistSemester();
        Person person = persistPerson();
        persistEntry(person.id, semester.id, 150);

        int istMinutesFromOurEndpoint = io.restassured.RestAssured.given()
                .when().get("/api/v1/hour-entries/our?semesterId=" + semester.id)
                .then().statusCode(200)
                .extract().path("istMinutes");

        // Der Provider formatiert dieselbe Zahl, die /our als Minuten ausweist.
        assertEquals(
                String.format(java.util.Locale.GERMAN, "%.1f", istMinutesFromOurEndpoint / 60.0),
                provider.values(person).get("{{stunden.geleistet}}"));
    }
}
