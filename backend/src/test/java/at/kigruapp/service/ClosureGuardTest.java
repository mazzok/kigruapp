package at.kigruapp.service;

import at.kigruapp.entity.ClosureDefinition;
import at.kigruapp.entity.ClosurePeriod;
import at.kigruapp.entity.FieldDefinition;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ClosureGuardTest {

    @Inject
    ClosureGuard closureGuard;

    private ObjectId definitionId;

    @BeforeEach
    void setup() {
        ClosureDefinition.deleteAll();
        ClosurePeriod.deleteAll();

        ClosureDefinition definition = new ClosureDefinition();
        definition.label = "Ferien";
        definition.color = "#d94f4f";
        definition.active = true;
        definition.createdAt = Instant.now();
        definition.persist();
        definitionId = definition.id;

        ClosurePeriod period = new ClosurePeriod();
        period.from = LocalDate.parse("2026-09-07");
        period.to = LocalDate.parse("2026-09-11");
        period.definitionId = definitionId;
        period.persist();
    }

    private static FieldDefinition cookingDutyDefinition() {
        FieldDefinition definition = new FieldDefinition();
        definition.fieldName = "cookingDuty";
        return definition;
    }

    private static FieldDefinition otherDefinition() {
        FieldDefinition definition = new FieldDefinition();
        definition.fieldName = "firstName";
        return definition;
    }

    private static Document dutyValue(String date) {
        return new Document("date", date).append("description", "Suppe");
    }

    @Test
    void dayInsideAClosurePeriodIsClosed() {
        assertTrue(closureGuard.isClosed(LocalDate.parse("2026-09-09")));
    }

    @Test
    void dayOutsideAnyClosurePeriodIsOpen() {
        assertFalse(closureGuard.isClosed(LocalDate.parse("2026-09-16")));
    }

    @Test
    void publicHolidayIsClosedEvenWithoutAPeriod() {
        // 2026-10-26, oesterreichischer Nationalfeiertag, kein erfasster Zeitraum.
        assertTrue(closureGuard.isClosed(LocalDate.parse("2026-10-26")));
    }

    @Test
    void cookingDutyOnAClosedDayIsRejected() {
        ClientErrorException thrown = assertThrows(ClientErrorException.class, () ->
            closureGuard.rejectIfClosed(cookingDutyDefinition(), dutyValue("2026-09-09")));

        assertEquals(409, thrown.getResponse().getStatus());
    }

    @Test
    void cookingDutyOnAHolidayIsRejected() {
        ClientErrorException thrown = assertThrows(ClientErrorException.class, () ->
            closureGuard.rejectIfClosed(cookingDutyDefinition(), dutyValue("2026-10-26")));

        assertEquals(409, thrown.getResponse().getStatus());
    }

    @Test
    void cookingDutyOnAnOpenDayPassesThrough() {
        assertDoesNotThrow(() ->
            closureGuard.rejectIfClosed(cookingDutyDefinition(), dutyValue("2026-09-16")));
    }

    @Test
    void otherFieldTypesAreNeverBlocked() {
        assertDoesNotThrow(() ->
            closureGuard.rejectIfClosed(otherDefinition(), dutyValue("2026-09-09")));
    }

    @Test
    void valuesWithoutAUsableDatePassThrough() {
        assertDoesNotThrow(() -> closureGuard.rejectIfClosed(cookingDutyDefinition(), null));
        assertDoesNotThrow(() -> closureGuard.rejectIfClosed(cookingDutyDefinition(), "kein Dokument"));
        assertDoesNotThrow(() ->
            closureGuard.rejectIfClosed(cookingDutyDefinition(), new Document("date", "kein Datum")));
    }
}
