package at.kigruapp.service;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class FamilyHoursTotalsServiceTest {

    @Inject
    FamilyHoursTotalsService service;

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

    private Person persistPerson(ObjectId familyId) {
        Person person = new Person();
        person.familyId = familyId;
        person.persist();
        return person;
    }

    private void persistEntry(ObjectId personId, ObjectId semesterId, String date, int minutes) {
        HourEntry entry = new HourEntry();
        entry.personId = personId;
        entry.semesterId = semesterId;
        entry.date = date;
        entry.minutes = minutes;
        entry.createdAt = Instant.now();
        entry.persist();
    }

    @Test
    void latestSemesterIdIsNullWithoutSemester() {
        assertNull(service.latestSemesterId());
    }

    @Test
    void istIsTheSumOfAllFamilyMembersEntries() {
        Semester semester = persistSemester();
        ObjectId familyId = new ObjectId();
        Person a = persistPerson(familyId);
        Person b = persistPerson(familyId);
        persistEntry(a.id, semester.id, "2026-09-10", 90);
        persistEntry(b.id, semester.id, "2026-09-11", 30);

        FamilyHoursTotalsService.Totals totals = service.totalsFor(a, semester.id);

        assertEquals(120, totals.istMinutes());
    }

    @Test
    void entriesOfOtherFamiliesAreIgnored() {
        Semester semester = persistSemester();
        Person mine = persistPerson(new ObjectId());
        Person other = persistPerson(new ObjectId());
        persistEntry(mine.id, semester.id, "2026-09-10", 60);
        persistEntry(other.id, semester.id, "2026-09-10", 600);

        assertEquals(60, service.totalsFor(mine, semester.id).istMinutes());
    }

    @Test
    void sollIsZeroWithoutRequiredHoursConfiguration() {
        Semester semester = persistSemester();
        Person person = persistPerson(new ObjectId());

        assertEquals(0, service.totalsFor(person, semester.id).sollMinutes());
    }

    @Test
    void totalsAreZeroWhenSemesterIsMissing() {
        Person person = persistPerson(new ObjectId());

        FamilyHoursTotalsService.Totals totals = service.totalsFor(person, null);

        assertEquals(0, totals.sollMinutes());
        assertEquals(0, totals.istMinutes());
    }
}
