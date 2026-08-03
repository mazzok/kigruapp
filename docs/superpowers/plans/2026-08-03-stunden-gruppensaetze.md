# Gruppenabhängige Stundensätze, Soll-Aufschlüsselung und Ring-Farbverlauf — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die zu leistenden Stunden lassen sich je Gruppe konfigurieren (Staffelung bleibt global, jetzt in Prozent), die Seite „Unsere Stunden" zeigt die vollständige Herleitung des Familien-Solls, und der Stunden-Ring färbt sich in fünf Stufen nach dem Erfüllungsgrad bis heute.

**Architecture:** Das Soll wird im Backend monatsweise gerechnet (`HoursBalanceService`), weil Aliquotierung und Rabatt-Rang im Semesterverlauf wechseln; die Aufteilung je Kind und Monat wandert als Datenstruktur in die `/hours/our`-Antwort, das Frontend formatiert nur. Die Seite „Unsere Stunden" wird aus einer Datei in Übersicht, Eintragstabelle und Bearbeitungsdialog zerlegt. Die Ringfarbe wird zu einer reinen Funktion der Kennzahl `fulfillmentPercent`.

**Tech Stack:** Quarkus 3 mit MongoDB (Panache, `MongoClient`), JUnit 5 / RestAssured; Angular 18 standalone components, RxJS `BehaviorSubject`, Angular Material, Jasmine/Karma, SCSS.

Spec: `docs/superpowers/specs/2026-08-03-stunden-gruppensaetze-design.md`

## Global Constraints

- Backend-Verzeichnis: `backend/`. Testbefehl: `cd backend && ./mvnw test -Dtest=<TestKlasse>`; voller Lauf `cd backend && ./mvnw test`.
- Frontend-Verzeichnis: `frontend/`. Testbefehl: `npm test -- --watch=false --browsers=ChromeHeadless` (aus `frontend/`), einzeln mit `--include=**/<datei>.spec.ts`.
- Der Branch ist `feature/stunden-ring-header`. Jede Task endet mit einem eigenen Commit auf diesem Branch.
- Auf `main` bestehen 13 vorher schon rote Backend-Tests und 1 roter Frontend-Test. Neue Rotfärbung nur an den in der jeweiligen Task genannten Tests bewerten.
- Alle Benutzertexte auf Deutsch. Zeiten über `formatMinutes()` aus `frontend/src/app/shared/util/time-format.util.ts` im Format `HH:MM`.
- Kommentare im Code auf Deutsch, sparsam, nur wo die Absicht nicht aus dem Code folgt (Hausstil).
- Keine neuen Abhängigkeiten, weder Maven noch npm.
- Angular: standalone Komponenten mit explizitem `imports`-Array, Zustand über `BehaviorSubject`, keine Signals.
- Prozentwerte sind ganzzahlig (`int` / `number`), Minuten sind ganzzahlig, Rundung immer kaufmännisch (`RoundingMode.HALF_UP` bzw. `Math.round`).
- Reihenfolge-Konstanten heißen exakt `MOST_EXPENSIVE_FIRST` und `LEAST_EXPENSIVE_FIRST`.
- Ring-Farbstufen heißen exakt `level1` … `level5`.

---

### Task 1: `GroupCatalogService` — Gruppen mit Label und Farbe auflisten

Gruppen sind `field_instances` der aktiven `FieldDefinition` mit `fieldName = "group"`. Ihr `value` ist ein Dokument `{ label, color }`. Sowohl die Validierung der Gruppensätze als auch die Aufschlüsselung auf „Unsere Stunden" brauchen diese Liste, deshalb bekommt sie einen eigenen Service.

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/GroupCatalogService.java`
- Test: `backend/src/test/java/at/kigruapp/service/GroupCatalogServiceTest.java`

**Interfaces:**
- Consumes: `MongoClient`, Collection `field_definitions` (Felder `fieldName`, `outdatedAt`), Collection `field_instances` (Felder `definitionId`, `value.label`, `value.color`).
- Produces:
  - `record GroupInfo(ObjectId id, String label, String color)`
  - `List<GroupInfo> listGroups()` — alle Gruppen, sortiert nach Label
  - `Map<ObjectId, GroupInfo> byId()` — dieselbe Liste als Map

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/service/GroupCatalogServiceTest.java`:

```java
package at.kigruapp.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class GroupCatalogServiceTest {

    @Inject
    GroupCatalogService service;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private ObjectId definitionId;

    @BeforeEach
    void seed() {
        var db = mongoClient.getDatabase(databaseName);
        db.getCollection("field_instances").deleteMany(new Document());
        db.getCollection("field_definitions").deleteMany(new Document("fieldName", "group"));

        definitionId = new ObjectId();
        db.getCollection("field_definitions").insertOne(new Document("_id", definitionId)
                .append("fieldName", "group")
                .append("label", new Document("de", "Gruppen"))
                .append("outdatedAt", null));

        db.getCollection("field_instances").insertOne(new Document("_id", new ObjectId())
                .append("definitionId", definitionId)
                .append("value", new Document("label", "Käfergruppe").append("color", "#43a047")));
        db.getCollection("field_instances").insertOne(new Document("_id", new ObjectId())
                .append("definitionId", definitionId)
                .append("value", new Document("label", "Bärengruppe").append("color", "#fb8c00")));
    }

    @Test
    void listsGroupsSortedByLabel() {
        List<GroupCatalogService.GroupInfo> groups = service.listGroups();
        assertEquals(2, groups.size());
        assertEquals("Bärengruppe", groups.get(0).label());
        assertEquals("#fb8c00", groups.get(0).color());
        assertEquals("Käfergruppe", groups.get(1).label());
    }

    @Test
    void byIdContainsEveryGroup() {
        Map<ObjectId, GroupCatalogService.GroupInfo> byId = service.byId();
        assertEquals(2, byId.size());
        for (GroupCatalogService.GroupInfo info : service.listGroups()) {
            assertEquals(info.label(), byId.get(info.id()).label());
        }
    }

    @Test
    void ignoresInstancesOfOutdatedDefinitions() {
        var db = mongoClient.getDatabase(databaseName);
        ObjectId outdated = new ObjectId();
        db.getCollection("field_definitions").insertOne(new Document("_id", outdated)
                .append("fieldName", "group")
                .append("outdatedAt", new java.util.Date()));
        db.getCollection("field_instances").insertOne(new Document("_id", new ObjectId())
                .append("definitionId", outdated)
                .append("value", new Document("label", "Alte Gruppe").append("color", "#000000")));

        assertEquals(2, service.listGroups().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=GroupCatalogServiceTest`
Expected: FAIL — Kompilierfehler, `GroupCatalogService` existiert nicht.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/at/kigruapp/service/GroupCatalogService.java`:

```java
package at.kigruapp.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Gruppen der Organisation als flache Liste: field_instances der aktiven
 * "group"-Definition, mit Anzeigename und Farbe aus dem value-Dokument.
 */
@ApplicationScoped
public class GroupCatalogService {

    public record GroupInfo(ObjectId id, String label, String color) {}

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public List<GroupInfo> listGroups() {
        var db = mongoClient.getDatabase(databaseName);

        List<ObjectId> definitionIds = new ArrayList<>();
        for (Document def : db.getCollection("field_definitions")
                .find(Filters.and(Filters.eq("fieldName", "group"),
                        Filters.eq("outdatedAt", null)))) {
            definitionIds.add(def.getObjectId("_id"));
        }
        if (definitionIds.isEmpty()) {
            return List.of();
        }

        List<GroupInfo> groups = new ArrayList<>();
        for (Document instance : db.getCollection("field_instances")
                .find(Filters.in("definitionId", definitionIds))) {
            Object value = instance.get("value");
            String label = null;
            String color = null;
            if (value instanceof Document valueDoc) {
                label = valueDoc.getString("label");
                color = valueDoc.getString("color");
            } else if (value instanceof String stringValue) {
                label = stringValue;
            }
            ObjectId id = instance.getObjectId("_id");
            groups.add(new GroupInfo(id, label == null || label.isBlank()
                    ? id.toHexString() : label, color));
        }
        groups.sort(Comparator.comparing(GroupInfo::label));
        return groups;
    }

    public Map<ObjectId, GroupInfo> byId() {
        Map<ObjectId, GroupInfo> map = new LinkedHashMap<>();
        for (GroupInfo info : listGroups()) {
            map.put(info.id(), info);
        }
        return map;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=GroupCatalogServiceTest`
Expected: PASS (3 Tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/GroupCatalogService.java backend/src/test/java/at/kigruapp/service/GroupCatalogServiceTest.java
git commit -m "feat(be): GroupCatalogService liefert Gruppen mit Label und Farbe"
```

---

### Task 2: `RequiredHours` erweitern und bestehende Staffeln migrieren

Die Entität bekommt `allGroups`, `groupRates`, `order`; `Tier.minutesPerMonth` wird zu `Tier.percent`. Eine Startup-Migration rechnet bestehende Dokumente um. Nach dieser Task kompiliert der bisherige Code nicht mehr — die Anpassung der Aufrufer erfolgt in Task 3 bis 6; deshalb werden die betroffenen Stellen hier bereits mechanisch mitgezogen (siehe Step 5).

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/RequiredHours.java`
- Create: `backend/src/main/java/at/kigruapp/migration/RequiredHoursPercentTiersMigration.java`
- Test: `backend/src/test/java/at/kigruapp/migration/RequiredHoursPercentTiersMigrationTest.java`
- Modify (nur damit es kompiliert): `backend/src/main/java/at/kigruapp/service/HoursBalanceService.java`, `backend/src/main/java/at/kigruapp/resource/RequiredHoursResource.java`, `backend/src/test/java/at/kigruapp/service/HoursBalanceServiceTest.java`

**Interfaces:**
- Consumes: `GroupCatalogService` (nicht in dieser Task, nur ab Task 3).
- Produces:
  - `RequiredHours.allGroups: boolean` (Default `true`)
  - `RequiredHours.order: String` (Default `"MOST_EXPENSIVE_FIRST"`)
  - `RequiredHours.groupRates: List<GroupRate>` mit `GroupRate { ObjectId groupInstanceId; int minutesPerMonth; }`
  - `RequiredHours.Tier { int fromChild; int percent; }`
  - `RequiredHours.MOST_EXPENSIVE_FIRST` / `RequiredHours.LEAST_EXPENSIVE_FIRST` als `public static final String`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/migration/RequiredHoursPercentTiersMigrationTest.java`:

```java
package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RequiredHoursPercentTiersMigrationTest {

    @Inject
    MongoClient mongoClient;

    @Inject
    RequiredHoursPercentTiersMigration migration;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private Document runOn(Document requiredHours) {
        var db = mongoClient.getDatabase(databaseName);
        db.getCollection("migrations").deleteOne(new Document("_id", RequiredHoursPercentTiersMigration.MIGRATION_ID));
        db.getCollection("requiredHours").deleteMany(new Document());
        ObjectId id = new ObjectId();
        db.getCollection("requiredHours").insertOne(requiredHours.append("_id", id));

        migration.migrate();

        return db.getCollection("requiredHours").find(new Document("_id", id)).first();
    }

    @Test
    void convertsAbsoluteTiersToPercent() {
        Document after = runOn(new Document("semesterId", new ObjectId())
                .append("defaultMinutesPerMonth", 480)
                .append("tiers", List.of(
                        new Document("fromChild", 2).append("minutesPerMonth", 360),
                        new Document("fromChild", 3).append("minutesPerMonth", 0))));

        List<Document> tiers = after.getList("tiers", Document.class);
        assertEquals(25, tiers.get(0).getInteger("percent"));   // 360 von 480 -> 25 % Rabatt
        assertEquals(100, tiers.get(1).getInteger("percent"));  // 0 von 480 -> 100 % Rabatt
        assertNull(tiers.get(0).get("minutesPerMonth"));
    }

    @Test
    void setsNewFieldsToDefaults() {
        Document after = runOn(new Document("semesterId", new ObjectId())
                .append("defaultMinutesPerMonth", 480)
                .append("tiers", List.of()));

        assertTrue(after.getBoolean("allGroups"));
        assertEquals("MOST_EXPENSIVE_FIRST", after.getString("order"));
        assertTrue(after.getList("groupRates", Document.class).isEmpty());
    }

    @Test
    void zeroDefaultYieldsZeroPercent() {
        Document after = runOn(new Document("semesterId", new ObjectId())
                .append("defaultMinutesPerMonth", 0)
                .append("tiers", List.of(new Document("fromChild", 2).append("minutesPerMonth", 120))));

        assertEquals(0, after.getList("tiers", Document.class).get(0).getInteger("percent"));
    }

    @Test
    void tierAboveDefaultIsClampedToZeroPercent() {
        Document after = runOn(new Document("semesterId", new ObjectId())
                .append("defaultMinutesPerMonth", 480)
                .append("tiers", List.of(new Document("fromChild", 2).append("minutesPerMonth", 600))));

        assertEquals(0, after.getList("tiers", Document.class).get(0).getInteger("percent"));
    }

    @Test
    void runsOnlyOnce() {
        var db = mongoClient.getDatabase(databaseName);
        Document after = runOn(new Document("semesterId", new ObjectId())
                .append("defaultMinutesPerMonth", 480)
                .append("tiers", List.of(new Document("fromChild", 2).append("minutesPerMonth", 360))));
        assertEquals(25, after.getList("tiers", Document.class).get(0).getInteger("percent"));

        // Zweiter Lauf darf die bereits umgerechneten Werte nicht erneut anfassen.
        migration.migrate();
        Document again = db.getCollection("requiredHours").find(new Document("_id", after.getObjectId("_id"))).first();
        assertEquals(25, again.getList("tiers", Document.class).get(0).getInteger("percent"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=RequiredHoursPercentTiersMigrationTest`
Expected: FAIL — Kompilierfehler, `RequiredHoursPercentTiersMigration` existiert nicht.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/at/kigruapp/entity/RequiredHours.java` vollständig ersetzen:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "requiredHours")
public class RequiredHours extends PanacheMongoEntity {

    public static final String MOST_EXPENSIVE_FIRST = "MOST_EXPENSIVE_FIRST";
    public static final String LEAST_EXPENSIVE_FIRST = "LEAST_EXPENSIVE_FIRST";

    public ObjectId semesterId;
    /** Satz je Kind und Monat; gilt, wenn allGroups true ist. */
    public int defaultMinutesPerMonth;
    /** true: ein Satz für alle Gruppen. false: Satz je Gruppe aus groupRates. */
    public boolean allGroups = true;
    /** Reihenfolge für den Geschwisterrabatt. */
    public String order = MOST_EXPENSIVE_FIRST;
    public List<GroupRate> groupRates = new ArrayList<>();
    /** Geschwisterrabatt in Prozent, gilt gruppenübergreifend. */
    public List<Tier> tiers = new ArrayList<>();

    public static class GroupRate {
        public ObjectId groupInstanceId;
        public int minutesPerMonth;
    }

    public static class Tier {
        public int fromChild;
        public int percent;
    }

    public static RequiredHours findBySemesterId(ObjectId semesterId) {
        return find("semesterId", semesterId).firstResult();
    }
}
```

`backend/src/main/java/at/kigruapp/migration/RequiredHoursPercentTiersMigration.java`:

```java
package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Staffeln der zu leistenden Stunden werden von absoluten Minuten auf einen
 * Prozent-Rabatt umgestellt, damit sie auch bei gruppenabhängigen Sätzen gelten.
 */
@ApplicationScoped
@Startup
public class RequiredHoursPercentTiersMigration {

    public static final String MIGRATION_ID = "required-hours-percent-tiers-v1";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        migrate();
    }

    public void migrate() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> migrations = db.getCollection("migrations");
        if (migrations.find(new Document("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        MongoCollection<Document> collection = db.getCollection("requiredHours");
        for (Document doc : collection.find()) {
            int defaultMinutes = doc.getInteger("defaultMinutesPerMonth", 0);
            List<Document> tiers = doc.getList("tiers", Document.class);
            List<Document> converted = new ArrayList<>();
            if (tiers != null) {
                for (Document tier : tiers) {
                    converted.add(new Document("fromChild", tier.getInteger("fromChild", 0))
                            .append("percent", percentFor(defaultMinutes, tier)));
                }
            }
            collection.updateOne(new Document("_id", doc.getObjectId("_id")),
                    new Document("$set", new Document("tiers", converted)
                            .append("allGroups", true)
                            .append("order", "MOST_EXPENSIVE_FIRST")
                            .append("groupRates", List.of())));
        }

        migrations.insertOne(new Document("_id", MIGRATION_ID)
                .append("executedAt", Date.from(Instant.now())));
    }

    /** Rabatt in Prozent: 100 − 100 × minutes / default, auf 0..100 geklemmt. */
    private int percentFor(int defaultMinutes, Document tier) {
        if (tier.get("percent") != null) {
            return tier.getInteger("percent", 0);   // bereits umgerechnet
        }
        int minutes = tier.getInteger("minutesPerMonth", 0);
        if (defaultMinutes <= 0) {
            return 0;
        }
        int percent = BigDecimal.valueOf(100L * minutes)
                .divide(BigDecimal.valueOf(defaultMinutes), 0, RoundingMode.HALF_UP)
                .intValue();
        int discount = 100 - percent;
        return Math.max(0, Math.min(100, discount));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=RequiredHoursPercentTiersMigrationTest`
Expected: PASS (5 Tests). Falls stattdessen Kompilierfehler in `HoursBalanceService`, `RequiredHoursResource` oder `HoursBalanceServiceTest` auftreten, weiter mit Step 5 und dann erneut laufen lassen.

- [ ] **Step 5: Bestehende Aufrufer kompilierfähig halten**

`HoursBalanceService.rateForChild` liest bisher `t.minutesPerMonth`. Bis Task 4 die Methode ersetzt, wird sie auf Prozent umgestellt:

```java
    /** Minutes/month owed for the n-th child (1-based), Rabatt aus der höchsten passenden Staffel. */
    public int rateForChild(RequiredHours cfg, int childOrdinal) {
        if (cfg == null) {
            return 0;
        }
        int percent = 0;
        if (cfg.tiers != null) {
            int bestFrom = 0;
            for (RequiredHours.Tier t : cfg.tiers) {
                if (t.fromChild <= childOrdinal && t.fromChild >= bestFrom) {
                    bestFrom = t.fromChild;
                    percent = t.percent;
                }
            }
        }
        return Math.round(cfg.defaultMinutesPerMonth * (100 - percent) / 100f);
    }
```

In `RequiredHoursResource` (Zeilen um 36–41, 66–68 und 80–84) `minutesPerMonth` durch `percent` ersetzen — die endgültige Fassung entsteht in Task 3, hier reicht ein mechanischer Austausch der Feldnamen inklusive `RequiredHoursDto.TierDto.percent` (Feld in `backend/src/main/java/at/kigruapp/dto/RequiredHoursDto.java` umbenennen).

In `HoursBalanceServiceTest` die Hilfsmethode `tier(int fromChild, int minutes)` auf Prozent umstellen und die betroffenen Erwartungswerte anpassen:

```java
    private RequiredHours.Tier tier(int fromChild, int percent) {
        RequiredHours.Tier t = new RequiredHours.Tier();
        t.fromChild = fromChild;
        t.percent = percent;
        return t;
    }
```

`tier(2, 360)` bei Default 480 wird zu `tier(2, 25)`, `tier(3, 0)` zu `tier(3, 100)`. Die Erwartungswerte der bestehenden Tests bleiben dadurch identisch.

- [ ] **Step 6: Gesamten Backend-Testlauf prüfen**

Run: `cd backend && ./mvnw test -Dtest=RequiredHoursPercentTiersMigrationTest+HoursBalanceServiceTest+RequiredHoursResourceTest`
Expected: PASS für `RequiredHoursPercentTiersMigrationTest` und `HoursBalanceServiceTest`. `RequiredHoursResourceTest` darf hier noch rot sein, wenn er Staffelwerte in Minuten prüft — er wird in Task 3 angepasst.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/RequiredHours.java backend/src/main/java/at/kigruapp/migration/RequiredHoursPercentTiersMigration.java backend/src/test/java/at/kigruapp/migration/RequiredHoursPercentTiersMigrationTest.java backend/src/main/java/at/kigruapp/service/HoursBalanceService.java backend/src/main/java/at/kigruapp/resource/RequiredHoursResource.java backend/src/main/java/at/kigruapp/dto/RequiredHoursDto.java backend/src/test/java/at/kigruapp/service/HoursBalanceServiceTest.java
git commit -m "feat(be): RequiredHours mit Gruppensaetzen, Prozent-Staffeln und Migration"
```

---

### Task 3: `RequiredHoursResource` — neue Felder speichern und validieren

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/dto/RequiredHoursDto.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/RequiredHoursResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/RequiredHoursResourceTest.java`

**Interfaces:**
- Consumes: `RequiredHours` (Task 2), `GroupCatalogService.listGroups()` (Task 1).
- Produces: JSON-Vertrag von `GET/PUT /api/v1/required-hours?semesterId=`:
  `{ semesterId, defaultMinutesPerMonth, allGroups, order, groupRates: [{ groupInstanceId, minutesPerMonth }], tiers: [{ fromChild, percent }] }`

- [ ] **Step 1: Write the failing test**

Bestehende Tests in `RequiredHoursResourceTest` auf `percent` umstellen und folgende Tests ergänzen (Paketnamen und Testaufbau des vorhandenen Tests beibehalten):

```java
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
```

Dazu die Hilfsmethode im selben Test (legt eine Gruppe direkt in Mongo an, `MongoClient` per `@Inject` wie in `GroupCatalogServiceTest`):

```java
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
```

In `@BeforeEach` des Tests zusätzlich `field_instances` leeren, damit die Gruppen je Test frisch sind.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=RequiredHoursResourceTest`
Expected: FAIL — `allGroups`, `order`, `groupRates` fehlen im DTO; Validierung greift nicht.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/at/kigruapp/dto/RequiredHoursDto.java`:

```java
package at.kigruapp.dto;

import java.util.ArrayList;
import java.util.List;

public class RequiredHoursDto {
    public String semesterId;
    public int defaultMinutesPerMonth;
    public boolean allGroups = true;
    public String order = "MOST_EXPENSIVE_FIRST";
    public List<GroupRateDto> groupRates = new ArrayList<>();
    public List<TierDto> tiers = new ArrayList<>();

    public static class GroupRateDto {
        public String groupInstanceId;
        public int minutesPerMonth;
    }

    public static class TierDto {
        public int fromChild;
        public int percent;
    }
}
```

`RequiredHoursResource`: `put` schreibt die neuen Felder, `validate` prüft sie, `toDto` gibt sie zurück.

```java
    @Inject
    at.kigruapp.service.GroupCatalogService groupCatalog;

    @PUT
    public RequiredHoursDto put(@QueryParam("semesterId") String semesterIdParam, RequiredHoursDto in) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        validate(in);

        RequiredHours cfg = RequiredHours.findBySemesterId(semesterId);
        if (cfg == null) {
            cfg = new RequiredHours();
            cfg.semesterId = semesterId;
        }
        cfg.defaultMinutesPerMonth = in.defaultMinutesPerMonth;
        cfg.allGroups = in.allGroups;
        cfg.order = RequiredHours.LEAST_EXPENSIVE_FIRST.equals(in.order)
                ? RequiredHours.LEAST_EXPENSIVE_FIRST : RequiredHours.MOST_EXPENSIVE_FIRST;
        // Beim Umschalten auf "für alle Gruppen" bleiben die Gruppenwerte erhalten,
        // damit ein versehentlicher Klick die Eingaben nicht vernichtet.
        if (!in.allGroups) {
            cfg.groupRates = new ArrayList<>();
            for (RequiredHoursDto.GroupRateDto g : in.groupRates) {
                RequiredHours.GroupRate rate = new RequiredHours.GroupRate();
                rate.groupInstanceId = new ObjectId(g.groupInstanceId);
                rate.minutesPerMonth = g.minutesPerMonth;
                cfg.groupRates.add(rate);
            }
        }
        cfg.tiers = new ArrayList<>();
        for (RequiredHoursDto.TierDto t : in.tiers) {
            RequiredHours.Tier tier = new RequiredHours.Tier();
            tier.fromChild = t.fromChild;
            tier.percent = t.percent;
            cfg.tiers.add(tier);
        }
        cfg.persistOrUpdate();
        return toDto(semesterId, cfg);
    }

    private void validate(RequiredHoursDto in) {
        if (in == null || in.defaultMinutesPerMonth <= 0) {
            throw new BadRequestException("defaultMinutesPerMonth muss größer als 0 sein");
        }
        List<RequiredHoursDto.TierDto> tiers = in.tiers == null ? List.of() : in.tiers;
        int prevFrom = 1; // erster gültiger Tier-Wert ist 2 -> strikt größer als 1
        for (RequiredHoursDto.TierDto t : tiers) {
            if (t.fromChild < 2) {
                throw new BadRequestException("fromChild muss mindestens 2 sein");
            }
            if (t.fromChild <= prevFrom) {
                throw new BadRequestException("fromChild muss eindeutig und aufsteigend sein");
            }
            if (t.percent < 0 || t.percent > 100) {
                throw new BadRequestException("percent muss zwischen 0 und 100 liegen");
            }
            prevFrom = t.fromChild;
        }
        if (!in.allGroups) {
            List<RequiredHoursDto.GroupRateDto> rates =
                    in.groupRates == null ? List.of() : in.groupRates;
            java.util.Map<String, Integer> byGroup = new java.util.HashMap<>();
            for (RequiredHoursDto.GroupRateDto g : rates) {
                if (g.groupInstanceId == null || !ObjectId.isValid(g.groupInstanceId)) {
                    throw new BadRequestException("groupInstanceId ungültig");
                }
                if (g.minutesPerMonth <= 0) {
                    throw new BadRequestException("Stunden je Gruppe müssen größer als 0 sein");
                }
                byGroup.put(g.groupInstanceId, g.minutesPerMonth);
            }
            for (at.kigruapp.service.GroupCatalogService.GroupInfo group : groupCatalog.listGroups()) {
                if (!byGroup.containsKey(group.id().toHexString())) {
                    throw new BadRequestException("Stunden fehlen für Gruppe " + group.label());
                }
            }
        }
    }

    private RequiredHoursDto toDto(ObjectId semesterId, RequiredHours cfg) {
        RequiredHoursDto dto = new RequiredHoursDto();
        dto.semesterId = semesterId.toHexString();
        dto.tiers = new ArrayList<>();
        dto.groupRates = new ArrayList<>();
        if (cfg != null) {
            dto.defaultMinutesPerMonth = cfg.defaultMinutesPerMonth;
            dto.allGroups = cfg.allGroups;
            dto.order = cfg.order == null ? RequiredHours.MOST_EXPENSIVE_FIRST : cfg.order;
            if (cfg.groupRates != null) {
                for (RequiredHours.GroupRate g : cfg.groupRates) {
                    RequiredHoursDto.GroupRateDto gd = new RequiredHoursDto.GroupRateDto();
                    gd.groupInstanceId = g.groupInstanceId == null ? null : g.groupInstanceId.toHexString();
                    gd.minutesPerMonth = g.minutesPerMonth;
                    dto.groupRates.add(gd);
                }
            }
            if (cfg.tiers != null) {
                for (RequiredHours.Tier t : cfg.tiers) {
                    RequiredHoursDto.TierDto td = new RequiredHoursDto.TierDto();
                    td.fromChild = t.fromChild;
                    td.percent = t.percent;
                    dto.tiers.add(td);
                }
            }
        }
        return dto;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=RequiredHoursResourceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/RequiredHoursDto.java backend/src/main/java/at/kigruapp/resource/RequiredHoursResource.java backend/src/test/java/at/kigruapp/resource/RequiredHoursResourceTest.java
git commit -m "feat(be): Gruppensaetze und Prozent-Staffeln im required-hours-Endpunkt"
```

---

### Task 4: `HoursBalanceService` — monatsweise Berechnung mit Gruppensatz, Rang und Rabatt

Kern der Änderung. Die Aufteilung je Kind und Monat wird zur zentralen Rechengröße; Monats- und Semestersummen leiten sich daraus ab.

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/HoursBalanceService.java`
- Test: `backend/src/test/java/at/kigruapp/service/HoursBalanceServiceTest.java`

**Interfaces:**
- Consumes: `RequiredHours` (Task 2), `AliquotService.monthFraction(AliquotMode, String entryDate, String exitDate, int year, int month): BigDecimal`, `Semester` mit `start`/`end` als `Instant`.
- Produces:
  - `ChildPlacement` zusätzlich mit `public ObjectId groupInstanceId;`
  - `record ChildMonthShare(String childId, int minutes, int fractionPercent, int discountPercent)`
  - `int baseRate(RequiredHours cfg, ObjectId groupInstanceId)`
  - `int discountPercentForRank(RequiredHours cfg, int rank)`
  - `Map<String, List<ChildMonthShare>> familySharesByMonth(RequiredHours cfg, AliquotMode mode, Semester semester, List<ChildPlacement> placements)`
  - `Map<String, Integer> familySollByMonth(...)` — Signatur unverändert, Summen aus `familySharesByMonth`
  - `int familySollMinutes(...)` — Signatur unverändert
  - `int fullMonthMinutes(RequiredHours cfg, AliquotMode mode, Semester semester, List<ChildPlacement> placements)`
  - entfällt: `rateForChild(RequiredHours, int)`, `familyMonthlyMinutes(RequiredHours, int)`

- [ ] **Step 1: Write the failing test**

In `HoursBalanceServiceTest` die Hilfsmethoden erweitern und die neuen Tests ergänzen. Die bestehenden Tests, die `familyMonthlyMinutes(cfg, n)` aufrufen, werden auf `fullMonthMinutes` bzw. `familySollByMonth` umgeschrieben — der Rechenweg über eine reine Kinderzahl entfällt.

```java
    private RequiredHours cfgAll(int def, RequiredHours.Tier... tiers) {
        RequiredHours c = new RequiredHours();
        c.defaultMinutesPerMonth = def;
        c.allGroups = true;
        c.order = RequiredHours.MOST_EXPENSIVE_FIRST;
        c.tiers = new java.util.ArrayList<>(List.of(tiers));
        return c;
    }

    private RequiredHours cfgPerGroup(String order, java.util.Map<ObjectId, Integer> rates,
                                      RequiredHours.Tier... tiers) {
        RequiredHours c = new RequiredHours();
        c.defaultMinutesPerMonth = 0;
        c.allGroups = false;
        c.order = order;
        c.groupRates = new java.util.ArrayList<>();
        rates.forEach((groupId, minutes) -> {
            RequiredHours.GroupRate r = new RequiredHours.GroupRate();
            r.groupInstanceId = groupId;
            r.minutesPerMonth = minutes;
            c.groupRates.add(r);
        });
        c.tiers = new java.util.ArrayList<>(List.of(tiers));
        return c;
    }

    private HoursBalanceService.ChildPlacement placement(String childId, ObjectId groupId,
                                                         String entryDate, String exitDate) {
        HoursBalanceService.ChildPlacement p = new HoursBalanceService.ChildPlacement();
        p.childId = childId;
        p.groupInstanceId = groupId;
        p.entryDate = entryDate;
        p.exitDate = exitDate;
        return p;
    }

    private Semester semester(String startIso, String endIso) {
        Semester s = new Semester();
        s.start = Instant.parse(startIso);
        s.end = Instant.parse(endIso);
        return s;
    }

    @Test
    void perGroupRates_mostExpensiveFirst() {
        ObjectId kaefer = new ObjectId();
        ObjectId baeren = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480, baeren, 300), tier(2, 25));
        Semester s = semester("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z");

        java.util.Map<String, Integer> byMonth = service.familySollByMonth(c, AliquotMode.NONE, s,
                List.of(placement("a", kaefer, null, null), placement("b", baeren, null, null)));

        // Käfer 480 voll (Rang 1), Bären 300 minus 25 % = 225 -> 705
        assertEquals(705, byMonth.get("2026-09"));
    }

    @Test
    void perGroupRates_leastExpensiveFirst() {
        ObjectId kaefer = new ObjectId();
        ObjectId baeren = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.LEAST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480, baeren, 300), tier(2, 25));
        Semester s = semester("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z");

        java.util.Map<String, Integer> byMonth = service.familySollByMonth(c, AliquotMode.NONE, s,
                List.of(placement("a", kaefer, null, null), placement("b", baeren, null, null)));

        // Bären 300 voll (Rang 1), Käfer 480 minus 25 % = 360 -> 660
        assertEquals(660, byMonth.get("2026-09"));
    }

    @Test
    void childWithoutConfiguredGroupRateOwesNothing() {
        ObjectId kaefer = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480));
        Semester s = semester("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z");

        java.util.Map<String, Integer> byMonth = service.familySollByMonth(c, AliquotMode.NONE, s,
                List.of(placement("a", kaefer, null, null), placement("b", new ObjectId(), null, null)));

        assertEquals(480, byMonth.get("2026-09"));
    }

    @Test
    void allGroupsUsesDefaultRateForEveryGroup() {
        RequiredHours c = cfgAll(480, tier(2, 25));
        Semester s = semester("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z");

        java.util.Map<String, Integer> byMonth = service.familySollByMonth(c, AliquotMode.NONE, s,
                List.of(placement("a", new ObjectId(), null, null),
                        placement("b", new ObjectId(), null, null)));

        assertEquals(840, byMonth.get("2026-09"));   // 480 + 360
    }

    @Test
    void midSemesterEntry_changesRankAndFraction() {
        ObjectId kaefer = new ObjectId();
        ObjectId baeren = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480, baeren, 300), tier(2, 25));
        Semester s = semester("2026-10-01T00:00:00Z", "2026-11-30T00:00:00Z");

        java.util.Map<String, List<HoursBalanceService.ChildMonthShare>> shares =
                service.familySharesByMonth(c, AliquotMode.PER_DAY, s,
                        List.of(placement("a", kaefer, null, null),
                                placement("b", baeren, "2026-11-16", null)));

        // Oktober: nur Kind a, voller Satz, kein Rabatt
        assertEquals(1, shares.get("2026-10").size());
        assertEquals(480, shares.get("2026-10").get(0).minutes());
        assertEquals(0, shares.get("2026-10").get(0).discountPercent());

        // November: b ab 16.11. -> 15 von 30 Tagen = 50 %, Basis 150, Rang 2 -> 25 % Rabatt = 113
        List<HoursBalanceService.ChildMonthShare> november = shares.get("2026-11");
        HoursBalanceService.ChildMonthShare b = november.stream()
                .filter(x -> x.childId().equals("b")).findFirst().orElseThrow();
        assertEquals(50, b.fractionPercent());
        assertEquals(25, b.discountPercent());
        assertEquals(113, b.minutes());
    }

    @Test
    void exitEndsObligationAfterLastMonth() {
        ObjectId kaefer = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480));
        Semester s = semester("2026-09-01T00:00:00Z", "2026-11-30T00:00:00Z");

        java.util.Map<String, Integer> byMonth = service.familySollByMonth(c, AliquotMode.WHOLE_MONTH, s,
                List.of(placement("a", kaefer, null, "2026-10-15")));

        assertEquals(480, byMonth.get("2026-09"));
        assertEquals(480, byMonth.get("2026-10"));   // angefangener Monat zählt voll
        assertEquals(0, byMonth.get("2026-11"));
    }

    @Test
    void fullMonthMinutes_returnsMonthWithEveryChildPresent() {
        ObjectId kaefer = new ObjectId();
        ObjectId baeren = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480, baeren, 300), tier(2, 25));
        Semester s = semester("2026-10-01T00:00:00Z", "2026-12-31T00:00:00Z");

        int full = service.fullMonthMinutes(c, AliquotMode.PER_DAY, s,
                List.of(placement("a", kaefer, null, null),
                        placement("b", baeren, "2026-11-16", null)));

        assertEquals(705, full);   // Dezember: beide voll da
    }

    @Test
    void fullMonthMinutes_isZeroWhenNoMonthHasEveryChild() {
        ObjectId kaefer = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480));
        Semester s = semester("2026-10-01T00:00:00Z", "2026-10-31T00:00:00Z");

        int full = service.fullMonthMinutes(c, AliquotMode.PER_DAY, s,
                List.of(placement("a", kaefer, "2026-10-16", null)));

        assertEquals(0, full);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=HoursBalanceServiceTest`
Expected: FAIL — `groupInstanceId`, `ChildMonthShare`, `familySharesByMonth`, `fullMonthMinutes` fehlen.

- [ ] **Step 3: Write minimal implementation**

In `HoursBalanceService`: `ChildPlacement` erweitern, `rateForChild` und `familyMonthlyMinutes` löschen, `monthSoll` durch `monthShares` ersetzen.

```java
    public static class ChildPlacement {
        public String childId;
        public String entryDate;
        public String exitDate;
        public ObjectId groupInstanceId;
    }

    /** Anteil eines Kindes an einem Monat: Minuten samt Anwesenheits- und Rabattanteil. */
    public record ChildMonthShare(String childId, int minutes, int fractionPercent, int discountPercent) {}

    /** Satz je Monat für die Gruppe des Kindes; 0, wenn für die Gruppe nichts hinterlegt ist. */
    public int baseRate(RequiredHours cfg, ObjectId groupInstanceId) {
        if (cfg == null) {
            return 0;
        }
        if (cfg.allGroups) {
            return cfg.defaultMinutesPerMonth;
        }
        if (groupInstanceId == null || cfg.groupRates == null) {
            return 0;
        }
        int best = 0;
        for (RequiredHours.GroupRate rate : cfg.groupRates) {
            if (groupInstanceId.equals(rate.groupInstanceId)) {
                best = Math.max(best, rate.minutesPerMonth);
            }
        }
        return best;
    }

    /** Rabatt der höchsten passenden Staffel für einen 1-basierten Rang; 0 wenn keine passt. */
    public int discountPercentForRank(RequiredHours cfg, int rank) {
        if (cfg == null || cfg.tiers == null) {
            return 0;
        }
        int bestFrom = 0;
        int percent = 0;
        for (RequiredHours.Tier t : cfg.tiers) {
            if (t.fromChild <= rank && t.fromChild >= bestFrom) {
                bestFrom = t.fromChild;
                percent = t.percent;
            }
        }
        return percent;
    }

    /** Aufteilung je Monat ("YYYY-MM") auf die in diesem Monat anwesenden Kinder. */
    public java.util.Map<String, List<ChildMonthShare>> familySharesByMonth(
            RequiredHours cfg, AliquotMode mode, Semester semester, List<ChildPlacement> placements) {
        java.util.Map<String, List<ChildMonthShare>> out = new java.util.LinkedHashMap<>();
        if (semester == null || semester.start == null || semester.end == null) {
            return out;
        }
        YearMonth cur = YearMonth.from(semester.start.atZone(ZoneOffset.UTC));
        YearMonth last = YearMonth.from(semester.end.atZone(ZoneOffset.UTC));
        while (!cur.isAfter(last)) {
            String key = String.format("%04d-%02d", cur.getYear(), cur.getMonthValue());
            out.put(key, monthShares(cfg, mode, cur, placements));
            cur = cur.plusMonths(1);
        }
        return out;
    }

    public java.util.Map<String, Integer> familySollByMonth(RequiredHours cfg, AliquotMode mode,
            Semester semester, List<ChildPlacement> placements) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        familySharesByMonth(cfg, mode, semester, placements).forEach((month, shares) ->
                out.put(month, shares.stream().mapToInt(ChildMonthShare::minutes).sum()));
        return out;
    }

    public int familySollMinutes(RequiredHours cfg, AliquotMode mode, Semester semester,
                                 List<ChildPlacement> placements) {
        return familySollByMonth(cfg, mode, semester, placements).values().stream()
                .mapToInt(Integer::intValue).sum();
    }

    /**
     * Monatswert eines Monats, in dem alle Kinder voll anwesend sind — die Zahl, die als
     * "x h/Monat" angezeigt wird. 0, wenn es keinen solchen Monat gibt.
     */
    public int fullMonthMinutes(RequiredHours cfg, AliquotMode mode, Semester semester,
                                List<ChildPlacement> placements) {
        if (placements == null || placements.isEmpty()) {
            return 0;
        }
        for (List<ChildMonthShare> shares :
                familySharesByMonth(cfg, mode, semester, placements).values()) {
            if (shares.size() != placements.size()) {
                continue;
            }
            boolean allFull = shares.stream().allMatch(s -> s.fractionPercent() == 100);
            if (allFull) {
                return shares.stream().mapToInt(ChildMonthShare::minutes).sum();
            }
        }
        return 0;
    }

    /** Anteile eines einzelnen Monats: Basissatz × Anwesenheit, Rang nach cfg.order, dann Rabatt. */
    private List<ChildMonthShare> monthShares(RequiredHours cfg, AliquotMode mode, YearMonth ym,
                                              List<ChildPlacement> placements) {
        record Candidate(String childId, BigDecimal base, int fractionPercent) {}
        List<Candidate> present = new ArrayList<>();
        for (ChildPlacement p : placements) {
            BigDecimal fraction = aliquotService.monthFraction(
                    mode, p.entryDate, p.exitDate, ym.getYear(), ym.getMonthValue());
            if (fraction.signum() <= 0) {
                continue;
            }
            BigDecimal base = BigDecimal.valueOf(baseRate(cfg, p.groupInstanceId)).multiply(fraction);
            int fractionPercent = fraction.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP).intValue();
            present.add(new Candidate(p.childId, base, fractionPercent));
        }

        boolean leastFirst = RequiredHours.LEAST_EXPENSIVE_FIRST.equals(cfg == null ? null : cfg.order);
        Comparator<Candidate> byBase = Comparator.comparing(Candidate::base);
        present.sort((leastFirst ? byBase : byBase.reversed())
                .thenComparing(Candidate::childId));

        List<ChildMonthShare> shares = new ArrayList<>();
        for (int i = 0; i < present.size(); i++) {
            Candidate c = present.get(i);
            int discount = discountPercentForRank(cfg, i + 1);
            int minutes = c.base().multiply(BigDecimal.valueOf(100 - discount))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).intValue();
            shares.add(new ChildMonthShare(c.childId(), minutes, c.fractionPercent(), discount));
        }
        return shares;
    }
```

`monthsInSemester` und `countPlacedChildren` bleiben unverändert.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=HoursBalanceServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/HoursBalanceService.java backend/src/test/java/at/kigruapp/service/HoursBalanceServiceTest.java
git commit -m "feat(be): monatsweise Soll-Berechnung mit Gruppensatz, Rang und Prozent-Rabatt"
```

---

### Task 5: `/hours/our` und `/family-summary` auf die neue Rechnung umstellen

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/dto/OurHoursDto.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java` (`familyPlacements` ~Zeile 80, `familySummary` ~Zeile 183, `our` ~Zeile 257)
- Test: `backend/src/test/java/at/kigruapp/resource/HourEntryOurTest.java` (bestehende Klasse erweitern)

**Interfaces:**
- Consumes: `HoursBalanceService.familySharesByMonth`, `fullMonthMinutes`, `ChildMonthShare` (Task 4); `GroupCatalogService.byId()` (Task 1); `PersonPropertyResolver.resolve(List<Person>)`.
- Produces: JSON von `GET /api/v1/hour-entries/our?semesterId=` zusätzlich mit
  `allGroups: boolean`,
  `children: [{ childId, name, groupLabel, groupColor, baseMinutesPerMonth, entryDate, exitDate, sollMinutes }]`,
  `months[].children: [{ childId, minutes, fractionPercent, discountPercent }]`.

- [ ] **Step 1: Write the failing test**

`HourEntryOurTest` enthält bereits `persistSemester`, `persistFamily`, `persistPerson`, `persistConfig`, `persistAliquot` und `placeChild`. Diese Helfer werden erweitert und die Tests ergänzt:

```java
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
```

Die eingeloggte Testperson wird in `HourEntryOurTest` bereits über `persistPerson(familyId)` erzeugt — dieselbe Zuordnung wie in den vorhandenen Tests der Klasse beibehalten; der oben angelegte `parent` erfüllt diese Rolle.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=HourEntryOurTest`
Expected: FAIL — `children` und `months[].children` fehlen in der Antwort.

- [ ] **Step 3: Write minimal implementation**

`OurHoursDto` erweitern:

```java
public class OurHoursDto {
    public String familyId;
    /** Monatswert bei voller Anwesenheit aller Kinder; 0, wenn es keinen solchen Monat gibt. */
    public int familyMonthlyMinutes;
    public int monthsInSemester;
    public int sollMinutes;
    public int istMinutes;
    public boolean allGroups = true;
    public List<ChildSoll> children = new ArrayList<>();
    public List<MonthRow> months = new ArrayList<>();
    public List<Entry> entries = new ArrayList<>();

    public static class ChildSoll {
        public String childId;
        public String name;
        public String groupLabel;
        public String groupColor;
        public int baseMinutesPerMonth;
        public String entryDate;
        public String exitDate;
        public int sollMinutes;
    }

    public static class MonthRow {
        public String month;        // "YYYY-MM"
        public int sollMinutes;
        public int istMinutes;
        public List<ChildShare> children = new ArrayList<>();
    }

    public static class ChildShare {
        public String childId;
        public int minutes;
        public int fractionPercent;
        public int discountPercent;
    }

    public static class Entry {
        public String id;
        public String personId;
        public String personName;
        public String roleLabel;
        public String date;
        public int minutes;
        public String comment;
    }
}
```

`HourEntryResource.familyPlacements` liest die Gruppe mit:

```java
            HoursBalanceService.ChildPlacement pl = new HoursBalanceService.ChildPlacement();
            pl.childId = pid.toHexString();
            pl.entryDate = d.getString("entryDate");
            pl.exitDate = d.getString("exitDate");
            pl.groupInstanceId = d.getObjectId("fieldInstanceId");
            placements.add(pl);
```

In `familySummary` die entfallene Methode ersetzen:

```java
            List<HoursBalanceService.ChildPlacement> placements =
                    familyPlacements(members, semesterId);
            int familyMonthly = hoursBalanceService.fullMonthMinutes(cfg, mode, semester, placements);
            int soll = hoursBalanceService.familySollMinutes(cfg, mode, semester, placements);
```

(Die Zeile `int familyMonthly = hoursBalanceService.familyMonthlyMinutes(cfg, childCount);` entfällt; `childCount` bleibt für `fam.childCount` und die Ausblendlogik erhalten. `placements` wird dadurch vor `familyMonthly` ermittelt.)

In `our` analog und zusätzlich die Aufschlüsselung:

```java
        List<HoursBalanceService.ChildPlacement> placements = familyPlacements(members, semesterId);
        Map<String, List<HoursBalanceService.ChildMonthShare>> sharesByMonth =
                hoursBalanceService.familySharesByMonth(cfg, mode, semester, placements);
        Map<String, Integer> sollByMonth = new HashMap<>();
        sharesByMonth.forEach((month, shares) -> sollByMonth.put(month,
                shares.stream().mapToInt(HoursBalanceService.ChildMonthShare::minutes).sum()));

        dto.allGroups = cfg == null || cfg.allGroups;
        dto.familyMonthlyMinutes = hoursBalanceService.fullMonthMinutes(cfg, mode, semester, placements);
        dto.monthsInSemester = months;
        dto.sollMinutes = sollByMonth.values().stream().mapToInt(Integer::intValue).sum();
```

Nach dem Auflösen der Namen (`props`) die Kinderzeilen erzeugen:

```java
        Map<ObjectId, GroupCatalogService.GroupInfo> groups = groupCatalog.byId();
        Map<String, Integer> sollByChild = new HashMap<>();
        for (List<HoursBalanceService.ChildMonthShare> shares : sharesByMonth.values()) {
            for (HoursBalanceService.ChildMonthShare share : shares) {
                sollByChild.merge(share.childId(), share.minutes(), Integer::sum);
            }
        }
        for (HoursBalanceService.ChildPlacement placement : placements) {
            OurHoursDto.ChildSoll child = new OurHoursDto.ChildSoll();
            child.childId = placement.childId;
            Map<String, String> pr = props.getOrDefault(new ObjectId(placement.childId), Map.of());
            String childName = (pr.getOrDefault("firstName", "") + " " + pr.getOrDefault("lastName", "")).trim();
            child.name = childName.isEmpty() ? placement.childId : childName;
            GroupCatalogService.GroupInfo group = groups.get(placement.groupInstanceId);
            child.groupLabel = group == null ? null : group.label();
            child.groupColor = group == null ? null : group.color();
            child.baseMinutesPerMonth = hoursBalanceService.baseRate(cfg, placement.groupInstanceId);
            child.entryDate = placement.entryDate;
            child.exitDate = placement.exitDate;
            child.sollMinutes = sollByChild.getOrDefault(placement.childId, 0);
            dto.children.add(child);
        }
```

Und beim Aufbau der Monatszeilen die Anteile mitgeben:

```java
                row.sollMinutes = sollByMonth.getOrDefault(row.month, 0);
                row.istMinutes = istByMonth.getOrDefault(row.month, 0);
                for (HoursBalanceService.ChildMonthShare share :
                        sharesByMonth.getOrDefault(row.month, List.of())) {
                    OurHoursDto.ChildShare cs = new OurHoursDto.ChildShare();
                    cs.childId = share.childId();
                    cs.minutes = share.minutes();
                    cs.fractionPercent = share.fractionPercent();
                    cs.discountPercent = share.discountPercent();
                    row.children.add(cs);
                }
```

Dazu `@Inject GroupCatalogService groupCatalog;` in der Resource ergänzen.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=HourEntryOurTest+HourEntryFamilySummaryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/OurHoursDto.java backend/src/main/java/at/kigruapp/resource/HourEntryResource.java backend/src/test/java/at/kigruapp/resource/HourEntryOurTest.java
git commit -m "feat(be): /hours/our liefert Soll-Aufschlüsselung je Kind und Monat"
```

---

### Task 6: Semesterwechsel kopiert die neuen Felder

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/SemesterResource.java` (`copyConfig`, ~Zeile 62)
- Test: `backend/src/test/java/at/kigruapp/resource/SemesterResourceTest.java`

**Interfaces:**
- Consumes: `RequiredHours` mit `allGroups`, `order`, `groupRates` (Task 2).
- Produces: keine neuen Signaturen.

- [ ] **Step 1: Write the failing test**

In `SemesterResourceTest` ergänzen (falls die Klasse nicht existiert, mit `@QuarkusTest` neu anlegen und `MongoClient` injizieren):

```java
    @Test
    void copiesGroupRatesAndOrderToNewSemester() {
        org.bson.types.ObjectId previous = seedSemester("2026-09-01", "2027-04-30");
        org.bson.types.ObjectId groupId = new org.bson.types.ObjectId();

        at.kigruapp.entity.RequiredHours cfg = new at.kigruapp.entity.RequiredHours();
        cfg.semesterId = previous;
        cfg.defaultMinutesPerMonth = 480;
        cfg.allGroups = false;
        cfg.order = at.kigruapp.entity.RequiredHours.LEAST_EXPENSIVE_FIRST;
        at.kigruapp.entity.RequiredHours.GroupRate rate = new at.kigruapp.entity.RequiredHours.GroupRate();
        rate.groupInstanceId = groupId;
        rate.minutesPerMonth = 300;
        cfg.groupRates = new java.util.ArrayList<>(java.util.List.of(rate));
        cfg.persist();

        String created = given().contentType("application/json")
                .body("{\"start\":\"2027-09-01T00:00:00Z\",\"end\":\"2028-04-30T00:00:00Z\"}")
                .when().post("/api/v1/semesters")
                .then().statusCode(201).extract().jsonPath().getString("id");

        at.kigruapp.entity.RequiredHours copied = at.kigruapp.entity.RequiredHours
                .findBySemesterId(new org.bson.types.ObjectId(created));
        org.junit.jupiter.api.Assertions.assertFalse(copied.allGroups);
        org.junit.jupiter.api.Assertions.assertEquals("LEAST_EXPENSIVE_FIRST", copied.order);
        org.junit.jupiter.api.Assertions.assertEquals(1, copied.groupRates.size());
        org.junit.jupiter.api.Assertions.assertEquals(300, copied.groupRates.get(0).minutesPerMonth);
        org.junit.jupiter.api.Assertions.assertEquals(groupId, copied.groupRates.get(0).groupInstanceId);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SemesterResourceTest`
Expected: FAIL — `allGroups` ist im kopierten Datensatz `true`, `groupRates` leer.

- [ ] **Step 3: Write minimal implementation**

In `SemesterResource.copyConfig` den `RequiredHours`-Block ergänzen:

```java
        if (rh != null) {
            at.kigruapp.entity.RequiredHours c = new at.kigruapp.entity.RequiredHours();
            c.semesterId = to;
            c.defaultMinutesPerMonth = rh.defaultMinutesPerMonth;
            c.allGroups = rh.allGroups;
            c.order = rh.order;
            c.groupRates = new java.util.ArrayList<>(rh.groupRates == null ? java.util.List.of() : rh.groupRates);
            c.tiers = new java.util.ArrayList<>(rh.tiers == null ? java.util.List.of() : rh.tiers);
            c.persist();
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SemesterResourceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/SemesterResource.java backend/src/test/java/at/kigruapp/resource/SemesterResourceTest.java
git commit -m "feat(be): Semesterkopie uebernimmt Gruppensaetze und Reihenfolge"
```

---

### Task 7: Frontend-Modelle an den neuen Vertrag anpassen

**Files:**
- Modify: `frontend/src/app/shared/models/hour-entry.model.ts`
- Modify: `frontend/src/app/shared/models/required-hours.model.ts`

**Interfaces:**
- Consumes: JSON aus Task 3 und Task 5.
- Produces:
  - `interface OurHoursChild { childId: string; name: string; groupLabel: string | null; groupColor: string | null; baseMinutesPerMonth: number; entryDate: string | null; exitDate: string | null; sollMinutes: number; }`
  - `interface OurHoursChildShare { childId: string; minutes: number; fractionPercent: number; discountPercent: number; }`
  - `OurHoursMonthRow` zusätzlich mit `children: OurHoursChildShare[]`
  - `OurHours` zusätzlich mit `allGroups: boolean` und `children: OurHoursChild[]`
  - `RequiredHoursTier { fromChild: number; percent: number }`
  - `RequiredHoursGroupRate { groupInstanceId: string; minutesPerMonth: number }`
  - `RequiredHours` zusätzlich mit `allGroups: boolean`, `order: 'MOST_EXPENSIVE_FIRST' | 'LEAST_EXPENSIVE_FIRST'`, `groupRates: RequiredHoursGroupRate[]`

- [ ] **Step 1: Modelle ergänzen**

In `hour-entry.model.ts`:

```ts
export interface OurHoursChild {
  childId: string;
  name: string;
  groupLabel: string | null;
  groupColor: string | null;
  baseMinutesPerMonth: number;
  entryDate: string | null;
  exitDate: string | null;
  sollMinutes: number;
}

export interface OurHoursChildShare {
  childId: string;
  minutes: number;
  fractionPercent: number;
  discountPercent: number;
}

export interface OurHoursMonthRow {
  month: string;        // "YYYY-MM"
  sollMinutes: number;
  istMinutes: number;
  children: OurHoursChildShare[];
}

export interface OurHours {
  familyId: string | null;
  familyMonthlyMinutes: number;
  monthsInSemester: number;
  sollMinutes: number;
  istMinutes: number;
  allGroups: boolean;
  children: OurHoursChild[];
  months: OurHoursMonthRow[];
  entries: OurHoursEntry[];
}
```

In `required-hours.model.ts`:

```ts
export type RequiredHoursOrder = 'MOST_EXPENSIVE_FIRST' | 'LEAST_EXPENSIVE_FIRST';

export interface RequiredHoursTier {
  fromChild: number;
  percent: number;
}

export interface RequiredHoursGroupRate {
  groupInstanceId: string;
  minutesPerMonth: number;
}

export interface RequiredHours {
  semesterId: string;
  defaultMinutesPerMonth: number;
  allGroups: boolean;
  order: RequiredHoursOrder;
  groupRates: RequiredHoursGroupRate[];
  tiers: RequiredHoursTier[];
}
```

- [ ] **Step 2: Kompilierfehler auflisten**

Run: `cd frontend && npx tsc --noEmit -p tsconfig.json`
Expected: Fehler in `hours-ring.util.ts` (kein Fehler erwartet), `required-hours-preview.util.ts` (`minutesPerMonth` fehlt), `organisation.component.ts`, sowie in Spec-Dateien, die `OurHours`-Objektliterale bauen. Die Liste dient als Arbeitsvorrat für Task 8 und 9.

- [ ] **Step 3: Bestehende Spec-Fixtures ergänzen**

In allen Spec-Dateien, die ein `OurHours`-Literal bauen (`hours-ring.util.spec.ts`, `hours-summary.service.spec.ts`, `stunden.component.spec.ts`, `hours-ring.component.spec.ts`), die neuen Pflichtfelder mit Standardwerten ergänzen: `allGroups: true`, `children: []`, und in jeder Monatszeile `children: []`.

- [ ] **Step 4: Test to verify**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-ring.util.spec.ts --include=**/hours-summary.service.spec.ts`
Expected: PASS — die bestehenden Tests laufen unverändert grün.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/models/hour-entry.model.ts frontend/src/app/shared/models/required-hours.model.ts frontend/src/app
git commit -m "feat(fe): Modelle fuer Gruppensaetze und Soll-Aufschluesselung"
```

---

### Task 8: Ring-Farbstufen nach Erfüllungsgrad

**Files:**
- Modify: `frontend/src/app/shared/components/hours-ring/hours-ring.util.ts`
- Modify: `frontend/src/app/shared/components/hours-ring/hours-ring.util.spec.ts`
- Modify: `frontend/src/app/shared/components/hours-ring/hours-ring.component.html`
- Modify: `frontend/src/app/shared/components/hours-ring/hours-ring.component.scss`
- Modify: `frontend/src/app/shared/components/hours-ring/hours-ring.component.spec.ts`

**Interfaces:**
- Consumes: `OurHours` (Task 7).
- Produces:
  - `type RingLevel = 'level1' | 'level2' | 'level3' | 'level4' | 'level5'`
  - `RingState` zusätzlich mit `fulfillmentPercent: number` und `level: RingLevel`
  - `function ringLevel(fulfillmentPercent: number): RingLevel` — exportiert, damit die Seite „Unsere Stunden" dieselbe Stufe für den Fortschrittsbalken nutzt
  - `RingStatus` bleibt unverändert (`done | onTrack | slightlyBehind | behind`)

- [ ] **Step 1: Write the failing test**

In `hours-ring.util.spec.ts` ergänzen:

```ts
  describe('Farbstufen', () => {
    it('ordnet jeden Erfüllungsgrad der richtigen Stufe zu', () => {
      expect(ringLevel(0)).toBe('level1');
      expect(ringLevel(19)).toBe('level1');
      expect(ringLevel(20)).toBe('level2');
      expect(ringLevel(39)).toBe('level2');
      expect(ringLevel(40)).toBe('level3');
      expect(ringLevel(59)).toBe('level3');
      expect(ringLevel(60)).toBe('level4');
      expect(ringLevel(79)).toBe('level4');
      expect(ringLevel(80)).toBe('level5');
      expect(ringLevel(100)).toBe('level5');
    });

    it('misst den Erfüllungsgrad am bis heute fälligen Soll', () => {
      const state = buildRingState(our({
        sollMinutes: 4800,
        istMinutes: 600,
        months: [
          month('2026-09', 600, 300),
          month('2026-10', 600, 300),
          month('2026-11', 600, 0),
        ],
      }), '2026-10');

      // fällig bis Oktober: 1200, geleistet 600 -> 50 %
      expect(state!.fulfillmentPercent).toBe(50);
      expect(state!.level).toBe('level3');
    });

    it('zeigt vor dem ersten fälligen Monat die grüne Stufe', () => {
      const state = buildRingState(our({
        sollMinutes: 4800,
        istMinutes: 0,
        months: [month('2026-11', 600, 0)],
      }), '2026-10');

      expect(state!.fulfillmentPercent).toBe(100);
      expect(state!.level).toBe('level5');
    });

    it('kappt den Erfüllungsgrad bei 100', () => {
      const state = buildRingState(our({
        sollMinutes: 4800,
        istMinutes: 2000,
        months: [month('2026-09', 600, 2000)],
      }), '2026-10');

      expect(state!.fulfillmentPercent).toBe(100);
    });

    it('erklärt die Farbe im Tooltip', () => {
      const state = buildRingState(our({
        sollMinutes: 4800,
        istMinutes: 600,
        months: [month('2026-09', 600, 300), month('2026-10', 600, 300)],
      }), '2026-10');

      expect(state!.tooltip).toContain('Farbe: 50 % des bis heute Fälligen geleistet');
      expect(state!.tooltip).toContain('ab 80 % grün');
    });
  });
```

Dazu die vorhandenen Test-Hilfsfunktionen nutzen; falls `our()` und `month()` in der Spec noch nicht existieren, ergänzen:

```ts
  function month(m: string, sollMinutes: number, istMinutes: number): OurHoursMonthRow {
    return { month: m, sollMinutes, istMinutes, children: [] };
  }

  function our(partial: Partial<OurHours>): OurHours {
    return {
      familyId: 'f1', familyMonthlyMinutes: 600, monthsInSemester: 8,
      sollMinutes: 4800, istMinutes: 0, allGroups: true, children: [],
      months: [], entries: [], ...partial,
    };
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-ring.util.spec.ts`
Expected: FAIL — `ringLevel` ist nicht exportiert, `fulfillmentPercent` fehlt.

- [ ] **Step 3: Write minimal implementation**

In `hours-ring.util.ts`:

```ts
export type RingLevel = 'level1' | 'level2' | 'level3' | 'level4' | 'level5';
```

`RingState` um `fulfillmentPercent: number;` und `level: RingLevel;` erweitern und ergänzen:

```ts
/** Fünf Stufen à 20 Prozentpunkte: level1 dunkelrot bis level5 grün. */
export function ringLevel(fulfillmentPercent: number): RingLevel {
  if (fulfillmentPercent < 20) return 'level1';
  if (fulfillmentPercent < 40) return 'level2';
  if (fulfillmentPercent < 60) return 'level3';
  if (fulfillmentPercent < 80) return 'level4';
  return 'level5';
}
```

In `buildRingState` nach der Berechnung von `sollToDateMinutes`:

```ts
  // Vor dem ersten fälligen Monat ist nichts offen — dann gilt der Ring als erfüllt.
  const fulfillmentPercent = sollToDateMinutes <= 0
    ? 100
    : Math.min(100, Math.round((istMinutes / sollToDateMinutes) * 100));
  const level = ringLevel(fulfillmentPercent);
```

Und im Tooltip als letzte Zeile:

```ts
    `Farbe: ${fulfillmentPercent} % des bis heute Fälligen geleistet — ab 80 % grün, darunter gelb bis rot.`,
```

`fulfillmentPercent` und `level` in das zurückgegebene Objekt aufnehmen.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-ring.util.spec.ts`
Expected: PASS

- [ ] **Step 5: Farben in Komponente und Stylesheet umstellen**

`hours-ring.component.html`: `[ngClass]="'status-' + state.status"` wird zu `[ngClass]="'level-' + state.level"`.

`hours-ring.component.scss`: Block `.ring-arc` ersetzen:

```scss
  &.level-level1 { stroke: #b71c1c; }
  &.level-level2 { stroke: #e64a19; }
  &.level-level3 { stroke: #f57c00; }
  &.level-level4 { stroke: #fbc02d; }
  &.level-level5 { stroke: #43a047; }
```

In `hours-ring.component.spec.ts` die Klassenprüfungen von `status-…` auf `level-…` umstellen und einen Fall ergänzen, der bei 50 % Erfüllungsgrad `level-level3` erwartet.

- [ ] **Step 6: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-ring.component.spec.ts --include=**/hours-ring.util.spec.ts`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/components/hours-ring
git commit -m "feat(fe): Ringfarbe in fuenf Stufen nach Erfuellungsgrad"
```

---

### Task 9: Monatsverlauf zusammenfassen (`hours-breakdown.util.ts`)

Reine Funktion, kein Angular: fasst aufeinanderfolgende Monate mit identischen Werten zu einer Zeile zusammen.

**Files:**
- Create: `frontend/src/app/stunden/hours-breakdown.util.ts`
- Test: `frontend/src/app/stunden/hours-breakdown.util.spec.ts`

**Interfaces:**
- Consumes: `OurHoursMonthRow`, `OurHoursChildShare` (Task 7).
- Produces:
  - `interface MonthSpan { label: string; months: string[]; sollMinutes: number; shares: OurHoursChildShare[]; }`
  - `function summarizeMonths(months: OurHoursMonthRow[]): MonthSpan[]` — Monate ohne Soll entfallen; Monate sind aufsteigend sortiert erwartet
  - `function formatMonthLabel(month: string): string` — `"2026-11"` → `"Nov 2026"`

- [ ] **Step 1: Write the failing test**

`frontend/src/app/stunden/hours-breakdown.util.spec.ts`:

```ts
import { OurHoursMonthRow } from '../shared/models/hour-entry.model';
import { formatMonthLabel, summarizeMonths } from './hours-breakdown.util';

describe('hours-breakdown.util', () => {
  function share(childId: string, minutes: number, fractionPercent = 100, discountPercent = 0) {
    return { childId, minutes, fractionPercent, discountPercent };
  }

  function row(month: string, sollMinutes: number, shares = [share('a', sollMinutes)]): OurHoursMonthRow {
    return { month, sollMinutes, istMinutes: 0, children: shares };
  }

  it('formatiert Monatsbezeichnungen deutsch', () => {
    expect(formatMonthLabel('2026-11')).toBe('Nov 2026');
    expect(formatMonthLabel('2027-01')).toBe('Jän 2027');
  });

  it('fasst aufeinanderfolgende Monate mit gleichen Werten zusammen', () => {
    const spans = summarizeMonths([
      row('2026-09', 480), row('2026-10', 480), row('2026-11', 480),
    ]);

    expect(spans.length).toBe(1);
    expect(spans[0].label).toBe('Sep 2026 – Nov 2026');
    expect(spans[0].months).toEqual(['2026-09', '2026-10', '2026-11']);
    expect(spans[0].sollMinutes).toBe(480);
  });

  it('trennt bei abweichenden Werten', () => {
    const spans = summarizeMonths([
      row('2026-09', 480),
      row('2026-10', 593, [share('a', 480), share('b', 113, 50, 25)]),
      row('2026-11', 705, [share('a', 480), share('b', 225, 100, 25)]),
      row('2026-12', 705, [share('a', 480), share('b', 225, 100, 25)]),
    ]);

    expect(spans.map((s) => s.label)).toEqual([
      'Sep 2026', 'Okt 2026', 'Nov 2026 – Dez 2026',
    ]);
  });

  it('lässt Monate ohne Soll weg', () => {
    const spans = summarizeMonths([
      { month: '2026-09', sollMinutes: 0, istMinutes: 120, children: [] },
      row('2026-10', 480),
    ]);

    expect(spans.length).toBe(1);
    expect(spans[0].label).toBe('Okt 2026');
  });

  it('fasst über den Jahreswechsel zusammen', () => {
    const spans = summarizeMonths([row('2026-12', 480), row('2027-01', 480)]);

    expect(spans.length).toBe(1);
    expect(spans[0].label).toBe('Dez 2026 – Jän 2027');
  });

  it('trennt bei einer Lücke in der Monatsfolge', () => {
    const spans = summarizeMonths([row('2026-09', 480), row('2026-11', 480)]);

    expect(spans.map((s) => s.label)).toEqual(['Sep 2026', 'Nov 2026']);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-breakdown.util.spec.ts`
Expected: FAIL — Datei `hours-breakdown.util.ts` existiert nicht.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/app/stunden/hours-breakdown.util.ts`:

```ts
import { OurHoursChildShare, OurHoursMonthRow } from '../shared/models/hour-entry.model';

export interface MonthSpan {
  label: string;
  months: string[];
  sollMinutes: number;
  shares: OurHoursChildShare[];
}

const MONTH_NAMES = [
  'Jän', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun',
  'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez',
];

export function formatMonthLabel(month: string): string {
  const [year, monthPart] = month.split('-');
  const index = Number(monthPart) - 1;
  return `${MONTH_NAMES[index] ?? monthPart} ${year}`;
}

/** Vergleichsschlüssel einer Monatszeile: gleiche Aufteilung ergibt denselben Schlüssel. */
function signature(row: OurHoursMonthRow): string {
  const shares = [...row.children]
    .sort((a, b) => a.childId.localeCompare(b.childId))
    .map((s) => `${s.childId}:${s.minutes}:${s.fractionPercent}:${s.discountPercent}`)
    .join('|');
  return `${row.sollMinutes}#${shares}`;
}

/** Fortlaufender Monat nach "YYYY-MM". */
function nextMonth(month: string): string {
  const [year, monthPart] = month.split('-').map(Number);
  return monthPart === 12
    ? `${year + 1}-01`
    : `${year}-${String(monthPart + 1).padStart(2, '0')}`;
}

/**
 * Monate mit Soll, benachbarte Monate mit identischer Aufteilung zu einer Spanne
 * zusammengefasst. Monate ohne Soll entfallen.
 */
export function summarizeMonths(months: OurHoursMonthRow[]): MonthSpan[] {
  const spans: MonthSpan[] = [];
  let previous: OurHoursMonthRow | null = null;

  for (const row of months) {
    if (row.sollMinutes <= 0) {
      previous = null;
      continue;
    }
    const continues = previous !== null
      && signature(previous) === signature(row)
      && nextMonth(previous.month) === row.month;

    if (continues) {
      const span = spans[spans.length - 1];
      span.months.push(row.month);
      span.label = `${formatMonthLabel(span.months[0])} – ${formatMonthLabel(row.month)}`;
    } else {
      spans.push({
        label: formatMonthLabel(row.month),
        months: [row.month],
        sollMinutes: row.sollMinutes,
        shares: row.children,
      });
    }
    previous = row;
  }
  return spans;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-breakdown.util.spec.ts`
Expected: PASS (6 Tests)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/stunden/hours-breakdown.util.ts frontend/src/app/stunden/hours-breakdown.util.spec.ts
git commit -m "feat(fe): Monatsverlauf zu Spannen zusammenfassen"
```

---

### Task 10: `HoursBreakdownComponent` — Kennzahlen, Balken, Zusammensetzung

**Files:**
- Create: `frontend/src/app/stunden/hours-breakdown/hours-breakdown.component.ts`
- Create: `frontend/src/app/stunden/hours-breakdown/hours-breakdown.component.html`
- Create: `frontend/src/app/stunden/hours-breakdown/hours-breakdown.component.scss`
- Test: `frontend/src/app/stunden/hours-breakdown/hours-breakdown.component.spec.ts`

**Interfaces:**
- Consumes: `OurHours`, `OurHoursChild` (Task 7); `buildRingState`, `currentYearMonth`, `RingState` (Task 8); `summarizeMonths`, `formatMonthLabel`, `MonthSpan` (Task 9); `formatMinutes`, `formatIsoDateDe` aus `shared/util/time-format.util.ts`.
- Produces: Selector `app-hours-breakdown`, Input `our: OurHours | null`. Kein Output.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/stunden/hours-breakdown/hours-breakdown.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HoursBreakdownComponent } from './hours-breakdown.component';
import { OurHours } from '../../shared/models/hour-entry.model';

describe('HoursBreakdownComponent', () => {
  let fixture: ComponentFixture<HoursBreakdownComponent>;

  const our: OurHours = {
    familyId: 'f1',
    familyMonthlyMinutes: 705,
    monthsInSemester: 3,
    sollMinutes: 1890,
    istMinutes: 900,
    allGroups: false,
    children: [
      {
        childId: 'a', name: 'Lena', groupLabel: 'Käfergruppe', groupColor: '#43a047',
        baseMinutesPerMonth: 480, entryDate: null, exitDate: null, sollMinutes: 1440,
      },
      {
        childId: 'b', name: 'Jonas', groupLabel: 'Bärengruppe', groupColor: '#fb8c00',
        baseMinutesPerMonth: 300, entryDate: '2026-10-16', exitDate: null, sollMinutes: 450,
      },
    ],
    months: [
      { month: '2026-09', sollMinutes: 480, istMinutes: 300, children: [
        { childId: 'a', minutes: 480, fractionPercent: 100, discountPercent: 0 },
      ] },
      { month: '2026-10', sollMinutes: 593, istMinutes: 300, children: [
        { childId: 'a', minutes: 480, fractionPercent: 100, discountPercent: 0 },
        { childId: 'b', minutes: 113, fractionPercent: 50, discountPercent: 25 },
      ] },
      { month: '2026-11', sollMinutes: 705, istMinutes: 300, children: [
        { childId: 'a', minutes: 480, fractionPercent: 100, discountPercent: 0 },
        { childId: 'b', minutes: 225, fractionPercent: 100, discountPercent: 25 },
      ] },
    ],
    entries: [],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HoursBreakdownComponent] }).compileComponents();
    fixture = TestBed.createComponent(HoursBreakdownComponent);
  });

  function render(value: OurHours | null): HTMLElement {
    fixture.componentInstance.our = value;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('zeigt eine Zeile je Kind mit Gruppe und Satz', () => {
    const el = render(our);
    const rows = el.querySelectorAll('.child-row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Lena');
    expect(rows[0].textContent).toContain('Käfergruppe');
    expect(rows[0].textContent).toContain('08:00');   // Satz/Monat Lena
    expect(rows[1].textContent).toContain('Jonas');
    expect(rows[1].textContent).toContain('05:00');   // Satz/Monat Jonas
    expect(rows[1].textContent).toContain('07:30');   // Summe Jonas: 450 Minuten
  });

  it('nennt bei abweichendem Zeitraum das Eintrittsdatum', () => {
    const el = render(our);
    expect(el.querySelectorAll('.child-row')[1].textContent).toContain('ab 16.10.2026');
  });

  it('blendet die Gruppenspalte aus, wenn ein Satz für alle gilt', () => {
    const el = render({ ...our, allGroups: true });
    expect(el.querySelector('.group-column')).toBeNull();
  });

  it('zeigt den Monatsverlauf als Spannen', () => {
    const el = render(our);
    const spans = el.querySelectorAll('.month-span');
    expect(spans.length).toBe(3);
    expect(spans[0].textContent).toContain('Sep 2026');
  });

  it('zeigt Kennzahlen mit Bilanz und Erfüllungsstufe', () => {
    const el = render(our);
    expect(el.querySelector('.kpi-soll')!.textContent).toContain('31:30');
    expect(el.querySelector('.kpi-ist')!.textContent).toContain('15:00');
    expect(el.querySelector('.progress-bar-fill')!.classList).toContain('level-level5');
  });

  it('zeigt bei fehlendem Soll nur einen Hinweis', () => {
    const el = render({ ...our, sollMinutes: 0, children: [], months: [] });
    expect(el.querySelector('.kpi-soll')).toBeNull();
    expect(el.textContent).toContain('keine zu leistenden Stunden hinterlegt');
  });

  it('zeigt nichts, solange keine Daten geladen sind', () => {
    const el = render(null);
    expect(el.textContent!.trim()).toBe('');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-breakdown.component.spec.ts`
Expected: FAIL — Komponente existiert nicht.

- [ ] **Step 3: Write minimal implementation**

`hours-breakdown.component.ts`:

```ts
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { OurHours, OurHoursChild } from '../../shared/models/hour-entry.model';
import { buildRingState, currentYearMonth, RingState } from '../../shared/components/hours-ring/hours-ring.util';
import { MonthSpan, summarizeMonths } from '../hours-breakdown.util';
import { formatMinutes, formatIsoDateDe } from '../../shared/util/time-format.util';

@Component({
  selector: 'app-hours-breakdown',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatTooltipModule],
  templateUrl: './hours-breakdown.component.html',
  styleUrl: './hours-breakdown.component.scss',
})
export class HoursBreakdownComponent {
  @Input() set our(value: OurHours | null) {
    this.data = value;
    this.state = buildRingState(value, currentYearMonth(new Date()));
    this.spans = value ? summarizeMonths(value.months) : [];
    this.expanded = false;
  }

  data: OurHours | null = null;
  state: RingState | null = null;
  spans: MonthSpan[] = [];
  expanded = false;

  formatMinutes = formatMinutes;

  get hasSoll(): boolean {
    return !!this.data && this.data.sollMinutes > 0;
  }

  /** Kinder in der Reihenfolge der Aufschlüsselung; Spaltenreihenfolge des Monatsverlaufs. */
  get children(): OurHoursChild[] {
    return this.data?.children ?? [];
  }

  minutesFor(span: MonthSpan, childId: string): number | null {
    const share = span.shares.find((s) => s.childId === childId);
    return share ? share.minutes : null;
  }

  shareHint(span: MonthSpan, childId: string): string {
    const share = span.shares.find((s) => s.childId === childId);
    if (!share) return '';
    const parts: string[] = [];
    if (share.fractionPercent < 100) parts.push(`${share.fractionPercent} % anteilig`);
    if (share.discountPercent > 0) parts.push(`−${share.discountPercent} %`);
    return parts.join(', ');
  }

  /** "ab 16.10.2026" / "bis 31.01.2027" / "" bei ganzem Semester. */
  periodHint(child: OurHoursChild): string {
    const parts: string[] = [];
    if (child.entryDate) parts.push(`ab ${formatIsoDateDe(child.entryDate)}`);
    if (child.exitDate) parts.push(`bis ${formatIsoDateDe(child.exitDate)}`);
    return parts.join(', ');
  }
}
```

`hours-breakdown.component.html`:

```html
@if (data) {
  @if (hasSoll) {
    <div class="kpi-row">
      <div class="kpi kpi-soll">
        <span class="kpi-label">Soll Semester</span>
        <b>{{ formatMinutes(data.sollMinutes) }}</b>
        <span class="kpi-sub">{{ spans.length }} Monate mit Soll</span>
      </div>
      <div class="kpi kpi-ist">
        <span class="kpi-label">Geleistet</span>
        <b>{{ formatMinutes(data.istMinutes) }}</b>
        <span class="kpi-sub">{{ state?.realPercent }} %</span>
      </div>
      <div class="kpi kpi-balance" [class.negative]="(state?.deltaMinutes ?? 0) < 0">
        <span class="kpi-label">Bilanz</span>
        <b>{{ (state?.deltaMinutes ?? 0) < 0 ? '−' : '+' }}{{ formatMinutes(state ? (state.deltaMinutes < 0 ? -state.deltaMinutes : state.deltaMinutes) : 0) }}</b>
        <span class="kpi-sub">{{ (state?.deltaMinutes ?? 0) < 0 ? 'Rückstand' : 'Vorsprung' }}</span>
      </div>
      <div class="kpi kpi-average">
        <span class="kpi-label">Ø geleistet</span>
        <b>{{ formatMinutes(state?.avgDoneMinutes ?? 0) }}</b>
        <span class="kpi-sub">nötig {{ formatMinutes(state?.monthlySollMinutes ?? 0) }}/Monat</span>
      </div>
    </div>

    <div class="progress-bar" [matTooltip]="state?.tooltip ?? ''" matTooltipClass="hours-ring-tooltip">
      <span class="progress-bar-fill" [ngClass]="'level-' + (state?.level ?? 'level5')"
            [style.width.%]="state?.ringPercent ?? 0"></span>
    </div>

    <section class="composition">
      <header>
        <b>{{ children.length }} Kinder
          @if (data.familyMonthlyMinutes > 0) { · {{ formatMinutes(data.familyMonthlyMinutes) }} h/Monat }
          · {{ formatMinutes(data.sollMinutes) }} h gesamt</b>
        <button type="button" class="toggle" (click)="expanded = !expanded">
          Monatsverlauf <mat-icon>{{ expanded ? 'expand_less' : 'expand_more' }}</mat-icon>
        </button>
      </header>

      <table class="children">
        <tr>
          <th>Kind</th>
          @if (!data.allGroups) { <th class="group-column">Gruppe</th> }
          <th>Satz/Monat</th>
          <th>Summe</th>
        </tr>
        @for (child of children; track child.childId) {
          <tr class="child-row">
            <td>
              {{ child.name }}
              @if (periodHint(child)) { <span class="period">{{ periodHint(child) }}</span> }
            </td>
            @if (!data.allGroups) {
              <td class="group-column">
                <span class="dot" [style.background]="child.groupColor"></span>{{ child.groupLabel }}
              </td>
            }
            <td>{{ formatMinutes(child.baseMinutesPerMonth) }}</td>
            <td>{{ formatMinutes(child.sollMinutes) }}</td>
          </tr>
        }
      </table>

      @if (expanded) {
        <table class="months">
          <tr>
            <th>Monat</th>
            @for (child of children; track child.childId) { <th>{{ child.name }}</th> }
            <th>Soll</th>
          </tr>
          @for (span of spans; track span.label) {
            <tr class="month-span">
              <td>{{ span.label }}</td>
              @for (child of children; track child.childId) {
                <td>
                  @if (minutesFor(span, child.childId) !== null) {
                    {{ formatMinutes(minutesFor(span, child.childId)!) }}
                    @if (shareHint(span, child.childId)) {
                      <span class="hint">{{ shareHint(span, child.childId) }}</span>
                    }
                  } @else { — }
                </td>
              }
              <td>{{ formatMinutes(span.sollMinutes) }}</td>
            </tr>
          }
        </table>
      }
    </section>
  } @else {
    <p class="empty-hint">Für dieses Semester sind keine zu leistenden Stunden hinterlegt.</p>
  }
}
```

`hours-breakdown.component.scss`:

```scss
.kpi-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.kpi {
  flex: 1 1 140px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 8px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;

  b { font-size: 20px; }
  &.kpi-balance b { color: #2e7d32; }
  &.kpi-balance.negative b { color: #c62828; }
}

.kpi-label, .kpi-sub { font-size: 12px; opacity: 0.7; }

.progress-bar {
  height: 8px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.08);
  overflow: hidden;
  margin-bottom: 12px;
}

.progress-bar-fill {
  display: block;
  height: 100%;
  transition: width 0.4s ease-out;

  &.level-level1 { background: #b71c1c; }
  &.level-level2 { background: #e64a19; }
  &.level-level3 { background: #f57c00; }
  &.level-level4 { background: #fbc02d; }
  &.level-level5 { background: #43a047; }
}

.composition {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 16px;

  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
  }
}

.toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: 0;
  cursor: pointer;
  color: inherit;
  opacity: 0.75;
}

table {
  width: 100%;
  border-collapse: collapse;

  th, td { text-align: left; padding: 3px 6px; border-bottom: 1px solid rgba(0, 0, 0, 0.06); }
  th { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; opacity: 0.65; }
}

.months { margin-top: 8px; }

.period, .hint { font-size: 11px; opacity: 0.65; margin-left: 6px; }

.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 6px;
}

.empty-hint { opacity: 0.7; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-breakdown.component.spec.ts`
Expected: PASS (7 Tests)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/stunden/hours-breakdown
git commit -m "feat(fe): Zusammensetzung der zu leistenden Stunden als Komponente"
```

---

### Task 11: Eintragstabelle und Bearbeitungsdialog

**Files:**
- Create: `frontend/src/app/stunden/hours-entries/hours-entries.component.ts`
- Create: `frontend/src/app/stunden/hours-entries/hours-entries.component.html`
- Create: `frontend/src/app/stunden/hours-entries/hours-entries.component.scss`
- Test: `frontend/src/app/stunden/hours-entries/hours-entries.component.spec.ts`
- Create: `frontend/src/app/stunden/hours-entry-dialog/hours-entry-dialog.component.ts`
- Create: `frontend/src/app/stunden/hours-entry-dialog/hours-entry-dialog.component.html`
- Test: `frontend/src/app/stunden/hours-entry-dialog/hours-entry-dialog.component.spec.ts`

**Interfaces:**
- Consumes: `OurHoursEntry`, `HourEntry`, `RoleOption`, `SaveHourEntryRequest` (`shared/models/hour-entry.model.ts`); `parseHhmm`, `formatMinutes`, `toIsoDate`, `parseIsoDate`, `formatIsoDateDe` aus `shared/util/time-format.util.ts`; `formatMonthLabel` (Task 9).
- Produces:
  - `HoursEntriesComponent`: Selector `app-hours-entries`, Inputs `entries: OurHoursEntry[]`, `ownPersonId: string | null`; Outputs `edit = new EventEmitter<OurHoursEntry>()`, `remove = new EventEmitter<OurHoursEntry>()`, `create = new EventEmitter<void>()`. Interner Monatsfilter.
  - `HoursEntryDialogComponent`: `MAT_DIALOG_DATA` vom Typ `HoursEntryDialogData { entry: HourEntry | null; options: RoleOption[] }`, schließt mit `SaveHourEntryRequest | undefined`.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/stunden/hours-entries/hours-entries.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HoursEntriesComponent } from './hours-entries.component';
import { OurHoursEntry } from '../../shared/models/hour-entry.model';

describe('HoursEntriesComponent', () => {
  let fixture: ComponentFixture<HoursEntriesComponent>;

  const entries: OurHoursEntry[] = [
    { id: '1', personId: 'p1', personName: 'Martin', roleLabel: 'Reparatur', date: '2026-11-04', minutes: 450, comment: '' },
    { id: '2', personId: 'p2', personName: 'Anna', roleLabel: 'Küche', date: '2026-10-19', minutes: 270, comment: '' },
    { id: '3', personId: 'p1', personName: 'Martin', roleLabel: 'Garten', date: '2026-10-12', minutes: 180, comment: '' },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HoursEntriesComponent, NoopAnimationsModule],
    }).compileComponents();
    fixture = TestBed.createComponent(HoursEntriesComponent);
    fixture.componentInstance.entries = entries;
    fixture.componentInstance.ownPersonId = 'p1';
    fixture.detectChanges();
  });

  it('zeigt alle Einträge absteigend nach Datum', () => {
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.entry-row');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('04.11.2026');
    expect(rows[2].textContent).toContain('12.10.2026');
  });

  it('bietet je vorkommendem Monat einen Filterwert', () => {
    expect(fixture.componentInstance.months).toEqual(['2026-11', '2026-10']);
  });

  it('filtert auf den gewählten Monat', () => {
    fixture.componentInstance.selectedMonth = '2026-10';
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.entry-row').length).toBe(2);
  });

  it('zeigt Aktionen nur bei eigenen Einträgen', () => {
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.entry-row');
    expect(rows[0].querySelector('.entry-actions')).not.toBeNull();   // Martin, eigener Eintrag
    expect(rows[1].querySelector('.entry-actions')).toBeNull();       // Anna
  });

  it('meldet Bearbeiten und Löschen nach außen', () => {
    const edit = jasmine.createSpy('edit');
    const remove = jasmine.createSpy('remove');
    fixture.componentInstance.edit.subscribe(edit);
    fixture.componentInstance.remove.subscribe(remove);
    fixture.detectChanges();

    const row = (fixture.nativeElement as HTMLElement).querySelector('.entry-row')!;
    (row.querySelector('.action-edit') as HTMLButtonElement).click();
    (row.querySelector('.action-delete') as HTMLButtonElement).click();

    expect(edit).toHaveBeenCalledWith(entries[0]);
    expect(remove).toHaveBeenCalledWith(entries[0]);
  });

  it('zeigt einen Hinweis, wenn keine Einträge vorliegen', () => {
    fixture.componentInstance.entries = [];
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Noch keine Einträge');
  });
});
```

`frontend/src/app/stunden/hours-entry-dialog/hours-entry-dialog.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { HoursEntryDialogComponent, HoursEntryDialogData } from './hours-entry-dialog.component';
import { HourEntry, RoleOption } from '../../shared/models/hour-entry.model';

describe('HoursEntryDialogComponent', () => {
  let fixture: ComponentFixture<HoursEntryDialogComponent>;
  const dialogRef = { close: jasmine.createSpy('close') };

  const options: RoleOption[] = [
    { fieldInstanceId: 'r1', definitionId: 'd1', label: 'Garten' },
    { fieldInstanceId: null, definitionId: null, label: 'Kochen' },
  ];

  async function setup(data: HoursEntryDialogData) {
    dialogRef.close.calls.reset();
    await TestBed.resetTestingModule().configureTestingModule({
      imports: [HoursEntryDialogComponent, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(HoursEntryDialogComponent);
    fixture.detectChanges();
  }

  it('startet leer beim Anlegen', async () => {
    await setup({ entry: null, options });
    expect(fixture.componentInstance.form.value.time).toBe('');
  });

  it('übernimmt die Werte beim Bearbeiten', async () => {
    const entry: HourEntry = {
      id: '1', personId: 'p1', semesterId: 's1', roleFieldInstanceId: 'r1',
      roleLabel: 'Garten', date: '2026-10-12', minutes: 180, comment: 'Hecke',
    };
    await setup({ entry, options });

    expect(fixture.componentInstance.form.value.time).toBe('03:00');
    expect(fixture.componentInstance.form.value.comment).toBe('Hecke');
  });

  it('schließt mit dem Request, wenn das Formular gültig ist', async () => {
    await setup({ entry: null, options });
    fixture.componentInstance.form.setValue({
      roleKey: 'r1', date: new Date(2026, 9, 12), time: '02:30', comment: '',
    });

    fixture.componentInstance.save();

    expect(dialogRef.close).toHaveBeenCalledWith({
      roleFieldInstanceId: 'r1', date: '2026-10-12', minutes: 150, comment: '',
    });
  });

  it('schließt nicht bei ungültiger Dauer', async () => {
    await setup({ entry: null, options });
    fixture.componentInstance.form.setValue({
      roleKey: 'r1', date: new Date(2026, 9, 12), time: 'abc', comment: '',
    });

    fixture.componentInstance.save();

    expect(dialogRef.close).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-entries.component.spec.ts --include=**/hours-entry-dialog.component.spec.ts`
Expected: FAIL — beide Komponenten existieren nicht.

- [ ] **Step 3: Write minimal implementation**

`hours-entries.component.ts`:

```ts
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { OurHoursEntry } from '../../shared/models/hour-entry.model';
import { formatIsoDateDe, formatMinutes } from '../../shared/util/time-format.util';
import { formatMonthLabel } from '../hours-breakdown.util';

@Component({
  selector: 'app-hours-entries',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatSelectModule,
  ],
  templateUrl: './hours-entries.component.html',
  styleUrl: './hours-entries.component.scss',
})
export class HoursEntriesComponent {
  @Input() entries: OurHoursEntry[] = [];
  @Input() ownPersonId: string | null = null;

  @Output() edit = new EventEmitter<OurHoursEntry>();
  @Output() remove = new EventEmitter<OurHoursEntry>();
  @Output() create = new EventEmitter<void>();

  selectedMonth = '';

  formatMinutes = formatMinutes;
  formatIsoDateDe = formatIsoDateDe;
  formatMonthLabel = formatMonthLabel;

  /** Monate der vorhandenen Einträge, absteigend. */
  get months(): string[] {
    const months = new Set<string>();
    for (const entry of this.sorted) {
      if (entry.date) months.add(entry.date.substring(0, 7));
    }
    return [...months];
  }

  get sorted(): OurHoursEntry[] {
    return [...this.entries].sort((a, b) => (b.date ?? '').localeCompare(a.date ?? ''));
  }

  get visible(): OurHoursEntry[] {
    return this.selectedMonth
      ? this.sorted.filter((e) => (e.date ?? '').startsWith(this.selectedMonth))
      : this.sorted;
  }

  isOwn(entry: OurHoursEntry): boolean {
    return this.ownPersonId !== null && entry.personId === this.ownPersonId;
  }
}
```

`hours-entries.component.html`:

```html
<div class="entries-head">
  <b>Alle Einträge</b>
  <span class="entries-actions">
    <mat-form-field appearance="outline" class="month-filter" subscriptSizing="dynamic">
      <mat-label>Monat</mat-label>
      <mat-select [(ngModel)]="selectedMonth">
        <mat-option value="">alle</mat-option>
        @for (month of months; track month) {
          <mat-option [value]="month">{{ formatMonthLabel(month) }}</mat-option>
        }
      </mat-select>
    </mat-form-field>
    <button mat-flat-button color="primary" type="button" (click)="create.emit()">
      <mat-icon>add</mat-icon> Neuer Eintrag
    </button>
  </span>
</div>

<table class="entries">
  <tr>
    <th>Datum</th><th>Tätigkeit</th><th>Person</th><th>Dauer</th><th></th>
  </tr>
  @for (entry of visible; track entry.id) {
    <tr class="entry-row">
      <td>{{ formatIsoDateDe(entry.date) }}</td>
      <td>{{ entry.roleLabel }}</td>
      <td>{{ entry.personName }}</td>
      <td>{{ formatMinutes(entry.minutes) }}</td>
      <td>
        @if (isOwn(entry)) {
          <span class="entry-actions">
            <button mat-icon-button class="action-edit" type="button"
                    aria-label="Bearbeiten" (click)="edit.emit(entry)">
              <mat-icon>edit</mat-icon>
            </button>
            <button mat-icon-button class="action-delete" type="button"
                    aria-label="Löschen" (click)="remove.emit(entry)">
              <mat-icon>delete_outline</mat-icon>
            </button>
          </span>
        }
      </td>
    </tr>
  }
</table>

@if (visible.length === 0) {
  <p class="empty-hint">Noch keine Einträge.</p>
}
```

`hours-entries.component.scss`:

```scss
.entries-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.entries-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.month-filter { width: 140px; }

.entries {
  width: 100%;
  border-collapse: collapse;

  th, td { text-align: left; padding: 4px 6px; border-bottom: 1px solid rgba(0, 0, 0, 0.06); }
  th { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; opacity: 0.65; }
}

.empty-hint { opacity: 0.7; }
```

`hours-entry-dialog.component.ts`:

```ts
import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DATE_LOCALE, provideNativeDateAdapter } from '@angular/material/core';
import { HourEntry, RoleOption, SaveHourEntryRequest } from '../../shared/models/hour-entry.model';
import { formatMinutes, parseHhmm, parseIsoDate, toIsoDate } from '../../shared/util/time-format.util';

export const KOCHEN_KEY = '__kochen__';

export interface HoursEntryDialogData {
  entry: HourEntry | null;
  options: RoleOption[];
}

@Component({
  selector: 'app-hours-entry-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatButtonModule, MatDatepickerModule,
  ],
  providers: [
    provideNativeDateAdapter(),
    { provide: MAT_DATE_LOCALE, useValue: 'de-AT' },
  ],
  templateUrl: './hours-entry-dialog.component.html',
})
export class HoursEntryDialogComponent {
  /** Zusatzoption, falls ein bearbeiteter Alt-Eintrag eine nicht mehr aktive Rolle hat. */
  extraOption: { key: string; label: string } | null = null;

  form = new FormGroup({
    roleKey: new FormControl<string | null>(null, Validators.required),
    date: new FormControl<Date | null>(null, Validators.required),
    time: new FormControl<string>('', [Validators.required, HoursEntryDialogComponent.timeValidator]),
    comment: new FormControl<string>(''),
  });

  constructor(
    private dialogRef: MatDialogRef<HoursEntryDialogComponent, SaveHourEntryRequest>,
    @Inject(MAT_DIALOG_DATA) public data: HoursEntryDialogData,
  ) {
    const entry = data.entry;
    if (!entry) {
      return;
    }
    const key = entry.roleFieldInstanceId ?? KOCHEN_KEY;
    const known = data.options.some((o) => this.roleKey(o) === key);
    this.extraOption = known ? null : { key, label: entry.roleLabel };
    this.form.reset({
      roleKey: key,
      date: parseIsoDate(entry.date),
      time: formatMinutes(entry.minutes),
      comment: entry.comment ?? '',
    });
  }

  private static timeValidator(control: FormControl): { [k: string]: boolean } | null {
    return parseHhmm(control.value ?? '') === null ? { time: true } : null;
  }

  roleKey(option: RoleOption): string {
    return option.fieldInstanceId ?? KOCHEN_KEY;
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const minutes = parseHhmm(this.form.value.time ?? '');
    const date = this.form.value.date;
    if (minutes === null || !date) {
      return;
    }
    const roleKey = this.form.value.roleKey;
    this.dialogRef.close({
      roleFieldInstanceId: roleKey === KOCHEN_KEY ? null : (roleKey ?? null),
      date: toIsoDate(date),
      minutes,
      comment: this.form.value.comment ?? '',
    });
  }
}
```

`hours-entry-dialog.component.html`:

```html
<h2 mat-dialog-title>{{ data.entry ? 'Eintrag bearbeiten' : 'Neuer Eintrag' }}</h2>

<form [formGroup]="form" (ngSubmit)="save()">
  <mat-dialog-content class="entry-form">
    <mat-form-field appearance="outline">
      <mat-label>Tätigkeit / Rolle</mat-label>
      <mat-select formControlName="roleKey">
        @if (extraOption) { <mat-option [value]="extraOption.key">{{ extraOption.label }}</mat-option> }
        @for (option of data.options; track option.fieldInstanceId) {
          <mat-option [value]="roleKey(option)">{{ option.label }}</mat-option>
        }
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Datum</mat-label>
      <input matInput [matDatepicker]="picker" formControlName="date" />
      <mat-datepicker-toggle matIconSuffix [for]="picker"></mat-datepicker-toggle>
      <mat-datepicker #picker></mat-datepicker>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Dauer (HH:MM)</mat-label>
      <input matInput formControlName="time" placeholder="01:30" />
      @if (form.controls.time.hasError('time')) {
        <mat-error>Format HH:MM, größer als 00:00</mat-error>
      }
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Kommentar</mat-label>
      <textarea matInput formControlName="comment" rows="3"></textarea>
    </mat-form-field>
  </mat-dialog-content>

  <mat-dialog-actions align="end">
    <button mat-button type="button" mat-dialog-close>Abbrechen</button>
    <button mat-flat-button color="primary" type="submit">Speichern</button>
  </mat-dialog-actions>
</form>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-entries.component.spec.ts --include=**/hours-entry-dialog.component.spec.ts`
Expected: PASS (10 Tests)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/stunden/hours-entries frontend/src/app/stunden/hours-entry-dialog
git commit -m "feat(fe): Eintragstabelle und Bearbeitungsdialog fuer Unsere Stunden"
```

---

### Task 12: `StundenComponent` als Seitengerüst neu aufsetzen

**Files:**
- Modify: `frontend/src/app/stunden/stunden.component.ts`
- Modify: `frontend/src/app/stunden/stunden.component.html`
- Modify: `frontend/src/app/stunden/stunden.component.scss`
- Modify: `frontend/src/app/stunden/stunden.component.spec.ts`

**Interfaces:**
- Consumes: `HoursBreakdownComponent` (Task 10), `HoursEntriesComponent`, `HoursEntryDialogComponent`, `HoursEntryDialogData` (Task 11), `HourEntryService`, `HoursSummaryService`, `NotificationService`, `CurrentUserService`.
- Produces: keine öffentlichen Signaturen außer der Komponente selbst.

- [ ] **Step 1: Write the failing test**

`stunden.component.spec.ts` ersetzen (bestehendes TestBed-Setup mit `HttpClientTestingModule` bzw. den vorhandenen Service-Stubs übernehmen):

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of, BehaviorSubject } from 'rxjs';
import { StundenComponent } from './stunden.component';
import { HourEntryService } from '../shared/services/hour-entry.service';
import { HoursSummaryService } from '../shared/services/hours-summary.service';
import { NotificationService } from '../shared/services/notification.service';
import { CurrentUserService } from '../core/services/current-user.service';
import { OurHours } from '../shared/models/hour-entry.model';

describe('StundenComponent', () => {
  let fixture: ComponentFixture<StundenComponent>;
  const summary = new BehaviorSubject<OurHours | null>(null);

  const our: OurHours = {
    familyId: 'f1', familyMonthlyMinutes: 480, monthsInSemester: 1,
    sollMinutes: 480, istMinutes: 180, allGroups: true,
    children: [{
      childId: 'c1', name: 'Lena', groupLabel: 'Käfergruppe', groupColor: '#43a047',
      baseMinutesPerMonth: 480, entryDate: null, exitDate: null, sollMinutes: 480,
    }],
    months: [{ month: '2026-10', sollMinutes: 480, istMinutes: 180, children: [
      { childId: 'c1', minutes: 480, fractionPercent: 100, discountPercent: 0 },
    ] }],
    entries: [{
      id: '1', personId: 'p1', personName: 'Martin', roleLabel: 'Garten',
      date: '2026-10-12', minutes: 180, comment: '',
    }],
  };

  const hourService = jasmine.createSpyObj('HourEntryService',
    ['listMine', 'roleOptions', 'create', 'update', 'delete']);
  const hoursSummary = {
    summary$: summary.asObservable(),
    current: null as OurHours | null,
    reload: jasmine.createSpy('reload'),
  };
  const dialog = jasmine.createSpyObj('MatDialog', ['open']);

  beforeEach(async () => {
    hourService.listMine.and.returnValue(of([]));
    hourService.roleOptions.and.returnValue(of([]));
    hourService.create.and.returnValue(of({}));
    hourService.delete.and.returnValue(of({}));
    hoursSummary.reload.calls.reset();

    await TestBed.configureTestingModule({
      imports: [StundenComponent, NoopAnimationsModule],
      providers: [
        { provide: HourEntryService, useValue: hourService },
        { provide: HoursSummaryService, useValue: hoursSummary },
        { provide: MatDialog, useValue: dialog },
        { provide: NotificationService, useValue: jasmine.createSpyObj('NotificationService', ['success', 'error', 'extractError']) },
        { provide: CurrentUserService, useValue: { currentPerson: { id: 'p1' } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StundenComponent);
    fixture.detectChanges();
    summary.next(our);
    fixture.detectChanges();
  });

  it('zeigt Zusammensetzung und Eintragstabelle', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-hours-breakdown')).not.toBeNull();
    expect(el.querySelector('app-hours-entries')).not.toBeNull();
    expect(el.textContent).toContain('Unsere Stunden');
  });

  it('öffnet den Dialog beim Anlegen und lädt nach dem Speichern neu', () => {
    dialog.open.and.returnValue({ afterClosed: () => of({
      roleFieldInstanceId: null, date: '2026-10-12', minutes: 90, comment: '',
    }) });

    fixture.componentInstance.newEntry();

    expect(dialog.open).toHaveBeenCalled();
    expect(hourService.create).toHaveBeenCalled();
    expect(hoursSummary.reload).toHaveBeenCalled();
  });

  it('speichert nicht, wenn der Dialog abgebrochen wird', () => {
    dialog.open.and.returnValue({ afterClosed: () => of(undefined) });
    hourService.create.calls.reset();

    fixture.componentInstance.newEntry();

    expect(hourService.create).not.toHaveBeenCalled();
  });

  it('löscht einen eigenen Eintrag und lädt neu', () => {
    fixture.componentInstance.deleteEntry(our.entries[0]);

    expect(hourService.delete).toHaveBeenCalledWith('1');
    expect(hoursSummary.reload).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/stunden.component.spec.ts`
Expected: FAIL — `newEntry` öffnet keinen Dialog, `deleteEntry` existiert nicht, Kindkomponenten fehlen im Template.

- [ ] **Step 3: Write minimal implementation**

`stunden.component.ts`:

```ts
import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { HourEntryService } from '../shared/services/hour-entry.service';
import { HoursSummaryService } from '../shared/services/hours-summary.service';
import { NotificationService } from '../shared/services/notification.service';
import { CurrentUserService } from '../core/services/current-user.service';
import {
  HourEntry, OurHours, OurHoursEntry, RoleOption, SaveHourEntryRequest,
} from '../shared/models/hour-entry.model';
import { HoursBreakdownComponent } from './hours-breakdown/hours-breakdown.component';
import { HoursEntriesComponent } from './hours-entries/hours-entries.component';
import {
  HoursEntryDialogComponent, HoursEntryDialogData,
} from './hours-entry-dialog/hours-entry-dialog.component';

@Component({
  selector: 'app-stunden',
  standalone: true,
  imports: [CommonModule, HoursBreakdownComponent, HoursEntriesComponent],
  templateUrl: './stunden.component.html',
  styleUrl: './stunden.component.scss',
})
export class StundenComponent implements OnInit, OnDestroy {
  our: OurHours | null = null;
  options: RoleOption[] = [];
  /** Eigene Einträge als Formular-Modell, für Bearbeiten und Löschen. */
  private mine: HourEntry[] = [];
  private summarySub?: Subscription;

  constructor(
    private hourService: HourEntryService,
    private notify: NotificationService,
    private currentUser: CurrentUserService,
    private hoursSummary: HoursSummaryService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadMine();
    this.summarySub = this.hoursSummary.summary$.subscribe((o) => (this.our = o));
    if (!this.hoursSummary.current) {
      this.hoursSummary.reload();
    }
    this.hourService.roleOptions().subscribe({
      next: (opts) => (this.options = opts),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  ngOnDestroy(): void {
    this.summarySub?.unsubscribe();
  }

  get ownPersonId(): string | null {
    return this.currentUser.currentPerson?.id ?? null;
  }

  get entries(): OurHoursEntry[] {
    return this.our?.entries ?? [];
  }

  newEntry(): void {
    this.openDialog(null);
  }

  editEntry(entry: OurHoursEntry): void {
    const own = this.mine.find((e) => e.id === entry.id);
    if (own) {
      this.openDialog(own);
    }
  }

  deleteEntry(entry: OurHoursEntry): void {
    this.hourService.delete(entry.id).subscribe({
      next: () => {
        this.notify.success('Eintrag gelöscht');
        this.loadMine();
        this.hoursSummary.reload();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  private openDialog(entry: HourEntry | null): void {
    const data: HoursEntryDialogData = { entry, options: this.options };
    this.dialog.open(HoursEntryDialogComponent, { data, width: '420px' })
      .afterClosed().subscribe((request?: SaveHourEntryRequest) => {
        if (!request) {
          return;
        }
        const save$ = entry
          ? this.hourService.update(entry.id, request)
          : this.hourService.create(request);
        save$.subscribe({
          next: () => {
            this.notify.success(entry ? 'Eintrag aktualisiert' : 'Eintrag gespeichert');
            this.loadMine();
            this.hoursSummary.reload();
          },
          error: (err) => this.notify.error(this.notify.extractError(err)),
        });
      });
  }

  private loadMine(): void {
    this.hourService.listMine().subscribe({
      next: (entries) => (this.mine = entries),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
```

`stunden.component.html`:

```html
<h2>Unsere Stunden</h2>

<app-hours-breakdown [our]="our"></app-hours-breakdown>

<app-hours-entries
  [entries]="entries"
  [ownPersonId]="ownPersonId"
  (create)="newEntry()"
  (edit)="editEntry($event)"
  (remove)="deleteEntry($event)"></app-hours-entries>
```

`stunden.component.scss` auf das Nötige eindampfen — die Regeln für `.stunden-grid`, `.entry-list`, `.entry-form`, `.month-block`, `.entry-row` und `.family-total` entfallen ersatzlos, weil die zugehörige Struktur in die Kindkomponenten gewandert ist:

```scss
:host {
  display: block;
  padding: 16px;
}

h2 { margin-top: 0; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/stunden.component.spec.ts`
Expected: PASS

- [ ] **Step 5: Volle Frontend-Suite prüfen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: Nur der eine schon vorher rote Test bleibt rot; alles andere grün.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/stunden
git commit -m "refactor(fe): Unsere Stunden in Uebersicht, Tabelle und Dialog zerlegt"
```

---

### Task 13: Organisation → „Zu leistende Stunden" mit Gruppensätzen

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts` (Abschnitt „Zu leistende Stunden", ~Zeile 490–580)
- Modify: `frontend/src/app/settings/organisation/organisation.component.html` (Tab „Zu leistende Stunden", ~Zeile 303–360)
- Modify: `frontend/src/app/settings/organisation/required-hours-preview.util.ts`
- Modify: `frontend/src/app/settings/organisation/required-hours-preview.util.spec.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.spec.ts`

**Interfaces:**
- Consumes: `RequiredHours`, `RequiredHoursTier`, `RequiredHoursGroupRate`, `RequiredHoursOrder` (Task 7); `FieldInstanceDTO` mit `value: { label, color }`; `RequiredHoursService.get/save`.
- Produces:
  - `interface GroupRateRow { groupInstanceId: string; label: string; color: string | null; hhmm: string }` — in `organisation.component.ts` deklariert, direkt über der Klasse
  - `familyMonthlyMinutes(cfg: { defaultMinutesPerMonth: number; tiers: RequiredHoursTier[] }, childCount: number): number` — jetzt mit Prozent-Staffeln
  - `groupCombinationMinutes(rates: number[], tiers: RequiredHoursTier[], order: RequiredHoursOrder): number` — Vorschau für gruppenabhängige Sätze
  - Komponentenfelder `rhAllGroups: boolean`, `rhOrder: RequiredHoursOrder`, `rhGroupRates: { groupInstanceId: string; label: string; color: string | null; hhmm: string }[]`

- [ ] **Step 1: Write the failing test**

`required-hours-preview.util.spec.ts` ergänzen:

```ts
  it('rechnet Prozent-Staffeln auf den Default an', () => {
    const cfg = { defaultMinutesPerMonth: 480, tiers: [{ fromChild: 2, percent: 25 }] };
    expect(familyMonthlyMinutes(cfg, 1)).toBe(480);
    expect(familyMonthlyMinutes(cfg, 2)).toBe(840);    // 480 + 360
    expect(familyMonthlyMinutes(cfg, 3)).toBe(1200);   // 480 + 360 + 360
  });

  it('behandelt 100 % Rabatt als beitragsfrei', () => {
    const cfg = { defaultMinutesPerMonth: 480, tiers: [{ fromChild: 3, percent: 100 }] };
    expect(familyMonthlyMinutes(cfg, 3)).toBe(960);
  });

  it('vergibt Rabatte bei Gruppensätzen nach der teuersten Gruppe zuerst', () => {
    const minutes = groupCombinationMinutes([300, 480], [{ fromChild: 2, percent: 25 }], 'MOST_EXPENSIVE_FIRST');
    expect(minutes).toBe(705);   // 480 voll + 300 minus 25 %
  });

  it('vergibt Rabatte bei Gruppensätzen nach der günstigsten Gruppe zuerst', () => {
    const minutes = groupCombinationMinutes([300, 480], [{ fromChild: 2, percent: 25 }], 'LEAST_EXPENSIVE_FIRST');
    expect(minutes).toBe(660);   // 300 voll + 480 minus 25 %
  });
```

`organisation.component.spec.ts` ergänzen (bestehendes TestBed-Setup übernehmen, `RequiredHoursService` und `FieldInstanceService` sind dort bereits gestubbt):

```ts
  it('befüllt beim Abhaken jede Gruppe mit dem bisherigen Default', () => {
    component.rhDefaultHhmm = '08:00';
    component.groupsDataSource.data = [
      { id: 'g1', definitionId: 'd1', fieldName: 'group', label: {}, jsonSchema: {}, required: false,
        value: { label: 'Käfergruppe', color: '#43a047' }, definitionOutdated: false },
      { id: 'g2', definitionId: 'd1', fieldName: 'group', label: {}, jsonSchema: {}, required: false,
        value: { label: 'Bärengruppe', color: '#fb8c00' }, definitionOutdated: false },
    ] as any;

    component.onRhAllGroupsChange(false);

    expect(component.rhGroupRates.length).toBe(2);
    expect(component.rhGroupRates[0].hhmm).toBe('08:00');
    expect(component.rhGroupRates[0].label).toBe('Käfergruppe');
  });

  it('blockiert das Speichern bei leerem Gruppenfeld', () => {
    requiredHoursService.save.calls.reset();
    component.rhSelectedSemesterId = 's1';
    component.rhDefaultHhmm = '08:00';
    component.rhAllGroups = false;
    component.rhGroupRates = [
      { groupInstanceId: 'g1', label: 'Käfergruppe', color: null, hhmm: '08:00' },
      { groupInstanceId: 'g2', label: 'Bärengruppe', color: null, hhmm: '' },
    ];

    component.saveRequiredHours();

    expect(requiredHoursService.save).not.toHaveBeenCalled();
    expect(component.rhError).toContain('Bärengruppe');
  });

  it('speichert Gruppensätze und Reihenfolge', () => {
    requiredHoursService.save.calls.reset();
    requiredHoursService.save.and.returnValue(of({}));
    component.rhSelectedSemesterId = 's1';
    component.rhDefaultHhmm = '08:00';
    component.rhAllGroups = false;
    component.rhOrder = 'LEAST_EXPENSIVE_FIRST';
    component.rhGroupRates = [{ groupInstanceId: 'g1', label: 'Käfergruppe', color: null, hhmm: '05:00' }];
    component.rhTiers = [{ fromChild: 2, percent: 25 }];

    component.saveRequiredHours();

    expect(requiredHoursService.save).toHaveBeenCalledWith('s1', jasmine.objectContaining({
      allGroups: false,
      order: 'LEAST_EXPENSIVE_FIRST',
      groupRates: [{ groupInstanceId: 'g1', minutesPerMonth: 300 }],
      tiers: [{ fromChild: 2, percent: 25 }],
    }));
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/required-hours-preview.util.spec.ts --include=**/organisation.component.spec.ts`
Expected: FAIL — `groupCombinationMinutes`, `onRhAllGroupsChange`, `rhGroupRates` fehlen.

- [ ] **Step 3: Write minimal implementation**

`required-hours-preview.util.ts`:

```ts
import { RequiredHoursOrder, RequiredHoursTier } from '../../shared/models/required-hours.model';

/** Rabatt der höchsten passenden Staffel für einen 1-basierten Rang. */
function discountPercent(tiers: RequiredHoursTier[], rank: number): number {
  let bestFrom = 0;
  let percent = 0;
  for (const t of tiers) {
    if (t.fromChild <= rank && t.fromChild >= bestFrom) {
      bestFrom = t.fromChild;
      percent = t.percent;
    }
  }
  return percent;
}

export function familyMonthlyMinutes(
  cfg: { defaultMinutesPerMonth: number; tiers: RequiredHoursTier[] },
  childCount: number,
): number {
  let total = 0;
  for (let n = 1; n <= childCount; n++) {
    total += Math.round(cfg.defaultMinutesPerMonth * (100 - discountPercent(cfg.tiers, n)) / 100);
  }
  return total;
}

/** Monatswert einer Kombination von Gruppensätzen unter der gewählten Reihenfolge. */
export function groupCombinationMinutes(
  rates: number[],
  tiers: RequiredHoursTier[],
  order: RequiredHoursOrder,
): number {
  const sorted = [...rates].sort((a, b) => (order === 'LEAST_EXPENSIVE_FIRST' ? a - b : b - a));
  return sorted.reduce(
    (total, rate, index) => total + Math.round(rate * (100 - discountPercent(tiers, index + 1)) / 100),
    0,
  );
}
```

`organisation.component.ts` — Felder und Methoden im Abschnitt „Zu leistende Stunden" ersetzen:

```ts
  rhAllGroups = true;
  rhOrder: RequiredHoursOrder = 'MOST_EXPENSIVE_FIRST';
  rhGroupRates: GroupRateRow[] = [];
  rhTiers: { fromChild: number; percent: number }[] = [];
  rhPreview: { label: string; hhmm: string }[] = [];
```

```ts
  private loadRequiredHours(): void {
    if (!this.rhSelectedSemesterId) return;
    this.requiredHoursService.get(this.rhSelectedSemesterId).subscribe((cfg) => {
      this.rhDefaultHhmm = cfg.defaultMinutesPerMonth ? formatMinutes(cfg.defaultMinutesPerMonth) : '';
      this.rhAllGroups = cfg.allGroups ?? true;
      this.rhOrder = cfg.order ?? 'MOST_EXPENSIVE_FIRST';
      this.rhTiers = (cfg.tiers ?? []).map((t) => ({ fromChild: t.fromChild, percent: t.percent }));
      this.rhGroupRates = this.groupRateRows(cfg.groupRates ?? []);
      this.recomputeRhPreview();
      this.loadAliquot();
    });
  }

  /** Eine Zeile je vorhandener Gruppe; bekannte Werte werden übernommen, sonst der Default. */
  private groupRateRows(saved: RequiredHoursGroupRate[]): GroupRateRow[] {
    const byId = new Map(saved.map((r) => [r.groupInstanceId, r.minutesPerMonth]));
    const fallback = parseHhmm(this.rhDefaultHhmm) ?? 0;
    return this.groupsDataSource.data.map((instance) => {
      const value = instance.value as { label?: string; color?: string } | null;
      const minutes = byId.get(instance.id!) ?? fallback;
      return {
        groupInstanceId: instance.id!,
        label: value?.label ?? instance.id!,
        color: value?.color ?? null,
        hhmm: minutes > 0 ? formatMinutes(minutes) : '',
      };
    });
  }

  onRhAllGroupsChange(allGroups: boolean): void {
    this.rhAllGroups = allGroups;
    if (!allGroups) {
      this.rhGroupRates = this.groupRateRows(
        this.rhGroupRates.map((r) => ({
          groupInstanceId: r.groupInstanceId,
          minutesPerMonth: parseHhmm(r.hhmm) ?? 0,
        })),
      );
    }
    this.recomputeRhPreview();
    if (this.rhAllGroups) {
      this.saveRequiredHours();
    }
  }

  addRhTier(): void {
    const nextFrom = this.rhTiers.length === 0 ? 2 : Math.max(...this.rhTiers.map((t) => t.fromChild)) + 1;
    this.rhTiers.push({ fromChild: nextFrom, percent: 0 });
    this.recomputeRhPreview();
  }

  recomputeRhPreview(): void {
    const tiers = [...this.rhTiers].sort((a, b) => a.fromChild - b.fromChild);
    if (this.rhAllGroups) {
      const def = parseHhmm(this.rhDefaultHhmm) ?? 0;
      const childCounts = Array.from(new Set([1, ...tiers.map((t) => t.fromChild)])).sort((a, b) => a - b);
      this.rhPreview = childCounts.map((n) => ({
        label: `${n} ${n === 1 ? 'Kind' : 'Kinder'}`,
        hhmm: formatMinutes(familyMonthlyMinutes({ defaultMinutesPerMonth: def, tiers }, n)),
      }));
      return;
    }
    // Gruppensätze: je Gruppe eine Zeile mit einem Kind, dann die beiden teuersten kombiniert.
    const rows = this.rhGroupRates
      .map((r) => ({ label: r.label, minutes: parseHhmm(r.hhmm) ?? 0 }))
      .filter((r) => r.minutes > 0);
    this.rhPreview = rows.map((r) => ({
      label: `1 Kind · ${r.label}`,
      hhmm: formatMinutes(groupCombinationMinutes([r.minutes], tiers, this.rhOrder)),
    }));
    const topTwo = [...rows].sort((a, b) => b.minutes - a.minutes).slice(0, 2);
    if (topTwo.length === 2) {
      this.rhPreview.push({
        label: `2 Kinder · ${topTwo[0].label} + ${topTwo[1].label}`,
        hhmm: formatMinutes(groupCombinationMinutes(topTwo.map((r) => r.minutes), tiers, this.rhOrder)),
      });
    }
  }

  saveRequiredHours(): void {
    this.rhError = null;
    const def = parseHhmm(this.rhDefaultHhmm);
    if (def === null || def <= 0) { this.rhError = 'Default-Stunden ungültig'; return; }

    const tiers = this.rhTiers.map((t) => ({ fromChild: t.fromChild, percent: t.percent }));
    const froms = tiers.map((t) => t.fromChild);
    const ascendingUnique = froms.every((f, i) => f >= 2 && (i === 0 || f > froms[i - 1]));
    if (!ascendingUnique || tiers.some((t) => t.percent < 0 || t.percent > 100)) {
      this.rhError = 'Staffeln müssen ab dem 2. Kind eindeutig und aufsteigend sein; Rabatt zwischen 0 und 100 %.';
      return;
    }

    const groupRates: RequiredHoursGroupRate[] = [];
    if (!this.rhAllGroups) {
      for (const row of this.rhGroupRates) {
        const minutes = parseHhmm(row.hhmm);
        if (minutes === null || minutes <= 0) {
          this.rhError = `Stunden fehlen für ${row.label}`;
          return;
        }
        groupRates.push({ groupInstanceId: row.groupInstanceId, minutesPerMonth: minutes });
      }
    }

    if (!this.rhSelectedSemesterId) return;
    this.requiredHoursService.save(this.rhSelectedSemesterId, {
      semesterId: this.rhSelectedSemesterId,
      defaultMinutesPerMonth: def,
      allGroups: this.rhAllGroups,
      order: this.rhOrder,
      groupRates,
      tiers,
    }).subscribe({
      next: () => { this.rhError = null; },
      error: () => { this.rhError = 'Speichern fehlgeschlagen'; },
    });
  }
```

Imports ergänzen: `groupCombinationMinutes` aus `./required-hours-preview.util`, `RequiredHoursGroupRate` und `RequiredHoursOrder` aus `../../shared/models/required-hours.model`, `MatCheckboxModule` in `imports` der Komponente.

`organisation.component.html`, Tab „Zu leistende Stunden" — Block zwischen Semester-Auswahl und Aliquotierung ersetzen:

```html
        <mat-checkbox [checked]="rhAllGroups" (change)="onRhAllGroupsChange($event.checked)">
          Für alle Gruppen dieselben Stunden
        </mat-checkbox>

        @if (rhAllGroups) {
          <mat-form-field appearance="outline">
            <mat-label>Stunden pro Monat pro Kind (HH:mm)</mat-label>
            <input matInput [(ngModel)]="rhDefaultHhmm" (ngModelChange)="recomputeRhPreview()"
                   (change)="saveRequiredHours()" placeholder="08:00">
          </mat-form-field>
        } @else {
          @for (rate of rhGroupRates; track rate.groupInstanceId) {
            <div class="group-rate-row">
              <span class="dot" [style.background]="rate.color"></span>
              <mat-form-field appearance="outline">
                <mat-label>{{ rate.label }} — Stunden/Monat (HH:mm)</mat-label>
                <input matInput [(ngModel)]="rate.hhmm" (ngModelChange)="recomputeRhPreview()"
                       (change)="saveRequiredHours()" placeholder="08:00">
              </mat-form-field>
            </div>
          }
          @if (rhGroupRates.length === 0) {
            <p class="error">Es sind noch keine Gruppen angelegt.</p>
          }
        }

        <h4>Staffelung (Geschwisterrabatt) — gilt für alle Gruppen</h4>
        <mat-form-field appearance="outline">
          <mat-label>Reihenfolge</mat-label>
          <mat-select [(ngModel)]="rhOrder" (selectionChange)="recomputeRhPreview(); saveRequiredHours()">
            <mat-option value="MOST_EXPENSIVE_FIRST">Teuerste Gruppe zuerst</mat-option>
            <mat-option value="LEAST_EXPENSIVE_FIRST">Günstigste Gruppe zuerst</mat-option>
          </mat-select>
        </mat-form-field>

        @for (tier of rhTiers; track $index; let i = $index) {
          <div class="tier-row">
            <span>Ab dem</span>
            <mat-form-field appearance="outline" class="narrow">
              <input matInput type="number" min="2" [(ngModel)]="tier.fromChild"
                     (ngModelChange)="recomputeRhPreview()" (change)="saveRequiredHours()">
            </mat-form-field>
            <span>. Kind:</span>
            <mat-form-field appearance="outline" class="narrow">
              <input matInput type="number" min="0" max="100" [(ngModel)]="tier.percent"
                     (ngModelChange)="recomputeRhPreview()" (change)="saveRequiredHours()">
            </mat-form-field>
            <span>% Rabatt</span>
            <button mat-icon-button color="warn" (click)="removeRhTier(i)"><mat-icon>delete</mat-icon></button>
          </div>
        }
        <button mat-stroked-button (click)="addRhTier()"><mat-icon>add</mat-icon> Staffel hinzufügen</button>

        <h4>Vorschau</h4>
        <table class="preview">
          <tr><th>Fall</th><th>Stunden/Monat</th></tr>
          @for (row of rhPreview; track row.label) {
            <tr><td>{{ row.label }}</td><td>{{ row.hhmm }}</td></tr>
          }
        </table>
```

Dazu in `organisation.component.scss`:

```scss
.group-rate-row {
  display: flex;
  align-items: center;
  gap: 8px;

  mat-form-field { flex: 1; }
}

.group-rate-row .dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex: 0 0 auto;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/required-hours-preview.util.spec.ts --include=**/organisation.component.spec.ts`
Expected: PASS

- [ ] **Step 5: Volle Frontend-Suite und Produktionsbau prüfen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: Nur der eine schon vorher rote Test bleibt rot.

Run: `cd frontend && npm run build`
Expected: Erfolgreich.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/organisation
git commit -m "feat(fe): Gruppensaetze und Prozent-Staffeln im Organisations-Tab"
```

---

### Task 14: Admin-Stundenübersicht an die neue Monatszahl anpassen

`stundenuebersicht.component.ts` zeigt „x Kinder · y/Monat × z Monate". Bei aliquotierten oder gruppenabhängigen Familien ist `familyMonthlyMinutes` jetzt 0, wenn es keinen Monat mit voller Anwesenheit gibt — dann darf der Satzteil nicht erscheinen.

**Files:**
- Modify: `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.ts` (~Zeile 96)
- Modify: `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.spec.ts`

**Interfaces:**
- Consumes: `FamilyHoursSummary` (unverändertes Modell).
- Produces: keine neuen Signaturen.

- [ ] **Step 1: Write the failing test**

```ts
  it('nennt den Monatswert nur, wenn es einen vollen Monat gibt', () => {
    const withMonthly = component.sollTooltip({
      childCount: 2, familyMonthlyMinutes: 705, monthsInSemester: 8, sollMinutes: 5640,
    } as any);
    expect(withMonthly).toContain('11:45');

    const withoutMonthly = component.sollTooltip({
      childCount: 2, familyMonthlyMinutes: 0, monthsInSemester: 8, sollMinutes: 5000,
    } as any);
    expect(withoutMonthly).not.toContain('/Monat');
    expect(withoutMonthly).toContain('2 Kinder');
  });
```

Der Methodenname folgt der bestehenden Methode in Zeile ~96; heißt sie anders, den vorhandenen Namen im Test verwenden.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/stundenuebersicht.component.spec.ts`
Expected: FAIL — der Text enthält `00:00/Monat`.

- [ ] **Step 3: Write minimal implementation**

```ts
    // familyMonthlyMinutes ist 0, wenn kein Monat alle Kinder voll enthält
    // (unterjähriger Ein-/Austritt) — dann bleibt der Satzteil weg.
    const monthly = f.familyMonthlyMinutes > 0
      ? ` · ${formatMinutes(f.familyMonthlyMinutes)}/Monat × ${f.monthsInSemester} Monate`
      : '';
    return `${f.childCount} Kinder${monthly}`;
```

Den umgebenden Text der bestehenden Methode beibehalten und nur den Monatsteil bedingt machen.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=**/stundenuebersicht.component.spec.ts`
Expected: PASS

- [ ] **Step 5: Gesamtlauf beider Suiten**

Run: `cd backend && ./mvnw test`
Expected: Nur die 13 vorher schon roten Tests bleiben rot.

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: Nur der eine vorher schon rote Test bleibt rot.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/administration/stundenuebersicht
git commit -m "fix(fe): Stundenuebersicht blendet Monatswert ohne vollen Monat aus"
```

---

## Offene Punkte für den manuellen Smoke-Test

Nach Task 14 von Hand prüfen (kein automatisierter Ersatz möglich):

1. Organisation → Zu leistende Stunden: Haken entfernen, Werte je Gruppe setzen, speichern; Feld leeren und prüfen, dass nicht gespeichert wird.
2. „Unsere Stunden" als Elternteil mit zwei Kindern in unterschiedlichen Gruppen: Zusammensetzung, Monatsverlauf aufklappen, Summen gegen die Kennzahlen prüfen.
3. Ein Kind mit unterjährigem Eintritt anlegen und prüfen, dass der Monatsverlauf den anteiligen Monat und den Rabattwechsel zeigt.
4. Ring im Header: Farbe wechselt bei geändertem Ist (Eintrag erfassen), Tooltip nennt den Erfüllungsgrad.
5. Neues Semester anlegen und prüfen, dass Gruppensätze und Reihenfolge übernommen wurden.
