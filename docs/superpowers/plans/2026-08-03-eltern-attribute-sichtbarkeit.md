# Sichtbare Eltern-Attribute — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admins wählen in Organisation > Gruppen global aus, welche Attribute in der Eltern-Übersicht `/eltern` erscheinen; abgewählte Werte verlassen den Server nicht.

**Architecture:** Ein Singleton-Dokument `parent_directory_settings` hält die freigegebenen Attribut-Schlüssel. Ein `ParentDirectoryAttributeService` baut daraus den Katalog (feste Kernliste plus benutzerdefinierte Personenfelder) und beantwortet "ist Schlüssel X sichtbar". `ParentDirectoryService` liefert je Elternteil nur noch eine `values`-Map der freigegebenen Schlüssel, dazu eine Spaltenliste, aus der das Frontend die Tabelle dynamisch baut.

**Tech Stack:** Quarkus 3.36 / Java 17, MongoDB (roher Treiber + Panache), RESTEasy Reactive, JUnit 5 + RestAssured; Angular 20 Standalone Components, Angular Material, Karma/Jasmine.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-03-eltern-attribute-sichtbarkeit-design.md`
- Alle Oberflächentexte auf Deutsch.
- Maven-Wrapper ist in dieser Umgebung defekt (`ClassNotFoundException: plexus.classworlds.launcher.Launcher`). Immer `mvn` verwenden, nie `./mvnw`.
- Backend-Tests laufen gegen die Datenbank `kigruapp_test` (Profil `test`) und brauchen einen laufenden MongoDB-Container (`kigruapp-mongodb-1`).
- Auf `main` existieren 12 vorbestehende, fachfremde Backend-Testfehler. Maßstab ist: keine *neuen* Fehler.
- `SecurityFilter` ist standardmäßig admin-only; nur explizit whitelisted Pfade sind für Eltern offen. `/api/v1/parent-directory/attributes` wird **nicht** whitelisted und ist damit automatisch admin-pflichtig. `SecurityFilter` wird in diesem Plan nicht angefasst.
- Commit-Nachrichten nach bestehendem Muster: `feat(be):`, `feat(fe):`, `refactor(be):`, `fix(fe):` — Betreff auf Deutsch.
- Der Schlüssel `childName` ist nie abwählbar und wird serverseitig erzwungen.

---

## Dateiübersicht

**Backend, neu**
- `backend/src/main/java/at/kigruapp/service/FieldInstanceLabelResolver.java` — Anzeigename aus `field_instances.value`, Batch
- `backend/src/main/java/at/kigruapp/entity/ParentDirectorySettings.java` — Singleton-Dokument
- `backend/src/main/java/at/kigruapp/service/ParentDirectoryAttributeService.java` — Katalog, Vorgaben, Validierung
- `backend/src/main/java/at/kigruapp/dto/ParentDirectoryAttributeDTO.java` — Katalog-DTOs
- `backend/src/main/java/at/kigruapp/migration/PersonEnrollmentFieldRetirementMigration.java` — setzt `outdatedAt` auf `entryDate`/`exitDate`

**Backend, geändert**
- `dto/ParentDirectoryDTO.java` — `columns`, `ChildEntry`, `values`-Map
- `service/ParentDirectoryService.java` — Sichtbarkeit, Kind-Daten, Team/Rolle
- `service/PersonPropertyResolver.java` — Allowlist ohne `entryDate`/`exitDate`, neue Methode `resolveCustom`
- `resource/ParentDirectoryResource.java` — zwei Admin-Endpunkte
- `resource/HourEntryResource.java` — nutzt `FieldInstanceLabelResolver`
- `resource/MailTemplateResource.java` — Platzhalter-Allowlist
- `migration/FieldDefinitionSeedMigration.java` — seedet die zwei Felder nicht mehr

**Frontend, neu**
- `frontend/src/app/settings/organisation/parent-directory-attributes/parent-directory-attributes.component.ts|html|scss|spec.ts`
- `frontend/src/app/settings/organisation/parent-directory-attributes/parent-directory-settings.service.ts` + `.spec.ts`

**Frontend, geändert**
- `shared/models/parent-directory.model.ts`
- `settings/organisation/organisation.component.ts|html`
- `eltern/eltern.component.ts|html|spec.ts`
- `settings/mail/mail-template-editor/mail-token.util.ts`

---

### Task 1: Gemeinsame Label-Auflösung

Die Logik "Anzeigename aus `field_instances.value`" steht doppelt (`HourEntryResource.labelFromValue`, `ParentDirectoryService.resolveGroupNames`) und bekäme in Task 6 eine dritte Kopie.

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/FieldInstanceLabelResolver.java`
- Create: `backend/src/test/java/at/kigruapp/service/FieldInstanceLabelResolverTest.java`
- Modify: `backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java` (Methoden `resolveGroupNames`, `labelFromValue` entfallen)
- Modify: `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java:478-508`

**Interfaces:**
- Consumes: nichts
- Produces: `FieldInstanceLabelResolver.resolveLabels(Collection<ObjectId> instanceIds)` → `Map<ObjectId, String>`; fehlende Instanzen fehlen in der Map, unauflösbare Namen stehen als `null` darin.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/service/FieldInstanceLabelResolverTest.java`:

```java
package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FieldInstanceLabelResolverTest {

    @Inject
    FieldInstanceLabelResolver resolver;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    ObjectId definitionId;

    @BeforeEach
    void setUp() {
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();

        FieldDefinition def = new FieldDefinition();
        def.fieldName = "group";
        def.label = Map.of("de", "Gruppen");
        def.createdAt = Instant.now();
        def.persist();
        definitionId = def.id;
    }

    private ObjectId persistInstance(Object value) {
        ObjectId id = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", id)
                        .append("definitionId", definitionId)
                        .append("value", value));
        return id;
    }

    @Test
    void objectValueUsesItsLabel() {
        ObjectId id = persistInstance(new Document("label", "Kaefergruppe").append("color", "#f00"));

        assertEquals("Kaefergruppe", resolver.resolveLabels(List.of(id)).get(id));
    }

    @Test
    void stringValueIsUsedDirectly() {
        ObjectId id = persistInstance("Bienengruppe");

        assertEquals("Bienengruppe", resolver.resolveLabels(List.of(id)).get(id));
    }

    @Test
    void valueWithoutLabelFallsBackToDefinitionLabel() {
        ObjectId id = persistInstance(true);

        assertEquals("Gruppen", resolver.resolveLabels(List.of(id)).get(id));
    }

    @Test
    void missingInstanceIsAbsentFromResult() {
        assertTrue(resolver.resolveLabels(List.of(new ObjectId())).isEmpty());
    }

    @Test
    void blankLabelAndBlankFieldNameYieldNull() {
        FieldDefinition nameless = new FieldDefinition();
        nameless.fieldName = "   ";
        nameless.createdAt = Instant.now();
        nameless.persist();

        ObjectId id = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", id)
                        .append("definitionId", nameless.id)
                        .append("value", new Document("color", "#f00")));

        assertNull(resolver.resolveLabels(List.of(id)).get(id));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -Dtest=FieldInstanceLabelResolverTest test`
Expected: Kompilierfehler — `FieldInstanceLabelResolver` existiert nicht.

- [ ] **Step 3: Write the resolver**

`backend/src/main/java/at/kigruapp/service/FieldInstanceLabelResolver.java`:

```java
package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Loest Anzeigenamen von field_instances in konstant zwei Abfragen auf:
 * value.label, sonst der skalare Wert, sonst das deutsche Label bzw. der
 * fieldName der zugehoerigen FieldDefinition. Geloeschte Instanzen fehlen im
 * Ergebnis, unaufloesbare Namen stehen als null darin.
 */
@ApplicationScoped
public class FieldInstanceLabelResolver {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public Map<ObjectId, String> resolveLabels(Collection<ObjectId> instanceIds) {
        Map<ObjectId, String> labels = new LinkedHashMap<>();
        if (instanceIds == null || instanceIds.isEmpty()) return labels;

        MongoCollection<Document> instances = mongoClient.getDatabase(databaseName)
                .getCollection("field_instances");

        Map<ObjectId, ObjectId> definitionByInstance = new LinkedHashMap<>();
        for (Document instance : instances.find(Filters.in("_id", new LinkedHashSet<>(instanceIds)))) {
            ObjectId instanceId = instance.getObjectId("_id");
            labels.put(instanceId, labelFromValue(instance.get("value")));
            ObjectId definitionId = instance.getObjectId("definitionId");
            if (definitionId != null) {
                definitionByInstance.put(instanceId, definitionId);
            }
        }

        Set<ObjectId> missing = new LinkedHashSet<>();
        for (Map.Entry<ObjectId, ObjectId> entry : definitionByInstance.entrySet()) {
            if (labels.get(entry.getKey()) == null) {
                missing.add(entry.getValue());
            }
        }
        if (missing.isEmpty()) return labels;

        Map<ObjectId, String> definitionNames = new LinkedHashMap<>();
        for (FieldDefinition def : FieldDefinition.<FieldDefinition>list("_id in ?1", new ArrayList<>(missing))) {
            String label = def.label != null ? trimToNull(def.label.get("de")) : null;
            definitionNames.put(def.id, label != null ? label : trimToNull(def.fieldName));
        }
        for (Map.Entry<ObjectId, ObjectId> entry : definitionByInstance.entrySet()) {
            if (labels.get(entry.getKey()) == null) {
                labels.put(entry.getKey(), definitionNames.get(entry.getValue()));
            }
        }
        return labels;
    }

    private String labelFromValue(Object value) {
        if (value instanceof Document valueDoc) {
            return trimToNull(valueDoc.getString("label"));
        }
        if (value instanceof String stringValue) {
            return trimToNull(stringValue);
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -Dtest=FieldInstanceLabelResolverTest test`
Expected: PASS, 5 Tests.

- [ ] **Step 5: ParentDirectoryService auf den Resolver umstellen**

In `ParentDirectoryService`: Feld ergänzen

```java
    @Inject
    FieldInstanceLabelResolver labelResolver;
```

Den Aufruf in `buildForFamily` ersetzen:

```java
        Map<ObjectId, String> groupNames = labelResolver.resolveLabels(ownGroupIds);
```

Die Methoden `resolveGroupNames` und `labelFromValue` ersatzlos löschen, ebenso den Import von `at.kigruapp.entity.FieldDefinition`, falls sonst ungenutzt. `trimToNull` bleibt — `formatAddress` braucht es.

- [ ] **Step 6: HourEntryResource auf den Resolver umstellen**

Feld ergänzen:

```java
    @Inject
    at.kigruapp.service.FieldInstanceLabelResolver labelResolver;
```

Den Block bei `HourEntryResource.java:478-484` ersetzen:

```java
        // 2. Eine Batch-Query fuer alle field_instances -> Map id -> label.
        Map<ObjectId, String> labelById = labelResolver.resolveLabels(instanceIds);
```

`opt.label` darf nicht `null` werden — Zeile 494 anpassen:

```java
            String resolved = instId == null ? null : labelById.get(instId);
            opt.label = resolved != null ? resolved : "";
```

Die Methode `labelFromValue` in `HourEntryResource` löschen.

- [ ] **Step 7: Betroffene Tests laufen lassen**

Run: `cd backend && mvn "-Dtest=FieldInstanceLabelResolverTest+ParentDirectoryServiceTest+ParentDirectoryResourceTest+HourEntryResourceTest" test`
Expected: PASS. `ParentDirectoryServiceTest` enthält bereits `groupNameComesFromValueLabelWhenValueIsAnObject` und `groupNameFallsBackToDefinitionLabelWhenValueCarriesNoLabel` — beide müssen grün bleiben.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/FieldInstanceLabelResolver.java \
        backend/src/test/java/at/kigruapp/service/FieldInstanceLabelResolverTest.java \
        backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java \
        backend/src/main/java/at/kigruapp/resource/HourEntryResource.java
git commit -m "refactor(be): Label-Aufloesung von field_instances in einen Service ziehen"
```

---

### Task 2: Einstellungen und Attributkatalog

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/ParentDirectorySettings.java`
- Create: `backend/src/main/java/at/kigruapp/service/ParentDirectoryAttributeService.java`
- Create: `backend/src/test/java/at/kigruapp/service/ParentDirectoryAttributeServiceTest.java`

**Interfaces:**
- Consumes: nichts aus Task 1
- Produces:
  - `ParentDirectoryAttributeService.CatalogEntry` — Record mit `String key`, `String label`, `String scope`
  - `List<CatalogEntry> catalog()` — Kernliste plus `custom:<definitionId>`-Einträge, in stabiler Reihenfolge
  - `Set<String> visibleKeys()` — gespeicherte Auswahl oder Vorgabe, immer inklusive `childName`
  - `List<CatalogEntry> visibleCatalog()` — `catalog()` gefiltert auf `visibleKeys()`, Reihenfolge des Katalogs
  - `void save(List<String> keys)` — wirft `IllegalArgumentException` bei unbekanntem Schlüssel
  - Konstanten `CHILD_NAME`, `CHILD_ENTRY_DATE`, `CHILD_EXIT_DATE`, `TEAM`, `ROLE`, `ADDRESS`, `CUSTOM_PREFIX`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/service/ParentDirectoryAttributeServiceTest.java`:

```java
package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.ParentDirectorySettings;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ParentDirectoryAttributeServiceTest {

    @Inject
    ParentDirectoryAttributeService service;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void setUp() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        ParentDirectorySettings.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
    }

    private FieldDefinition persistDefinition(String fieldName, String labelDe) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        if (labelDe != null) def.label = Map.of("de", labelDe);
        def.createdAt = Instant.now();
        def.persist();
        return def;
    }

    private void persistPersonWithCustomField(ObjectId definitionId) {
        ObjectId instanceId = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", instanceId)
                        .append("definitionId", definitionId)
                        .append("value", "ja"));
        Person p = new Person();
        p.customProperties.add(new FieldRef(definitionId, instanceId));
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
    }

    @Test
    void catalogContainsCoreAttributesInFixedOrder() {
        List<String> keys = service.catalog().stream().map(ParentDirectoryAttributeService.CatalogEntry::key).toList();

        assertEquals(List.of("childName", "childEntryDate", "childExitDate",
                "firstName", "lastName", "email", "phone", "team", "role", "address"), keys);
    }

    @Test
    void catalogContainsCustomFieldsUsedByPersons() {
        FieldDefinition allergies = persistDefinition("allergies", "Allergien");
        persistPersonWithCustomField(allergies.id);

        var entry = service.catalog().stream()
                .filter(e -> e.key().equals("custom:" + allergies.id.toHexString()))
                .findFirst().orElseThrow();

        assertEquals("Allergien", entry.label());
        assertEquals("PARENT", entry.scope());
    }

    @Test
    void catalogIgnoresDefinitionsNoPersonUses() {
        persistDefinition("group", "Gruppen");

        assertTrue(service.catalog().stream().noneMatch(e -> e.key().startsWith("custom:")));
    }

    @Test
    void defaultsApplyWhenNothingWasSaved() {
        assertEquals(Set.of("childName", "firstName", "lastName", "email", "phone", "address"),
                service.visibleKeys());
    }

    @Test
    void savedSelectionIsReturnedAndChildNameIsForced() {
        service.save(List.of("firstName", "team"));

        assertEquals(Set.of("childName", "firstName", "team"), service.visibleKeys());
    }

    @Test
    void savingTwiceReplacesTheSelectionInsteadOfAppending() {
        service.save(List.of("firstName", "team"));
        service.save(List.of("email"));

        assertEquals(Set.of("childName", "email"), service.visibleKeys());
        assertEquals(1, ParentDirectorySettings.count());
    }

    @Test
    void unknownKeyIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(List.of("firstName", "salary")));

        assertTrue(ex.getMessage().contains("salary"));
    }

    @Test
    void visibleCatalogKeepsCatalogOrderAndDropsUnselected() {
        service.save(List.of("email", "childName", "firstName"));

        assertEquals(List.of("childName", "firstName", "email"),
                service.visibleCatalog().stream()
                        .map(ParentDirectoryAttributeService.CatalogEntry::key).toList());
        assertFalse(service.visibleKeys().contains("address"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -Dtest=ParentDirectoryAttributeServiceTest test`
Expected: Kompilierfehler — `ParentDirectorySettings` und `ParentDirectoryAttributeService` existieren nicht.

- [ ] **Step 3: Entity anlegen**

`backend/src/main/java/at/kigruapp/entity/ParentDirectorySettings.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Genau ein Dokument: welche Attribute die Eltern-Uebersicht zeigt. Gilt global,
 * unabhaengig von Gruppe und Semester.
 */
@MongoEntity(collection = "parent_directory_settings")
public class ParentDirectorySettings extends PanacheMongoEntity {
    public List<String> visibleAttributes = new ArrayList<>();

    public static ParentDirectorySettings findSingleton() {
        return findAll().firstResult();
    }
}
```

- [ ] **Step 4: Service anlegen**

`backend/src/main/java/at/kigruapp/service/ParentDirectoryAttributeService.java`:

```java
package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.ParentDirectorySettings;
import com.mongodb.client.MongoClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Baut den Katalog waehlbarer Attribute der Eltern-Uebersicht und haelt die
 * global gueltige Auswahl. Der Katalog besteht aus einer festen Kernliste und
 * den benutzerdefinierten Feldern, die an mindestens einer Person gepflegt sind
 * — FieldDefinition kennt keine Entitaets-Zuordnung, aus der man das sonst
 * ableiten koennte.
 */
@ApplicationScoped
public class ParentDirectoryAttributeService {

    public static final String CHILD_NAME = "childName";
    public static final String CHILD_ENTRY_DATE = "childEntryDate";
    public static final String CHILD_EXIT_DATE = "childExitDate";
    public static final String FIRST_NAME = "firstName";
    public static final String LAST_NAME = "lastName";
    public static final String EMAIL = "email";
    public static final String PHONE = "phone";
    public static final String TEAM = "team";
    public static final String ROLE = "role";
    public static final String ADDRESS = "address";
    public static final String CUSTOM_PREFIX = "custom:";

    public static final String SCOPE_CHILD = "CHILD";
    public static final String SCOPE_PARENT = "PARENT";
    public static final String SCOPE_FAMILY = "FAMILY";

    private static final List<CatalogEntry> CORE = List.of(
            new CatalogEntry(CHILD_NAME, "Vorname", SCOPE_CHILD),
            new CatalogEntry(CHILD_ENTRY_DATE, "Eintritt", SCOPE_CHILD),
            new CatalogEntry(CHILD_EXIT_DATE, "Austritt", SCOPE_CHILD),
            new CatalogEntry(FIRST_NAME, "Vorname", SCOPE_PARENT),
            new CatalogEntry(LAST_NAME, "Nachname", SCOPE_PARENT),
            new CatalogEntry(EMAIL, "E-Mail", SCOPE_PARENT),
            new CatalogEntry(PHONE, "Telefon", SCOPE_PARENT),
            new CatalogEntry(TEAM, "Team", SCOPE_PARENT),
            new CatalogEntry(ROLE, "Rolle", SCOPE_PARENT),
            new CatalogEntry(ADDRESS, "Adresse", SCOPE_FAMILY));

    private static final Set<String> DEFAULTS = Set.of(
            CHILD_NAME, FIRST_NAME, LAST_NAME, EMAIL, PHONE, ADDRESS);

    public record CatalogEntry(String key, String label, String scope) {}

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public List<CatalogEntry> catalog() {
        List<CatalogEntry> entries = new ArrayList<>(CORE);
        for (ObjectId definitionId : customDefinitionIds()) {
            FieldDefinition def = FieldDefinition.findById(definitionId);
            if (def == null) continue;
            String label = def.label != null ? def.label.get("de") : null;
            if (label == null || label.isBlank()) label = def.fieldName;
            entries.add(new CatalogEntry(CUSTOM_PREFIX + definitionId.toHexString(), label, SCOPE_PARENT));
        }
        return entries;
    }

    /** definitionIds, die an mindestens einer Person unter customProperties haengen. */
    public Set<ObjectId> customDefinitionIds() {
        Set<ObjectId> ids = new LinkedHashSet<>();
        mongoClient.getDatabase(databaseName).getCollection("persons")
                .distinct("customProperties.definitionId", ObjectId.class)
                .forEach(ids::add);
        return ids;
    }

    public Set<String> visibleKeys() {
        ParentDirectorySettings settings = ParentDirectorySettings.findSingleton();
        if (settings == null || settings.visibleAttributes == null || settings.visibleAttributes.isEmpty()) {
            return DEFAULTS;
        }
        Set<String> keys = new LinkedHashSet<>(settings.visibleAttributes);
        keys.add(CHILD_NAME);
        return keys;
    }

    public List<CatalogEntry> visibleCatalog() {
        Set<String> visible = visibleKeys();
        return catalog().stream().filter(e -> visible.contains(e.key())).collect(Collectors.toList());
    }

    public void save(List<String> keys) {
        Set<String> known = catalog().stream().map(CatalogEntry::key).collect(Collectors.toSet());
        List<String> unknown = (keys == null ? List.<String>of() : keys).stream()
                .filter(k -> !known.contains(k)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unbekannte Attribute: " + String.join(", ", unknown));
        }

        Set<String> selection = new LinkedHashSet<>(keys == null ? List.of() : keys);
        selection.add(CHILD_NAME);

        ParentDirectorySettings settings = ParentDirectorySettings.findSingleton();
        if (settings == null) {
            settings = new ParentDirectorySettings();
            settings.visibleAttributes = new ArrayList<>(selection);
            settings.persist();
        } else {
            settings.visibleAttributes = new ArrayList<>(selection);
            settings.update();
        }
    }

    /** Hilfsmittel fuer den Aufrufer: Map Label je Katalog-Schluessel. */
    public Map<String, String> labelsByKey() {
        return catalog().stream().collect(Collectors.toMap(CatalogEntry::key, CatalogEntry::label, (a, b) -> a));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && mvn -Dtest=ParentDirectoryAttributeServiceTest test`
Expected: PASS, 8 Tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/ParentDirectorySettings.java \
        backend/src/main/java/at/kigruapp/service/ParentDirectoryAttributeService.java \
        backend/src/test/java/at/kigruapp/service/ParentDirectoryAttributeServiceTest.java
git commit -m "feat(be): Katalog und Speicherung der sichtbaren Eltern-Attribute"
```

---

### Task 3: Admin-Endpunkte für den Katalog

**Files:**
- Create: `backend/src/main/java/at/kigruapp/dto/ParentDirectoryAttributeDTO.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/ParentDirectoryResource.java`
- Create: `backend/src/test/java/at/kigruapp/resource/ParentDirectoryAttributeResourceTest.java`

**Interfaces:**
- Consumes: `ParentDirectoryAttributeService.catalog()`, `visibleKeys()`, `save(List<String>)`, Konstante `CHILD_NAME`
- Produces:
  - `GET /api/v1/parent-directory/attributes` → `{ "attributes": [{ key, label, scope, selected, locked }] }`
  - `PUT /api/v1/parent-directory/attributes` mit `{ "visibleAttributes": [...] }` → 204, bei unbekanntem Schlüssel 400 mit Klartext

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/resource/ParentDirectoryAttributeResourceTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.ParentDirectorySettings;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class ParentDirectoryAttributeResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    ObjectId roleDefId;

    @BeforeEach
    void setUp() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        ParentDirectorySettings.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();

        FieldDefinition roleDef = new FieldDefinition();
        roleDef.fieldName = "role";
        roleDef.createdAt = Instant.now();
        roleDef.persist();
        roleDefId = roleDef.id;
    }

    private ObjectId persistInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", id)
                        .append("definitionId", definitionId)
                        .append("value", value));
        return id;
    }

    /** Erste persistierte Person ist im Dev-Modus der aktuelle Benutzer. */
    private void persistCurrentUser(boolean admin) {
        Person p = new Person();
        p.familyId = new ObjectId();
        if (admin) {
            p.roles.add(new FieldRef(roleDefId, persistInstance(roleDefId, "ADMIN")));
        }
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
    }

    @Test
    void adminSeesCatalogWithDefaultSelection() {
        persistCurrentUser(true);

        given().when().get("/api/v1/parent-directory/attributes")
            .then().statusCode(200)
            .body("attributes.key", hasItem("childName"))
            .body("attributes.find { it.key == 'childName' }.locked", is(true))
            .body("attributes.find { it.key == 'childName' }.selected", is(true))
            .body("attributes.find { it.key == 'email' }.selected", is(true))
            .body("attributes.find { it.key == 'team' }.selected", is(false))
            .body("attributes.find { it.key == 'team' }.scope", is("PARENT"));
    }

    @Test
    void adminSavesSelectionAndReadsItBack() {
        persistCurrentUser(true);

        given().contentType("application/json")
            .body(Map.of("visibleAttributes", List.of("firstName", "team")))
            .when().put("/api/v1/parent-directory/attributes")
            .then().statusCode(204);

        given().when().get("/api/v1/parent-directory/attributes")
            .then().statusCode(200)
            .body("attributes.find { it.key == 'team' }.selected", is(true))
            .body("attributes.find { it.key == 'childName' }.selected", is(true))
            .body("attributes.find { it.key == 'address' }.selected", is(false));
    }

    @Test
    void unknownKeyIsRejectedWithReason() {
        persistCurrentUser(true);

        given().contentType("application/json")
            .body(Map.of("visibleAttributes", List.of("salary")))
            .when().put("/api/v1/parent-directory/attributes")
            .then().statusCode(400)
            .body(org.hamcrest.Matchers.containsString("salary"));
    }

    @Test
    void nonAdminIsDeniedOnBothEndpoints() {
        persistCurrentUser(false);

        given().when().get("/api/v1/parent-directory/attributes")
            .then().statusCode(not(is(200)));

        given().contentType("application/json")
            .body(Map.of("visibleAttributes", List.of("firstName")))
            .when().put("/api/v1/parent-directory/attributes")
            .then().statusCode(not(is(204)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -Dtest=ParentDirectoryAttributeResourceTest test`
Expected: FAIL — 404 statt 200, die Endpunkte existieren nicht.

- [ ] **Step 3: DTOs anlegen**

`backend/src/main/java/at/kigruapp/dto/ParentDirectoryAttributeDTO.java`:

```java
package at.kigruapp.dto;

import java.util.List;

/** Katalog der waehlbaren Attribute der Eltern-Uebersicht (Admin-Ansicht). */
public record ParentDirectoryAttributeDTO(
        String key, String label, String scope, boolean selected, boolean locked) {

    public record Catalog(List<ParentDirectoryAttributeDTO> attributes) {}

    public record VisibleAttributesRequest(List<String> visibleAttributes) {}
}
```

- [ ] **Step 4: Endpunkte ergänzen**

In `ParentDirectoryResource` die Importe ergänzen:

```java
import at.kigruapp.dto.ParentDirectoryAttributeDTO;
import at.kigruapp.service.ParentDirectoryAttributeService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.core.Response;
import java.util.List;
```

Feld und Methoden ergänzen:

```java
    @Inject
    ParentDirectoryAttributeService attributeService;

    /**
     * Admin-pflichtig durch den Standard des SecurityFilters: nur
     * /api/v1/parent-directory selbst ist fuer Eltern whitelisted.
     */
    @GET
    @Path("/attributes")
    public ParentDirectoryAttributeDTO.Catalog attributes() {
        var visible = attributeService.visibleKeys();
        List<ParentDirectoryAttributeDTO> attributes = attributeService.catalog().stream()
                .map(entry -> new ParentDirectoryAttributeDTO(
                        entry.key(), entry.label(), entry.scope(),
                        visible.contains(entry.key()),
                        ParentDirectoryAttributeService.CHILD_NAME.equals(entry.key())))
                .toList();
        return new ParentDirectoryAttributeDTO.Catalog(attributes);
    }

    @PUT
    @Path("/attributes")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveAttributes(ParentDirectoryAttributeDTO.VisibleAttributesRequest request) {
        try {
            attributeService.save(request == null ? List.of() : request.visibleAttributes());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    Response.status(400).entity(e.getMessage()).type(MediaType.TEXT_PLAIN).build());
        }
        return Response.noContent().build();
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && mvn -Dtest=ParentDirectoryAttributeResourceTest test`
Expected: PASS, 4 Tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/ParentDirectoryAttributeDTO.java \
        backend/src/main/java/at/kigruapp/resource/ParentDirectoryResource.java \
        backend/src/test/java/at/kigruapp/resource/ParentDirectoryAttributeResourceTest.java
git commit -m "feat(be): Admin-Endpunkte fuer sichtbare Eltern-Attribute"
```

---

### Task 4: DTO-Umbau und Sichtbarkeitsfilter

Ab hier liefert `GET /api/v1/parent-directory` Spalten und `values`-Maps. Kind-Daten, Team und Rolle folgen in Task 5 und 6; hier werden `firstName`, `lastName`, `email`, `phone`, `address` und Custom Fields umgestellt.

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/dto/ParentDirectoryDTO.java`
- Modify: `backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java`
- Modify: `backend/src/main/java/at/kigruapp/service/PersonPropertyResolver.java`
- Modify: `backend/src/test/java/at/kigruapp/service/ParentDirectoryServiceTest.java`
- Modify: `backend/src/test/java/at/kigruapp/resource/ParentDirectoryResourceTest.java`

**Interfaces:**
- Consumes: `ParentDirectoryAttributeService.visibleKeys()`, `visibleCatalog()`, `customDefinitionIds()`, `CatalogEntry`
- Produces:
  - `ParentDirectoryDTO(String semesterId, List<ColumnEntry> columns, List<GroupEntry> groups)`
  - `ColumnEntry(String key, String label, String scope)`
  - `FamilyEntry(String familyId, boolean isOwnFamily, List<ChildEntry> children, List<ParentEntry> parents, String address)`
  - `ChildEntry(String name, String entryDate, String exitDate)`
  - `ParentEntry(Map<String, String> values)`
  - `PersonPropertyResolver.resolveCustom(List<Person> persons, Set<ObjectId> definitionIds)` → `Map<ObjectId, Map<String, String>>`, äußerer Schlüssel Person-Id, innerer Schlüssel `custom:<definitionIdHex>`

- [ ] **Step 1: Write the failing test**

In `ParentDirectoryServiceTest` diese Tests ergänzen (die bestehenden Tests werden in Step 5 angepasst):

```java
    @Test
    void onlyVisibleAttributesAreDelivered() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        persistPerson(ownFamily, "PARENT", "Anna", "Muster", "anna@x.at", "0660 111");

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        attributeService.save(List.of("firstName"));

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        Map<String, String> values = result.groups().get(0).families().get(0).parents().get(0).values();
        assertEquals(Map.of("firstName", "Anna"), values);
        assertNull(result.groups().get(0).families().get(0).address());
        assertEquals(List.of("childName", "firstName"),
                result.columns().stream().map(ParentDirectoryDTO.ColumnEntry::key).toList());
    }

    @Test
    void addressIsDeliveredOnlyWhenSelected() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        attributeService.save(List.of("address"));

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals("Hauptstrasse 1, 1010 Wien", result.groups().get(0).families().get(0).address());
    }

    @Test
    void customFieldOfParentIsDeliveredWhenSelected() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        Person parent = persistPerson(ownFamily, "PARENT", "Anna", "Muster", null, null);

        FieldDefinition allergies = new FieldDefinition();
        allergies.fieldName = "allergies";
        allergies.label = Map.of("de", "Allergien");
        allergies.createdAt = Instant.now();
        allergies.persist();

        parent.customProperties.add(new FieldRef(allergies.id, persistInstance(allergies.id, "Nuesse")));
        parent.update();

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        String key = "custom:" + allergies.id.toHexString();
        attributeService.save(List.of(key));

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals("Nuesse",
                result.groups().get(0).families().get(0).parents().get(0).values().get(key));
    }
```

Dazu im Test-Setup ergänzen:

```java
    @Inject
    ParentDirectoryAttributeService attributeService;
```

und in `setUp()` nach den übrigen `deleteAll()`-Aufrufen:

```java
        ParentDirectorySettings.deleteAll();
```

Nötige Importe: `at.kigruapp.entity.ParentDirectorySettings`, `at.kigruapp.entity.FieldRef`, `at.kigruapp.service.ParentDirectoryAttributeService`, `java.util.Map`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -Dtest=ParentDirectoryServiceTest test`
Expected: Kompilierfehler — `ParentEntry.values()` und `ParentDirectoryDTO.columns()` existieren nicht.

- [ ] **Step 3: DTO umbauen**

`backend/src/main/java/at/kigruapp/dto/ParentDirectoryDTO.java` vollständig ersetzen:

```java
package at.kigruapp.dto;

import java.util.List;
import java.util.Map;

/**
 * Antwort von GET /api/v1/parent-directory: alle Gruppen der eigenen Kinder im
 * laufenden Semester, je Gruppe die dort vertretenen Familien. Enthält bewusst
 * keine Personen-IDs — der Client soll damit nichts nachladen können.
 *
 * Welche Werte enthalten sind, entscheidet die globale Attribut-Auswahl:
 * abgewählte Attribute fehlen in {@code columns} und in {@code values}, statt
 * leer geliefert zu werden.
 */
public record ParentDirectoryDTO(String semesterId, List<ColumnEntry> columns, List<GroupEntry> groups) {

    public record ColumnEntry(String key, String label, String scope) {}

    public record GroupEntry(String groupInstanceId, String groupName, List<FamilyEntry> families) {}

    public record FamilyEntry(
            String familyId,
            boolean isOwnFamily,
            List<ChildEntry> children,
            List<ParentEntry> parents,
            String address) {}

    public record ChildEntry(String name, String entryDate, String exitDate) {}

    public record ParentEntry(Map<String, String> values) {}
}
```

- [ ] **Step 4: PersonPropertyResolver um Custom Fields erweitern**

In `PersonPropertyResolver` die Methode ergänzen (die bestehende `resolve` bleibt unverändert):

```java
    /**
     * Wie {@link #resolve}, aber fuer benutzerdefinierte Felder: liefert je Person
     * eine Map "custom:<definitionIdHex>" -> Wert, beschraenkt auf die uebergebenen
     * Definitionen. Zwei Abfragen unabhaengig von der Personenzahl.
     */
    public Map<ObjectId, Map<String, String>> resolveCustom(List<Person> persons, Set<ObjectId> definitionIds) {
        Map<ObjectId, Map<String, String>> result = new HashMap<>();
        if (persons == null || persons.isEmpty() || definitionIds == null || definitionIds.isEmpty()) {
            return result;
        }

        Map<ObjectId, Map<ObjectId, String>> perPersonInstanceToKey = new HashMap<>();
        Set<ObjectId> allInstanceIds = new HashSet<>();
        for (Person person : persons) {
            Map<ObjectId, String> instanceToKey = new HashMap<>();
            if (person.customProperties != null) {
                for (FieldRef ref : person.customProperties) {
                    if (definitionIds.contains(ref.definitionId)) {
                        instanceToKey.put(ref.fieldInstanceId, "custom:" + ref.definitionId.toHexString());
                        allInstanceIds.add(ref.fieldInstanceId);
                    }
                }
            }
            perPersonInstanceToKey.put(person.id, instanceToKey);
        }

        Map<ObjectId, String> instanceIdToValue = new HashMap<>();
        if (!allInstanceIds.isEmpty()) {
            MongoCollection<Document> col = mongoClient.getDatabase(databaseName).getCollection("field_instances");
            for (Document doc : col.find(Filters.in("_id", allInstanceIds))) {
                Object value = doc.get("value");
                if (value != null) {
                    instanceIdToValue.put(doc.getObjectId("_id"), value.toString());
                }
            }
        }

        for (Person person : persons) {
            Map<String, String> properties = new HashMap<>();
            for (Map.Entry<ObjectId, String> e :
                    perPersonInstanceToKey.getOrDefault(person.id, Map.of()).entrySet()) {
                String value = instanceIdToValue.get(e.getKey());
                if (value != null) {
                    properties.put(e.getValue(), value);
                }
            }
            result.put(person.id, properties);
        }
        return result;
    }
```

- [ ] **Step 5: ParentDirectoryService umbauen**

Feld ergänzen:

```java
    @Inject
    ParentDirectoryAttributeService attributeService;
```

In `buildForFamily` zu Beginn die Sichtbarkeit bestimmen und in die drei frühen Rückgaben einsetzen — jede der drei `return new ParentDirectoryDTO(...)`-Zeilen bekommt die Spaltenliste als zweites Argument:

```java
        List<ParentDirectoryDTO.ColumnEntry> columns = attributeService.visibleCatalog().stream()
                .map(e -> new ParentDirectoryDTO.ColumnEntry(e.key(), e.label(), e.scope()))
                .toList();
        Set<String> visible = attributeService.visibleKeys();
```

Beispiel für die erste frühe Rückgabe:

```java
        if (ownFamilyId == null || semesterId == null) {
            return new ParentDirectoryDTO(
                    semesterId != null ? semesterId.toHexString() : null, columns, List.of());
        }
```

Custom-Definitionen einmalig laden, direkt vor der Auflösung der Eltern-Eigenschaften:

```java
        Set<ObjectId> selectedCustomDefinitionIds = attributeService.customDefinitionIds().stream()
                .filter(id -> visible.contains("custom:" + id.toHexString()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<ObjectId, Map<String, String>> customProperties =
                personPropertyResolver.resolveCustom(allParents, selectedCustomDefinitionIds);
```

Den Aufbau der `ParentEntry`-Liste ersetzen:

```java
                List<ParentDirectoryDTO.ParentEntry> parents = new ArrayList<>();
                for (Person parent : parentsByFamily.getOrDefault(familyId, List.of())) {
                    Map<String, String> props = parentProperties.getOrDefault(parent.id, Map.of());
                    Map<String, String> values = new LinkedHashMap<>();
                    putIfVisible(values, visible, ParentDirectoryAttributeService.FIRST_NAME, props.get("firstName"));
                    putIfVisible(values, visible, ParentDirectoryAttributeService.LAST_NAME, props.get("lastName"));
                    putIfVisible(values, visible, ParentDirectoryAttributeService.EMAIL, props.get("email"));
                    putIfVisible(values, visible, ParentDirectoryAttributeService.PHONE, props.get("phone"));
                    for (Map.Entry<String, String> custom :
                            customProperties.getOrDefault(parent.id, Map.of()).entrySet()) {
                        putIfVisible(values, visible, custom.getKey(), custom.getValue());
                    }
                    parents.add(new ParentDirectoryDTO.ParentEntry(values));
                }
```

Kindernamen als `ChildEntry` aufbauen — `childNamesByFamily` wird zu `childEntriesByFamily`:

```java
            Map<ObjectId, List<ParentDirectoryDTO.ChildEntry>> childEntriesByFamily = new LinkedHashMap<>();
            for (ObjectId childId : entry.getValue()) {
                Person child = childrenById.get(childId);
                if (child == null || child.familyId == null) continue;
                String name = childProperties.getOrDefault(child.id, Map.of()).get("firstName");
                childEntriesByFamily.computeIfAbsent(child.familyId, k -> new ArrayList<>())
                        .add(new ParentDirectoryDTO.ChildEntry(name, null, null));
            }
```

Die anschließende Schleife über `childNamesByFamily` auf `childEntriesByFamily` umstellen; die Sortierung der Kinder wird zu:

```java
                List<ParentDirectoryDTO.ChildEntry> childEntries = new ArrayList<>(famEntry.getValue());
                childEntries.sort(Comparator.comparing(ParentDirectoryDTO.ChildEntry::name,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
```

Adresse nur bei Freigabe:

```java
                families.add(new ParentDirectoryDTO.FamilyEntry(
                        familyId.toHexString(),
                        familyId.equals(ownFamilyId),
                        childEntries,
                        parents,
                        visible.contains(ParentDirectoryAttributeService.ADDRESS)
                                ? formatAddress(familiesById.get(familyId))
                                : null));
```

Die Familien-Sortierung greift jetzt auf `name()` zu:

```java
            families.sort(Comparator
                    .comparing(ParentDirectoryDTO.FamilyEntry::isOwnFamily).reversed()
                    .thenComparing(
                            (ParentDirectoryDTO.FamilyEntry f) ->
                                    f.children().isEmpty() ? null : f.children().get(0).name(),
                            Comparator.nullsLast(Comparator.naturalOrder())));
```

Abschließende Rückgabe:

```java
        return new ParentDirectoryDTO(semesterId.toHexString(), columns, groups);
```

Kleine Hilfsmethode am Klassenende:

```java
    private void putIfVisible(Map<String, String> target, Set<String> visible, String key, String value) {
        if (value != null && visible.contains(key)) {
            target.put(key, value);
        }
    }
```

Importe ergänzen: `java.util.stream.Collectors` ist vorhanden; `java.util.LinkedHashSet` ebenfalls.

- [ ] **Step 6: Bestehende Tests an die neue Form anpassen**

In `ParentDirectoryServiceTest`: alle Zugriffe auf `parents().get(0).firstName()` etc. werden zu `parents().get(0).values().get("firstName")`. Zugriffe auf `children()` liefern jetzt `ChildEntry` — `children().get(0)` wird zu `children().get(0).name()`. In `groupsAreSortedByName` und `groupWithDeletedFieldInstanceRendersWithNullNameWithoutThrowing` ändert sich nichts.

In `ParentDirectoryResourceTest`: `.body("groups[0].families[0].children", contains("Lena"))` wird zu `.body("groups[0].families[0].children[0].name", is("Lena"))`; `.body("groups[0].families[1].parents[0].firstName", is("Clara"))` wird zu `.body("groups[0].families[1].parents[0].values.firstName", is("Clara"))`, analog für `email`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd backend && mvn "-Dtest=ParentDirectoryServiceTest+ParentDirectoryResourceTest+ParentDirectoryAttributeServiceTest" test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/ParentDirectoryDTO.java \
        backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java \
        backend/src/main/java/at/kigruapp/service/PersonPropertyResolver.java \
        backend/src/test/java/at/kigruapp/service/ParentDirectoryServiceTest.java \
        backend/src/test/java/at/kigruapp/resource/ParentDirectoryResourceTest.java
git commit -m "feat(be): Eltern-Uebersicht liefert nur freigegebene Attribute"
```

---

### Task 5: Ein- und Austrittsdatum des Kindes

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java`
- Modify: `backend/src/test/java/at/kigruapp/service/ParentDirectoryServiceTest.java`

**Interfaces:**
- Consumes: `ParentDirectoryDTO.ChildEntry` aus Task 4, Konstanten `CHILD_ENTRY_DATE`, `CHILD_EXIT_DATE`
- Produces: `ChildEntry.entryDate()` / `.exitDate()` gefüllt aus `semester_assignments`, `null` bei Abwahl

- [ ] **Step 1: Write the failing test**

In `ParentDirectoryServiceTest`:

```java
    @Test
    void childDatesComeFromTheGroupAssignment() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
                .insertOne(new Document("_id", new ObjectId())
                        .append("personId", ownChild.id)
                        .append("semesterId", semesterId)
                        .append("section", "group")
                        .append("definitionId", groupDefId)
                        .append("fieldInstanceId", kaefer)
                        .append("entryDate", "2026-09-01")
                        .append("exitDate", "2027-01-31"));

        attributeService.save(List.of("childEntryDate", "childExitDate"));

        ParentDirectoryDTO.ChildEntry child =
                service.buildForFamily(ownFamily).groups().get(0).families().get(0).children().get(0);

        assertEquals("2026-09-01", child.entryDate());
        assertEquals("2027-01-31", child.exitDate());
    }

    @Test
    void childDatesAreOmittedWhenNotSelected() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
                .insertOne(new Document("_id", new ObjectId())
                        .append("personId", ownChild.id)
                        .append("semesterId", semesterId)
                        .append("section", "group")
                        .append("definitionId", groupDefId)
                        .append("fieldInstanceId", kaefer)
                        .append("entryDate", "2026-09-01")
                        .append("exitDate", "2027-01-31"));

        attributeService.save(List.of("firstName"));

        ParentDirectoryDTO.ChildEntry child =
                service.buildForFamily(ownFamily).groups().get(0).families().get(0).children().get(0);

        assertNull(child.entryDate());
        assertNull(child.exitDate());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -Dtest=ParentDirectoryServiceTest test`
Expected: FAIL — `expected: <2026-09-01> but was: <null>`.

- [ ] **Step 3: Daten beim Einlesen der Zuweisungen mitnehmen**

In `buildForFamily`, in der Schleife über die Gruppen-Zuweisungen, zusätzlich zu `childIdsByGroup` eine Map der Datumsangaben aufbauen. Direkt vor der Schleife:

```java
        record ChildDates(String entryDate, String exitDate) {}
        Map<String, ChildDates> datesByChildAndGroup = new LinkedHashMap<>();
```

In der Schleife, nach dem bestehenden `allChildIds.add(sa.personId);`:

```java
            datesByChildAndGroup.put(
                    sa.personId.toHexString() + "/" + sa.fieldInstanceId.toHexString(),
                    new ChildDates(doc.getString("entryDate"), doc.getString("exitDate")));
```

Beim Aufbau der `ChildEntry`-Liste die Werte einsetzen:

```java
            boolean showEntry = visible.contains(ParentDirectoryAttributeService.CHILD_ENTRY_DATE);
            boolean showExit = visible.contains(ParentDirectoryAttributeService.CHILD_EXIT_DATE);
            for (ObjectId childId : entry.getValue()) {
                Person child = childrenById.get(childId);
                if (child == null || child.familyId == null) continue;
                String name = childProperties.getOrDefault(child.id, Map.of()).get("firstName");
                ChildDates dates = datesByChildAndGroup.get(
                        childId.toHexString() + "/" + groupInstanceId.toHexString());
                childEntriesByFamily.computeIfAbsent(child.familyId, k -> new ArrayList<>())
                        .add(new ParentDirectoryDTO.ChildEntry(
                                name,
                                showEntry && dates != null ? dates.entryDate() : null,
                                showExit && dates != null ? dates.exitDate() : null));
            }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -Dtest=ParentDirectoryServiceTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java \
        backend/src/test/java/at/kigruapp/service/ParentDirectoryServiceTest.java
git commit -m "feat(be): Ein- und Austrittsdatum des Kindes aus der Gruppen-Zuweisung"
```

---

### Task 6: Team und Rolle der Eltern

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java`
- Modify: `backend/src/test/java/at/kigruapp/service/ParentDirectoryServiceTest.java`

**Interfaces:**
- Consumes: `FieldInstanceLabelResolver.resolveLabels` aus Task 1, Konstanten `TEAM`, `ROLE`
- Produces: `values["team"]` / `values["role"]` je Elternteil; mehrere Zuweisungen werden mit `", "` verbunden

- [ ] **Step 1: Write the failing test**

In `ParentDirectoryServiceTest`:

```java
    @Test
    void teamAndRoleComeFromTheCurrentSemester() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        Person parent = persistPerson(ownFamily, "PARENT", "Anna", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        ObjectId teamDefId = persistDefinition("parent-team");
        ObjectId roleDefId = persistDefinition("parent-team-role");
        ObjectId gartenTeam = persistInstance(teamDefId, "Gartenteam");
        ObjectId kassier = persistInstance(roleDefId, "Kassier");

        assignSection(parent.id, "team", teamDefId, gartenTeam);
        assignSection(parent.id, "role", roleDefId, kassier);

        attributeService.save(List.of("team", "role"));

        Map<String, String> values = service.buildForFamily(ownFamily)
                .groups().get(0).families().get(0).parents().get(0).values();

        assertEquals("Gartenteam", values.get("team"));
        assertEquals("Kassier", values.get("role"));
    }

    @Test
    void severalTeamsAreJoinedIntoOneValue() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        Person parent = persistPerson(ownFamily, "PARENT", "Anna", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        ObjectId teamDefId = persistDefinition("parent-team");
        assignSection(parent.id, "team", teamDefId, persistInstance(teamDefId, "Gartenteam"));
        assignSection(parent.id, "team", teamDefId, persistInstance(teamDefId, "Festteam"));

        attributeService.save(List.of("team"));

        String team = service.buildForFamily(ownFamily)
                .groups().get(0).families().get(0).parents().get(0).values().get("team");

        assertEquals("Gartenteam, Festteam", team);
    }
```

Hilfsmethode im Test ergänzen:

```java
    private void assignSection(ObjectId personId, String section, ObjectId definitionId, ObjectId instanceId) {
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
                .insertOne(new Document("_id", new ObjectId())
                        .append("personId", personId)
                        .append("semesterId", semesterId)
                        .append("section", section)
                        .append("definitionId", definitionId)
                        .append("fieldInstanceId", instanceId));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -Dtest=ParentDirectoryServiceTest test`
Expected: FAIL — `expected: <Gartenteam> but was: <null>`.

- [ ] **Step 3: Auflösung ergänzen**

Neue Methode in `ParentDirectoryService`:

```java
    /**
     * Team- bzw. Rollenzuweisungen der Eltern im laufenden Semester, als
     * Anzeigetext je Person. Mehrfachzuweisungen werden verbunden; die
     * Reihenfolge folgt der Reihenfolge in semester_assignments.
     */
    private Map<ObjectId, String> resolveSectionLabels(
            ObjectId semesterId, String section, Collection<ObjectId> personIds) {
        Map<ObjectId, List<ObjectId>> instancesByPerson = new LinkedHashMap<>();
        if (personIds.isEmpty()) return Map.of();

        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName)
                .getCollection("semester_assignments");
        Set<ObjectId> allInstanceIds = new LinkedHashSet<>();
        for (Document doc : collection.find(Filters.and(
                Filters.eq("section", section),
                Filters.eq("semesterId", semesterId),
                Filters.in("personId", personIds)))) {
            SemesterAssignment sa = SemesterAssignment.fromDocument(doc);
            if (sa.personId == null || sa.fieldInstanceId == null) continue;
            instancesByPerson.computeIfAbsent(sa.personId, k -> new ArrayList<>()).add(sa.fieldInstanceId);
            allInstanceIds.add(sa.fieldInstanceId);
        }
        if (allInstanceIds.isEmpty()) return Map.of();

        Map<ObjectId, String> labels = labelResolver.resolveLabels(allInstanceIds);
        Map<ObjectId, String> result = new LinkedHashMap<>();
        for (Map.Entry<ObjectId, List<ObjectId>> entry : instancesByPerson.entrySet()) {
            String joined = entry.getValue().stream()
                    .map(labels::get)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.joining(", "));
            if (!joined.isEmpty()) {
                result.put(entry.getKey(), joined);
            }
        }
        return result;
    }
```

In `buildForFamily`, nach dem Aufbau von `allParents`:

```java
        List<ObjectId> parentIdList = allParents.stream().map(p -> p.id).toList();
        Map<ObjectId, String> teamLabels = visible.contains(ParentDirectoryAttributeService.TEAM)
                ? resolveSectionLabels(semesterId, "team", parentIdList)
                : Map.of();
        Map<ObjectId, String> roleLabels = visible.contains(ParentDirectoryAttributeService.ROLE)
                ? resolveSectionLabels(semesterId, "role", parentIdList)
                : Map.of();
```

Im Aufbau der `values`-Map, nach den Custom Fields:

```java
                    putIfVisible(values, visible, ParentDirectoryAttributeService.TEAM, teamLabels.get(parent.id));
                    putIfVisible(values, visible, ParentDirectoryAttributeService.ROLE, roleLabels.get(parent.id));
```

Import ergänzen: `java.util.Collection`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -Dtest=ParentDirectoryServiceTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java \
        backend/src/test/java/at/kigruapp/service/ParentDirectoryServiceTest.java
git commit -m "feat(be): Team und Rolle der Eltern in der Uebersicht"
```

---

### Task 7: `entryDate` und `exitDate` als Personenfelder abschaffen

Beide Definitionen sind im Bestand bei keiner Person befüllt; Ein- und Austritt werden über `PersonService.setEnrollmentDates` in die Semester-Zuweisung geschrieben.

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/PersonPropertyResolver.java:32-34`
- Modify: `backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java:31`
- Modify: `backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java:90-98`
- Create: `backend/src/main/java/at/kigruapp/migration/PersonEnrollmentFieldRetirementMigration.java`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-token.util.ts:42-43`
- Modify: `backend/src/test/java/at/kigruapp/service/PersonPropertyResolverTest.java`

**Interfaces:**
- Consumes: nichts
- Produces: `PersonPropertyResolver.resolve` liefert die Schlüssel `entryDate`/`exitDate` nicht mehr

- [ ] **Step 1: Write the failing test**

In `backend/src/test/java/at/kigruapp/service/PersonPropertyResolverTest.java` ergänzen. Die Datei existiert bereits und bringt die Hilfsmethoden `persistDefinition(String)` — liefert die `FieldDefinition`, nicht deren Id — und `persistFieldInstance(ObjectId, String)` mit:

```java
    @Test
    void enrollmentDatesAreNoLongerPersonProperties() {
        FieldDefinition entryDef = persistDefinition("entryDate");

        Person person = new Person();
        person.basicProperties.add(new FieldRef(entryDef.id, persistFieldInstance(entryDef.id, "2026-09-01")));
        person.createdAt = java.time.Instant.now();
        person.updatedAt = person.createdAt;
        person.persist();

        Map<String, String> props = resolver.resolve(List.of(person)).getOrDefault(person.id, Map.of());

        assertFalse(props.containsKey("entryDate"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -Dtest=PersonPropertyResolverTest test`
Expected: FAIL — `entryDate` steht noch in der Map.

- [ ] **Step 3: Allowlists kürzen**

`PersonPropertyResolver`:

```java
    private static final Set<String> SCALAR_PERSON_FIELD_ALLOWLIST = Set.of(
            "firstName", "lastName", "email", "phone", "dateOfBirth", "gender", "notes"
    );
```

`MailTemplateResource:31` analog:

```java
            "firstName", "lastName", "email", "phone", "dateOfBirth", "gender", "notes"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn "-Dtest=PersonPropertyResolverTest+MailTemplateResourceTest" test`
Expected: PASS. `MailTemplateResourceTest` prüft die Platzhalterliste nicht namentlich auf `entryDate` (geprüft), muss also nicht angepasst werden.

- [ ] **Step 5: Seed-Migration kürzen**

In `FieldDefinitionSeedMigration` die beiden `seedDef`-Blöcke für `entryDate` und `exitDate` (Zeilen 90-98) löschen. `MIGRATION_ID` bleibt bei `seed-basic-property-definitions-v4` — die Kennung darf nicht hochgezählt werden, sonst legt die Migration auf Bestandsinstallationen erneut alle Felder an.

- [ ] **Step 6: Stilllegungs-Migration schreiben**

`backend/src/main/java/at/kigruapp/migration/PersonEnrollmentFieldRetirementMigration.java`:

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

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Ein- und Austrittsdatum sind Eigenschaften der Gruppen-Zuweisung, nicht der
 * Person. Die gleichnamigen Personen-Definitionen werden stillgelegt statt
 * geloescht, damit vorhandene FieldRef-Verweise nicht ins Leere zeigen.
 */
@ApplicationScoped
@Startup
public class PersonEnrollmentFieldRetirementMigration {

    private static final String MIGRATION_ID = "retire-person-enrollment-fields-v1";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        MongoDatabase db = mongoClient.getDatabase(databaseName);

        MongoCollection<Document> migrations = db.getCollection("migrations");
        if (migrations.find(new Document("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        Date now = Date.from(Instant.now());
        db.getCollection("field_definitions").updateMany(
                new Document("fieldName", new Document("$in", List.of("entryDate", "exitDate")))
                        .append("outdatedAt", null),
                new Document("$set", new Document("outdatedAt", now)));

        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", now));
    }
}
```

- [ ] **Step 7: Mail-Token im Frontend kürzen**

In `frontend/src/app/settings/mail/mail-template-editor/mail-token.util.ts` die beiden Zeilen `entryDate: '01.09.2023',` und `exitDate: '31.08.2025',` (Zeilen 42-43) entfernen. Wenn dieselbe Datei eine Liste wählbarer Token führt, dort ebenfalls streichen.

- [ ] **Step 8: Tests laufen lassen**

Run: `cd backend && mvn test 2>&1 | tail -40`
Expected: keine *neuen* Fehler gegenüber dem Ausgangszustand (12 vorbestehende).

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless "--include=**/mail/**/*.spec.ts"`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/PersonPropertyResolver.java \
        backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java \
        backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java \
        backend/src/main/java/at/kigruapp/migration/PersonEnrollmentFieldRetirementMigration.java \
        backend/src/test/java/at/kigruapp/service/PersonPropertyResolverTest.java \
        frontend/src/app/settings/mail/mail-template-editor/mail-token.util.ts
git commit -m "refactor(be): Ein- und Austrittsdatum sind keine Personenfelder mehr"
```

---

### Task 8: Admin-Sektion im Frontend

**Files:**
- Create: `frontend/src/app/settings/organisation/parent-directory-attributes/parent-directory-settings.service.ts`
- Create: `frontend/src/app/settings/organisation/parent-directory-attributes/parent-directory-attributes.component.ts`
- Create: `frontend/src/app/settings/organisation/parent-directory-attributes/parent-directory-attributes.component.html`
- Create: `frontend/src/app/settings/organisation/parent-directory-attributes/parent-directory-attributes.component.scss`
- Create: `frontend/src/app/settings/organisation/parent-directory-attributes/parent-directory-attributes.component.spec.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts` (Import-Array)
- Modify: `frontend/src/app/settings/organisation/organisation.component.html:44` (innerhalb `.tab-content` des Gruppen-Tabs, nach der Tabelle)

**Interfaces:**
- Consumes: `GET/PUT /api/v1/parent-directory/attributes` aus Task 3
- Produces:
  - `ParentDirectoryAttribute` — `{ key: string; label: string; scope: 'CHILD' | 'PARENT' | 'FAMILY'; selected: boolean; locked: boolean }`
  - `ParentDirectorySettingsService.load(): Observable<{ attributes: ParentDirectoryAttribute[] }>`
  - `ParentDirectorySettingsService.save(keys: string[]): Observable<void>`
  - `<app-parent-directory-attributes>`

- [ ] **Step 1: Write the failing test**

`parent-directory-attributes.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ParentDirectoryAttributesComponent } from './parent-directory-attributes.component';
import { ParentDirectorySettingsService } from './parent-directory-settings.service';
import { NotificationService } from '../../../shared/services/notification.service';

describe('ParentDirectoryAttributesComponent', () => {
  let fixture: ComponentFixture<ParentDirectoryAttributesComponent>;
  let component: ParentDirectoryAttributesComponent;
  let service: jasmine.SpyObj<ParentDirectorySettingsService>;
  let notify: jasmine.SpyObj<NotificationService>;

  const catalog = {
    attributes: [
      { key: 'childName', label: 'Vorname', scope: 'CHILD' as const, selected: true, locked: true },
      { key: 'childEntryDate', label: 'Eintritt', scope: 'CHILD' as const, selected: false, locked: false },
      { key: 'firstName', label: 'Vorname', scope: 'PARENT' as const, selected: true, locked: false },
      { key: 'team', label: 'Team', scope: 'PARENT' as const, selected: false, locked: false },
      { key: 'address', label: 'Adresse', scope: 'FAMILY' as const, selected: true, locked: false },
    ],
  };

  async function setup(response = of(catalog)): Promise<void> {
    service = jasmine.createSpyObj<ParentDirectorySettingsService>(
      'ParentDirectorySettingsService', ['load', 'save']);
    service.load.and.returnValue(response);
    service.save.and.returnValue(of(void 0));
    notify = jasmine.createSpyObj<NotificationService>(
      'NotificationService', ['success', 'error', 'extractError']);
    notify.extractError.and.returnValue('Fehler');

    await TestBed.configureTestingModule({
      imports: [ParentDirectoryAttributesComponent],
      providers: [
        provideNoopAnimations(),
        { provide: ParentDirectorySettingsService, useValue: service },
        { provide: NotificationService, useValue: notify },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ParentDirectoryAttributesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('gruppiert die Attribute nach Bereich', async () => {
    await setup();

    expect(component.childAttributes.map((a) => a.key)).toEqual(['childName', 'childEntryDate']);
    expect(component.parentAttributes.map((a) => a.key)).toEqual(['firstName', 'team']);
    expect(component.familyAttributes.map((a) => a.key)).toEqual(['address']);
  });

  it('sperrt childName gegen Abwahl', async () => {
    await setup();

    const boxes: HTMLInputElement[] =
      Array.from(fixture.nativeElement.querySelectorAll('input[type=checkbox]'));
    const locked = boxes.find((b) => b.id === 'attr-childName');

    expect(locked?.disabled).toBe(true);
    expect(locked?.checked).toBe(true);
  });

  it('speichert die ausgewaehlten Schluessel', async () => {
    await setup();

    component.toggle(component.parentAttributes[1], true);
    component.save();

    expect(service.save).toHaveBeenCalledWith(['childName', 'firstName', 'team', 'address']);
    expect(notify.success).toHaveBeenCalled();
  });

  it('meldet einen Fehler beim Speichern', async () => {
    await setup();
    service.save.and.returnValue(throwError(() => new Error('kaputt')));

    component.save();

    expect(notify.error).toHaveBeenCalledWith('Fehler');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless "--include=**/parent-directory-attributes/**/*.spec.ts"`
Expected: FAIL — Modul nicht gefunden.

- [ ] **Step 3: Service schreiben**

`parent-directory-settings.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../../core/services/api.service';

export type ParentDirectoryScope = 'CHILD' | 'PARENT' | 'FAMILY';

export interface ParentDirectoryAttribute {
  key: string;
  label: string;
  scope: ParentDirectoryScope;
  selected: boolean;
  locked: boolean;
}

export interface ParentDirectoryAttributeCatalog {
  attributes: ParentDirectoryAttribute[];
}

@Injectable({ providedIn: 'root' })
export class ParentDirectorySettingsService {
  constructor(private api: ApiService) {}

  load(): Observable<ParentDirectoryAttributeCatalog> {
    return this.api.get<ParentDirectoryAttributeCatalog>('/parent-directory/attributes');
  }

  save(visibleAttributes: string[]): Observable<void> {
    return this.api.put<void>('/parent-directory/attributes', { visibleAttributes });
  }
}
```

- [ ] **Step 4: Komponente schreiben**

`parent-directory-attributes.component.ts`:

```ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { NotificationService } from '../../../shared/services/notification.service';
import {
  ParentDirectoryAttribute,
  ParentDirectorySettingsService,
} from './parent-directory-settings.service';

/**
 * Globale Auswahl der Attribute, die die Eltern-Uebersicht zeigt. Gilt fuer alle
 * Gruppen und Semester; childName ist als Zeilenanker nicht abwaehlbar.
 */
@Component({
  selector: 'app-parent-directory-attributes',
  standalone: true,
  imports: [CommonModule, MatCheckboxModule, MatButtonModule],
  templateUrl: './parent-directory-attributes.component.html',
  styleUrl: './parent-directory-attributes.component.scss',
})
export class ParentDirectoryAttributesComponent implements OnInit {
  attributes: ParentDirectoryAttribute[] = [];
  saving = false;

  constructor(
    private settings: ParentDirectorySettingsService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.settings.load().subscribe({
      next: (catalog) => (this.attributes = catalog.attributes),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  get childAttributes(): ParentDirectoryAttribute[] {
    return this.attributes.filter((a) => a.scope === 'CHILD');
  }

  get parentAttributes(): ParentDirectoryAttribute[] {
    return this.attributes.filter((a) => a.scope === 'PARENT');
  }

  get familyAttributes(): ParentDirectoryAttribute[] {
    return this.attributes.filter((a) => a.scope === 'FAMILY');
  }

  toggle(attribute: ParentDirectoryAttribute, selected: boolean): void {
    if (attribute.locked) return;
    attribute.selected = selected;
  }

  save(): void {
    this.saving = true;
    const keys = this.attributes.filter((a) => a.selected).map((a) => a.key);
    this.settings.save(keys).subscribe({
      next: () => {
        this.saving = false;
        this.notify.success('Sichtbare Attribute gespeichert');
      },
      error: (err) => {
        this.saving = false;
        this.notify.error(this.notify.extractError(err));
      },
    });
  }
}
```

`parent-directory-attributes.component.html`:

```html
<section class="attribute-settings">
  <h3>Sichtbare Eltern-Attribute</h3>
  <p class="hint">
    Diese Auswahl bestimmt, was Eltern in der Uebersicht "Eltern in unseren Gruppen" sehen.
    Sie gilt fuer alle Gruppen und Semester.
  </p>

  <div class="attribute-group">
    <h4>Kind</h4>
    @for (attribute of childAttributes; track attribute.key) {
      <mat-checkbox
        [id]="'attr-' + attribute.key"
        [checked]="attribute.selected"
        [disabled]="attribute.locked"
        (change)="toggle(attribute, $event.checked)">
        {{ attribute.label }}
      </mat-checkbox>
    }
  </div>

  <div class="attribute-group">
    <h4>Eltern</h4>
    @for (attribute of parentAttributes; track attribute.key) {
      <mat-checkbox
        [id]="'attr-' + attribute.key"
        [checked]="attribute.selected"
        [disabled]="attribute.locked"
        (change)="toggle(attribute, $event.checked)">
        {{ attribute.label }}
      </mat-checkbox>
    }
  </div>

  <div class="attribute-group">
    <h4>Familie</h4>
    @for (attribute of familyAttributes; track attribute.key) {
      <mat-checkbox
        [id]="'attr-' + attribute.key"
        [checked]="attribute.selected"
        [disabled]="attribute.locked"
        (change)="toggle(attribute, $event.checked)">
        {{ attribute.label }}
      </mat-checkbox>
    }
  </div>

  <button mat-raised-button color="primary" type="button" [disabled]="saving" (click)="save()">
    Speichern
  </button>
</section>
```

`parent-directory-attributes.component.scss`:

```scss
.attribute-settings {
  margin-top: 32px;
  padding-top: 16px;
  border-top: 1px solid rgba(0, 0, 0, 0.12);

  .hint {
    margin: 0 0 16px;
    opacity: 0.7;
  }

  .attribute-group {
    margin-bottom: 16px;

    h4 {
      margin: 0 0 8px;
    }

    mat-checkbox {
      display: block;
    }
  }
}
```

Der Test prüft `input[type=checkbox]` mit `id="attr-childName"`. `mat-checkbox` reicht `[id]` an das innere `<input>` durch — deshalb steht das Attribut auf der Material-Komponente, nicht auf einem Wrapper.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless "--include=**/parent-directory-attributes/**/*.spec.ts"`
Expected: PASS, 4 Tests.

- [ ] **Step 6: In den Gruppen-Tab einhängen**

In `organisation.component.ts` das Import-Array um `ParentDirectoryAttributesComponent` ergänzen und den Import setzen:

```ts
import { ParentDirectoryAttributesComponent } from './parent-directory-attributes/parent-directory-attributes.component';
```

In `organisation.component.html` direkt vor dem schließenden `</div>` der `.tab-content` des Gruppen-Tabs (nach der Gruppentabelle, vor Zeile 46 `</mat-tab>`):

```html
        <app-parent-directory-attributes></app-parent-directory-attributes>
```

- [ ] **Step 7: Organisation-Tests laufen lassen**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless "--include=**/organisation/**/*.spec.ts"`
Expected: PASS. Bricht `organisation.component.spec.ts`, weil der HTTP-Aufruf der neuen Sektion nicht bedient wird, dort `ParentDirectorySettingsService` als Spy bereitstellen, der `of({ attributes: [] })` liefert.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/settings/organisation/parent-directory-attributes \
        frontend/src/app/settings/organisation/organisation.component.ts \
        frontend/src/app/settings/organisation/organisation.component.html \
        frontend/src/app/settings/organisation/organisation.component.spec.ts
git commit -m "feat(fe): Admin-Sektion fuer sichtbare Eltern-Attribute"
```

---

### Task 9: Eltern-Übersicht mit dynamischen Spalten

**Files:**
- Modify: `frontend/src/app/shared/models/parent-directory.model.ts`
- Modify: `frontend/src/app/eltern/eltern.component.ts`
- Modify: `frontend/src/app/eltern/eltern.component.html`
- Modify: `frontend/src/app/eltern/eltern.component.spec.ts`

**Interfaces:**
- Consumes: die Antwort aus Task 4-6 (`columns`, `children[].name|entryDate|exitDate`, `parents[].values`)
- Produces: nichts für spätere Tasks

- [ ] **Step 1: Write the failing test**

`eltern.component.spec.ts`: das Objekt `directory` ersetzen und Tests ergänzen.

```ts
  const directory: ParentDirectory = {
    semesterId: 's1',
    columns: [
      { key: 'childName', label: 'Vorname', scope: 'CHILD' },
      { key: 'firstName', label: 'Vorname', scope: 'PARENT' },
      { key: 'email', label: 'E-Mail', scope: 'PARENT' },
      { key: 'address', label: 'Adresse', scope: 'FAMILY' },
    ],
    groups: [
      {
        groupInstanceId: 'g1',
        groupName: 'Käfergruppe',
        families: [
          {
            familyId: 'f1',
            isOwnFamily: true,
            children: [{ name: 'Lena', entryDate: null, exitDate: null }],
            parents: [{ values: { firstName: 'Anna', email: 'anna@x.at' } }],
            address: 'Hauptstraße 1, 1010 Wien',
          },
          {
            familyId: 'f2',
            isOwnFamily: false,
            children: [{ name: 'Tim', entryDate: null, exitDate: null }],
            parents: [{ values: { firstName: 'Clara' } }],
            address: null,
          },
        ],
      },
      {
        groupInstanceId: 'g2',
        groupName: 'Bienengruppe',
        families: [
          {
            familyId: 'f1',
            isOwnFamily: true,
            children: [{ name: 'Paul', entryDate: '2026-09-01', exitDate: null }],
            parents: [{ values: { firstName: 'Anna', email: 'anna@x.at' } }],
            address: 'Hauptstraße 1, 1010 Wien',
          },
        ],
      },
    ],
  };
```

Neue Tests:

```ts
  it('rendert genau die gelieferten Spalten', async () => {
    await setup();

    const headers = Array.from(fixture.nativeElement.querySelectorAll('th'))
      .map((th) => (th as HTMLElement).textContent?.trim());

    expect(headers).toEqual(['Kind(er)', 'Vorname', 'E-Mail', 'Adresse']);
  });

  it('laesst die Adressspalte weg, wenn sie nicht geliefert wird', async () => {
    await setup(of({ ...directory, columns: directory.columns.filter((c) => c.key !== 'address') }));

    const headers = Array.from(fixture.nativeElement.querySelectorAll('th'))
      .map((th) => (th as HTMLElement).textContent?.trim());

    expect(headers).not.toContain('Adresse');
  });

  it('zeigt Eintrittsdatum unter dem Kindernamen', async () => {
    await setup(of({
      ...directory,
      columns: [...directory.columns, { key: 'childEntryDate', label: 'Eintritt', scope: 'CHILD' as const }],
    }));

    component.selectGroup('g2');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('2026-09-01');
  });

  it('verlinkt E-Mail-Adressen', async () => {
    await setup();

    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('a[href^="mailto:"]');

    expect(link.getAttribute('href')).toBe('mailto:anna@x.at');
  });
```

Der bestehende Test `zeigt nur die Familien der gewaehlten Gruppe` prüft `textContent` auf `'Paul'` und `'Tim'` — er bleibt unverändert gültig.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless "--include=**/eltern/**/*.spec.ts"`
Expected: FAIL — Typfehler, `columns` existiert nicht auf `ParentDirectory`.

- [ ] **Step 3: Modell anpassen**

`frontend/src/app/shared/models/parent-directory.model.ts` vollständig ersetzen:

```ts
export type ParentDirectoryScope = 'CHILD' | 'PARENT' | 'FAMILY';

export interface ParentDirectoryColumn {
  key: string;
  label: string;
  scope: ParentDirectoryScope;
}

export interface ParentDirectoryParent {
  values: Record<string, string>;
}

export interface ParentDirectoryChild {
  name: string | null;
  entryDate: string | null;
  exitDate: string | null;
}

export interface ParentDirectoryFamily {
  familyId: string;
  isOwnFamily: boolean;
  children: ParentDirectoryChild[];
  parents: ParentDirectoryParent[];
  address: string | null;
}

export interface ParentDirectoryGroup {
  groupInstanceId: string;
  groupName: string | null;
  families: ParentDirectoryFamily[];
}

export interface ParentDirectory {
  semesterId: string | null;
  columns: ParentDirectoryColumn[];
  groups: ParentDirectoryGroup[];
}
```

- [ ] **Step 4: Komponente anpassen**

In `eltern.component.ts` ergänzen:

```ts
  columns: ParentDirectoryColumn[] = [];
```

In `load()` im `next`-Zweig ergänzen und im `error`-Zweig zurücksetzen:

```ts
        this.columns = result.columns ?? [];
```
```ts
        this.columns = [];
```

Neue Zugriffshilfen:

```ts
  get parentColumns(): ParentDirectoryColumn[] {
    return this.columns.filter((c) => c.scope === 'PARENT');
  }

  get showAddress(): boolean {
    return this.columns.some((c) => c.key === 'address');
  }

  get showEntryDate(): boolean {
    return this.columns.some((c) => c.key === 'childEntryDate');
  }

  get showExitDate(): boolean {
    return this.columns.some((c) => c.key === 'childExitDate');
  }

  /** mailto:/tel:-Verweis fuer die beiden Kontaktspalten, sonst reiner Text. */
  linkFor(key: string, value: string): string | null {
    if (key === 'email') return `mailto:${value}`;
    if (key === 'phone') return `tel:${value}`;
    return null;
  }
```

Import ergänzen: `ParentDirectoryColumn` aus dem Modell. Die Methode `parentName` entfällt ersatzlos.

- [ ] **Step 5: Template anpassen**

In `eltern.component.html` Tabellenkopf und -körper ersetzen:

```html
        <thead>
          <tr>
            <th>Kind(er)</th>
            @for (column of parentColumns; track column.key) {
              <th>{{ column.label }}</th>
            }
            @if (showAddress) {
              <th>Adresse</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (family of selectedGroup?.families ?? []; track family.familyId) {
            <tr [class.own-family]="family.isOwnFamily">
              <td>
                @for (child of family.children; track $index) {
                  <div>
                    {{ child.name }}
                    @if (showEntryDate && child.entryDate) {
                      <span class="child-date">ab {{ child.entryDate }}</span>
                    }
                    @if (showExitDate && child.exitDate) {
                      <span class="child-date">bis {{ child.exitDate }}</span>
                    }
                  </div>
                }
              </td>
              @for (column of parentColumns; track column.key) {
                <td>
                  @for (parent of family.parents; track $index) {
                    <div>
                      @if (parent.values[column.key]) {
                        @if (linkFor(column.key, parent.values[column.key]); as href) {
                          <a [href]="href">{{ parent.values[column.key] }}</a>
                        } @else {
                          {{ parent.values[column.key] }}
                        }
                      }
                    </div>
                  }
                </td>
              }
              @if (showAddress) {
                <td>{{ family.address }}</td>
              }
            </tr>
          }
        </tbody>
```

In `eltern.component.scss` ergänzen:

```scss
.child-date {
  margin-left: 6px;
  font-size: 12px;
  opacity: 0.7;
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless "--include=**/eltern/**/*.spec.ts"`
Expected: PASS.

- [ ] **Step 7: Gesamten Frontend-Lauf und Produktionsbau**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`
Expected: PASS, keine neuen Fehler.

Run: `cd frontend && npx ng build --configuration production`
Expected: erfolgreicher Bau ohne Template-Typfehler.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/shared/models/parent-directory.model.ts \
        frontend/src/app/eltern/eltern.component.ts \
        frontend/src/app/eltern/eltern.component.html \
        frontend/src/app/eltern/eltern.component.scss \
        frontend/src/app/eltern/eltern.component.spec.ts
git commit -m "feat(fe): Eltern-Uebersicht mit konfigurierbaren Spalten"
```

---

## Abschluss

- [ ] **Backend-Gesamtlauf:** `cd backend && mvn test 2>&1 | tail -40` — keine neuen Fehler gegenüber den 12 vorbestehenden.
- [ ] **Frontend-Gesamtlauf:** `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`
- [ ] **Handprobe:** Als Admin unter Organisation > Gruppen Team und Eintrittsdatum zusätzlich aktivieren, Adresse abwählen, speichern; `/eltern` neu laden und prüfen, dass die Adressspalte fehlt, Team erscheint und in der Netzwerkantwort kein `address`-Wert mehr steht.
