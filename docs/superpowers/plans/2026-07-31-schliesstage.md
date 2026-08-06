# Schließtage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admins erfassen Schließzeiten des Kindergartens über einen Mehrmonatskalender; Eltern sehen sie schreibgeschützt, und Kochdienste können an Schließtagen nicht mehr angelegt werden.

**Architecture:** Zwei MongoDB-Collections (`closure_definitions`, `closure_periods`) mit Zeiträumen `{from, to, definitionId}`. Sämtliche Split- und Merge-Regeln liegen in einer DB-freien, rein per Unit-Test abgedeckten Klasse `ClosurePeriodNormalizer`; das Frontend sendet nur die rohe Tagesauswahl. Eine selbst gebaute, zwischen Admin- und Elternansicht geteilte Angular-Komponente rendert die Monate als CSS Grid.

**Tech Stack:** Quarkus 3.36.1, Java 17, MongoDB Panache, RESTassured/JUnit 5 · Angular 18, Angular Material 18, Karma/Jasmine · Jollyday 1.5.2 für Feiertage

## Global Constraints

- Java 17 (`maven.compiler.release=17`), Quarkus 3.36.1 — keine neueren Sprachfeatures.
- Angular 18.2 / Angular Material 18.2, Standalone Components, `@if`/`@for` Control Flow.
- **Keine neue UI-Bibliothek.** `angular-calendar` bleibt ausschließlich beim Kochdienst.
- Backend-Entities erben von `PanacheMongoEntity` mit `@MongoEntity(collection = "...")`, Muster: `at.kigruapp.entity.Currency`.
- Frontend-Services nutzen `ApiService` (`get`/`post`/`put`/`delete`), Basis-URL `/api/v1`, Muster: `CurrencyService`.
- Kalendertage sind `LocalDate` (ISO `yyyy-MM-dd`), niemals `Instant`.
- Alle sichtbaren Texte auf Deutsch.
- Farbwahl über `<input matInput type="color">`, Default `#4285f4` — identisch zu Gruppen und Teams.
- Ein Zeitraum beginnt und endet immer auf einem auswählbaren Tag (Werktag, kein Feiertag). Wochenenden und Feiertage dürfen *innerhalb* eines Zeitraums liegen.
- Tests: Backend `./mvnw test`, Frontend `npm test -- --watch=false --browsers=ChromeHeadless` (im Verzeichnis `frontend/`).
- **Vorbestand:** `main` hat 13 bereits vorher fehlschlagende Backend-Tests und 1 fehlschlagenden Frontend-Test. Nur neue oder verschlechterte Fehlschläge sind ein Problem.

**Referenz-Spec:** `docs/superpowers/specs/2026-07-31-schliesstage-design.md`

---

## File Structure

**Backend — neu**

| Datei | Verantwortung |
| --- | --- |
| `entity/ClosureDefinition.java` | Stammdaten einer Schließtag-Art |
| `entity/ClosurePeriod.java` | ein Zeitraum mit Verweis auf eine Definition |
| `service/ClosurePeriodNormalizer.java` | Split-/Merge-Regeln, DB-frei |
| `service/HolidayService.java` | Feiertage über Jollyday, konfigurationsgesteuert |
| `service/ClosureGuard.java` | „ist dieser Tag geschlossen?" — eine Quelle für alle Sperren |
| `resource/ClosureDefinitionResource.java` | CRUD, Deaktivieren, Kopie-Flow |
| `resource/ClosurePeriodResource.java` | Laden nach Zeitfenster, `apply` |
| `resource/HolidayResource.java` | Feiertage nach Zeitfenster |

**Backend — geändert**

| Datei | Änderung |
| --- | --- |
| `pom.xml` | Jollyday-Abhängigkeiten |
| `resource/FieldInstanceResource.java` | `ClosureGuard` in `create` und `update` |
| `src/main/resources/application.properties` | `kigruapp.holidays.*` |

**Frontend — neu**

| Datei | Verantwortung |
| --- | --- |
| `shared/models/closure.model.ts` | Typen für Definitionen, Zeiträume, Feiertage |
| `shared/services/closure-definition.service.ts` | Definitionen |
| `shared/services/closure-period.service.ts` | Zeiträume |
| `shared/services/holiday.service.ts` | Feiertage |
| `shared/components/closure-calendar/closure-calendar.component.*` | Kalenderraster, Darstellung und Auswahl |
| `settings/schliesstage/schliesstage.component.*` | Admin-Maske |
| `schliesstage/schliesstage-view.component.*` | Elternansicht |

**Frontend — geändert**

| Datei | Änderung |
| --- | --- |
| `settings/organisation/organisation.component.html` | neuer Tab, bindet `<app-schliesstage>` ein |
| `settings/organisation/organisation.component.ts` | Import der Kind-Komponente |
| `app.routes.ts` | Route `/schliesstage` |
| `app.component.html` | Navigationspunkt |
| `cooking/cooking.component.ts` / `.html` | Einfärbung und Konfliktmarkierung |
| `cooking/cooking-duty-dialog.component.ts` / `.html` | Datepicker-Sperre |

`organisation.component.ts` umfasst bereits 582 Zeilen. Die Schließtage-Logik kommt deshalb in eine eigenständige Kind-Komponente; der Tab bindet sie nur ein.

---

## Task 1: Normalizer — Zuweisen und Verschmelzen

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/ClosurePeriodNormalizer.java`
- Test: `backend/src/test/java/at/kigruapp/service/ClosurePeriodNormalizerAssignTest.java`

**Interfaces:**
- Consumes: nichts
- Produces:
  - `record ClosurePeriodNormalizer.DateSpan(LocalDate from, LocalDate to)`
  - `static List<DateSpan> assign(List<DateSpan> existing, Collection<LocalDate> days, Predicate<LocalDate> selectable)` — liefert die vollständige, nach `from` sortierte Liste für **eine** Definition
  - `static boolean isWeekday(LocalDate d)` — Montag bis Freitag

Der `selectable`-Predicate entscheidet, ob ein Tag ein echter Betriebstag ist. Damit deckt dieselbe Logik Wochenenden *und* Feiertage ab, ohne dass der Normalizer den Feiertagskalender kennt.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/service/ClosurePeriodNormalizerAssignTest.java`:

```java
package at.kigruapp.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

import static at.kigruapp.service.ClosurePeriodNormalizer.DateSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClosurePeriodNormalizerAssignTest {

    // Montag bis Freitag sind Betriebstage, Wochenenden nicht.
    private static final Predicate<LocalDate> WEEKDAYS = ClosurePeriodNormalizer::isWeekday;

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static DateSpan span(String from, String to) {
        return new DateSpan(d(from), d(to));
    }

    @Test
    void singleDayBecomesOneSpan() {
        // 2026-09-07 ist ein Montag.
        List<DateSpan> result =
            ClosurePeriodNormalizer.assign(List.of(), List.of(d("2026-09-07")), WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-07")), result);
    }

    @Test
    void consecutiveDaysBecomeOneSpan() {
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(),
            List.of(d("2026-09-07"), d("2026-09-08"), d("2026-09-09")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-09")), result);
    }

    @Test
    void weekendGapIsBridged() {
        // Mo 07. bis Fr 11., dann Mo 14. bis Fr 18. — dazwischen nur Sa/So.
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-09-14"), d("2026-09-15"), d("2026-09-16"),
                    d("2026-09-17"), d("2026-09-18")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-18")), result);
    }

    @Test
    void workingDayGapIsNotBridged() {
        // Zwischen Di 08. und Do 10. liegt der Betriebstag Mi 09.
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-09-07", "2026-09-08")),
            List.of(d("2026-09-10")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-08"),
                             span("2026-09-10", "2026-09-10")), result);
    }

    @Test
    void filledGapMergesBothNeighbours() {
        // Lücke Mi 09. schließt zwei bestehende Zeiträume zusammen.
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-09-07", "2026-09-08"), span("2026-09-10", "2026-09-11")),
            List.of(d("2026-09-09")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-11")), result);
    }

    @Test
    void overlappingAssignmentIsAbsorbed() {
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-09-09"), d("2026-09-10")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-11")), result);
    }

    @Test
    void holidayGapIsBridgedViaPredicate() {
        // 2026-10-26 ist ein Montag und in Österreich Nationalfeiertag.
        Predicate<LocalDate> selectable =
            day -> ClosurePeriodNormalizer.isWeekday(day) && !day.equals(d("2026-10-26"));

        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-10-23", "2026-10-23")),
            List.of(d("2026-10-27")),
            selectable);

        assertEquals(List.of(span("2026-10-23", "2026-10-27")), result);
    }

    @Test
    void resultIsSortedByFrom() {
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-11-02", "2026-11-03")),
            List.of(d("2026-09-07")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-07"),
                             span("2026-11-02", "2026-11-03")), result);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClosurePeriodNormalizerAssignTest`
Expected: Compilerfehler — `package at.kigruapp.service does not exist` bzw. `cannot find symbol: class ClosurePeriodNormalizer`.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/at/kigruapp/service/ClosurePeriodNormalizer.java`:

```java
package at.kigruapp.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Split- und Merge-Regeln fuer Schliesszeitraeume einer einzelnen Definition.
 *
 * <p>Bewusst ohne Datenbankzugriff, damit die gesamte Regellogik per Unit-Test
 * abgedeckt werden kann. Der {@code selectable}-Predicate entscheidet, ob ein Tag
 * ein Betriebstag ist; damit deckt dieselbe Logik Wochenenden und Feiertage ab.
 */
public final class ClosurePeriodNormalizer {

    private ClosurePeriodNormalizer() {
    }

    public record DateSpan(LocalDate from, LocalDate to) {
    }

    public static boolean isWeekday(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    public static List<DateSpan> assign(List<DateSpan> existing,
                                        Collection<LocalDate> days,
                                        Predicate<LocalDate> selectable) {
        List<DateSpan> all = new ArrayList<>(existing);
        all.addAll(toSpans(days));
        return coalesce(all, selectable);
    }

    /** Verdichtet eine Tagesmenge zu zusammenhaengenden Spans. */
    private static List<DateSpan> toSpans(Collection<LocalDate> days) {
        List<LocalDate> sorted = days.stream().distinct().sorted().toList();
        List<DateSpan> spans = new ArrayList<>();
        for (LocalDate day : sorted) {
            if (!spans.isEmpty()) {
                DateSpan last = spans.get(spans.size() - 1);
                if (last.to().plusDays(1).equals(day)) {
                    spans.set(spans.size() - 1, new DateSpan(last.from(), day));
                    continue;
                }
            }
            spans.add(new DateSpan(day, day));
        }
        return spans;
    }

    private static List<DateSpan> coalesce(List<DateSpan> spans, Predicate<LocalDate> selectable) {
        List<DateSpan> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparing(DateSpan::from).thenComparing(DateSpan::to));

        List<DateSpan> out = new ArrayList<>();
        for (DateSpan span : sorted) {
            if (out.isEmpty()) {
                out.add(span);
                continue;
            }
            DateSpan last = out.get(out.size() - 1);
            if (bridgeable(last, span, selectable)) {
                LocalDate end = last.to().isAfter(span.to()) ? last.to() : span.to();
                out.set(out.size() - 1, new DateSpan(last.from(), end));
            } else {
                out.add(span);
            }
        }
        return out;
    }

    /**
     * Zwei Spans gelten als verbindbar, wenn sie ueberlappen, direkt aneinander
     * grenzen, oder die Luecke dazwischen ausschliesslich aus Nicht-Betriebstagen
     * besteht. Ohne die letzte Regel wuerden zusammenhaengende Ferien in
     * Wochenpakete zerfallen.
     */
    private static boolean bridgeable(DateSpan before, DateSpan after, Predicate<LocalDate> selectable) {
        if (!after.from().isAfter(before.to())) {
            return true;
        }
        for (LocalDate day = before.to().plusDays(1); day.isBefore(after.from()); day = day.plusDays(1)) {
            if (selectable.test(day)) {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ClosurePeriodNormalizerAssignTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/ClosurePeriodNormalizer.java \
        backend/src/test/java/at/kigruapp/service/ClosurePeriodNormalizerAssignTest.java
git commit -m "feat(be): Normalizer fuer Zuweisung und Verschmelzung von Schliesszeitraeumen"
```

---

## Task 2: Normalizer — Entfernen, Split und Randverschiebung

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/ClosurePeriodNormalizer.java`
- Test: `backend/src/test/java/at/kigruapp/service/ClosurePeriodNormalizerRemoveTest.java`

**Interfaces:**
- Consumes: `DateSpan`, `isWeekday` aus Task 1
- Produces: `static List<DateSpan> remove(List<DateSpan> existing, Collection<LocalDate> days, Predicate<LocalDate> selectable)`

Nach dem Entfernen wird **nicht** neu verschmolzen — Entfernen kann keine Verbindung erzeugen, sondern nur trennen. Wichtig ist die Randbereinigung: bleibt nach dem Herausschneiden ein Rest übrig, der nur aus Wochenenden oder Feiertagen besteht, wird er verworfen. Sonst entstünde ein Zeitraum, der ausschließlich aus Tagen besteht, an denen ohnehin geschlossen ist.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/service/ClosurePeriodNormalizerRemoveTest.java`:

```java
package at.kigruapp.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

import static at.kigruapp.service.ClosurePeriodNormalizer.DateSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClosurePeriodNormalizerRemoveTest {

    private static final Predicate<LocalDate> WEEKDAYS = ClosurePeriodNormalizer::isWeekday;

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static DateSpan span(String from, String to) {
        return new DateSpan(d(from), d(to));
    }

    @Test
    void removingFromTheMiddleSplitsTheSpan() {
        // Mo 07. bis Fr 11., Mi 09. wird herausgenommen.
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-09-09")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-08"),
                             span("2026-09-10", "2026-09-11")), result);
    }

    @Test
    void removingAtTheStartMovesFrom() {
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-09-07"), d("2026-09-08")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-09", "2026-09-11")), result);
    }

    @Test
    void removingAtTheEndMovesTo() {
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-09-10"), d("2026-09-11")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-09")), result);
    }

    @Test
    void removingEverythingDropsTheSpan() {
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-08")),
            List.of(d("2026-09-07"), d("2026-09-08")),
            WEEKDAYS);

        assertTrue(result.isEmpty());
    }

    @Test
    void weekendOnlyRemnantIsDropped() {
        // Mo 07. bis Mo 14.; alle Werktage der ersten Woche und Mo 14. gehen weg.
        // Uebrig blieben nur Sa 12. und So 13. — kein sinnvoller Zeitraum.
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-14")),
            List.of(d("2026-09-07"), d("2026-09-08"), d("2026-09-09"),
                    d("2026-09-10"), d("2026-09-11"), d("2026-09-14")),
            WEEKDAYS);

        assertTrue(result.isEmpty());
    }

    @Test
    void spanEndsAreTrimmedToSelectableDays() {
        // Mo 07. bis Mo 14.; nur Mo 14. geht weg. Der Rest darf nicht auf So 13. enden.
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-14")),
            List.of(d("2026-09-14")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-11")), result);
    }

    @Test
    void removingUnrelatedDaysChangesNothing() {
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-11-02")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-11")), result);
    }

    @Test
    void splitDoesNotRemergeAcrossTheRemovedDay() {
        // Gegenprobe zu Task 1: nach dem Entfernen darf nicht wieder verschmolzen werden.
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-18")),
            List.of(d("2026-09-09")),
            WEEKDAYS);

        assertEquals(2, result.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClosurePeriodNormalizerRemoveTest`
Expected: Compilerfehler — `cannot find symbol: method remove(...)`.

- [ ] **Step 3: Write minimal implementation**

In `ClosurePeriodNormalizer.java` ergänzen — die Imports `java.util.HashSet` und `java.util.Set` zur Importliste hinzufügen, dann nach `assign(...)` einfügen:

```java
    /**
     * Nimmt Tage aus bestehenden Zeitraeumen heraus. Es wird bewusst nicht neu
     * verschmolzen: Entfernen kann nur trennen, nie verbinden.
     */
    public static List<DateSpan> remove(List<DateSpan> existing,
                                        Collection<LocalDate> days,
                                        Predicate<LocalDate> selectable) {
        Set<LocalDate> cut = new HashSet<>(days);
        List<DateSpan> out = new ArrayList<>();

        for (DateSpan span : existing) {
            LocalDate start = null;
            for (LocalDate day = span.from(); !day.isAfter(span.to()); day = day.plusDays(1)) {
                if (cut.contains(day)) {
                    if (start != null) {
                        addTrimmed(out, start, day.minusDays(1), selectable);
                        start = null;
                    }
                } else if (start == null) {
                    start = day;
                }
            }
            if (start != null) {
                addTrimmed(out, start, span.to(), selectable);
            }
        }

        out.sort(Comparator.comparing(DateSpan::from));
        return out;
    }

    /**
     * Beschneidet einen Rest auf Betriebstage und verwirft ihn, wenn danach nichts
     * uebrig bleibt. Haelt die Invariante, dass ein Zeitraum immer auf einem
     * auswaehlbaren Tag beginnt und endet.
     */
    private static void addTrimmed(List<DateSpan> out, LocalDate from, LocalDate to,
                                   Predicate<LocalDate> selectable) {
        LocalDate first = from;
        LocalDate last = to;
        while (!first.isAfter(last) && !selectable.test(first)) {
            first = first.plusDays(1);
        }
        while (!last.isBefore(first) && !selectable.test(last)) {
            last = last.minusDays(1);
        }
        if (!first.isAfter(last)) {
            out.add(new DateSpan(first, last));
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest='ClosurePeriodNormalizer*Test'`
Expected: `Tests run: 16, Failures: 0, Errors: 0` — beide Klassen grün.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/ClosurePeriodNormalizer.java \
        backend/src/test/java/at/kigruapp/service/ClosurePeriodNormalizerRemoveTest.java
git commit -m "feat(be): Normalizer entfernt Tage mit Split und Randbereinigung"
```

---

## Task 3: Entities und Definitions-Endpoint

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/ClosureDefinition.java`
- Create: `backend/src/main/java/at/kigruapp/entity/ClosurePeriod.java`
- Create: `backend/src/main/java/at/kigruapp/resource/ClosureDefinitionResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/ClosureDefinitionResourceTest.java`

**Interfaces:**
- Consumes: nichts aus Task 1/2
- Produces:
  - `ClosureDefinition` mit den öffentlichen Feldern `label` (String), `color` (String), `active` (boolean), `createdAt` (Instant)
  - `ClosurePeriod` mit den öffentlichen Feldern `from` (LocalDate), `to` (LocalDate), `definitionId` (ObjectId) und `static List<ClosurePeriod> findByDefinition(ObjectId definitionId)`
  - Endpoints `GET/POST /api/v1/closure-definitions`, `PUT/DELETE /api/v1/closure-definitions/{id}`, `POST /api/v1/closure-definitions/{id}/revise`

`ClosurePeriod` wird hier nur angelegt, weil `ClosureDefinitionResource` prüfen muss, ob Zeiträume verknüpft sind. Befüllt wird die Collection erst in Task 5.

Fachliche Regeln dieses Endpoints:
- `GET` liefert nach `createdAt` absteigend — die zuletzt angelegte Definition steht oben, also auch die Kopie aus `revise`.
- `GET` liefert standardmäßig nur aktive Definitionen; `?includeInactive=true` liefert alle.
- `PUT` ist nur erlaubt, solange **keine** Zeiträume verknüpft sind, sonst 409. Damit gilt die Regel auch für Clients, die den Warndialog umgehen.
- `POST /{id}/revise` legt eine Kopie mit den neuen Werten an und setzt das Original auf `active = false`.
- `DELETE` löscht nicht, sondern deaktiviert.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/resource/ClosureDefinitionResourceTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.ClosureDefinition;
import at.kigruapp.entity.ClosurePeriod;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class ClosureDefinitionResourceTest {

    @BeforeEach
    void cleanup() {
        ClosureDefinition.deleteAll();
        ClosurePeriod.deleteAll();
    }

    private String createDefinition(String label, String color) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"" + label + "\", \"color\": \"" + color + "\"}")
            .when().post("/api/v1/closure-definitions")
            .then().statusCode(201)
            .extract().path("id");
    }

    private void linkPeriod(String definitionId) {
        ClosurePeriod period = new ClosurePeriod();
        period.from = LocalDate.parse("2026-09-07");
        period.to = LocalDate.parse("2026-09-11");
        period.definitionId = new ObjectId(definitionId);
        period.persist();
    }

    @Test
    void createAndList() {
        createDefinition("Ferien", "#d94f4f");

        given()
            .when().get("/api/v1/closure-definitions")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].label", is("Ferien"))
            .body("[0].color", is("#d94f4f"))
            .body("[0].active", is(true));
    }

    @Test
    void rejectsMissingLabel() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"\", \"color\": \"#d94f4f\"}")
            .when().post("/api/v1/closure-definitions")
            .then().statusCode(400);
    }

    @Test
    void rejectsMissingColor() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien\", \"color\": \"\"}")
            .when().post("/api/v1/closure-definitions")
            .then().statusCode(400);
    }

    @Test
    void updateIsAllowedWhileNoPeriodsAreLinked() {
        String id = createDefinition("Ferien", "#d94f4f");

        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien neu\", \"color\": \"#4f86d9\"}")
            .when().put("/api/v1/closure-definitions/" + id)
            .then().statusCode(200)
            .body("label", is("Ferien neu"))
            .body("color", is("#4f86d9"));
    }

    @Test
    void updateIsRejectedOncePeriodsAreLinked() {
        String id = createDefinition("Ferien", "#d94f4f");
        linkPeriod(id);

        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien neu\", \"color\": \"#4f86d9\"}")
            .when().put("/api/v1/closure-definitions/" + id)
            .then().statusCode(409);
    }

    @Test
    void reviseCreatesCopyAndDeactivatesOriginal() {
        String id = createDefinition("Ferien", "#d94f4f");
        linkPeriod(id);

        String copyId = given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien neu\", \"color\": \"#4f86d9\"}")
            .when().post("/api/v1/closure-definitions/" + id + "/revise")
            .then().statusCode(201)
            .body("label", is("Ferien neu"))
            .body("color", is("#4f86d9"))
            .body("active", is(true))
            .extract().path("id");

        // Die aktive Liste enthaelt nur noch die Kopie.
        given()
            .when().get("/api/v1/closure-definitions")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].id", is(copyId));

        // Das Original bleibt unveraendert erhalten, nur deaktiviert.
        given()
            .when().get("/api/v1/closure-definitions?includeInactive=true")
            .then().statusCode(200)
            .body("$.size()", is(2))
            .body("find { it.id == '" + id + "' }.label", is("Ferien"))
            .body("find { it.id == '" + id + "' }.color", is("#d94f4f"))
            .body("find { it.id == '" + id + "' }.active", is(false));
    }

    @Test
    void deleteOnlyDeactivates() {
        String id = createDefinition("Ferien", "#d94f4f");

        given()
            .when().delete("/api/v1/closure-definitions/" + id)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/closure-definitions")
            .then().statusCode(200)
            .body("$.size()", is(0));

        given()
            .when().get("/api/v1/closure-definitions?includeInactive=true")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].active", is(false));
    }

    @Test
    void reactivateViaUpdate() {
        String id = createDefinition("Ferien", "#d94f4f");
        given().when().delete("/api/v1/closure-definitions/" + id).then().statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien\", \"color\": \"#d94f4f\", \"active\": true}")
            .when().put("/api/v1/closure-definitions/" + id)
            .then().statusCode(200)
            .body("active", is(true));
    }

    @Test
    void linkedDefinitionCanStillBeReactivated() {
        // Nur das active-Flag kippt, Label und Farbe bleiben — das darf kein 409 geben,
        // sonst waere eine verknuepfte Definition dauerhaft deaktiviert.
        String id = createDefinition("Ferien", "#d94f4f");
        linkPeriod(id);
        given().when().delete("/api/v1/closure-definitions/" + id).then().statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien\", \"color\": \"#d94f4f\", \"active\": true}")
            .when().put("/api/v1/closure-definitions/" + id)
            .then().statusCode(200)
            .body("active", is(true));
    }

    @Test
    void newestDefinitionComesFirst() {
        createDefinition("Ferien", "#d94f4f");
        createDefinition("Fortbildung", "#e0a020");

        given()
            .when().get("/api/v1/closure-definitions")
            .then().statusCode(200)
            .body("[0].label", is("Fortbildung"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClosureDefinitionResourceTest`
Expected: Compilerfehler — `cannot find symbol: class ClosureDefinition`.

- [ ] **Step 3a: Write the entities**

`backend/src/main/java/at/kigruapp/entity/ClosureDefinition.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;

/**
 * Art eines Schliesstags, z.B. "Weihnachtsferien". Generell und immer gueltig,
 * ohne Datums- oder Semesterbezug.
 */
@MongoEntity(collection = "closure_definitions")
public class ClosureDefinition extends PanacheMongoEntity {
    public String label;
    public String color;
    public boolean active;
    public Instant createdAt;
}
```

`backend/src/main/java/at/kigruapp/entity/ClosurePeriod.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.LocalDate;
import java.util.List;

/**
 * Ein zusammenhaengender Schliesszeitraum, beide Grenzen inklusive.
 *
 * <p>LocalDate statt Instant: Schliesstage sind Kalendertage ohne Uhrzeit,
 * Instant wuerde bei jedem Zonenwechsel die Tagesgrenzen verschieben.
 * Kein Semesterfeld — das Semester ist nur das Anzeigefenster.
 */
@MongoEntity(collection = "closure_periods")
public class ClosurePeriod extends PanacheMongoEntity {
    public LocalDate from;
    public LocalDate to;
    public ObjectId definitionId;

    public static List<ClosurePeriod> findByDefinition(ObjectId definitionId) {
        return list("definitionId", definitionId);
    }

    /** Alle Zeitraeume, die das Fenster [from, to] beruehren. */
    public static List<ClosurePeriod> findOverlapping(LocalDate from, LocalDate to) {
        return list("{'from': {'$lte': ?1}, 'to': {'$gte': ?2}}", to, from);
    }
}
```

- [ ] **Step 3b: Write the resource**

`backend/src/main/java/at/kigruapp/resource/ClosureDefinitionResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.ClosureDefinition;
import at.kigruapp.entity.ClosurePeriod;
import io.quarkus.panache.common.Sort;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;

@Path("/api/v1/closure-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClosureDefinitionResource {

    public record DefinitionRequest(String label, String color, Boolean active) {}

    @GET
    public List<ClosureDefinition> list(@QueryParam("includeInactive") boolean includeInactive) {
        Sort newestFirst = Sort.descending("createdAt");
        if (includeInactive) {
            return ClosureDefinition.listAll(newestFirst);
        }
        return ClosureDefinition.list("active", newestFirst, true);
    }

    @POST
    public Response create(DefinitionRequest request) {
        validate(request);

        ClosureDefinition definition = new ClosureDefinition();
        definition.label = request.label().trim();
        definition.color = request.color().trim();
        definition.active = true;
        definition.createdAt = Instant.now();
        definition.persist();
        return Response.status(201).entity(definition).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") String id, DefinitionRequest request) {
        validate(request);
        ClosureDefinition definition = findOr404(id);

        boolean contentChanged = !definition.label.equals(request.label().trim())
            || !definition.color.equals(request.color().trim());

        // Reines Aktivieren/Deaktivieren bleibt erlaubt — sonst liesse sich eine
        // verknuepfte Definition nie wieder reaktivieren.
        if (contentChanged && !ClosurePeriod.findByDefinition(definition.id).isEmpty()) {
            throw new ClientErrorException(
                "Definition ist mit Zeitraeumen verknuepft und kann nicht geaendert werden. "
                    + "Bitte ueber /revise eine Kopie anlegen.",
                Response.Status.CONFLICT);
        }

        definition.label = request.label().trim();
        definition.color = request.color().trim();
        if (request.active() != null) {
            definition.active = request.active();
        }
        definition.update();
        return Response.ok(definition).build();
    }

    /**
     * Legt eine Kopie mit den neuen Werten an und deaktiviert das Original.
     * Bereits verknuepfte Zeitraeume behalten Label und Farbe von damals.
     */
    @POST
    @Path("/{id}/revise")
    public Response revise(@PathParam("id") String id, DefinitionRequest request) {
        validate(request);
        ClosureDefinition original = findOr404(id);

        ClosureDefinition copy = new ClosureDefinition();
        copy.label = request.label().trim();
        copy.color = request.color().trim();
        copy.active = true;
        copy.createdAt = Instant.now();
        copy.persist();

        original.active = false;
        original.update();

        return Response.status(201).entity(copy).build();
    }

    /** Loescht nicht, sondern deaktiviert — verknuepfte Zeitraeume bleiben gueltig. */
    @DELETE
    @Path("/{id}")
    public Response deactivate(@PathParam("id") String id) {
        ClosureDefinition definition = findOr404(id);
        definition.active = false;
        definition.update();
        return Response.noContent().build();
    }

    private void validate(DefinitionRequest request) {
        if (request == null || request.label() == null || request.label().isBlank()) {
            throw new BadRequestException("label is required");
        }
        if (request.color() == null || request.color().isBlank()) {
            throw new BadRequestException("color is required");
        }
    }

    private ClosureDefinition findOr404(String id) {
        if (!ObjectId.isValid(id)) {
            throw new NotFoundException("Definition nicht gefunden");
        }
        ClosureDefinition definition = ClosureDefinition.findById(new ObjectId(id));
        if (definition == null) {
            throw new NotFoundException("Definition nicht gefunden");
        }
        return definition;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ClosureDefinitionResourceTest`
Expected: `Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/ClosureDefinition.java \
        backend/src/main/java/at/kigruapp/entity/ClosurePeriod.java \
        backend/src/main/java/at/kigruapp/resource/ClosureDefinitionResource.java \
        backend/src/test/java/at/kigruapp/resource/ClosureDefinitionResourceTest.java
git commit -m "feat(be): Entities und Endpoint fuer Schliesstag-Definitionen mit Kopie-Flow"
```

---

## Task 4: Feiertage über Jollyday

**Files:**
- Modify: `backend/pom.xml` (Abhängigkeiten)
- Modify: `backend/src/main/resources/application.properties`
- Create: `backend/src/main/java/at/kigruapp/service/HolidayService.java`
- Create: `backend/src/main/java/at/kigruapp/resource/HolidayResource.java`
- Test: `backend/src/test/java/at/kigruapp/service/HolidayServiceTest.java`

**Interfaces:**
- Consumes: nichts
- Produces:
  - `record HolidayService.HolidayDto(LocalDate date, String name)`
  - `List<HolidayDto> HolidayService.between(LocalDate from, LocalDate to)`
  - `Set<LocalDate> HolidayService.datesBetween(LocalDate from, LocalDate to)` — von Task 5 und 6 benötigt
  - Endpoint `GET /api/v1/holidays?from=&to=`

Konfiguration einmalig beim Deploy über `kigruapp.holidays.country` (ISO 3166-1 alpha-2) und optional `kigruapp.holidays.subdivision` (ISO 3166-2). Fehlt die Property oder ist der Wert unbekannt, liefert der Service eine leere Liste — kein Fehler, kein Startabbruch. Der Kalender zeigt dann schlicht keine Feiertage.

- [ ] **Step 1: Add the dependencies and configuration**

In `backend/pom.xml` innerhalb von `<dependencies>` ergänzen:

```xml
        <dependency>
            <groupId>de.focus-shift</groupId>
            <artifactId>jollyday-core</artifactId>
            <version>1.5.2</version>
        </dependency>
        <dependency>
            <groupId>de.focus-shift</groupId>
            <artifactId>jollyday-jackson</artifactId>
            <version>1.5.2</version>
        </dependency>
```

`jollyday-jackson` stellt den Standard-`ConfigurationService` bereit; ohne ihn findet `jollyday-core` die Feiertagsdefinitionen nicht. Die JAXB-Variante wird bewusst nicht genommen — Jackson ist in Quarkus ohnehin vorhanden.

In `backend/src/main/resources/application.properties` am Ende ergänzen:

```properties
# Feiertage: ISO 3166-1 alpha-2 fuer das Land, ISO 3166-2 fuer die Region.
# Leer lassen deaktiviert die Feiertagsanzeige.
kigruapp.holidays.country=AT
kigruapp.holidays.subdivision=
```

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/at/kigruapp/service/HolidayServiceTest.java`:

```java
package at.kigruapp.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class HolidayServiceTest {

    @Inject
    HolidayService holidayService;

    @Test
    void findsAustrianNationalHoliday() {
        List<HolidayService.HolidayDto> holidays =
            holidayService.between(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

        assertTrue(holidays.stream().anyMatch(h -> h.date().equals(LocalDate.parse("2026-10-26"))),
            "Nationalfeiertag am 26.10. erwartet, erhalten: " + holidays);
    }

    @Test
    void resultIsLimitedToTheRequestedWindow() {
        List<HolidayService.HolidayDto> holidays =
            holidayService.between(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

        assertTrue(holidays.stream().allMatch(h ->
                !h.date().isBefore(LocalDate.parse("2026-10-01"))
                    && !h.date().isAfter(LocalDate.parse("2026-10-31"))),
            "Alle Feiertage muessen im Fenster liegen, erhalten: " + holidays);
    }

    @Test
    void holidaysCarryAName() {
        List<HolidayService.HolidayDto> holidays =
            holidayService.between(LocalDate.parse("2026-12-24"), LocalDate.parse("2026-12-26"));

        assertFalse(holidays.isEmpty());
        assertTrue(holidays.stream().allMatch(h -> h.name() != null && !h.name().isBlank()));
    }

    @Test
    void resultIsSortedByDate() {
        List<HolidayService.HolidayDto> holidays =
            holidayService.between(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));

        for (int i = 1; i < holidays.size(); i++) {
            assertFalse(holidays.get(i).date().isBefore(holidays.get(i - 1).date()),
                "Liste muss nach Datum sortiert sein");
        }
    }

    @Test
    void datesBetweenReturnsPlainDates() {
        Set<LocalDate> dates =
            holidayService.datesBetween(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

        assertTrue(dates.contains(LocalDate.parse("2026-10-26")));
    }

    @Test
    void emptyWindowYieldsNothing() {
        List<HolidayService.HolidayDto> holidays =
            holidayService.between(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-11"));

        assertTrue(holidays.isEmpty(), "In dieser Woche liegt kein Feiertag, erhalten: " + holidays);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=HolidayServiceTest`
Expected: Compilerfehler — `cannot find symbol: class HolidayService`.

- [ ] **Step 4: Write the service**

`backend/src/main/java/at/kigruapp/service/HolidayService.java`:

```java
package at.kigruapp.service;

import de.focus_shift.jollyday.core.Holiday;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.ManagerParameters;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gesetzliche Feiertage fuer den Standort, an dem die App betrieben wird.
 *
 * <p>Die Region wird einmalig beim Deploy gesetzt. Ueber Intl oder die
 * Server-Locale sind Feiertage nicht ermittelbar; es gibt dafuer weder eine
 * Browser-API noch einen Standard.
 */
@ApplicationScoped
public class HolidayService {

    private static final Logger LOG = Logger.getLogger(HolidayService.class);

    public record HolidayDto(LocalDate date, String name) {}

    @ConfigProperty(name = "kigruapp.holidays.country", defaultValue = "")
    String country;

    @ConfigProperty(name = "kigruapp.holidays.subdivision", defaultValue = "")
    String subdivision;

    public List<HolidayDto> between(LocalDate from, LocalDate to) {
        if (country == null || country.isBlank() || from == null || to == null || to.isBefore(from)) {
            return List.of();
        }
        try {
            HolidayManager manager =
                HolidayManager.getInstance(ManagerParameters.create(country.trim().toLowerCase()));

            Set<Holiday> holidays = subdivision == null || subdivision.isBlank()
                ? manager.getHolidays(from, to)
                : manager.getHolidays(from, to, subdivision.trim().toLowerCase());

            return holidays.stream()
                .filter(h -> !h.getDate().isBefore(from) && !h.getDate().isAfter(to))
                .map(h -> new HolidayDto(h.getDate(), h.getDescription()))
                .sorted(Comparator.comparing(HolidayDto::date))
                .collect(Collectors.toList());
        } catch (RuntimeException e) {
            // Unbekanntes Land oder fehlende Regionsdaten duerfen die App nicht lahmlegen.
            LOG.warnf(e, "Feiertage fuer '%s'/'%s' konnten nicht ermittelt werden", country, subdivision);
            return List.of();
        }
    }

    public Set<LocalDate> datesBetween(LocalDate from, LocalDate to) {
        return between(from, to).stream()
            .map(HolidayDto::date)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=HolidayServiceTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

Schlägt `findsAustrianNationalHoliday` mit einer leeren Liste fehl, fehlt der `ConfigurationService` — dann ist `jollyday-jackson` nicht auf dem Klassenpfad.

- [ ] **Step 6: Write the resource**

`backend/src/main/java/at/kigruapp/resource/HolidayResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.service.HolidayService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Path("/api/v1/holidays")
@Produces(MediaType.APPLICATION_JSON)
public class HolidayResource {

    @Inject
    HolidayService holidayService;

    @GET
    public List<HolidayService.HolidayDto> list(@QueryParam("from") String from,
                                                @QueryParam("to") String to) {
        return holidayService.between(parse(from, "from"), parse(to, "to"));
    }

    private LocalDate parse(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(name + " must be yyyy-MM-dd");
        }
    }
}
```

- [ ] **Step 7: Verify the endpoint compiles and the suite is unchanged**

Run: `cd backend && ./mvnw test`
Expected: keine neuen Fehlschläge gegenüber dem Vorbestand (13 vorbekannte).

- [ ] **Step 8: Commit**

```bash
git add backend/pom.xml \
        backend/src/main/resources/application.properties \
        backend/src/main/java/at/kigruapp/service/HolidayService.java \
        backend/src/main/java/at/kigruapp/resource/HolidayResource.java \
        backend/src/test/java/at/kigruapp/service/HolidayServiceTest.java
git commit -m "feat(be): Feiertage ueber Jollyday, Region per Deploy-Property"
```

---

## Task 5: Zeitraum-Endpoint mit serverseitiger Normalisierung

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/ClosurePeriodResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/ClosurePeriodResourceTest.java`

**Interfaces:**
- Consumes: `ClosurePeriodNormalizer.assign/remove/isWeekday` und `DateSpan` (Task 1/2), `ClosurePeriod`, `ClosureDefinition` (Task 3), `HolidayService.datesBetween` (Task 4)
- Produces:
  - `record ClosurePeriodResource.PeriodDto(String id, LocalDate from, LocalDate to, String definitionId)`
  - `GET /api/v1/closure-periods?from=&to=` → `List<PeriodDto>`
  - `POST /api/v1/closure-periods/apply` mit `{days: string[], definitionId: string, mode: "assign"|"remove"}` → `List<PeriodDto>` der betroffenen Definition

Das Frontend sendet die rohe Tagesauswahl und kennt die Split- und Merge-Regeln nicht. `apply` ersetzt sämtliche Zeiträume der betroffenen Definition durch das normalisierte Ergebnis — dadurch ist der Aufruf wiederholbar, ohne Duplikate zu erzeugen.

Nicht auswählbare Tage in `days` werden mit 400 abgelehnt. Damit kann kein Client einen Zeitraum erzeugen, der auf einem Wochenende oder Feiertag beginnt oder endet.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/resource/ClosurePeriodResourceTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.ClosureDefinition;
import at.kigruapp.entity.ClosurePeriod;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class ClosurePeriodResourceTest {

    private String ferienId;
    private String fortbildungId;

    @BeforeEach
    void setup() {
        ClosureDefinition.deleteAll();
        ClosurePeriod.deleteAll();
        ferienId = createDefinition("Ferien", "#d94f4f");
        fortbildungId = createDefinition("Fortbildung", "#e0a020");
    }

    private String createDefinition(String label, String color) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"" + label + "\", \"color\": \"" + color + "\"}")
            .when().post("/api/v1/closure-definitions")
            .then().statusCode(201)
            .extract().path("id");
    }

    private io.restassured.response.Response apply(String definitionId, String mode, String... days) {
        String list = String.join("\", \"", days);
        return given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + definitionId + "\", \"mode\": \"" + mode
                + "\", \"days\": [\"" + list + "\"]}")
            .when().post("/api/v1/closure-periods/apply");
    }

    @Test
    void assignCreatesOneSpan() {
        apply(ferienId, "assign", "2026-09-07", "2026-09-08", "2026-09-09")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].from", is("2026-09-07"))
            .body("[0].to", is("2026-09-09"))
            .body("[0].definitionId", is(ferienId));
    }

    @Test
    void assignMergesAcrossTheWeekend() {
        apply(ferienId, "assign", "2026-09-10", "2026-09-11").then().statusCode(200);

        apply(ferienId, "assign", "2026-09-14", "2026-09-15")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].from", is("2026-09-10"))
            .body("[0].to", is("2026-09-15"));
    }

    @Test
    void removeSplitsTheSpan() {
        apply(ferienId, "assign", "2026-09-07", "2026-09-08", "2026-09-09",
                                  "2026-09-10", "2026-09-11").then().statusCode(200);

        apply(ferienId, "remove", "2026-09-09")
            .then().statusCode(200)
            .body("$.size()", is(2))
            .body("[0].to", is("2026-09-08"))
            .body("[1].from", is("2026-09-10"));
    }

    @Test
    void applyIsRepeatableWithoutCreatingDuplicates() {
        apply(ferienId, "assign", "2026-09-07", "2026-09-08").then().statusCode(200);
        apply(ferienId, "assign", "2026-09-07", "2026-09-08")
            .then().statusCode(200)
            .body("$.size()", is(1));
    }

    @Test
    void differentDefinitionsCoexistOnTheSameDay() {
        apply(ferienId, "assign", "2026-09-07").then().statusCode(200);
        apply(fortbildungId, "assign", "2026-09-07").then().statusCode(200);

        given()
            .when().get("/api/v1/closure-periods?from=2026-09-01&to=2026-09-30")
            .then().statusCode(200)
            .body("$.size()", is(2));
    }

    @Test
    void applyOnlyTouchesItsOwnDefinition() {
        apply(ferienId, "assign", "2026-09-07").then().statusCode(200);
        apply(fortbildungId, "assign", "2026-09-08")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].definitionId", is(fortbildungId));

        given()
            .when().get("/api/v1/closure-periods?from=2026-09-01&to=2026-09-30")
            .then().statusCode(200)
            .body("$.size()", is(2));
    }

    @Test
    void listOnlyReturnsOverlappingPeriods() {
        apply(ferienId, "assign", "2026-09-07", "2026-09-08").then().statusCode(200);

        given()
            .when().get("/api/v1/closure-periods?from=2026-11-01&to=2026-11-30")
            .then().statusCode(200)
            .body("$.size()", is(0));

        given()
            .when().get("/api/v1/closure-periods?from=2026-09-08&to=2026-09-30")
            .then().statusCode(200)
            .body("$.size()", is(1));
    }

    @Test
    void weekendDaysAreRejected() {
        // 2026-09-12 ist ein Samstag.
        apply(ferienId, "assign", "2026-09-12").then().statusCode(400);
    }

    @Test
    void holidaysAreRejected() {
        // 2026-10-26 ist der oesterreichische Nationalfeiertag.
        apply(ferienId, "assign", "2026-10-26").then().statusCode(400);
    }

    @Test
    void emptyDayListIsRejected() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + ferienId + "\", \"mode\": \"assign\", \"days\": []}")
            .when().post("/api/v1/closure-periods/apply")
            .then().statusCode(400);
    }

    @Test
    void unknownModeIsRejected() {
        apply(ferienId, "toggle", "2026-09-07").then().statusCode(400);
    }

    @Test
    void unknownDefinitionYields404() {
        apply("64b7f1c2a1b2c3d4e5f60718", "assign", "2026-09-07").then().statusCode(404);
    }

    @Test
    void assigningToADeactivatedDefinitionIsRejected() {
        given().when().delete("/api/v1/closure-definitions/" + ferienId).then().statusCode(204);
        apply(ferienId, "assign", "2026-09-07").then().statusCode(409);
    }

    @Test
    void removingFromADeactivatedDefinitionIsAllowed() {
        apply(ferienId, "assign", "2026-09-07", "2026-09-08").then().statusCode(200);
        given().when().delete("/api/v1/closure-definitions/" + ferienId).then().statusCode(204);

        apply(ferienId, "remove", "2026-09-08")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].to", is("2026-09-07"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClosurePeriodResourceTest`
Expected: alle Tests scheitern mit 404 — die Ressource existiert noch nicht.

- [ ] **Step 3: Write the resource**

`backend/src/main/java/at/kigruapp/resource/ClosurePeriodResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.ClosureDefinition;
import at.kigruapp.entity.ClosurePeriod;
import at.kigruapp.service.ClosurePeriodNormalizer;
import at.kigruapp.service.ClosurePeriodNormalizer.DateSpan;
import at.kigruapp.service.HolidayService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@Path("/api/v1/closure-periods")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClosurePeriodResource {

    @Inject
    HolidayService holidayService;

    public record PeriodDto(String id, LocalDate from, LocalDate to, String definitionId) {}

    public record ApplyRequest(List<String> days, String definitionId, String mode) {}

    @GET
    public List<PeriodDto> list(@QueryParam("from") String from, @QueryParam("to") String to) {
        LocalDate start = parseDate(from, "from");
        LocalDate end = parseDate(to, "to");
        if (end.isBefore(start)) {
            throw new BadRequestException("to must not be before from");
        }
        return ClosurePeriod.findOverlapping(start, end).stream()
            .sorted(Comparator.comparing((ClosurePeriod p) -> p.from))
            .map(ClosurePeriodResource::toDto)
            .toList();
    }

    /**
     * Nimmt die rohe Tagesauswahl entgegen und ersetzt saemtliche Zeitraeume der
     * betroffenen Definition durch das normalisierte Ergebnis. Dadurch ist der
     * Aufruf wiederholbar, ohne Duplikate zu erzeugen.
     */
    @POST
    @Path("/apply")
    public List<PeriodDto> apply(ApplyRequest request) {
        if (request == null || request.days() == null || request.days().isEmpty()) {
            throw new BadRequestException("days must not be empty");
        }
        boolean assigning = "assign".equals(request.mode());
        if (!assigning && !"remove".equals(request.mode())) {
            throw new BadRequestException("mode must be 'assign' or 'remove'");
        }

        ClosureDefinition definition = findDefinitionOr404(request.definitionId());
        if (assigning && !definition.active) {
            throw new ClientErrorException(
                "Definition ist deaktiviert und kann nicht mehr zugewiesen werden.",
                Response.Status.CONFLICT);
        }

        List<LocalDate> days = request.days().stream()
            .map(value -> parseDate(value, "days"))
            .sorted()
            .toList();

        List<ClosurePeriod> existing = ClosurePeriod.findByDefinition(definition.id);
        Predicate<LocalDate> selectable = selectablePredicate(days, existing);

        for (LocalDate day : days) {
            if (!selectable.test(day)) {
                throw new BadRequestException(
                    "Tag " + day + " ist ein Wochenende oder Feiertag und kann nicht zugeordnet werden.");
            }
        }

        List<DateSpan> before = existing.stream()
            .map(p -> new DateSpan(p.from, p.to))
            .sorted(Comparator.comparing(DateSpan::from))
            .toList();

        List<DateSpan> after = assigning
            ? ClosurePeriodNormalizer.assign(before, days, selectable)
            : ClosurePeriodNormalizer.remove(before, days, selectable);

        ClosurePeriod.delete("definitionId", definition.id);
        List<PeriodDto> result = new ArrayList<>();
        for (DateSpan span : after) {
            ClosurePeriod period = new ClosurePeriod();
            period.from = span.from();
            period.to = span.to();
            period.definitionId = definition.id;
            period.persist();
            result.add(toDto(period));
        }
        return result;
    }

    /**
     * Ein Tag ist auswaehlbar, wenn er ein Werktag und kein Feiertag ist. Die
     * Feiertage werden einmal fuer das gesamte betroffene Fenster geladen, damit
     * der Normalizer sie ohne weitere Abfragen auswerten kann.
     */
    private Predicate<LocalDate> selectablePredicate(List<LocalDate> days, List<ClosurePeriod> existing) {
        LocalDate min = days.get(0);
        LocalDate max = days.get(days.size() - 1);
        for (ClosurePeriod period : existing) {
            if (period.from.isBefore(min)) {
                min = period.from;
            }
            if (period.to.isAfter(max)) {
                max = period.to;
            }
        }
        Set<LocalDate> holidays = holidayService.datesBetween(min, max);
        return day -> ClosurePeriodNormalizer.isWeekday(day) && !holidays.contains(day);
    }

    private ClosureDefinition findDefinitionOr404(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new NotFoundException("Definition nicht gefunden");
        }
        ClosureDefinition definition = ClosureDefinition.findById(new ObjectId(id));
        if (definition == null) {
            throw new NotFoundException("Definition nicht gefunden");
        }
        return definition;
    }

    private static PeriodDto toDto(ClosurePeriod period) {
        return new PeriodDto(period.id.toString(), period.from, period.to,
            period.definitionId.toString());
    }

    private static LocalDate parseDate(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(name + " must be yyyy-MM-dd");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ClosurePeriodResourceTest`
Expected: `Tests run: 14, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/ClosurePeriodResource.java \
        backend/src/test/java/at/kigruapp/resource/ClosurePeriodResourceTest.java
git commit -m "feat(be): Endpoint fuer Schliesszeitraeume mit serverseitiger Normalisierung"
```

---

## Task 6: Kochdienst-Sperre an geschlossenen Tagen

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/ClosureGuard.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/FieldInstanceResource.java:89-95` und `:123-129`
- Test: `backend/src/test/java/at/kigruapp/service/ClosureGuardTest.java`

**Interfaces:**
- Consumes: `ClosurePeriod.findOverlapping` (Task 3), `HolidayService.datesBetween` (Task 4)
- Produces:
  - `boolean ClosureGuard.isClosed(LocalDate day)`
  - `void ClosureGuard.rejectIfClosed(FieldDefinition definition, Object value)` — wirft `ClientErrorException` mit 409, wenn die Definition `cookingDuty` ist und der Tag geschlossen

**Warum hier und nicht in `CookingDutyResource`:** `CookingDutyResource` hat nur ein `GET`. Kochdienste werden über den generischen `POST /api/v1/field-instances` mit der `cookingDuty`-Definition angelegt (siehe `cooking.component.ts:209-236`). Das ist der einzige serverseitige Engpass. Die fachliche Regel liegt deshalb vollständig in `ClosureGuard`; `FieldInstanceResource` delegiert nur.

Feiertage werden gleich behandelt wie erfasste Schließzeiträume, obwohl sie nicht persistiert sind: der Kindergarten ist geschlossen, also kann dort kein Kochdienst stattfinden. Andernfalls wäre ein Kochdienst am 25. Dezember erlaubt, ein identischer Dienst am selbst eingetragenen Schließtag daneben aber nicht.

**Bewusste Asymmetrie:** Wird ein Schließzeitraum nachträglich über bestehende Kochdienste gelegt, bleiben diese unverändert erhalten. `ClosurePeriodResource.apply` prüft **nicht** auf Kollisionen. Das Löschen fremder Diensteinträge ohne Rückfrage wäre der größere Schaden. Derselbe Zustand ist damit je nach Reihenfolge der Eingaben erreichbar oder nicht — das ist gewollt und kein Fehler.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/service/ClosureGuardTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClosureGuardTest`
Expected: Compilerfehler — `cannot find symbol: class ClosureGuard`.

- [ ] **Step 3: Write the guard**

`backend/src/main/java/at/kigruapp/service/ClosureGuard.java`:

```java
package at.kigruapp.service;

import at.kigruapp.entity.ClosurePeriod;
import at.kigruapp.entity.FieldDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import org.bson.Document;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Einzige Quelle fuer die Frage "hat der Kindergarten an diesem Tag geschlossen?".
 *
 * <p>Feiertage zaehlen wie erfasste Schliesszeitraeume, obwohl sie nicht
 * persistiert sind — sonst waere ein Kochdienst am 25. Dezember erlaubt, ein
 * identischer Dienst am selbst eingetragenen Schliesstag daneben aber nicht.
 */
@ApplicationScoped
public class ClosureGuard {

    /** Feldname der Kochdienst-Definition in {@code field_definitions}. */
    private static final String COOKING_DUTY = "cookingDuty";

    @Inject
    HolidayService holidayService;

    public boolean isClosed(LocalDate day) {
        if (day == null) {
            return false;
        }
        if (holidayService.datesBetween(day, day).contains(day)) {
            return true;
        }
        return !ClosurePeriod.findOverlapping(day, day).isEmpty();
    }

    /**
     * Lehnt Kochdienste an geschlossenen Tagen ab. Andere Feldtypen passieren
     * unveraendert; der generische Endpoint bleibt dadurch generisch.
     */
    public void rejectIfClosed(FieldDefinition definition, Object value) {
        if (definition == null || !COOKING_DUTY.equals(definition.fieldName)) {
            return;
        }
        LocalDate date = extractDate(value);
        if (date != null && isClosed(date)) {
            throw new ClientErrorException(
                "Am " + date + " hat der Kindergarten geschlossen. Es kann kein Kochdienst eingetragen werden.",
                Response.Status.CONFLICT);
        }
    }

    /** Der Wert kommt je nach Aufrufweg als BSON-Document oder als Map an. */
    private LocalDate extractDate(Object value) {
        Object raw = null;
        if (value instanceof Document document) {
            raw = document.get("date");
        } else if (value instanceof Map<?, ?> map) {
            raw = map.get("date");
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ClosureGuardTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 5: Wire the guard into the generic endpoint**

In `FieldInstanceResource.java` die Injektion ergänzen, direkt nach dem vorhandenen `schemaValidator`-Feld (Zeile 32-33):

```java
    @Inject
    at.kigruapp.service.ClosureGuard closureGuard;
```

In `create(BatchItem item)` unmittelbar **nach** dem Schema-Validierungsblock (nach der schließenden Klammer von `if (item.value() != null) { ... }`, Zeile 95) einfügen:

```java
        closureGuard.rejectIfClosed(def, item.value());
```

In `update(String id, BatchItem item)` an derselben Stelle einfügen — nach dem Schema-Validierungsblock (Zeile 129), vor `Date now = Date.from(Instant.now());`:

```java
        closureGuard.rejectIfClosed(def, item.value());
```

Die Reihenfolge ist wichtig: erst Schema, dann Fachregel. Ein strukturell kaputter Wert soll weiterhin 400 liefern, nicht 409.

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && ./mvnw test`
Expected: keine neuen Fehlschläge gegenüber dem Vorbestand (13 vorbekannte).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/ClosureGuard.java \
        backend/src/main/java/at/kigruapp/resource/FieldInstanceResource.java \
        backend/src/test/java/at/kigruapp/service/ClosureGuardTest.java
git commit -m "feat(be): Kochdienste an Schliesstagen und Feiertagen sperren"
```

---

## Task 7: Frontend-Modelle und Services

**Files:**
- Create: `frontend/src/app/shared/models/closure.model.ts`
- Create: `frontend/src/app/shared/services/closure-definition.service.ts`
- Create: `frontend/src/app/shared/services/closure-period.service.ts`
- Create: `frontend/src/app/shared/services/holiday.service.ts`
- Test: `frontend/src/app/shared/services/closure.service.spec.ts`

**Interfaces:**
- Consumes: die Endpoints aus Task 3, 4 und 5; `ApiService` aus `core/services/api.service`
- Produces:
  - `interface ClosureDefinition { id, label, color, active, createdAt }`
  - `interface ClosureDefinitionRequest { label, color, active? }`
  - `interface ClosurePeriod { id, from, to, definitionId }`
  - `interface ApplyPeriodsRequest { days, definitionId, mode }`
  - `interface Holiday { date, name }`
  - `ClosureDefinitionService.getAll(includeInactive?)`, `.create()`, `.update()`, `.revise()`, `.deactivate()`
  - `ClosurePeriodService.getRange(from, to)`, `.apply(request)`
  - `HolidayService.getRange(from, to)`

Alle Datumsfelder sind ISO-Strings `yyyy-MM-dd`, niemals `Date`. Damit entfällt jede Zeitzonen-Umrechnung zwischen Backend und Anzeige.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/shared/services/closure.service.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { ClosureDefinitionService } from './closure-definition.service';
import { ClosurePeriodService } from './closure-period.service';
import { HolidayService } from './holiday.service';

describe('Closure services', () => {
  let http: HttpTestingController;
  let definitions: ClosureDefinitionService;
  let periods: ClosurePeriodService;
  let holidays: HolidayService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    definitions = TestBed.inject(ClosureDefinitionService);
    periods = TestBed.inject(ClosurePeriodService);
    holidays = TestBed.inject(HolidayService);
  });

  afterEach(() => http.verify());

  it('loads only active definitions by default', () => {
    definitions.getAll().subscribe();
    const req = http.expectOne('/api/v1/closure-definitions');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('can request inactive definitions as well', () => {
    definitions.getAll(true).subscribe();
    const req = http.expectOne('/api/v1/closure-definitions?includeInactive=true');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('creates a definition', () => {
    definitions.create({ label: 'Ferien', color: '#d94f4f' }).subscribe();
    const req = http.expectOne('/api/v1/closure-definitions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ label: 'Ferien', color: '#d94f4f' });
    req.flush({});
  });

  it('revises a definition through its own endpoint', () => {
    definitions.revise('abc', { label: 'Neu', color: '#4f86d9' }).subscribe();
    const req = http.expectOne('/api/v1/closure-definitions/abc/revise');
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('deactivates through DELETE', () => {
    definitions.deactivate('abc').subscribe();
    const req = http.expectOne('/api/v1/closure-definitions/abc');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('loads periods for a window', () => {
    periods.getRange('2026-09-01', '2027-02-28').subscribe();
    const req = http.expectOne('/api/v1/closure-periods?from=2026-09-01&to=2027-02-28');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('applies a day selection', () => {
    periods.apply({ days: ['2026-09-07'], definitionId: 'abc', mode: 'assign' }).subscribe();
    const req = http.expectOne('/api/v1/closure-periods/apply');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.mode).toBe('assign');
    req.flush([]);
  });

  it('loads holidays for a window', () => {
    holidays.getRange('2026-09-01', '2027-02-28').subscribe();
    const req = http.expectOne('/api/v1/holidays?from=2026-09-01&to=2027-02-28');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/closure.service.spec.ts'`
Expected: Kompilierfehler — `Cannot find module './closure-definition.service'`.

- [ ] **Step 3: Write the model**

`frontend/src/app/shared/models/closure.model.ts`:

```ts
/** Art eines Schliesstags. Generell und immer gueltig, ohne Datumsbezug. */
export interface ClosureDefinition {
  id: string;
  label: string;
  color: string;
  active: boolean;
  createdAt: string;
}

export interface ClosureDefinitionRequest {
  label: string;
  color: string;
  active?: boolean;
}

/** Ein zusammenhaengender Schliesszeitraum, beide Grenzen inklusive. */
export interface ClosurePeriod {
  id: string;
  /** ISO yyyy-MM-dd */
  from: string;
  /** ISO yyyy-MM-dd */
  to: string;
  definitionId: string;
}

export interface ApplyPeriodsRequest {
  /** ISO yyyy-MM-dd, roh wie im Kalender markiert — das Backend normalisiert. */
  days: string[];
  definitionId: string;
  mode: 'assign' | 'remove';
}

export interface Holiday {
  /** ISO yyyy-MM-dd */
  date: string;
  name: string;
}
```

- [ ] **Step 4: Write the services**

`frontend/src/app/shared/services/closure-definition.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ClosureDefinition, ClosureDefinitionRequest } from '../models/closure.model';

@Injectable({ providedIn: 'root' })
export class ClosureDefinitionService {
  constructor(private api: ApiService) {}

  getAll(includeInactive = false): Observable<ClosureDefinition[]> {
    const path = includeInactive
      ? '/closure-definitions?includeInactive=true'
      : '/closure-definitions';
    return this.api.get<ClosureDefinition[]>(path);
  }

  create(request: ClosureDefinitionRequest): Observable<ClosureDefinition> {
    return this.api.post<ClosureDefinition>('/closure-definitions', request);
  }

  /** Nur erlaubt, solange keine Zeitraeume verknuepft sind — sonst antwortet das Backend mit 409. */
  update(id: string, request: ClosureDefinitionRequest): Observable<ClosureDefinition> {
    return this.api.put<ClosureDefinition>(`/closure-definitions/${id}`, request);
  }

  /** Legt eine Kopie mit den neuen Werten an und deaktiviert das Original. */
  revise(id: string, request: ClosureDefinitionRequest): Observable<ClosureDefinition> {
    return this.api.post<ClosureDefinition>(`/closure-definitions/${id}/revise`, request);
  }

  /** Loescht nicht, sondern setzt active auf false. */
  deactivate(id: string): Observable<void> {
    return this.api.delete(`/closure-definitions/${id}`);
  }
}
```

`frontend/src/app/shared/services/closure-period.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ApplyPeriodsRequest, ClosurePeriod } from '../models/closure.model';

@Injectable({ providedIn: 'root' })
export class ClosurePeriodService {
  constructor(private api: ApiService) {}

  getRange(from: string, to: string): Observable<ClosurePeriod[]> {
    return this.api.get<ClosurePeriod[]>(`/closure-periods?from=${from}&to=${to}`);
  }

  /** Sendet die rohe Tagesauswahl; Split und Merge passieren im Backend. */
  apply(request: ApplyPeriodsRequest): Observable<ClosurePeriod[]> {
    return this.api.post<ClosurePeriod[]>('/closure-periods/apply', request);
  }
}
```

`frontend/src/app/shared/services/holiday.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { Holiday } from '../models/closure.model';

@Injectable({ providedIn: 'root' })
export class HolidayService {
  constructor(private api: ApiService) {}

  getRange(from: string, to: string): Observable<Holiday[]> {
    return this.api.get<Holiday[]>(`/holidays?from=${from}&to=${to}`);
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/closure.service.spec.ts'`
Expected: `Executed 8 of 8 SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/models/closure.model.ts \
        frontend/src/app/shared/services/closure-definition.service.ts \
        frontend/src/app/shared/services/closure-period.service.ts \
        frontend/src/app/shared/services/holiday.service.ts \
        frontend/src/app/shared/services/closure.service.spec.ts
git commit -m "feat(fe): Modelle und Services fuer Schliesstage"
```

---

## Task 8: Kalenderraster — Aufbau und Darstellung

**Files:**
- Create: `frontend/src/app/shared/components/closure-calendar/closure-calendar.util.ts`
- Create: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.ts`
- Create: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.html`
- Create: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.scss`
- Test: `frontend/src/app/shared/components/closure-calendar/closure-calendar.util.spec.ts`

**Interfaces:**
- Consumes: `ClosureDefinition`, `ClosurePeriod`, `Holiday` (Task 7)
- Produces:
  - `interface CalendarDay { date: string; dayOfMonth: number; selectable: boolean; holidayName: string | null; colors: string[]; labels: string[] }`
  - `interface CalendarMonth { key: string; label: string; leadingBlanks: number; days: CalendarDay[] }`
  - `function buildMonths(from, to, periods, definitions, holidays): CalendarMonth[]`
  - `function dayBackground(day: CalendarDay): string`
  - `function isWeekend(iso: string): boolean`
  - `ClosureCalendarComponent`, Selektor `app-closure-calendar`, Inputs `from`, `to`, `periods`, `definitions`, `holidays`, `readonly`

Die reine Logik liegt in `closure-calendar.util.ts` — analog zu `kosten-discount-preview.util.ts` und `required-hours-preview.util.ts` im Organisation-Bereich. Dadurch ist der gesamte Rasteraufbau ohne DOM testbar.

Gerendert wird exakt das übergebene Fenster: der erste Monat beginnt beim Semesterstart, der letzte endet beim Semesterende. `leadingBlanks` sorgt dafür, dass der erste gerenderte Tag in der richtigen Wochentagsspalte sitzt.

Datumsarithmetik läuft über `new Date(iso + 'T00:00:00')` — dasselbe Muster wie in `cooking.component.ts:122`. Ohne den Zeitanteil interpretiert der Browser den String als UTC und verschiebt den Tag je nach Zone.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/shared/components/closure-calendar/closure-calendar.util.spec.ts`:

```ts
import { buildMonths, dayBackground, isWeekend, CalendarDay } from './closure-calendar.util';
import { ClosureDefinition, ClosurePeriod, Holiday } from '../../models/closure.model';

const ferien: ClosureDefinition = {
  id: 'def-ferien', label: 'Ferien', color: '#d94f4f', active: true, createdAt: '2026-07-01T00:00:00Z',
};
const fortbildung: ClosureDefinition = {
  id: 'def-fortbildung', label: 'Fortbildung', color: '#e0a020', active: true, createdAt: '2026-07-02T00:00:00Z',
};

function period(id: string, from: string, to: string, definitionId: string): ClosurePeriod {
  return { id, from, to, definitionId };
}

function findDay(months: ReturnType<typeof buildMonths>, iso: string): CalendarDay {
  const day = months.flatMap(m => m.days).find(d => d.date === iso);
  if (!day) throw new Error(`Tag ${iso} nicht im Raster`);
  return day;
}

describe('closure-calendar.util', () => {
  describe('isWeekend', () => {
    it('erkennt Samstag und Sonntag', () => {
      expect(isWeekend('2026-09-12')).toBe(true);
      expect(isWeekend('2026-09-13')).toBe(true);
    });

    it('erkennt Werktage', () => {
      expect(isWeekend('2026-09-07')).toBe(false);
      expect(isWeekend('2026-09-11')).toBe(false);
    });
  });

  describe('buildMonths', () => {
    it('rendert genau das uebergebene Fenster', () => {
      const months = buildMonths('2026-09-07', '2026-09-11', [], [], []);

      expect(months.length).toBe(1);
      expect(months[0].days.length).toBe(5);
      expect(months[0].days[0].date).toBe('2026-09-07');
      expect(months[0].days[4].date).toBe('2026-09-11');
    });

    it('teilt ueber mehrere Monate auf', () => {
      const months = buildMonths('2026-09-01', '2027-02-28', [], [], []);

      expect(months.length).toBe(6);
      expect(months[0].label).toBe('September 2026');
      expect(months[5].label).toBe('Februar 2027');
    });

    it('setzt leadingBlanks auf den Wochentag des ersten gerenderten Tages', () => {
      // 2026-09-07 ist ein Montag -> keine Luecke.
      expect(buildMonths('2026-09-07', '2026-09-11', [], [], [])[0].leadingBlanks).toBe(0);
      // 2026-09-09 ist ein Mittwoch -> zwei Luecken.
      expect(buildMonths('2026-09-09', '2026-09-11', [], [], [])[0].leadingBlanks).toBe(2);
    });

    it('markiert Wochenenden als nicht auswaehlbar', () => {
      const months = buildMonths('2026-09-07', '2026-09-13', [], [], []);

      expect(findDay(months, '2026-09-11').selectable).toBe(true);
      expect(findDay(months, '2026-09-12').selectable).toBe(false);
      expect(findDay(months, '2026-09-13').selectable).toBe(false);
    });

    it('markiert Feiertage als nicht auswaehlbar und traegt den Namen ein', () => {
      const holidays: Holiday[] = [{ date: '2026-10-26', name: 'Nationalfeiertag' }];
      const months = buildMonths('2026-10-26', '2026-10-27', [], [], holidays);

      expect(findDay(months, '2026-10-26').selectable).toBe(false);
      expect(findDay(months, '2026-10-26').holidayName).toBe('Nationalfeiertag');
      expect(findDay(months, '2026-10-27').selectable).toBe(true);
      expect(findDay(months, '2026-10-27').holidayName).toBeNull();
    });

    it('faerbt Tage innerhalb eines Zeitraums', () => {
      const periods = [period('p1', '2026-09-07', '2026-09-09', 'def-ferien')];
      const months = buildMonths('2026-09-07', '2026-09-11', periods, [ferien], []);

      expect(findDay(months, '2026-09-08').colors).toEqual(['#d94f4f']);
      expect(findDay(months, '2026-09-08').labels).toEqual(['Ferien']);
      expect(findDay(months, '2026-09-10').colors).toEqual([]);
    });

    it('sammelt bei Mehrfachzuordnung alle Farben und Label', () => {
      const periods = [
        period('p1', '2026-09-07', '2026-09-09', 'def-ferien'),
        period('p2', '2026-09-08', '2026-09-08', 'def-fortbildung'),
      ];
      const months = buildMonths('2026-09-07', '2026-09-11', periods, [ferien, fortbildung], []);

      expect(findDay(months, '2026-09-08').colors).toEqual(['#d94f4f', '#e0a020']);
      expect(findDay(months, '2026-09-08').labels).toEqual(['Ferien', 'Fortbildung']);
    });

    it('faerbt auch Wochenenden innerhalb eines Zeitraums', () => {
      // Ein Zeitraum darf Wochenenden ueberspannen; sie werden mitgefaerbt,
      // bleiben aber nicht auswaehlbar.
      const periods = [period('p1', '2026-09-07', '2026-09-18', 'def-ferien')];
      const months = buildMonths('2026-09-07', '2026-09-18', periods, [ferien], []);

      expect(findDay(months, '2026-09-12').colors).toEqual(['#d94f4f']);
      expect(findDay(months, '2026-09-12').selectable).toBe(false);
    });

    it('ignoriert Zeitraeume mit unbekannter Definition', () => {
      const periods = [period('p1', '2026-09-07', '2026-09-09', 'geloescht')];
      const months = buildMonths('2026-09-07', '2026-09-11', periods, [ferien], []);

      expect(findDay(months, '2026-09-08').colors).toEqual([]);
    });

    it('liefert ein leeres Raster bei ungueltigem Fenster', () => {
      expect(buildMonths('2026-09-11', '2026-09-07', [], [], [])).toEqual([]);
      expect(buildMonths('', '', [], [], [])).toEqual([]);
    });
  });

  describe('dayBackground', () => {
    function day(colors: string[]): CalendarDay {
      return { date: '2026-09-08', dayOfMonth: 8, selectable: true, holidayName: null, colors, labels: [] };
    }

    it('liefert nichts ohne Zuordnung', () => {
      expect(dayBackground(day([]))).toBe('');
    });

    it('liefert die Farbe bei einer Zuordnung', () => {
      expect(dayBackground(day(['#d94f4f']))).toBe('#d94f4f');
    });

    it('teilt den Tag bei zwei Zuordnungen in gleiche Haelften', () => {
      expect(dayBackground(day(['#d94f4f', '#4f86d9'])))
        .toBe('linear-gradient(90deg, #d94f4f 0% 50%, #4f86d9 50% 100%)');
    });

    it('teilt den Tag bei drei Zuordnungen in gleiche Drittel', () => {
      const result = dayBackground(day(['#a', '#b', '#c']));
      expect(result).toContain('#a 0% 33.333%');
      expect(result).toContain('#b 33.333% 66.667%');
      expect(result).toContain('#c 66.667% 100%');
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/closure-calendar.util.spec.ts'`
Expected: Kompilierfehler — `Cannot find module './closure-calendar.util'`.

- [ ] **Step 3: Write the util**

`frontend/src/app/shared/components/closure-calendar/closure-calendar.util.ts`:

```ts
import { ClosureDefinition, ClosurePeriod, Holiday } from '../../models/closure.model';

export interface CalendarDay {
  /** ISO yyyy-MM-dd */
  date: string;
  dayOfMonth: number;
  /** Werktag und kein Feiertag — nur solche Tage lassen sich markieren. */
  selectable: boolean;
  holidayName: string | null;
  /** Eine Farbe je zugeordneter Definition, in Reihenfolge der Definitionsliste. */
  colors: string[];
  labels: string[];
}

export interface CalendarMonth {
  /** yyyy-MM, stabil fuer @for-Tracking */
  key: string;
  label: string;
  /** Leerzellen vor dem ersten gerenderten Tag, damit die Spalte stimmt. */
  leadingBlanks: number;
  days: CalendarDay[];
}

const MONTH_NAMES = [
  'Jänner', 'Februar', 'März', 'April', 'Mai', 'Juni',
  'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember',
];

/**
 * Ohne den Zeitanteil liest der Browser den String als UTC und verschiebt den
 * Tag je nach Zone. Gleiches Muster wie in cooking.component.ts.
 */
function parseIso(iso: string): Date {
  return new Date(`${iso}T00:00:00`);
}

function toIso(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function isWeekend(iso: string): boolean {
  const weekday = parseIso(iso).getDay();
  return weekday === 0 || weekday === 6;
}

/** Montag = 0 … Sonntag = 6, passend zur Spaltenreihenfolge des Rasters. */
function mondayBasedWeekday(iso: string): number {
  return (parseIso(iso).getDay() + 6) % 7;
}

export function buildMonths(
  from: string,
  to: string,
  periods: ClosurePeriod[],
  definitions: ClosureDefinition[],
  holidays: Holiday[],
): CalendarMonth[] {
  if (!from || !to || from > to) {
    return [];
  }

  const holidayNames = new Map(holidays.map(h => [h.date, h.name]));
  const definitionById = new Map(definitions.map(d => [d.id, d]));

  // Reihenfolge der Definitionsliste bestimmt die Segmentreihenfolge im Tag,
  // damit die Aufteilung ueber alle Tage hinweg gleich aussieht.
  const order = new Map(definitions.map((d, index) => [d.id, index]));
  const sortedPeriods = [...periods].sort(
    (a, b) => (order.get(a.definitionId) ?? Number.MAX_SAFE_INTEGER)
            - (order.get(b.definitionId) ?? Number.MAX_SAFE_INTEGER),
  );

  const months: CalendarMonth[] = [];
  let current: CalendarMonth | null = null;

  for (let cursor = parseIso(from); toIso(cursor) <= to; cursor.setDate(cursor.getDate() + 1)) {
    const iso = toIso(cursor);
    const key = iso.slice(0, 7);

    if (!current || current.key !== key) {
      current = {
        key,
        label: `${MONTH_NAMES[cursor.getMonth()]} ${cursor.getFullYear()}`,
        leadingBlanks: mondayBasedWeekday(iso),
        days: [],
      };
      months.push(current);
    }

    const holidayName = holidayNames.get(iso) ?? null;
    const colors: string[] = [];
    const labels: string[] = [];

    for (const period of sortedPeriods) {
      if (iso < period.from || iso > period.to) {
        continue;
      }
      const definition = definitionById.get(period.definitionId);
      if (!definition) {
        continue;
      }
      colors.push(definition.color);
      labels.push(definition.label);
    }

    current.days.push({
      date: iso,
      dayOfMonth: cursor.getDate(),
      selectable: !isWeekend(iso) && holidayName === null,
      holidayName,
      colors,
      labels,
    });
  }

  return months;
}

/** Mehrfachzuordnung teilt den Tag in gleich breite Segmente. */
export function dayBackground(day: CalendarDay): string {
  if (day.colors.length === 0) {
    return '';
  }
  if (day.colors.length === 1) {
    return day.colors[0];
  }
  const step = 100 / day.colors.length;
  const stops = day.colors
    .map((color, index) => {
      const start = index === 0 ? '0%' : `${(index * step).toFixed(3).replace(/\.?0+$/, '')}%`;
      const end = index === day.colors.length - 1
        ? '100%'
        : `${((index + 1) * step).toFixed(3).replace(/\.?0+$/, '')}%`;
      return `${color} ${start} ${end}`;
    })
    .join(', ');
  return `linear-gradient(90deg, ${stops})`;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/closure-calendar.util.spec.ts'`
Expected: `Executed 16 of 16 SUCCESS`

- [ ] **Step 5: Write the component shell**

`frontend/src/app/shared/components/closure-calendar/closure-calendar.component.ts`:

```ts
import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';

import { ClosureDefinition, ClosurePeriod, Holiday } from '../../models/closure.model';
import { buildMonths, dayBackground, CalendarDay, CalendarMonth } from './closure-calendar.util';

@Component({
  selector: 'app-closure-calendar',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  templateUrl: './closure-calendar.component.html',
  styleUrl: './closure-calendar.component.scss',
})
export class ClosureCalendarComponent implements OnChanges {
  /** ISO yyyy-MM-dd, Semesterbeginn */
  @Input() from = '';
  /** ISO yyyy-MM-dd, Semesterende */
  @Input() to = '';
  @Input() periods: ClosurePeriod[] = [];
  @Input() definitions: ClosureDefinition[] = [];
  @Input() holidays: Holiday[] = [];
  /** Elternansicht: keine Auswahl, keine Handler. */
  @Input() readonly = false;

  @Output() selectionChange = new EventEmitter<string[]>();

  readonly weekdayLabels = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
  months: CalendarMonth[] = [];

  ngOnChanges(): void {
    this.months = buildMonths(this.from, this.to, this.periods, this.definitions, this.holidays);
  }

  background(day: CalendarDay): string {
    return dayBackground(day);
  }

  tooltip(day: CalendarDay): string {
    const parts = [...day.labels];
    if (day.holidayName) {
      parts.unshift(day.holidayName);
    }
    return parts.join(' · ');
  }

  blanks(month: CalendarMonth): number[] {
    return Array.from({ length: month.leadingBlanks }, (_, index) => index);
  }
}
```

`frontend/src/app/shared/components/closure-calendar/closure-calendar.component.html`:

```html
<div class="closure-calendar">
  @for (month of months; track month.key) {
    <div class="month">
      <div class="month-label">{{ month.label }}</div>
      <div class="weekdays">
        @for (label of weekdayLabels; track label) {
          <span class="weekday">{{ label }}</span>
        }
      </div>
      <div class="days">
        @for (blank of blanks(month); track blank) {
          <span class="day blank"></span>
        }
        @for (day of month.days; track day.date) {
          <span class="day"
                [class.weekend]="!day.selectable && !day.holidayName"
                [class.holiday]="!!day.holidayName"
                [class.assigned]="day.colors.length > 0"
                [style.background]="background(day)"
                [matTooltip]="tooltip(day)"
                [matTooltipDisabled]="tooltip(day) === ''"
                [attr.data-date]="day.date">
            <span class="day-number">{{ day.dayOfMonth }}</span>
          </span>
        }
      </div>
    </div>
  }
  @if (months.length === 0) {
    <p class="empty">Kein Zeitraum ausgewählt.</p>
  }
</div>
```

`frontend/src/app/shared/components/closure-calendar/closure-calendar.component.scss`:

```scss
.closure-calendar {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.month {
  flex: 1 1 220px;
  min-width: 200px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 6px;
  padding: 8px;
}

.month-label {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  opacity: 0.75;
  margin-bottom: 6px;
}

.weekdays,
.days {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 2px;
}

.weekday {
  font-size: 10px;
  text-align: center;
  opacity: 0.55;
}

.day {
  position: relative;
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.05);
  font-size: 11px;
}

.day.blank {
  background: none;
}

.day.weekend,
.day.holiday {
  opacity: 0.45;
}

// Auf gefuellten Tagen braucht die Zahl einen Kontrastträger, damit sie auf
// dunklen wie hellen Definitionsfarben lesbar bleibt.
.day.assigned .day-number {
  padding: 0 3px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.78);
  color: rgba(0, 0, 0, 0.87);
}

.empty {
  opacity: 0.6;
  font-style: italic;
}
```

- [ ] **Step 6: Verify the build**

Run: `cd frontend && npm run build`
Expected: erfolgreicher Produktionsbuild ohne Fehler.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/components/closure-calendar/
git commit -m "feat(fe): Kalenderraster fuer Schliesstage mit geteilten Tageszellen"
```

---

## Task 9: Kalenderauswahl — Ziehen, STRG-Toggle, readonly

**Files:**
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.util.ts` (Ergänzung `selectableRange`)
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.ts`
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.html`
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.scss`
- Test: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.spec.ts`

**Interfaces:**
- Consumes: `buildMonths`, `CalendarDay`, `CalendarMonth` (Task 8)
- Produces:
  - `function selectableRange(months: CalendarMonth[], a: string, b: string): string[]`
  - `ClosureCalendarComponent.selectionChange: EventEmitter<string[]>` feuert beim Loslassen der Maustaste
  - `ClosureCalendarComponent.clearSelection(): void` — von der Admin-Maske nach `apply` aufgerufen

Verhalten:
- Klick markiert einen Tag, Ziehen markiert den Bereich zwischen Anker und aktuellem Tag. Nicht auswählbare Tage im Bereich werden übersprungen, der Bereich selbst darf sie überspannen.
- Ohne STRG ersetzt eine neue Ziehung die bisherige Auswahl.
- Mit STRG bleibt die bisherige Auswahl erhalten und der neue Bereich kommt hinzu. Beginnt die Ziehung auf einem bereits markierten Tag, wird der Bereich stattdessen entfernt — das ist das „hinzu/wegnehmen" aus Excel.
- Der Cursor wechselt zu `copy` (Pfeil mit Plus), solange STRG gedrückt ist **und** der Zeiger im Kalender steht. Außerhalb greift STRG nicht.
- `readonly` unterdrückt sämtliche Handler.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/shared/components/closure-calendar/closure-calendar.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { ClosureCalendarComponent } from './closure-calendar.component';
import { ClosureDefinition } from '../../models/closure.model';

const ferien: ClosureDefinition = {
  id: 'def-ferien', label: 'Ferien', color: '#d94f4f', active: true, createdAt: '2026-07-01T00:00:00Z',
};

describe('ClosureCalendarComponent', () => {
  let fixture: ComponentFixture<ClosureCalendarComponent>;
  let component: ClosureCalendarComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ClosureCalendarComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(ClosureCalendarComponent);
    component = fixture.componentInstance;
    // Mo 07.09. bis So 20.09.2026 — zwei volle Wochen mit Wochenenden dazwischen.
    component.from = '2026-09-07';
    component.to = '2026-09-20';
    component.definitions = [ferien];
    fixture.detectChanges();
  });

  function cell(iso: string): HTMLElement {
    const element = fixture.nativeElement.querySelector(`[data-date="${iso}"]`);
    if (!element) throw new Error(`Zelle ${iso} nicht gefunden`);
    return element as HTMLElement;
  }

  function press(iso: string, ctrl = false): void {
    cell(iso).dispatchEvent(new MouseEvent('mousedown', { ctrlKey: ctrl, bubbles: true }));
  }

  function moveOver(iso: string): void {
    cell(iso).dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
  }

  function release(): void {
    document.dispatchEvent(new MouseEvent('mouseup'));
    fixture.detectChanges();
  }

  it('markiert einen einzelnen Tag', () => {
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-08');
    release();

    expect(emitted.length).toBe(1);
    expect(emitted[0]).toEqual(['2026-09-08']);
  });

  it('markiert beim Ziehen den gesamten Bereich', () => {
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-07');
    moveOver('2026-09-09');
    release();

    expect(emitted[0]).toEqual(['2026-09-07', '2026-09-08', '2026-09-09']);
  });

  it('ueberspringt Wochenenden, ueberspannt sie aber', () => {
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-11');
    moveOver('2026-09-14');
    release();

    // Sa 12. und So 13. fehlen in der Auswahl.
    expect(emitted[0]).toEqual(['2026-09-11', '2026-09-14']);
  });

  it('zieht rueckwaerts genauso', () => {
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-09');
    moveOver('2026-09-07');
    release();

    expect(emitted[0]).toEqual(['2026-09-07', '2026-09-08', '2026-09-09']);
  });

  it('ersetzt die Auswahl ohne STRG', () => {
    press('2026-09-07');
    release();
    press('2026-09-10');
    release();

    expect(component.selectedDays).toEqual(['2026-09-10']);
  });

  it('erweitert die Auswahl mit STRG', () => {
    press('2026-09-07');
    release();
    press('2026-09-10', true);
    release();

    expect(component.selectedDays).toEqual(['2026-09-07', '2026-09-10']);
  });

  it('nimmt mit STRG einen bereits markierten Tag wieder weg', () => {
    press('2026-09-07');
    moveOver('2026-09-09');
    release();
    press('2026-09-08', true);
    release();

    expect(component.selectedDays).toEqual(['2026-09-07', '2026-09-09']);
  });

  it('ignoriert Klicks auf Wochenenden', () => {
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-12');
    release();

    expect(emitted.length).toBe(0);
    expect(component.selectedDays).toEqual([]);
  });

  it('reagiert im readonly-Modus gar nicht', () => {
    component.readonly = true;
    fixture.detectChanges();
    const emitted: string[][] = [];
    component.selectionChange.subscribe(days => emitted.push(days));

    press('2026-09-08');
    release();

    expect(emitted.length).toBe(0);
    expect(component.selectedDays).toEqual([]);
  });

  it('zeigt den Plus-Cursor nur bei STRG innerhalb des Kalenders', () => {
    const host = fixture.nativeElement.querySelector('.closure-calendar') as HTMLElement;

    host.dispatchEvent(new MouseEvent('mouseenter'));
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Control', ctrlKey: true }));
    fixture.detectChanges();
    expect(host.classList).toContain('ctrl-active');

    host.dispatchEvent(new MouseEvent('mouseleave'));
    fixture.detectChanges();
    expect(host.classList).not.toContain('ctrl-active');
  });

  it('leert die Auswahl auf Anforderung', () => {
    press('2026-09-08');
    release();
    expect(component.selectedDays.length).toBe(1);

    component.clearSelection();
    fixture.detectChanges();

    expect(component.selectedDays).toEqual([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/closure-calendar.component.spec.ts'`
Expected: Fehler — `component.selectedDays` und `clearSelection` existieren nicht.

- [ ] **Step 3: Extend the util**

Am Ende von `closure-calendar.util.ts` ergänzen:

```ts
/**
 * Alle auswaehlbaren Tage zwischen zwei Ankern, unabhaengig von der Ziehrichtung.
 * Wochenenden und Feiertage werden uebersprungen — der Bereich darf sie aber
 * ueberspannen.
 */
export function selectableRange(months: CalendarMonth[], a: string, b: string): string[] {
  const from = a <= b ? a : b;
  const to = a <= b ? b : a;
  return months
    .flatMap(month => month.days)
    .filter(day => day.selectable && day.date >= from && day.date <= to)
    .map(day => day.date);
}
```

- [ ] **Step 4: Extend the component**

In `closure-calendar.component.ts` den Import erweitern und die Auswahl ergänzen:

```ts
import { Component, EventEmitter, HostListener, Input, OnChanges, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';

import { ClosureDefinition, ClosurePeriod, Holiday } from '../../models/closure.model';
import {
  buildMonths, dayBackground, selectableRange, CalendarDay, CalendarMonth,
} from './closure-calendar.util';
```

Innerhalb der Klasse — nach `months` — ergänzen:

```ts
  /** Aufsteigend sortierte ISO-Tage der aktuellen Auswahl. */
  selectedDays: string[] = [];
  ctrlActive = false;

  private selected = new Set<string>();
  private base = new Set<string>();
  private anchor: string | null = null;
  private dragging = false;
  private removing = false;
  private pointerInside = false;

  isSelected(day: CalendarDay): boolean {
    return this.selected.has(day.date);
  }

  onDayMouseDown(day: CalendarDay, event: MouseEvent): void {
    if (this.readonly || !day.selectable) {
      return;
    }
    event.preventDefault();
    const additive = event.ctrlKey || event.metaKey;
    // Mit STRG bleibt Bestehendes erhalten; ohne STRG ersetzt die Ziehung alles.
    this.base = new Set(additive ? this.selected : []);
    // Beginnt die Ziehung mit STRG auf einem markierten Tag, nimmt sie weg.
    this.removing = additive && this.selected.has(day.date);
    this.anchor = day.date;
    this.dragging = true;
    this.applyRange(day.date);
  }

  onDayMouseEnter(day: CalendarDay): void {
    if (this.dragging) {
      this.applyRange(day.date);
    }
  }

  @HostListener('document:mouseup')
  onDocumentMouseUp(): void {
    if (!this.dragging) {
      return;
    }
    this.dragging = false;
    this.anchor = null;
    this.selectionChange.emit(this.selectedDays);
  }

  onPointerEnter(): void {
    this.pointerInside = true;
  }

  onPointerLeave(): void {
    this.pointerInside = false;
    this.ctrlActive = false;
  }

  // STRG wirkt ausschliesslich innerhalb des Kalenders.
  @HostListener('document:keydown', ['$event'])
  @HostListener('document:keyup', ['$event'])
  onCtrlState(event: KeyboardEvent): void {
    this.ctrlActive = !this.readonly && this.pointerInside && (event.ctrlKey || event.metaKey);
  }

  clearSelection(): void {
    this.selected = new Set();
    this.base = new Set();
    this.selectedDays = [];
  }

  private applyRange(end: string): void {
    if (this.anchor === null) {
      return;
    }
    const range = selectableRange(this.months, this.anchor, end);
    const next = new Set(this.base);
    for (const date of range) {
      if (this.removing) {
        next.delete(date);
      } else {
        next.add(date);
      }
    }
    this.selected = next;
    this.selectedDays = [...next].sort();
  }
```

In `ngOnChanges` nach dem Neuaufbau der Monate ergänzen, damit eine Auswahl aus einem anderen Semester nicht stehen bleibt:

```ts
    this.clearSelection();
```

- [ ] **Step 5: Wire the template and styles**

In `closure-calendar.component.html` das Wurzelelement und die Tageszelle erweitern:

```html
<div class="closure-calendar"
     [class.ctrl-active]="ctrlActive"
     [class.readonly]="readonly"
     (mouseenter)="onPointerEnter()"
     (mouseleave)="onPointerLeave()">
```

und die Tageszelle:

```html
        @for (day of month.days; track day.date) {
          <span class="day"
                [class.weekend]="!day.selectable && !day.holidayName"
                [class.holiday]="!!day.holidayName"
                [class.assigned]="day.colors.length > 0"
                [class.selected]="isSelected(day)"
                [class.selectable]="day.selectable && !readonly"
                [style.background]="background(day)"
                [matTooltip]="tooltip(day)"
                [matTooltipDisabled]="tooltip(day) === ''"
                [attr.data-date]="day.date"
                (mousedown)="onDayMouseDown(day, $event)"
                (mouseenter)="onDayMouseEnter(day)">
            <span class="day-number">{{ day.dayOfMonth }}</span>
          </span>
        }
```

In `closure-calendar.component.scss` ergänzen:

```scss
.day.selectable {
  cursor: pointer;
  user-select: none;
}

.day.selected {
  outline: 2px solid #2e7d32;
  outline-offset: -1px;
}

// Pfeil mit Plus, solange STRG gedrueckt ist und der Zeiger im Kalender steht.
.closure-calendar.ctrl-active .day.selectable {
  cursor: copy;
}

.closure-calendar.readonly .day {
  cursor: default;
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/closure-calendar*.spec.ts'`
Expected: `Executed 27 of 27 SUCCESS` — 16 Util- und 11 Komponententests.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/components/closure-calendar/
git commit -m "feat(fe): Kalenderauswahl mit Ziehen und STRG-Toggle nach Excel-Semantik"
```

---

## Task 10: Admin-Maske — Semester, Kalender, Zuweisungsleiste

**Files:**
- Create: `frontend/src/app/settings/schliesstage/schliesstage.component.ts`
- Create: `frontend/src/app/settings/schliesstage/schliesstage.component.html`
- Create: `frontend/src/app/settings/schliesstage/schliesstage.component.scss`
- Modify: `frontend/src/app/settings/organisation/organisation.component.html:358-359` (neuer Tab vor `</mat-tab-group>`)
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts` (Import der Kind-Komponente)
- Test: `frontend/src/app/settings/schliesstage/schliesstage.component.spec.ts`

**Interfaces:**
- Consumes: `ClosureCalendarComponent` inkl. `clearSelection()` (Task 8/9), alle drei Services (Task 7), `SemesterService`
- Produces: `SchliesstageComponent`, Selektor `app-schliesstage`, mit
  - `tristate(definitionId: string): 'all' | 'none' | 'some'`
  - `toggleDefinition(definition: ClosureDefinition): void`
  - `onSemesterChange(semesterId: string): void`

Aufbau von oben nach unten: Semester-Dropdown, Kalender über die volle Breite, Zuweisungsleiste (nur bei bestehender Auswahl), Definitionstabelle (kommt in Task 11).

Der Kalender lädt **ausschließlich** beim Wechsel der Semesterauswahl neu. Das Semester wird wie überall sonst im Projekt als `semesters[0]` vorbelegt — zuletzt angelegt, sortiert nach `createdAt` (`board`, `elterneinteilung`, `kosten-pro-semester`, `platzzuweisung`, `stundenuebersicht`, `organisation` machen es genauso).

Die Zuweisungsleiste zeigt jede aktive Definition mit Tri-State-Checkbox: angehakt = gilt für alle gewählten Tage, unbestimmt = für einen Teil, leer = für keinen. Anhaken weist zu, Abhaken entfernt. Damit sind Zuweisen und Entfernen dieselbe Geste.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/settings/schliesstage/schliesstage.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { SchliesstageComponent } from './schliesstage.component';
import { ClosureDefinitionService } from '../../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../../shared/services/closure-period.service';
import { HolidayService } from '../../shared/services/holiday.service';
import { SemesterService } from '../../shared/services/semester.service';
import { ApplyPeriodsRequest, ClosureDefinition, ClosurePeriod } from '../../shared/models/closure.model';

const ferien: ClosureDefinition = {
  id: 'def-ferien', label: 'Ferien', color: '#d94f4f', active: true, createdAt: '2026-07-02T00:00:00Z',
};
const fortbildung: ClosureDefinition = {
  id: 'def-fortbildung', label: 'Fortbildung', color: '#e0a020', active: true, createdAt: '2026-07-01T00:00:00Z',
};

describe('SchliesstageComponent', () => {
  let fixture: ComponentFixture<SchliesstageComponent>;
  let component: SchliesstageComponent;
  let periodService: jasmine.SpyObj<ClosurePeriodService>;
  let periods: ClosurePeriod[];

  beforeEach(async () => {
    periods = [{ id: 'p1', from: '2026-09-07', to: '2026-09-09', definitionId: 'def-ferien' }];

    const definitionService = jasmine.createSpyObj<ClosureDefinitionService>(
      'ClosureDefinitionService', ['getAll', 'create', 'update', 'revise', 'deactivate']);
    definitionService.getAll.and.callFake(() => of([ferien, fortbildung]));

    periodService = jasmine.createSpyObj<ClosurePeriodService>(
      'ClosurePeriodService', ['getRange', 'apply']);
    periodService.getRange.and.callFake(() => of(periods));
    periodService.apply.and.callFake(() => of([]));

    const holidayService = jasmine.createSpyObj<HolidayService>('HolidayService', ['getRange']);
    holidayService.getRange.and.returnValue(of([{ date: '2026-10-26', name: 'Nationalfeiertag' }]));

    const semesterService = jasmine.createSpyObj<SemesterService>('SemesterService', ['getAll', 'create']);
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-2', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z', createdAt: '2026-07-01T00:00:00Z' },
      { id: 'sem-1', start: '2026-02-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    ] as never));

    await TestBed.configureTestingModule({
      imports: [SchliesstageComponent, NoopAnimationsModule],
      providers: [
        { provide: ClosureDefinitionService, useValue: definitionService },
        { provide: ClosurePeriodService, useValue: periodService },
        { provide: HolidayService, useValue: holidayService },
        { provide: SemesterService, useValue: semesterService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SchliesstageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('waehlt das zuletzt angelegte Semester vor', () => {
    expect(component.selectedSemesterId).toBe('sem-2');
  });

  it('leitet den Semesterzeitraum an den Kalender weiter', () => {
    expect(component.from).toBe('2026-09-01');
    expect(component.to).toBe('2027-02-28');
  });

  it('laedt den Kalender nur beim Semesterwechsel neu', () => {
    expect(periodService.getRange).toHaveBeenCalledTimes(1);

    component.onSelectionChange(['2026-09-07']);
    fixture.detectChanges();
    expect(periodService.getRange).toHaveBeenCalledTimes(1);

    component.onSemesterChange('sem-1');
    fixture.detectChanges();
    expect(periodService.getRange).toHaveBeenCalledTimes(2);
  });

  it('meldet none, solange nichts markiert ist', () => {
    expect(component.tristate('def-ferien')).toBe('none');
  });

  it('meldet all, wenn die Definition auf allen gewaehlten Tagen liegt', () => {
    component.onSelectionChange(['2026-09-07', '2026-09-08']);
    expect(component.tristate('def-ferien')).toBe('all');
  });

  it('meldet some bei teilweiser Belegung', () => {
    component.onSelectionChange(['2026-09-08', '2026-09-10']);
    expect(component.tristate('def-ferien')).toBe('some');
  });

  it('meldet none, wenn die Definition auf keinem gewaehlten Tag liegt', () => {
    component.onSelectionChange(['2026-09-10', '2026-09-11']);
    expect(component.tristate('def-fortbildung')).toBe('none');
  });

  it('weist zu, wenn die Definition noch nicht ueberall gilt', () => {
    component.onSelectionChange(['2026-09-10']);
    component.toggleDefinition(ferien);

    const request = periodService.apply.calls.mostRecent().args[0] as ApplyPeriodsRequest;
    expect(request.mode).toBe('assign');
    expect(request.definitionId).toBe('def-ferien');
    expect(request.days).toEqual(['2026-09-10']);
  });

  it('entfernt, wenn die Definition bereits ueberall gilt', () => {
    component.onSelectionChange(['2026-09-07', '2026-09-08']);
    component.toggleDefinition(ferien);

    const request = periodService.apply.calls.mostRecent().args[0] as ApplyPeriodsRequest;
    expect(request.mode).toBe('remove');
  });

  it('laedt die Zeitraeume nach dem Zuweisen neu und leert die Auswahl', () => {
    component.onSelectionChange(['2026-09-10']);
    component.toggleDefinition(ferien);
    fixture.detectChanges();

    expect(periodService.getRange).toHaveBeenCalledTimes(2);
    expect(component.selectedDays).toEqual([]);
  });

  it('blendet die Zuweisungsleiste nur bei bestehender Auswahl ein', () => {
    expect(fixture.nativeElement.querySelector('.assign-bar')).toBeNull();

    component.onSelectionChange(['2026-09-10']);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.assign-bar')).not.toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/schliesstage.component.spec.ts'`
Expected: Kompilierfehler — `Cannot find module './schliesstage.component'`.

- [ ] **Step 3: Write the component**

`frontend/src/app/settings/schliesstage/schliesstage.component.ts`:

```ts
import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';

import { ClosureCalendarComponent } from '../../shared/components/closure-calendar/closure-calendar.component';
import { ClosureDefinitionService } from '../../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../../shared/services/closure-period.service';
import { HolidayService } from '../../shared/services/holiday.service';
import { SemesterService } from '../../shared/services/semester.service';
import { ClosureDefinition, ClosurePeriod, Holiday } from '../../shared/models/closure.model';
import { Semester } from '../../shared/models/semester.model';

export type TriState = 'all' | 'none' | 'some';

@Component({
  selector: 'app-schliesstage',
  standalone: true,
  imports: [
    CommonModule, MatFormFieldModule, MatSelectModule, MatCheckboxModule,
    MatProgressBarModule, ClosureCalendarComponent,
  ],
  templateUrl: './schliesstage.component.html',
  styleUrl: './schliesstage.component.scss',
})
export class SchliesstageComponent implements OnInit {
  @ViewChild(ClosureCalendarComponent) calendar?: ClosureCalendarComponent;

  semesters: Semester[] = [];
  selectedSemesterId: string | null = null;

  /** ISO yyyy-MM-dd, Grenzen des gewaehlten Semesters. */
  from = '';
  to = '';

  definitions: ClosureDefinition[] = [];
  periods: ClosurePeriod[] = [];
  holidays: Holiday[] = [];
  selectedDays: string[] = [];
  loading = false;

  constructor(
    private definitionService: ClosureDefinitionService,
    private periodService: ClosurePeriodService,
    private holidayService: HolidayService,
    private semesterService: SemesterService,
    private snackBar: MatSnackBar,
  ) {}

  ngOnInit(): void {
    this.semesterService.getAll().subscribe(semesters => {
      this.semesters = semesters;
      // Wie in allen anderen Masken: zuletzt angelegtes Semester zuerst.
      this.selectedSemesterId = semesters[0]?.id ?? null;
      this.loadCalendar();
    });
  }

  onSemesterChange(semesterId: string): void {
    this.selectedSemesterId = semesterId;
    this.loadCalendar();
  }

  onSelectionChange(days: string[]): void {
    this.selectedDays = days;
  }

  semesterLabel(semester: Semester): string {
    return `${semester.start.slice(0, 4)}/${semester.end.slice(0, 4)}`;
  }

  /**
   * Gilt die Definition auf allen gewaehlten Tagen, auf einem Teil, oder auf keinem?
   * Der unbestimmte Zustand macht sichtbar, was auf der Auswahl bereits liegt.
   */
  tristate(definitionId: string): TriState {
    if (this.selectedDays.length === 0) {
      return 'none';
    }
    const covered = this.selectedDays.filter(day => this.coversDay(definitionId, day)).length;
    if (covered === 0) {
      return 'none';
    }
    return covered === this.selectedDays.length ? 'all' : 'some';
  }

  /** Anhaken weist zu, Abhaken entfernt — dieselbe Geste fuer beides. */
  toggleDefinition(definition: ClosureDefinition): void {
    if (this.selectedDays.length === 0) {
      return;
    }
    const mode = this.tristate(definition.id) === 'all' ? 'remove' : 'assign';
    this.loading = true;
    this.periodService
      .apply({ days: this.selectedDays, definitionId: definition.id, mode })
      .subscribe({
        next: () => {
          this.calendar?.clearSelection();
          this.selectedDays = [];
          this.reloadPeriods();
        },
        error: (error: { error?: string }) => {
          this.loading = false;
          this.snackBar.open(
            typeof error?.error === 'string' ? error.error : 'Zuordnung fehlgeschlagen',
            'OK', { duration: 6000 });
        },
      });
  }

  /** Von Task 11 aufgerufen, wenn sich die Definitionsliste geaendert hat. */
  reloadDefinitions(): void {
    this.definitionService.getAll().subscribe(definitions => {
      this.definitions = definitions;
    });
  }

  private coversDay(definitionId: string, day: string): boolean {
    return this.periods.some(
      period => period.definitionId === definitionId && period.from <= day && day <= period.to);
  }

  private loadCalendar(): void {
    const semester = this.semesters.find(s => s.id === this.selectedSemesterId);
    if (!semester) {
      this.from = '';
      this.to = '';
      this.periods = [];
      this.holidays = [];
      return;
    }
    // Der Instant kommt als ISO-String; der Datumsanteil ist der Kalendertag.
    this.from = semester.start.slice(0, 10);
    this.to = semester.end.slice(0, 10);
    this.selectedDays = [];
    this.loading = true;

    forkJoin({
      definitions: this.definitionService.getAll(),
      periods: this.periodService.getRange(this.from, this.to),
      holidays: this.holidayService.getRange(this.from, this.to),
    }).subscribe({
      next: result => {
        this.definitions = result.definitions;
        this.periods = result.periods;
        this.holidays = result.holidays;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.snackBar.open('Schließtage konnten nicht geladen werden', 'OK', { duration: 6000 });
      },
    });
  }

  private reloadPeriods(): void {
    this.periodService.getRange(this.from, this.to).subscribe({
      next: periods => {
        this.periods = periods;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.snackBar.open('Zeiträume konnten nicht geladen werden', 'OK', { duration: 6000 });
      },
    });
  }
}
```

`frontend/src/app/settings/schliesstage/schliesstage.component.html`:

```html
<div class="schliesstage">
  <mat-form-field appearance="outline">
    <mat-label>Semester</mat-label>
    <mat-select [value]="selectedSemesterId" (selectionChange)="onSemesterChange($event.value)">
      @for (semester of semesters; track semester.id) {
        <mat-option [value]="semester.id">{{ semesterLabel(semester) }}</mat-option>
      }
    </mat-select>
  </mat-form-field>

  @if (loading) {
    <mat-progress-bar mode="indeterminate"></mat-progress-bar>
  }

  <app-closure-calendar
    [from]="from"
    [to]="to"
    [periods]="periods"
    [definitions]="definitions"
    [holidays]="holidays"
    (selectionChange)="onSelectionChange($event)">
  </app-closure-calendar>

  @if (selectedDays.length > 0) {
    <div class="assign-bar">
      <span class="assign-count">{{ selectedDays.length }} Tage gewählt</span>
      <div class="assign-options">
        @for (definition of definitions; track definition.id) {
          <mat-checkbox
            [checked]="tristate(definition.id) === 'all'"
            [indeterminate]="tristate(definition.id) === 'some'"
            (change)="toggleDefinition(definition)">
            <span class="swatch" [style.background-color]="definition.color"></span>
            {{ definition.label }}
          </mat-checkbox>
        }
      </div>
    </div>
  }
</div>
```

`frontend/src/app/settings/schliesstage/schliesstage.component.scss`:

```scss
.schliesstage {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 0;
}

.assign-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
  padding: 12px 16px;
  border: 1px dashed #2e7d32;
  border-radius: 6px;
}

.assign-count {
  font-weight: 600;
}

.assign-options {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.swatch {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 3px;
  margin-right: 6px;
  vertical-align: -1px;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/schliesstage.component.spec.ts'`
Expected: `Executed 11 of 11 SUCCESS`

- [ ] **Step 5: Wire the tab into the Organisation mask**

In `organisation.component.ts` den Import ergänzen:

```ts
import { SchliesstageComponent } from '../schliesstage/schliesstage.component';
```

und `SchliesstageComponent` dem `imports`-Array des `@Component`-Dekorators hinzufügen.

In `organisation.component.html` direkt vor der schließenden `</mat-tab-group>` einfügen:

```html
    <!-- Schließtage Tab -->
    <mat-tab label="Schließtage">
      <div class="tab-body">
        <app-schliesstage></app-schliesstage>
      </div>
    </mat-tab>
```

`organisation.component.ts` umfasst bereits 582 Zeilen — deshalb bleibt hier ausschließlich die Einbindung, die gesamte Logik lebt in der Kind-Komponente.

- [ ] **Step 6: Verify the full frontend suite and the build**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: keine neuen Fehlschläge gegenüber dem Vorbestand (1 vorbekannter).

Run: `cd frontend && npm run build`
Expected: erfolgreicher Produktionsbuild.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/settings/schliesstage/ \
        frontend/src/app/settings/organisation/organisation.component.ts \
        frontend/src/app/settings/organisation/organisation.component.html
git commit -m "feat(fe): Admin-Maske Schliesstage mit Semesterwahl und Tri-State-Zuweisung"
```

---

## Task 11: Definitionstabelle und Kopie-Dialog

**Files:**
- Create: `frontend/src/app/settings/schliesstage/closure-revise-dialog.component.ts`
- Modify: `frontend/src/app/settings/schliesstage/schliesstage.component.ts`
- Modify: `frontend/src/app/settings/schliesstage/schliesstage.component.html`
- Modify: `frontend/src/app/settings/schliesstage/schliesstage.component.scss`
- Test: `frontend/src/app/settings/schliesstage/closure-definitions.spec.ts`

**Interfaces:**
- Consumes: `ClosureDefinitionService` (Task 7), `SchliesstageComponent` (Task 10)
- Produces:
  - `ClosureReviseDialogComponent` — liefert `'revise' | undefined` beim Schließen
  - `SchliesstageComponent.addDefinition()`, `.startEdit(definition)`, `.commitEdit()`, `.cancelEdit()`, `.setActive(definition, active)`

**Auslösung des Kopie-Dialogs:** Ob Zeiträume verknüpft sind, weiß nur das Backend. Statt einen zusätzlichen Zähl-Endpoint zu bauen, wird schlicht `PUT` versucht. Antwortet das Backend mit 409, erscheint der Warndialog; bestätigt der Admin, geht derselbe Inhalt an `revise`. Bricht er ab, wird das Formular auf den zuletzt gespeicherten Stand zurückgesetzt. Es gibt damit nur einen Weg und keine Möglichkeit, dass Anzeige und Serverzustand auseinanderlaufen.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/settings/schliesstage/closure-definitions.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { SchliesstageComponent } from './schliesstage.component';
import { ClosureDefinitionService } from '../../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../../shared/services/closure-period.service';
import { HolidayService } from '../../shared/services/holiday.service';
import { SemesterService } from '../../shared/services/semester.service';
import { ClosureDefinition } from '../../shared/models/closure.model';

const ferien: ClosureDefinition = {
  id: 'def-ferien', label: 'Ferien', color: '#d94f4f', active: true, createdAt: '2026-07-02T00:00:00Z',
};
const umbau: ClosureDefinition = {
  id: 'def-umbau', label: 'Umbau', color: '#888888', active: false, createdAt: '2026-07-01T00:00:00Z',
};

describe('SchliesstageComponent — Definitionen', () => {
  let fixture: ComponentFixture<SchliesstageComponent>;
  let component: SchliesstageComponent;
  let definitionService: jasmine.SpyObj<ClosureDefinitionService>;
  let dialog: jasmine.SpyObj<MatDialog>;

  beforeEach(async () => {
    definitionService = jasmine.createSpyObj<ClosureDefinitionService>(
      'ClosureDefinitionService', ['getAll', 'create', 'update', 'revise', 'deactivate']);
    definitionService.getAll.and.callFake(() => of([ferien, umbau]));
    definitionService.create.and.returnValue(of(ferien));
    definitionService.update.and.returnValue(of(ferien));
    definitionService.revise.and.returnValue(of(ferien));
    definitionService.deactivate.and.returnValue(of(undefined));

    const periodService = jasmine.createSpyObj<ClosurePeriodService>(
      'ClosurePeriodService', ['getRange', 'apply']);
    periodService.getRange.and.returnValue(of([]));
    periodService.apply.and.returnValue(of([]));

    const holidayService = jasmine.createSpyObj<HolidayService>('HolidayService', ['getRange']);
    holidayService.getRange.and.returnValue(of([]));

    const semesterService = jasmine.createSpyObj<SemesterService>('SemesterService', ['getAll', 'create']);
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-1', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z', createdAt: '2026-07-01T00:00:00Z' },
    ] as never));

    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    await TestBed.configureTestingModule({
      imports: [SchliesstageComponent, NoopAnimationsModule],
      providers: [
        { provide: ClosureDefinitionService, useValue: definitionService },
        { provide: ClosurePeriodService, useValue: periodService },
        { provide: HolidayService, useValue: holidayService },
        { provide: SemesterService, useValue: semesterService },
        { provide: MatDialog, useValue: dialog },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SchliesstageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('laedt auch deaktivierte Definitionen fuer die Tabelle', () => {
    expect(definitionService.getAll).toHaveBeenCalledWith(true);
  });

  it('bietet nur aktive Definitionen zur Zuweisung an', () => {
    expect(component.assignableDefinitions.map(d => d.id)).toEqual(['def-ferien']);
  });

  it('legt eine neue Definition an', () => {
    component.definitionForm.setValue({ label: 'Fortbildung', color: '#e0a020' });
    component.addDefinition();

    expect(definitionService.create).toHaveBeenCalledWith({ label: 'Fortbildung', color: '#e0a020' });
  });

  it('legt ohne Label nichts an', () => {
    component.definitionForm.setValue({ label: '  ', color: '#e0a020' });
    component.addDefinition();

    expect(definitionService.create).not.toHaveBeenCalled();
  });

  it('speichert eine unveraenderte Bearbeitung gar nicht erst', () => {
    component.startEdit(ferien);
    component.commitEdit();

    expect(definitionService.update).not.toHaveBeenCalled();
  });

  it('speichert eine Aenderung ohne Verknuepfung direkt', () => {
    component.startEdit(ferien);
    component.editForm.patchValue({ label: 'Ferien neu' });
    component.commitEdit();

    expect(definitionService.update).toHaveBeenCalledWith('def-ferien', {
      label: 'Ferien neu', color: '#d94f4f',
    });
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('zeigt bei 409 den Warndialog und legt nach OK eine Kopie an', () => {
    definitionService.update.and.returnValue(throwError(() => ({ status: 409 })));
    dialog.open.and.returnValue({ afterClosed: () => of('revise') } as never);

    component.startEdit(ferien);
    component.editForm.patchValue({ label: 'Ferien neu' });
    component.commitEdit();

    expect(dialog.open).toHaveBeenCalled();
    expect(definitionService.revise).toHaveBeenCalledWith('def-ferien', {
      label: 'Ferien neu', color: '#d94f4f',
    });
  });

  it('verwirft die Aenderung bei Abbruch', () => {
    definitionService.update.and.returnValue(throwError(() => ({ status: 409 })));
    dialog.open.and.returnValue({ afterClosed: () => of(undefined) } as never);

    component.startEdit(ferien);
    component.editForm.patchValue({ label: 'Ferien neu' });
    component.commitEdit();

    expect(definitionService.revise).not.toHaveBeenCalled();
    expect(component.editingId).toBeNull();
  });

  it('deaktiviert ueber DELETE', () => {
    component.setActive(ferien, false);
    expect(definitionService.deactivate).toHaveBeenCalledWith('def-ferien');
  });

  it('reaktiviert ueber PUT mit unveraendertem Inhalt', () => {
    component.setActive(umbau, true);
    expect(definitionService.update).toHaveBeenCalledWith('def-umbau', {
      label: 'Umbau', color: '#888888', active: true,
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/closure-definitions.spec.ts'`
Expected: Fehler — `component.definitionForm` und die Bearbeitungsmethoden existieren nicht.

- [ ] **Step 3: Write the dialog**

`frontend/src/app/settings/schliesstage/closure-revise-dialog.component.ts`:

```ts
import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';

@Component({
  selector: 'app-closure-revise-dialog',
  standalone: true,
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>Achtung</h2>
    <mat-dialog-content>
      <p>
        Die Definition wurde geändert. Bereits verknüpfte Daten werden
        <strong>nicht</strong> geändert.
      </p>
      <p>
        Mit „Fortfahren" wird eine Kopie mit den neuen Werten angelegt; die
        bisherige Definition bleibt für vorhandene Zeiträume erhalten und
        verschwindet aus der Auswahlliste.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">Abbrechen</button>
      <button mat-raised-button color="primary" (click)="confirm()">Fortfahren</button>
    </mat-dialog-actions>
  `,
})
export class ClosureReviseDialogComponent {
  constructor(private dialogRef: MatDialogRef<ClosureReviseDialogComponent, 'revise' | undefined>) {}

  confirm(): void {
    this.dialogRef.close('revise');
  }

  cancel(): void {
    this.dialogRef.close(undefined);
  }
}
```

- [ ] **Step 4: Extend the component**

In `schliesstage.component.ts` die Imports erweitern:

```ts
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTableModule } from '@angular/material/table';

import { ClosureReviseDialogComponent } from './closure-revise-dialog.component';
import { ClosureDefinitionRequest } from '../../shared/models/closure.model';
```

und dem `imports`-Array des Dekorators `ReactiveFormsModule`, `MatButtonModule`, `MatIconModule`, `MatInputModule`, `MatTableModule` hinzufügen. `MatDialog` wird über den Konstruktor injiziert:

```ts
    private dialog: MatDialog,
```

Innerhalb der Klasse ergänzen:

```ts
  readonly definitionColumns = ['color', 'label', 'status', 'actions'];

  definitionForm = new FormGroup({
    label: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    color: new FormControl('#4285f4', { nonNullable: true, validators: [Validators.required] }),
  });

  editForm = new FormGroup({
    label: new FormControl('', { nonNullable: true }),
    color: new FormControl('#4285f4', { nonNullable: true }),
  });

  editingId: string | null = null;
  private editingOriginal: ClosureDefinition | null = null;

  /** Nur aktive Definitionen lassen sich zuweisen; deaktivierte bleiben sichtbar. */
  get assignableDefinitions(): ClosureDefinition[] {
    return this.definitions.filter(definition => definition.active);
  }

  addDefinition(): void {
    const label = this.definitionForm.value.label?.trim() ?? '';
    const color = this.definitionForm.value.color ?? '#4285f4';
    if (!label) {
      return;
    }
    this.definitionService.create({ label, color }).subscribe({
      next: () => {
        this.definitionForm.reset({ label: '', color: '#4285f4' });
        this.reloadDefinitions();
      },
      error: () => this.snackBar.open('Definition konnte nicht angelegt werden', 'OK', { duration: 6000 }),
    });
  }

  startEdit(definition: ClosureDefinition): void {
    this.editingId = definition.id;
    this.editingOriginal = definition;
    this.editForm.setValue({ label: definition.label, color: definition.color });
  }

  cancelEdit(): void {
    this.editingId = null;
    this.editingOriginal = null;
  }

  /**
   * Ob Zeitraeume verknuepft sind, weiss nur das Backend. Statt eines eigenen
   * Zaehl-Endpoints wird PUT versucht; ein 409 loest den Warndialog aus.
   */
  commitEdit(): void {
    const original = this.editingOriginal;
    if (!original) {
      return;
    }
    const request: ClosureDefinitionRequest = {
      label: this.editForm.value.label?.trim() ?? '',
      color: this.editForm.value.color ?? original.color,
    };
    if (!request.label || (request.label === original.label && request.color === original.color)) {
      this.cancelEdit();
      return;
    }

    this.definitionService.update(original.id, request).subscribe({
      next: () => {
        this.cancelEdit();
        this.reloadDefinitions();
      },
      error: (error: { status?: number }) => {
        if (error?.status !== 409) {
          this.snackBar.open('Änderung konnte nicht gespeichert werden', 'OK', { duration: 6000 });
          this.cancelEdit();
          return;
        }
        this.dialog.open(ClosureReviseDialogComponent).afterClosed().subscribe(result => {
          if (result !== 'revise') {
            // Abbrechen: zurueck auf den zuletzt gespeicherten Stand.
            this.cancelEdit();
            return;
          }
          this.definitionService.revise(original.id, request).subscribe({
            next: () => {
              this.cancelEdit();
              this.reloadDefinitions();
            },
            error: () => {
              this.snackBar.open('Kopie konnte nicht angelegt werden', 'OK', { duration: 6000 });
              this.cancelEdit();
            },
          });
        });
      },
    });
  }

  setActive(definition: ClosureDefinition, active: boolean): void {
    const request = active
      ? this.definitionService.update(definition.id, {
          label: definition.label, color: definition.color, active: true,
        })
      : this.definitionService.deactivate(definition.id);

    request.subscribe({
      next: () => this.reloadDefinitions(),
      error: () => this.snackBar.open('Status konnte nicht geändert werden', 'OK', { duration: 6000 }),
    });
  }
```

`reloadDefinitions` so ändern, dass sie auch inaktive Definitionen lädt — die Tabelle zeigt sie abgeschwächt an:

```ts
  reloadDefinitions(): void {
    this.definitionService.getAll(true).subscribe(definitions => {
      this.definitions = definitions;
    });
  }
```

Ebenso in `loadCalendar` das `forkJoin` auf `this.definitionService.getAll(true)` umstellen.

In der Zuweisungsleiste im Template `definitions` durch `assignableDefinitions` ersetzen, damit deaktivierte Definitionen nicht mehr zuweisbar sind.

- [ ] **Step 5: Add the table to the template**

In `schliesstage.component.html` nach der Zuweisungsleiste ergänzen:

```html
  <form class="definition-form" [formGroup]="definitionForm" (ngSubmit)="addDefinition()">
    <mat-form-field appearance="outline">
      <mat-label>Bezeichnung</mat-label>
      <input matInput formControlName="label">
    </mat-form-field>
    <mat-form-field appearance="outline">
      <mat-label>Farbe</mat-label>
      <input matInput formControlName="color" type="color">
    </mat-form-field>
    <button mat-raised-button color="primary" type="submit" [disabled]="!definitionForm.valid">
      Hinzufügen
    </button>
  </form>

  <table mat-table [dataSource]="definitions" class="mat-elevation-z2">
    <ng-container matColumnDef="color">
      <th mat-header-cell *matHeaderCellDef></th>
      <td mat-cell *matCellDef="let row">
        @if (editingId === row.id) {
          <input [formControl]="editForm.controls.color" type="color" (blur)="commitEdit()">
        } @else {
          <span class="swatch" [style.background-color]="row.color"></span>
        }
      </td>
    </ng-container>

    <ng-container matColumnDef="label">
      <th mat-header-cell *matHeaderCellDef>Bezeichnung</th>
      <td mat-cell *matCellDef="let row">
        @if (editingId === row.id) {
          <input [formControl]="editForm.controls.label" (blur)="commitEdit()">
        } @else {
          {{ row.label }}
        }
      </td>
    </ng-container>

    <ng-container matColumnDef="status">
      <th mat-header-cell *matHeaderCellDef>Status</th>
      <td mat-cell *matCellDef="let row">{{ row.active ? 'Aktiv' : 'Deaktiviert' }}</td>
    </ng-container>

    <ng-container matColumnDef="actions">
      <th mat-header-cell *matHeaderCellDef></th>
      <td mat-cell *matCellDef="let row">
        <button mat-icon-button (click)="startEdit(row)" title="Bearbeiten">
          <mat-icon>edit</mat-icon>
        </button>
        @if (row.active) {
          <button mat-icon-button color="warn" (click)="setActive(row, false)" title="Deaktivieren">
            <mat-icon>block</mat-icon>
          </button>
        } @else {
          <button mat-icon-button (click)="setActive(row, true)" title="Reaktivieren">
            <mat-icon>undo</mat-icon>
          </button>
        }
      </td>
    </ng-container>

    <tr mat-header-row *matHeaderRowDef="definitionColumns"></tr>
    <tr mat-row *matRowDef="let row; columns: definitionColumns;"
        [class.inactive]="!row.active"></tr>
  </table>
```

In `schliesstage.component.scss` ergänzen:

```scss
.definition-form {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}

tr.inactive {
  opacity: 0.5;
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/settings/schliesstage/*.spec.ts'`
Expected: `Executed 21 of 21 SUCCESS` — beide Spec-Dateien der Admin-Maske.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/settings/schliesstage/
git commit -m "feat(fe): Definitionstabelle mit Kopie-Dialog und Deaktivierung"
```

---

## Task 12: Elternansicht

**Files:**
- Create: `frontend/src/app/schliesstage/schliesstage-view.component.ts`
- Create: `frontend/src/app/schliesstage/schliesstage-view.component.html`
- Create: `frontend/src/app/schliesstage/schliesstage-view.component.scss`
- Modify: `frontend/src/app/app.routes.ts` (Route vor dem `administration`-Block)
- Modify: `frontend/src/app/app.component.html:14` (Nav-Punkt nach „Unsere Stunden", vor dem Administration-Block)
- Test: `frontend/src/app/schliesstage/schliesstage-view.component.spec.ts`

**Interfaces:**
- Consumes: `ClosureCalendarComponent` (Task 8/9), `ClosureDefinitionService`, `ClosurePeriodService`, `HolidayService` (Task 7), `SemesterService`
- Produces: `SchliesstageViewComponent`, Route `/schliesstage`, geschützt nur durch `authGuard`

Die Ansicht bestimmt das Semester **nach dem heutigen Datum** — abweichend von den sechs Admin-Masken, die `semesters[0]` nach `createdAt` nehmen. Das ist bewusst so entschieden: ein frisch angelegtes, noch nicht begonnenes Semester soll Eltern nicht als laufend angezeigt werden. Liegt heute in keinem Semester, erscheint ein Hinweistext statt eines Kalenders.

Die Legende zeigt nur Definitionen, die im geladenen Zeitraum tatsächlich vorkommen — auch deaktivierte, denn ältere Zeiträume verweisen weiterhin auf sie.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/schliesstage/schliesstage-view.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { SchliesstageViewComponent } from './schliesstage-view.component';
import { ClosureDefinitionService } from '../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../shared/services/closure-period.service';
import { HolidayService } from '../shared/services/holiday.service';
import { SemesterService } from '../shared/services/semester.service';
import { ClosureDefinition } from '../shared/models/closure.model';

const ferien: ClosureDefinition = {
  id: 'def-ferien', label: 'Ferien', color: '#d94f4f', active: true, createdAt: '2026-07-02T00:00:00Z',
};
const umbau: ClosureDefinition = {
  id: 'def-umbau', label: 'Umbau', color: '#888888', active: false, createdAt: '2026-07-01T00:00:00Z',
};

describe('SchliesstageViewComponent', () => {
  let periodService: jasmine.SpyObj<ClosurePeriodService>;
  let semesterService: jasmine.SpyObj<SemesterService>;

  function build(): ComponentFixture<SchliesstageViewComponent> {
    const fixture = TestBed.createComponent(SchliesstageViewComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(async () => {
    const definitionService = jasmine.createSpyObj<ClosureDefinitionService>(
      'ClosureDefinitionService', ['getAll', 'create', 'update', 'revise', 'deactivate']);
    definitionService.getAll.and.returnValue(of([ferien, umbau]));

    periodService = jasmine.createSpyObj<ClosurePeriodService>(
      'ClosurePeriodService', ['getRange', 'apply']);
    periodService.getRange.and.returnValue(of([
      { id: 'p1', from: '2026-09-07', to: '2026-09-09', definitionId: 'def-ferien' },
    ]));

    const holidayService = jasmine.createSpyObj<HolidayService>('HolidayService', ['getRange']);
    holidayService.getRange.and.returnValue(of([]));

    semesterService = jasmine.createSpyObj<SemesterService>('SemesterService', ['getAll', 'create']);

    await TestBed.configureTestingModule({
      imports: [SchliesstageViewComponent, NoopAnimationsModule],
      providers: [
        { provide: ClosureDefinitionService, useValue: definitionService },
        { provide: ClosurePeriodService, useValue: periodService },
        { provide: HolidayService, useValue: holidayService },
        { provide: SemesterService, useValue: semesterService },
      ],
    }).compileComponents();

    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(2026, 8, 15)); // 15.09.2026, lokale Zeit
  });

  afterEach(() => jasmine.clock().uninstall());

  it('waehlt das Semester, in das das heutige Datum faellt', () => {
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-neu', start: '2027-03-01T00:00:00Z', end: '2027-08-31T00:00:00Z', createdAt: '2026-08-01T00:00:00Z' },
      { id: 'sem-laufend', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z', createdAt: '2026-07-01T00:00:00Z' },
    ] as never));

    const fixture = build();

    // Nicht semesters[0] — das waere das noch nicht begonnene Semester.
    expect(fixture.componentInstance.from).toBe('2026-09-01');
    expect(fixture.componentInstance.to).toBe('2027-02-28');
    expect(periodService.getRange).toHaveBeenCalledWith('2026-09-01', '2027-02-28');
  });

  it('zeigt einen Hinweis, wenn heute in keinem Semester liegt', () => {
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-alt', start: '2026-02-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    ] as never));

    const fixture = build();

    expect(fixture.componentInstance.from).toBe('');
    expect(periodService.getRange).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.no-semester')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-closure-calendar')).toBeNull();
  });

  it('zeigt einen Hinweis, wenn gar kein Semester existiert', () => {
    semesterService.getAll.and.returnValue(of([] as never));

    const fixture = build();

    expect(fixture.nativeElement.querySelector('.no-semester')).not.toBeNull();
  });

  it('rendert den Kalender schreibgeschuetzt', () => {
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-laufend', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z', createdAt: '2026-07-01T00:00:00Z' },
    ] as never));

    const fixture = build();

    expect(fixture.componentInstance.readonly).toBe(true);
  });

  it('zeigt in der Legende nur vorkommende Definitionen', () => {
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-laufend', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z', createdAt: '2026-07-01T00:00:00Z' },
    ] as never));

    const fixture = build();

    expect(fixture.componentInstance.legend.map(d => d.id)).toEqual(['def-ferien']);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/schliesstage-view.component.spec.ts'`
Expected: Kompilierfehler — `Cannot find module './schliesstage-view.component'`.

- [ ] **Step 3: Write the component**

`frontend/src/app/schliesstage/schliesstage-view.component.ts`:

```ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { forkJoin } from 'rxjs';

import { ClosureCalendarComponent } from '../shared/components/closure-calendar/closure-calendar.component';
import { ClosureDefinitionService } from '../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../shared/services/closure-period.service';
import { HolidayService } from '../shared/services/holiday.service';
import { SemesterService } from '../shared/services/semester.service';
import { ClosureDefinition, ClosurePeriod, Holiday } from '../shared/models/closure.model';

@Component({
  selector: 'app-schliesstage-view',
  standalone: true,
  imports: [CommonModule, ClosureCalendarComponent],
  templateUrl: './schliesstage-view.component.html',
  styleUrl: './schliesstage-view.component.scss',
})
export class SchliesstageViewComponent implements OnInit {
  readonly readonly = true;

  from = '';
  to = '';
  semesterLabel = '';
  definitions: ClosureDefinition[] = [];
  periods: ClosurePeriod[] = [];
  holidays: Holiday[] = [];
  loaded = false;

  constructor(
    private definitionService: ClosureDefinitionService,
    private periodService: ClosurePeriodService,
    private holidayService: HolidayService,
    private semesterService: SemesterService,
  ) {}

  ngOnInit(): void {
    this.semesterService.getAll().subscribe(semesters => {
      const today = this.todayIso();
      // Bewusst datumsbasiert statt semesters[0]: ein noch nicht begonnenes
      // Semester soll Eltern nicht als laufend angezeigt werden.
      const current = semesters.find(
        semester => semester.start.slice(0, 10) <= today && today <= semester.end.slice(0, 10));

      if (!current) {
        this.loaded = true;
        return;
      }

      this.from = current.start.slice(0, 10);
      this.to = current.end.slice(0, 10);
      this.semesterLabel = `${this.from.slice(0, 4)}/${this.to.slice(0, 4)}`;

      forkJoin({
        definitions: this.definitionService.getAll(true),
        periods: this.periodService.getRange(this.from, this.to),
        holidays: this.holidayService.getRange(this.from, this.to),
      }).subscribe({
        next: result => {
          this.definitions = result.definitions;
          this.periods = result.periods;
          this.holidays = result.holidays;
          this.loaded = true;
        },
        error: () => {
          this.loaded = true;
        },
      });
    });
  }

  /** Nur Definitionen, die im geladenen Zeitraum vorkommen — auch deaktivierte. */
  get legend(): ClosureDefinition[] {
    const used = new Set(this.periods.map(period => period.definitionId));
    return this.definitions.filter(definition => used.has(definition.id));
  }

  private todayIso(): string {
    const now = new Date();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    return `${now.getFullYear()}-${month}-${day}`;
  }
}
```

`frontend/src/app/schliesstage/schliesstage-view.component.html`:

```html
<div class="schliesstage-view">
  <h2>Schließtage</h2>

  @if (from) {
    <p class="semester-label">Semester {{ semesterLabel }}</p>

    <app-closure-calendar
      [from]="from"
      [to]="to"
      [periods]="periods"
      [definitions]="definitions"
      [holidays]="holidays"
      [readonly]="readonly">
    </app-closure-calendar>

    @if (legend.length > 0) {
      <div class="legend">
        @for (definition of legend; track definition.id) {
          <span class="legend-entry">
            <span class="swatch" [style.background-color]="definition.color"></span>
            {{ definition.label }}
          </span>
        }
      </div>
    }
  } @else if (loaded) {
    <p class="no-semester">
      Für den heutigen Tag ist kein Semester hinterlegt. Sobald ein laufendes
      Semester eingetragen ist, erscheinen hier die Schließtage.
    </p>
  }
</div>
```

`frontend/src/app/schliesstage/schliesstage-view.component.scss`:

```scss
.schliesstage-view {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.semester-label {
  margin: 0;
  opacity: 0.7;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.swatch {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 3px;
  margin-right: 6px;
  vertical-align: -1px;
}

.no-semester {
  opacity: 0.7;
  font-style: italic;
}
```

- [ ] **Step 4: Wire route and navigation**

In `app.routes.ts` nach dem `stunden`-Eintrag einfügen — nur `authGuard`, ohne `adminGuard`:

```ts
  {
    path: 'schliesstage',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./schliesstage/schliesstage-view.component').then(
        m => m.SchliesstageViewComponent
      ),
  },
```

In `app.component.html` direkt nach dem „Unsere Stunden"-Link und **vor** dem `@if (currentUser.isAdmin)`-Block einfügen:

```html
      <a mat-list-item routerLink="/schliesstage" routerLinkActive="active">
        <mat-icon matListItemIcon>event_busy</mat-icon>
        <span matListItemTitle>Schließtage</span>
      </a>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/schliesstage-view.component.spec.ts'`
Expected: `Executed 5 of 5 SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/schliesstage/ \
        frontend/src/app/app.routes.ts \
        frontend/src/app/app.component.html
git commit -m "feat(fe): Elternansicht Schliesstage mit datumsbasiertem Semester"
```

---

## Task 13: Kochdienst-Integration im Frontend

**Files:**
- Modify: `frontend/src/app/cooking/cooking-duty-dialog.component.ts` (Dialog-Daten und Filter)
- Modify: `frontend/src/app/cooking/cooking-duty-dialog.component.html:7` (Datepicker-Filter)
- Modify: `frontend/src/app/cooking/cooking.component.ts`
- Modify: `frontend/src/app/cooking/cooking.component.html:35-44` (`beforeViewRender`)
- Modify: `frontend/src/app/cooking/cooking.component.scss`
- Test: `frontend/src/app/cooking/cooking-closure.spec.ts`

**Interfaces:**
- Consumes: `ClosurePeriodService`, `HolidayService` (Task 7)
- Produces:
  - `CookingDutyDialogData.closedDates: string[]` — ISO-Tage, an denen geschlossen ist
  - `CookingDutyDialogComponent.dateFilter: (date: Date | null) => boolean`
  - `CookingComponent.closedDates: Set<string>`, `.beforeMonthViewRender(event)`

Der Datepicker sperrt Schließzeiträume **und** Feiertage. Das Backend lehnt dieselben Tage mit 409 ab (Task 6) — die Sperre im Dialog ist Bequemlichkeit, nicht die Absicherung.

Bestehende Kochdienste an nachträglich eingetragenen Schließtagen bleiben erhalten und werden im Kalender als Konflikt markiert. Das ist die in der Spec festgehaltene Asymmetrie: vorwärts hart verhindern, rückwirkend nur markieren.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/cooking/cooking-closure.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

import { CookingDutyDialogComponent, CookingDutyDialogData } from './cooking-duty-dialog.component';
import { closedDatesFrom } from './cooking-closure.util';

describe('Kochdienst und Schließtage', () => {
  describe('closedDatesFrom', () => {
    it('expandiert Zeitraeume auf einzelne Tage', () => {
      const closed = closedDatesFrom(
        [{ id: 'p1', from: '2026-09-07', to: '2026-09-09', definitionId: 'def' }], []);

      expect([...closed].sort()).toEqual(['2026-09-07', '2026-09-08', '2026-09-09']);
    });

    it('nimmt Feiertage mit auf', () => {
      const closed = closedDatesFrom([], [{ date: '2026-10-26', name: 'Nationalfeiertag' }]);

      expect(closed.has('2026-10-26')).toBe(true);
    });

    it('bleibt bei leeren Eingaben leer', () => {
      expect(closedDatesFrom([], []).size).toBe(0);
    });
  });

  describe('CookingDutyDialogComponent', () => {
    function createDialog(closedDates: string[]): CookingDutyDialogComponent {
      const data: CookingDutyDialogData = {
        groups: [], foodProperties: [], familyParents: [],
        currentUserId: 'p1', canEdit: true, closedDates,
      };
      TestBed.configureTestingModule({
        imports: [CookingDutyDialogComponent, NoopAnimationsModule],
        providers: [
          { provide: MatDialogRef, useValue: { close: () => undefined } },
          { provide: MAT_DIALOG_DATA, useValue: data },
        ],
      });
      const fixture = TestBed.createComponent(CookingDutyDialogComponent);
      fixture.detectChanges();
      return fixture.componentInstance;
    }

    it('sperrt geschlossene Tage im Datepicker', () => {
      const component = createDialog(['2026-09-08']);
      expect(component.dateFilter(new Date(2026, 8, 8))).toBe(false);
    });

    it('laesst offene Tage zu', () => {
      const component = createDialog(['2026-09-08']);
      expect(component.dateFilter(new Date(2026, 8, 9))).toBe(true);
    });

    it('laesst null durch, damit das Feld leer bleiben kann', () => {
      const component = createDialog(['2026-09-08']);
      expect(component.dateFilter(null)).toBe(true);
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/cooking-closure.spec.ts'`
Expected: Kompilierfehler — `Cannot find module './cooking-closure.util'`.

- [ ] **Step 3: Write the util**

`frontend/src/app/cooking/cooking-closure.util.ts`:

```ts
import { ClosurePeriod, Holiday } from '../shared/models/closure.model';

/** ISO-Datum ohne Zeitzonenumrechnung, gleiches Muster wie im Kalenderraster. */
export function toIsoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * Alle Tage, an denen der Kindergarten geschlossen hat: erfasste Zeitraeume
 * plus gesetzliche Feiertage. Feiertage zaehlen mit, obwohl sie nicht
 * persistiert sind — sonst waere ein Kochdienst am 25. Dezember erlaubt.
 */
export function closedDatesFrom(periods: ClosurePeriod[], holidays: Holiday[]): Set<string> {
  const closed = new Set<string>();

  for (const period of periods) {
    for (
      let cursor = new Date(`${period.from}T00:00:00`);
      toIsoDate(cursor) <= period.to;
      cursor.setDate(cursor.getDate() + 1)
    ) {
      closed.add(toIsoDate(cursor));
    }
  }

  for (const holiday of holidays) {
    closed.add(holiday.date);
  }

  return closed;
}
```

- [ ] **Step 4: Extend the dialog**

In `cooking-duty-dialog.component.ts` das Interface erweitern:

```ts
export interface CookingDutyDialogData {
  groups: FieldDefinition[];
  foodProperties: FieldDefinition[];
  familyParents: PersonDTO[];
  currentUserId: string;
  existingDuty?: CookingDutyDTO;
  canEdit: boolean;
  /**
   * ISO-Tage, an denen geschlossen ist — im Datepicker gesperrt.
   * Optional, damit vorhandene Aufrufstellen und Specs nicht brechen.
   */
  closedDates?: string[];
}
```

Import ergänzen:

```ts
import { toIsoDate } from './cooking-closure.util';
```

und in der Klasse:

```ts
  private closedDates = new Set<string>();

  /**
   * Als Property gebunden, damit `this` im Datepicker-Filter erhalten bleibt.
   * null ist zulaessig, sonst liesse sich das Feld nicht leeren.
   */
  dateFilter = (date: Date | null): boolean =>
    date === null || !this.closedDates.has(toIsoDate(date));
```

Im Konstruktor nach den vorhandenen Zuweisungen:

```ts
    this.closedDates = new Set(data.closedDates ?? []);
```

In `cooking-duty-dialog.component.html` das Datumsfeld (Zeile 7) erweitern:

```html
      <input matInput [matDatepicker]="picker" formControlName="date"
             [matDatepickerFilter]="dateFilter">
```

- [ ] **Step 5: Extend the cooking calendar**

In `cooking.component.ts` die Imports ergänzen:

```ts
import { CalendarMonthViewBeforeRenderEvent } from 'angular-calendar';
import { ClosurePeriodService } from '../shared/services/closure-period.service';
import { HolidayService } from '../shared/services/holiday.service';
import { closedDatesFrom, toIsoDate } from './cooking-closure.util';
```

`ClosurePeriodService` und `HolidayService` in den Konstruktor aufnehmen, ein Feld ergänzen:

```ts
  closedDates = new Set<string>();
```

Beim Laden des Monats — dort, wo `loadDuties()` die Dienste holt — zusätzlich die Schließtage für denselben Monat laden:

```ts
  private loadClosedDates(): void {
    const year = this.viewDate.getFullYear();
    const month = this.viewDate.getMonth();
    const from = toIsoDate(new Date(year, month, 1));
    const to = toIsoDate(new Date(year, month + 1, 0));

    forkJoin({
      periods: this.closurePeriodService.getRange(from, to),
      holidays: this.holidayService.getRange(from, to),
    }).subscribe(result => {
      this.closedDates = closedDatesFrom(result.periods, result.holidays);
      this.refresh.next();
    });
  }
```

`loadClosedDates()` in `loadDuties()` mit aufrufen, damit jeder Monatswechsel beides nachzieht. `forkJoin` aus `rxjs` importieren.

Die Einfärbung läuft über den `beforeViewRender`-Hook von `angular-calendar`:

```ts
  beforeMonthViewRender(event: CalendarMonthViewBeforeRenderEvent): void {
    for (const day of event.body) {
      if (!this.closedDates.has(toIsoDate(day.date))) {
        continue;
      }
      day.cssClass = 'closure-day';
      // Bestehende Dienste an nachtraeglich eingetragenen Schliesstagen bleiben
      // erhalten und werden hier nur als Konflikt markiert.
      if (day.events.length > 0) {
        day.cssClass = 'closure-day closure-conflict';
      }
    }
  }
```

An **jeder** Stelle in `cooking.component.ts`, an der ein `CookingDutyDialogData`-Objekt für `dialog.open(CookingDutyDialogComponent, { data })` gebaut wird, das Feld ergänzen — mit `grep -n "CookingDutyDialogData\|dialog.open" cooking.component.ts` alle Vorkommen finden:

```ts
      closedDates: [...this.closedDates],
```

In `cooking.component.html` den Kalender erweitern:

```html
    <mwl-calendar-month-view
      [viewDate]="viewDate"
      [events]="events"
      [excludeDays]="excludeDays"
      [refresh]="refresh"
      (eventClicked)="onEventClicked($event.event)"
      (beforeViewRender)="beforeMonthViewRender($event)"
      [locale]="'de'"
      [weekStartsOn]="1">
    </mwl-calendar-month-view>
```

In `cooking.component.scss` ergänzen:

```scss
::ng-deep .cal-month-view .cal-day-cell.closure-day {
  background-color: rgba(217, 79, 79, 0.14);
}

::ng-deep .cal-month-view .cal-day-cell.closure-conflict {
  box-shadow: inset 0 0 0 2px #d32f2f;
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/cooking*.spec.ts'`
Expected: `Executed 6 of 6 SUCCESS` für `cooking-closure.spec.ts`, keine neuen Fehlschläge in den vorhandenen Cooking-Specs.

- [ ] **Step 7: Run the full suites and build**

Run: `cd backend && ./mvnw test`
Expected: keine neuen Fehlschläge gegenüber dem Vorbestand (13 vorbekannte).

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: keine neuen Fehlschläge gegenüber dem Vorbestand (1 vorbekannter).

Run: `cd frontend && npm run build`
Expected: erfolgreicher Produktionsbuild.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/cooking/
git commit -m "feat(fe): Kochdienst sperrt Schliesstage im Datepicker und markiert Konflikte"
```

---

## Selbstprüfung des Plans

**Spec-Abdeckung**

| Spec-Abschnitt | Task |
| --- | --- |
| Datenmodell, beide Collections | 3 |
| Normalisierung, Zuweisen und Merge | 1 |
| Normalisierung, Entfernen und Split | 2 |
| Wochenend-Regel beim Merge | 1 (Regel), 2 (Randbereinigung) |
| Backend-API Definitionen | 3 |
| Backend-API Zeiträume | 5 |
| Backend-API Feiertage | 4 |
| Änderung einer Definition, Kopie-Flow | 3 (Server), 11 (Dialog) |
| Feiertage, Konfiguration und Verhalten | 4 |
| Kalender-Komponente, Darstellung | 8 |
| Kalender-Komponente, Auswahl | 9 |
| Admin-Maske | 10, 11 |
| Elternansicht | 12 |
| Kochdienst-Sperre serverseitig | 6 |
| Kochdienst-Darstellung und Datepicker | 13 |

**Nicht im Plan, weil laut Spec außerhalb des Umfangs:** Auswirkungen auf die Stundenberechnung, automatisches Umbuchen kollidierender Kochdienste, Erfassung von Tagen außerhalb aller Semester, Benachrichtigung der Eltern.

**Bekannte Abweichung von der Spec:** Task 3 lässt `PUT` auch bei verknüpften Zeiträumen zu, sofern ausschließlich das `active`-Flag kippt. Ohne diese Ausnahme ließe sich eine verknüpfte, deaktivierte Definition nie wieder reaktivieren.
