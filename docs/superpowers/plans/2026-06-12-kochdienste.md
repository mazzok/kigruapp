# Kochdienste + Organisation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Kochen" section with a monthly calendar view of cooking duties for all families, plus an admin "Organisation" section for managing groups and duty settings.

**Architecture:** New `organisation` MongoDB collection (polymorphic, admin-only) stores group definitions and duty-specific settings (food properties). Cooking duties are stored as complex FieldInstances in Person.schedules. A dedicated backend endpoint aggregates all cooking duties with application-level filtering. Frontend uses `angular-calendar` for the month view.

**Tech Stack:** Java 17 / Quarkus 3.36 / MongoDB Panache (backend), Angular 18 / Material 18 / angular-calendar (frontend)

---

## File Structure

### New Files — Backend

| File | Responsibility |
|------|---------------|
| `backend/src/main/java/at/kigruapp/entity/Organisation.java` | Organisation Panache entity (polymorphic) |
| `backend/src/main/java/at/kigruapp/entity/DutyEntry.java` | DutyEntry subdocument POJO |
| `backend/src/main/java/at/kigruapp/dto/OrganisationDTO.java` | Organisation response DTO with resolved definitions |
| `backend/src/main/java/at/kigruapp/dto/DutyEntryDTO.java` | DutyEntry response DTO with resolved definitions |
| `backend/src/main/java/at/kigruapp/resource/OrganisationResource.java` | Organisation REST endpoints |
| `backend/src/main/java/at/kigruapp/dto/CookingDutyDTO.java` | Cooking duty response DTO |
| `backend/src/main/java/at/kigruapp/resource/CookingDutyResource.java` | Cooking duties aggregation endpoint |
| `backend/src/main/java/at/kigruapp/migration/OrganisationSeedMigration.java` | Seed organisation documents + food-property definitions |

### New Files — Frontend

| File | Responsibility |
|------|---------------|
| `frontend/src/app/shared/models/organisation.model.ts` | Organisation + DutyEntry + CookingDuty interfaces |
| `frontend/src/app/shared/services/organisation.service.ts` | Organisation API service |
| `frontend/src/app/cooking/services/cooking-duty.service.ts` | Cooking duty API service |
| `frontend/src/app/cooking/cooking.component.ts` | Calendar main page (TS) |
| `frontend/src/app/cooking/cooking.component.html` | Calendar main page (HTML) |
| `frontend/src/app/cooking/cooking.component.scss` | Calendar main page (styles) |
| `frontend/src/app/cooking/cooking-duty-dialog.component.ts` | Create/edit dialog (TS) |
| `frontend/src/app/cooking/cooking-duty-dialog.component.html` | Create/edit dialog (HTML) |
| `frontend/src/app/cooking/cooking-duty-dialog.component.scss` | Create/edit dialog (styles) |
| `frontend/src/app/settings/organisation/organisation.component.ts` | Admin organisation page (TS) |
| `frontend/src/app/settings/organisation/organisation.component.html` | Admin organisation page (HTML) |
| `frontend/src/app/settings/organisation/organisation.component.scss` | Admin organisation page (styles) |

### Modified Files

| File | Change |
|------|--------|
| `backend/src/main/java/at/kigruapp/entity/FieldDefinition.java` | Add `properties` field |
| `backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java` | Add `cookingDuty` definition |
| `frontend/src/app/shared/models/field-definition.model.ts` | Add `properties` field |
| `frontend/src/app/app.routes.ts` | Add `/cooking` and `/settings/organisation` routes |
| `frontend/src/app/app.component.html` | Add sidebar links |
| `frontend/package.json` | Add `angular-calendar` + `date-fns` dependencies |

---

## Task 1: Add `properties` field to FieldDefinition

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/FieldDefinition.java`
- Modify: `frontend/src/app/shared/models/field-definition.model.ts`

- [ ] **Step 1: Add properties to backend entity**

In `backend/src/main/java/at/kigruapp/entity/FieldDefinition.java`, add after `public String keycloakMapping;`:

```java
public Map<String, Object> properties;
```

No new imports needed — `Map` and `Object` are already imported.

- [ ] **Step 2: Add properties to frontend model**

In `frontend/src/app/shared/models/field-definition.model.ts`, add after `keycloakMapping`:

```typescript
export interface FieldDefinition {
  id?: string;
  fieldName: string;
  label: Record<string, string>;
  description?: string;
  jsonSchema: Record<string, unknown>;
  required: boolean;
  keycloakMapping?: string | null;
  properties?: Record<string, unknown>;
  createdAt?: string;
  outdatedAt?: string | null;
}
```

- [ ] **Step 3: Verify backend compiles**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/FieldDefinition.java frontend/src/app/shared/models/field-definition.model.ts
git commit -m "feat: add generic properties field to FieldDefinition"
```

---

## Task 2: Organisation backend entity + DutyEntry

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/Organisation.java`
- Create: `backend/src/main/java/at/kigruapp/entity/DutyEntry.java`

- [ ] **Step 1: Create DutyEntry POJO**

Create `backend/src/main/java/at/kigruapp/entity/DutyEntry.java`:

```java
package at.kigruapp.entity;

import org.bson.types.ObjectId;
import java.util.ArrayList;
import java.util.List;

public class DutyEntry {
    public String name;
    public List<ObjectId> definitionIds = new ArrayList<>();

    public DutyEntry() {}

    public DutyEntry(String name) {
        this.name = name;
    }
}
```

- [ ] **Step 2: Create Organisation entity**

Create `backend/src/main/java/at/kigruapp/entity/Organisation.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;
import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "organisation")
public class Organisation extends PanacheMongoEntity {
    public String tag;
    public List<ObjectId> definitionIds = new ArrayList<>();
    public List<DutyEntry> entries = new ArrayList<>();

    public static Organisation findByTag(String tag) {
        return find("tag", tag).firstResult();
    }
}
```

- [ ] **Step 3: Verify backend compiles**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/Organisation.java backend/src/main/java/at/kigruapp/entity/DutyEntry.java
git commit -m "feat: add Organisation entity and DutyEntry subdocument"
```

---

## Task 3: Organisation DTOs

**Files:**
- Create: `backend/src/main/java/at/kigruapp/dto/OrganisationDTO.java`
- Create: `backend/src/main/java/at/kigruapp/dto/DutyEntryDTO.java`

- [ ] **Step 1: Create DutyEntryDTO**

Create `backend/src/main/java/at/kigruapp/dto/DutyEntryDTO.java`:

```java
package at.kigruapp.dto;

import at.kigruapp.entity.FieldDefinition;
import java.util.List;

public class DutyEntryDTO {
    public String name;
    public List<FieldDefinition> definitions;
}
```

- [ ] **Step 2: Create OrganisationDTO**

Create `backend/src/main/java/at/kigruapp/dto/OrganisationDTO.java`:

```java
package at.kigruapp.dto;

import at.kigruapp.entity.FieldDefinition;
import java.util.List;

public class OrganisationDTO {
    public String id;
    public String tag;
    public List<FieldDefinition> definitions;
    public List<DutyEntryDTO> entries;
}
```

- [ ] **Step 3: Verify backend compiles**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/OrganisationDTO.java backend/src/main/java/at/kigruapp/dto/DutyEntryDTO.java
git commit -m "feat: add OrganisationDTO and DutyEntryDTO"
```

---

## Task 4: Organisation REST resource

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/OrganisationResource.java`

- [ ] **Step 1: Create OrganisationResource**

Create `backend/src/main/java/at/kigruapp/resource/OrganisationResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.dto.DutyEntryDTO;
import at.kigruapp.dto.OrganisationDTO;
import at.kigruapp.entity.DutyEntry;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.Organisation;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Path("/api/v1/organisation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrganisationResource {

    @GET
    public List<OrganisationDTO> list() {
        return Organisation.listAll().stream()
                .map(o -> toDTO((Organisation) o))
                .collect(Collectors.toList());
    }

    @GET
    @Path("/{tag}")
    public OrganisationDTO getByTag(@PathParam("tag") String tag) {
        Organisation org = Organisation.findByTag(tag);
        if (org == null) {
            throw new NotFoundException();
        }
        return toDTO(org);
    }

    @PUT
    @Path("/id/{id}")
    public Response update(@PathParam("id") String id, Organisation update) {
        Organisation org = Organisation.findById(new ObjectId(id));
        if (org == null) {
            throw new NotFoundException();
        }
        org.definitionIds = update.definitionIds;
        org.entries = update.entries;
        org.update();
        return Response.ok(toDTO(org)).build();
    }

    private OrganisationDTO toDTO(Organisation org) {
        OrganisationDTO dto = new OrganisationDTO();
        dto.id = org.id.toString();
        dto.tag = org.tag;
        dto.definitions = resolveDefinitions(org.definitionIds);
        if (org.entries != null) {
            dto.entries = org.entries.stream().map(this::toEntryDTO).collect(Collectors.toList());
        } else {
            dto.entries = new ArrayList<>();
        }
        return dto;
    }

    private DutyEntryDTO toEntryDTO(DutyEntry entry) {
        DutyEntryDTO dto = new DutyEntryDTO();
        dto.name = entry.name;
        dto.definitions = resolveDefinitions(entry.definitionIds);
        return dto;
    }

    private List<FieldDefinition> resolveDefinitions(List<ObjectId> defIds) {
        if (defIds == null || defIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<FieldDefinition> result = new ArrayList<>();
        for (ObjectId defId : defIds) {
            FieldDefinition def = FieldDefinition.findById(defId);
            if (def != null) {
                result.add(def);
            }
        }
        return result;
    }
}
```

- [ ] **Step 2: Verify backend compiles**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/OrganisationResource.java
git commit -m "feat: add OrganisationResource REST endpoints"
```

---

## Task 5: Organisation seed migration

**Files:**
- Create: `backend/src/main/java/at/kigruapp/migration/OrganisationSeedMigration.java`
- Modify: `backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java`

- [ ] **Step 1: Add cookingDuty and food-property definitions to seed migration**

In `backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java`, add the following seed calls at the end of the `onStart` method, just before the `migrations.insertOne(...)` line. Also update the `MIGRATION_ID` to trigger re-run:

Change the MIGRATION_ID:
```java
private static final String MIGRATION_ID = "seed-basic-property-definitions-v3";
```

Add the new seed calls (before the `migrations.insertOne` line):

```java
        // Cooking duty definition
        seedDef(defs, now, "cookingDuty",
                Map.of("de", "Kochdienst", "en", "Cooking Duty"),
                new Document("type", "object")
                        .append("properties", new Document()
                                .append("date", new Document("type", "string").append("format", "date"))
                                .append("groups", new Document("type", "array").append("items", new Document("type", "string")))
                                .append("description", new Document("type", "string"))
                                .append("foodProperties", new Document("type", "object")))
                        .append("required", List.of("date", "groups")),
                false, null);

        // Food property definitions
        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Glutenfrei", "en", "Gluten-free"),
                new Document("type", "boolean"), false,
                new Document("icon", "grain"));

        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Weizenfrei", "en", "Wheat-free"),
                new Document("type", "boolean"), false,
                new Document("icon", "do_not_disturb"));

        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Vegetarisch", "en", "Vegetarian"),
                new Document("type", "boolean"), false,
                new Document("icon", "eco"));

        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Vegan", "en", "Vegan"),
                new Document("type", "boolean"), false,
                new Document("icon", "spa"));

        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Ohne Milchprodukte", "en", "Dairy-free"),
                new Document("type", "boolean"), false,
                new Document("icon", "water_drop"));

        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Histaminvertraeglich", "en", "Histamine-friendly"),
                new Document("type", "boolean"), false,
                new Document("icon", "health_and_safety"));
```

Add the new `seedDefWithProperties` helper method after the existing `seedDef` method:

```java
    private void seedDefWithProperties(MongoCollection<Document> defs, Date now,
                                        String fieldName, Map<String, String> label,
                                        Document jsonSchema, boolean required,
                                        Document properties) {
        // Food properties share fieldName but differ by label — check label.de for uniqueness
        String labelDe = label.get("de");
        if (defs.find(new Document("fieldName", fieldName).append("label.de", labelDe)).first() != null) {
            return;
        }
        Document doc = new Document()
                .append("fieldName", fieldName)
                .append("label", new Document(label))
                .append("jsonSchema", jsonSchema)
                .append("required", required)
                .append("properties", properties)
                .append("createdAt", now);
        defs.insertOne(doc);
    }
```

- [ ] **Step 2: Create OrganisationSeedMigration**

Create `backend/src/main/java/at/kigruapp/migration/OrganisationSeedMigration.java`:

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

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Startup
public class OrganisationSeedMigration {

    private static final String MIGRATION_ID = "seed-organisation-v1";

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

        MongoCollection<Document> orgColl = db.getCollection("organisation");
        MongoCollection<Document> defs = db.getCollection("field_definitions");

        // Create groups document (initially empty)
        if (orgColl.find(new Document("tag", "groups")).first() == null) {
            orgColl.insertOne(new Document()
                    .append("tag", "groups")
                    .append("definitionIds", new ArrayList<>())
            );
        }

        // Create duty-settings document with cooking food-property references
        if (orgColl.find(new Document("tag", "duty-settings")).first() == null) {
            // Find all food-property definition IDs
            List<Object> foodDefIds = new ArrayList<>();
            for (Document def : defs.find(new Document("fieldName", "food-property"))) {
                foodDefIds.add(def.getObjectId("_id"));
            }

            Document dutySettings = new Document()
                    .append("tag", "duty-settings")
                    .append("entries", List.of(
                            new Document("name", "cooking").append("definitionIds", foodDefIds),
                            new Document("name", "cleaning").append("definitionIds", new ArrayList<>()),
                            new Document("name", "time-substitution").append("definitionIds", new ArrayList<>())
                    ));
            orgColl.insertOne(dutySettings);
        }

        migrations.insertOne(new Document("_id", MIGRATION_ID));
    }
}
```

- [ ] **Step 3: Verify backend compiles**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/at/kigruapp/migration/OrganisationSeedMigration.java backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java
git commit -m "feat: seed organisation documents and food-property definitions"
```

---

## Task 6: CookingDuty backend endpoint

**Files:**
- Create: `backend/src/main/java/at/kigruapp/dto/CookingDutyDTO.java`
- Create: `backend/src/main/java/at/kigruapp/resource/CookingDutyResource.java`

- [ ] **Step 1: Create CookingDutyDTO**

Create `backend/src/main/java/at/kigruapp/dto/CookingDutyDTO.java`:

```java
package at.kigruapp.dto;

import java.util.List;
import java.util.Map;

public class CookingDutyDTO {
    public String id;
    public String personId;
    public String familyId;
    public String personName;
    public String date;
    public List<String> groups;
    public String description;
    public Map<String, Boolean> foodProperties;
}
```

- [ ] **Step 2: Create CookingDutyResource**

Create `backend/src/main/java/at/kigruapp/resource/CookingDutyResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.dto.CookingDutyDTO;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;
import java.util.stream.Collectors;

@Path("/api/v1/cooking-duties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CookingDutyResource {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoCollection<Document> getFieldInstancesCollection() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    @GET
    public List<CookingDutyDTO> list(
            @QueryParam("month") String month,
            @QueryParam("groups") String groupsParam) {

        // Find the cookingDuty definition
        FieldDefinition cookingDutyDef = FieldDefinition.find("fieldName", "cookingDuty").firstResult();
        if (cookingDutyDef == null) {
            return new ArrayList<>();
        }
        ObjectId cookingDutyDefId = cookingDutyDef.id;

        // Parse group filter
        Set<String> groupFilter = new HashSet<>();
        if (groupsParam != null && !groupsParam.isBlank()) {
            groupFilter.addAll(Arrays.asList(groupsParam.split(",")));
        }

        MongoCollection<Document> instColl = getFieldInstancesCollection();
        List<Person> allPersons = Person.listAll();
        List<CookingDutyDTO> result = new ArrayList<>();

        for (Person person : allPersons) {
            if (person.schedules == null) continue;

            // Resolve person name (Nachname Vorname)
            String personName = resolvePersonName(person, instColl);

            for (FieldRef ref : person.schedules) {
                if (!ref.definitionId.equals(cookingDutyDefId)) continue;

                Document instDoc = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
                if (instDoc == null) continue;

                FieldInstance inst = FieldInstance.fromDocument(instDoc);
                if (!(inst.value instanceof Document valueDoc)) continue;

                String date = valueDoc.getString("date");
                if (date == null) continue;

                // Filter by month (format: "2026-06")
                if (month != null && !month.isBlank() && !date.startsWith(month)) continue;

                // Get groups from value
                List<String> groups = new ArrayList<>();
                Object groupsObj = valueDoc.get("groups");
                if (groupsObj instanceof List<?> groupList) {
                    for (Object g : groupList) {
                        groups.add(g.toString());
                    }
                }

                // Filter by groups
                if (!groupFilter.isEmpty() && groups.stream().noneMatch(groupFilter::contains)) continue;

                // Get food properties
                Map<String, Boolean> foodProps = new LinkedHashMap<>();
                Object fpObj = valueDoc.get("foodProperties");
                if (fpObj instanceof Document fpDoc) {
                    for (Map.Entry<String, Object> entry : fpDoc.entrySet()) {
                        if (entry.getValue() instanceof Boolean b) {
                            foodProps.put(entry.getKey(), b);
                        }
                    }
                }

                CookingDutyDTO dto = new CookingDutyDTO();
                dto.id = inst.id.toString();
                dto.personId = person.id.toString();
                dto.familyId = person.familyId.toString();
                dto.personName = personName;
                dto.date = date;
                dto.groups = groups;
                dto.description = valueDoc.getString("description");
                dto.foodProperties = foodProps;
                result.add(dto);
            }
        }

        return result;
    }

    private String resolvePersonName(Person person, MongoCollection<Document> instColl) {
        String firstName = "";
        String lastName = "";
        if (person.basicProperties == null) return "";

        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def == null) continue;

            Document instDoc = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
            if (instDoc == null) continue;

            Object value = instDoc.get("value");
            if ("firstName".equals(def.fieldName) && value instanceof String s) {
                firstName = s;
            } else if ("lastName".equals(def.fieldName) && value instanceof String s) {
                lastName = s;
            }
        }
        return (lastName + " " + firstName).trim();
    }
}
```

- [ ] **Step 3: Verify backend compiles**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/CookingDutyDTO.java backend/src/main/java/at/kigruapp/resource/CookingDutyResource.java
git commit -m "feat: add CookingDutyResource with month/group filtering"
```

---

## Task 7: Frontend models and services

**Files:**
- Create: `frontend/src/app/shared/models/organisation.model.ts`
- Create: `frontend/src/app/shared/services/organisation.service.ts`
- Create: `frontend/src/app/cooking/services/cooking-duty.service.ts`

- [ ] **Step 1: Create organisation model**

Create `frontend/src/app/shared/models/organisation.model.ts`:

```typescript
import { FieldDefinition } from './field-definition.model';

export interface DutyEntryDTO {
  name: string;
  definitions: FieldDefinition[];
}

export interface OrganisationDTO {
  id: string;
  tag: string;
  definitions: FieldDefinition[];
  entries: DutyEntryDTO[];
}

export interface CookingDutyDTO {
  id: string;
  personId: string;
  familyId: string;
  personName: string;
  date: string;
  groups: string[];
  description: string;
  foodProperties: Record<string, boolean>;
}
```

- [ ] **Step 2: Create organisation service**

Create `frontend/src/app/shared/services/organisation.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { OrganisationDTO } from '../models/organisation.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class OrganisationService {
  constructor(private api: ApiService) {}

  list(): Observable<OrganisationDTO[]> {
    return this.api.get<OrganisationDTO[]>('/organisation');
  }

  getByTag(tag: string): Observable<OrganisationDTO> {
    return this.api.get<OrganisationDTO>(`/organisation/${tag}`);
  }

  update(id: string, org: { definitionIds: string[]; entries?: { name: string; definitionIds: string[] }[] }): Observable<OrganisationDTO> {
    return this.api.put<OrganisationDTO>(`/organisation/id/${id}`, org);
  }
}
```

- [ ] **Step 3: Create cooking duty service**

Create `frontend/src/app/cooking/services/cooking-duty.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { CookingDutyDTO } from '../../shared/models/organisation.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class CookingDutyService {
  constructor(private api: ApiService) {}

  getByMonth(month: string, groups?: string[]): Observable<CookingDutyDTO[]> {
    let params = `?month=${month}`;
    if (groups && groups.length > 0) {
      params += `&groups=${groups.join(',')}`;
    }
    return this.api.get<CookingDutyDTO[]>(`/cooking-duties${params}`);
  }
}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/shared/models/organisation.model.ts frontend/src/app/shared/services/organisation.service.ts frontend/src/app/cooking/services/cooking-duty.service.ts
git commit -m "feat: add organisation model, organisation service, cooking duty service"
```

---

## Task 8: Install angular-calendar dependency

**Files:**
- Modify: `frontend/package.json`

- [ ] **Step 1: Install angular-calendar and date-fns**

Run: `cd frontend && npm install angular-calendar date-fns`

`angular-calendar` requires `date-fns` as its date adapter.

- [ ] **Step 2: Verify installation**

Run: `cd frontend && npx ng build --configuration development 2>&1 | tail -5`
Expected: Build should succeed (or show only unrelated warnings)

- [ ] **Step 3: Commit**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "feat: add angular-calendar and date-fns dependencies"
```

---

## Task 9: Admin Organisation component

**Files:**
- Create: `frontend/src/app/settings/organisation/organisation.component.ts`
- Create: `frontend/src/app/settings/organisation/organisation.component.html`
- Create: `frontend/src/app/settings/organisation/organisation.component.scss`

- [ ] **Step 1: Create organisation component TypeScript**

Create `frontend/src/app/settings/organisation/organisation.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldDefinitionService } from '../custom-fields/services/field-definition.service';
import { OrganisationDTO, DutyEntryDTO } from '../../shared/models/organisation.model';
import { FieldDefinition } from '../../shared/models/field-definition.model';

@Component({
  selector: 'app-organisation',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatTabsModule, MatTableModule, MatFormFieldModule,
    MatInputModule, MatButtonModule, MatIconModule,
  ],
  templateUrl: './organisation.component.html',
  styleUrl: './organisation.component.scss',
})
export class OrganisationComponent implements OnInit {
  // Groups tab
  groupsOrg: OrganisationDTO | null = null;
  groupsDataSource = new MatTableDataSource<FieldDefinition>();
  groupColumns = ['label', 'color', 'actions'];
  groupForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
    color: new FormControl('#4285f4', Validators.required),
  });

  // Duty settings tab
  dutySettingsOrg: OrganisationDTO | null = null;
  cookingDataSource = new MatTableDataSource<FieldDefinition>();
  dutyColumns = ['label', 'icon', 'actions'];
  dutyForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
    icon: new FormControl('restaurant', Validators.required),
  });

  constructor(
    private orgService: OrganisationService,
    private fieldDefService: FieldDefinitionService,
  ) {}

  ngOnInit(): void {
    this.loadGroups();
    this.loadDutySettings();
  }

  // --- Groups ---

  loadGroups(): void {
    this.orgService.getByTag('groups').subscribe((org) => {
      this.groupsOrg = org;
      this.groupsDataSource.data = org.definitions;
    });
  }

  addGroup(): void {
    if (!this.groupForm.valid || !this.groupsOrg) return;
    const labelDe = this.groupForm.value.labelDe!;
    const color = this.groupForm.value.color!;

    const newDef: FieldDefinition = {
      fieldName: 'group',
      label: { de: labelDe },
      jsonSchema: { type: 'string' },
      required: false,
      properties: { color },
    };

    this.fieldDefService.create(newDef).subscribe((created) => {
      const updatedIds = [...this.groupsOrg!.definitions.map((d) => d.id!), created.id!];
      this.orgService.update(this.groupsOrg!.id, { definitionIds: updatedIds }).subscribe(() => {
        this.groupForm.reset({ color: '#4285f4' });
        this.loadGroups();
      });
    });
  }

  deleteGroup(def: FieldDefinition): void {
    if (!this.groupsOrg) return;
    this.fieldDefService.outdate(def.id!).subscribe(() => {
      const updatedIds = this.groupsOrg!.definitions.filter((d) => d.id !== def.id).map((d) => d.id!);
      this.orgService.update(this.groupsOrg!.id, { definitionIds: updatedIds }).subscribe(() => {
        this.loadGroups();
      });
    });
  }

  // --- Duty Settings (Cooking) ---

  loadDutySettings(): void {
    this.orgService.getByTag('duty-settings').subscribe((org) => {
      this.dutySettingsOrg = org;
      const cookingEntry = org.entries.find((e) => e.name === 'cooking');
      this.cookingDataSource.data = cookingEntry?.definitions ?? [];
    });
  }

  addFoodProperty(): void {
    if (!this.dutyForm.valid || !this.dutySettingsOrg) return;
    const labelDe = this.dutyForm.value.labelDe!;
    const icon = this.dutyForm.value.icon!;

    const newDef: FieldDefinition = {
      fieldName: 'food-property',
      label: { de: labelDe },
      jsonSchema: { type: 'boolean' },
      required: false,
      properties: { icon },
    };

    this.fieldDefService.create(newDef).subscribe((created) => {
      const entries = this.dutySettingsOrg!.entries.map((e) => {
        const defIds = e.definitions.map((d) => d.id!);
        if (e.name === 'cooking') {
          defIds.push(created.id!);
        }
        return { name: e.name, definitionIds: defIds };
      });
      this.orgService.update(this.dutySettingsOrg!.id, { definitionIds: [], entries }).subscribe(() => {
        this.dutyForm.reset({ icon: 'restaurant' });
        this.loadDutySettings();
      });
    });
  }

  deleteFoodProperty(def: FieldDefinition): void {
    if (!this.dutySettingsOrg) return;
    this.fieldDefService.outdate(def.id!).subscribe(() => {
      const entries = this.dutySettingsOrg!.entries.map((e) => ({
        name: e.name,
        definitionIds: e.definitions.filter((d) => d.id !== def.id).map((d) => d.id!),
      }));
      this.orgService.update(this.dutySettingsOrg!.id, { definitionIds: [], entries }).subscribe(() => {
        this.loadDutySettings();
      });
    });
  }
}
```

- [ ] **Step 2: Create organisation component HTML**

Create `frontend/src/app/settings/organisation/organisation.component.html`:

```html
<div class="organisation-container">
  <h2>Organisation</h2>

  <mat-tab-group>
    <!-- Groups Tab -->
    <mat-tab label="Gruppen">
      <div class="tab-content">
        <form [formGroup]="groupForm" (ngSubmit)="addGroup()" class="add-form">
          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Gruppenname</mat-label>
              <input matInput formControlName="labelDe">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Farbe</mat-label>
              <input matInput formControlName="color" type="color">
            </mat-form-field>
            <button mat-raised-button color="primary" type="submit" [disabled]="!groupForm.valid">
              Gruppe hinzufuegen
            </button>
          </div>
        </form>

        <table mat-table [dataSource]="groupsDataSource" class="mat-elevation-z2">
          <ng-container matColumnDef="label">
            <th mat-header-cell *matHeaderCellDef>Name</th>
            <td mat-cell *matCellDef="let row">{{ row.label?.de }}</td>
          </ng-container>
          <ng-container matColumnDef="color">
            <th mat-header-cell *matHeaderCellDef>Farbe</th>
            <td mat-cell *matCellDef="let row">
              <span class="color-swatch" [style.background-color]="row.properties?.color"></span>
            </td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let row">
              <button mat-icon-button color="warn" (click)="deleteGroup(row)" title="Entfernen">
                <mat-icon>delete</mat-icon>
              </button>
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="groupColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: groupColumns;"></tr>
        </table>
      </div>
    </mat-tab>

    <!-- Duty Settings Tab -->
    <mat-tab label="Dienst-Einstellungen">
      <div class="tab-content">
        <h3>Kochdienst — Essen-Eigenschaften</h3>

        <form [formGroup]="dutyForm" (ngSubmit)="addFoodProperty()" class="add-form">
          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Bezeichnung</mat-label>
              <input matInput formControlName="labelDe">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Icon (Material Icon)</mat-label>
              <input matInput formControlName="icon">
            </mat-form-field>
            <button mat-raised-button color="primary" type="submit" [disabled]="!dutyForm.valid">
              Eigenschaft hinzufuegen
            </button>
          </div>
        </form>

        <table mat-table [dataSource]="cookingDataSource" class="mat-elevation-z2">
          <ng-container matColumnDef="label">
            <th mat-header-cell *matHeaderCellDef>Bezeichnung</th>
            <td mat-cell *matCellDef="let row">{{ row.label?.de }}</td>
          </ng-container>
          <ng-container matColumnDef="icon">
            <th mat-header-cell *matHeaderCellDef>Icon</th>
            <td mat-cell *matCellDef="let row">
              <mat-icon>{{ row.properties?.icon }}</mat-icon>
            </td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let row">
              <button mat-icon-button color="warn" (click)="deleteFoodProperty(row)" title="Entfernen">
                <mat-icon>delete</mat-icon>
              </button>
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="dutyColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: dutyColumns;"></tr>
        </table>
      </div>
    </mat-tab>
  </mat-tab-group>
</div>
```

- [ ] **Step 3: Create organisation component styles**

Create `frontend/src/app/settings/organisation/organisation.component.scss`:

```scss
.organisation-container {
  padding: 24px;
}

.tab-content {
  padding: 16px 0;
}

.add-form {
  margin-bottom: 24px;

  .row {
    display: flex;
    gap: 16px;
    align-items: center;
    flex-wrap: wrap;
  }
}

.color-swatch {
  display: inline-block;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  border: 1px solid #ccc;
}

table {
  width: 100%;
}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/settings/organisation/
git commit -m "feat: add admin Organisation component with groups and duty settings tabs"
```

---

## Task 10: Cooking duty dialog component

**Files:**
- Create: `frontend/src/app/cooking/cooking-duty-dialog.component.ts`
- Create: `frontend/src/app/cooking/cooking-duty-dialog.component.html`
- Create: `frontend/src/app/cooking/cooking-duty-dialog.component.scss`

- [ ] **Step 1: Create dialog component TypeScript**

Create `frontend/src/app/cooking/cooking-duty-dialog.component.ts`:

```typescript
import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FieldDefinition } from '../shared/models/field-definition.model';
import { CookingDutyDTO } from '../shared/models/organisation.model';
import { PersonDTO } from '../shared/models/person.model';

export interface CookingDutyDialogData {
  groups: FieldDefinition[];
  foodProperties: FieldDefinition[];
  familyParents: PersonDTO[];
  currentUserId: string;
  existingDuty?: CookingDutyDTO;
  canEdit: boolean;
}

export interface CookingDutyDialogResult {
  action: 'save' | 'delete';
  date: string;
  groups: string[];
  personId: string;
  description: string;
  foodProperties: Record<string, boolean>;
}

@Component({
  selector: 'app-cooking-duty-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatFormFieldModule, MatInputModule,
    MatDatepickerModule, MatNativeDateModule, MatCheckboxModule,
    MatSelectModule, MatButtonModule, MatIconModule,
  ],
  templateUrl: './cooking-duty-dialog.component.html',
  styleUrl: './cooking-duty-dialog.component.scss',
})
export class CookingDutyDialogComponent implements OnInit {
  form!: FormGroup;
  isEdit: boolean;
  canEdit: boolean;

  constructor(
    private dialogRef: MatDialogRef<CookingDutyDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: CookingDutyDialogData,
  ) {
    this.isEdit = !!data.existingDuty;
    this.canEdit = data.canEdit;
  }

  ngOnInit(): void {
    const duty = this.data.existingDuty;

    this.form = new FormGroup({
      date: new FormControl(duty ? new Date(duty.date) : null, Validators.required),
      person: new FormControl(
        duty ? duty.personId : this.data.currentUserId,
        Validators.required,
      ),
      description: new FormControl(duty?.description ?? ''),
    });

    // Add group checkboxes
    for (const group of this.data.groups) {
      const isChecked = duty ? duty.groups.includes(group.id!) : false;
      this.form.addControl('group_' + group.id, new FormControl(isChecked));
    }

    // Add food property checkboxes
    for (const fp of this.data.foodProperties) {
      const isChecked = duty ? duty.foodProperties[fp.id!] === true : false;
      this.form.addControl('food_' + fp.id, new FormControl(isChecked));
    }

    if (!this.canEdit) {
      this.form.disable();
    }
  }

  getParentName(parent: PersonDTO): string {
    const lastName = this.getFieldValue(parent, 'lastName');
    const firstName = this.getFieldValue(parent, 'firstName');
    return `${lastName} ${firstName}`.trim();
  }

  private getFieldValue(person: PersonDTO, fieldName: string): string {
    const field = person.basicProperties?.find((f) => f.fieldName === fieldName);
    return (field?.value as string) ?? '';
  }

  hasSelectedGroups(): boolean {
    return this.data.groups.some((g) => this.form.get('group_' + g.id)?.value);
  }

  save(): void {
    if (!this.form.valid || !this.hasSelectedGroups()) return;

    const dateValue: Date = this.form.value.date;
    const dateStr = dateValue.toISOString().split('T')[0];

    const selectedGroups = this.data.groups
      .filter((g) => this.form.get('group_' + g.id)?.value)
      .map((g) => g.id!);

    const foodProps: Record<string, boolean> = {};
    for (const fp of this.data.foodProperties) {
      if (this.form.get('food_' + fp.id)?.value) {
        foodProps[fp.id!] = true;
      }
    }

    this.dialogRef.close({
      action: 'save',
      date: dateStr,
      groups: selectedGroups,
      personId: this.form.value.person,
      description: this.form.value.description,
      foodProperties: foodProps,
    } as CookingDutyDialogResult);
  }

  deleteDuty(): void {
    this.dialogRef.close({ action: 'delete' } as CookingDutyDialogResult);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
```

- [ ] **Step 2: Create dialog component HTML**

Create `frontend/src/app/cooking/cooking-duty-dialog.component.html`:

```html
<h2 mat-dialog-title>{{ isEdit ? 'Kocheinteilung bearbeiten' : 'Neuen Kochdienst eintragen' }}</h2>

<mat-dialog-content>
  <form [formGroup]="form">
    <mat-form-field appearance="outline" class="full-width">
      <mat-label>Wann</mat-label>
      <input matInput [matDatepicker]="picker" formControlName="date">
      <mat-datepicker-toggle matIconSuffix [for]="picker"></mat-datepicker-toggle>
      <mat-datepicker #picker></mat-datepicker>
    </mat-form-field>

    <div class="checkbox-group">
      <label class="group-label">Fuer welche Gruppen *</label>
      @for (group of data.groups; track group.id) {
        <mat-checkbox [formControlName]="'group_' + group.id">
          <span class="color-dot" [style.background-color]="group.properties?.['color']"></span>
          {{ group.label?.de }}
        </mat-checkbox>
      }
    </div>

    <mat-form-field appearance="outline" class="full-width">
      <mat-label>Wer kocht</mat-label>
      <mat-select formControlName="person">
        @for (parent of data.familyParents; track parent.id) {
          <mat-option [value]="parent.id">{{ getParentName(parent) }}</mat-option>
        }
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline" class="full-width">
      <mat-label>Was wird gekocht</mat-label>
      <textarea matInput formControlName="description" rows="3"></textarea>
    </mat-form-field>

    <div class="checkbox-group">
      <label class="group-label">Essen ist</label>
      @for (fp of data.foodProperties; track fp.id) {
        <mat-checkbox [formControlName]="'food_' + fp.id">
          <mat-icon class="food-icon">{{ fp.properties?.['icon'] }}</mat-icon>
          {{ fp.label?.de }}
        </mat-checkbox>
      }
    </div>
  </form>
</mat-dialog-content>

<mat-dialog-actions align="end">
  @if (isEdit && canEdit) {
    <button mat-button color="warn" (click)="deleteDuty()">Loeschen</button>
  }
  <button mat-button (click)="cancel()">Abbrechen</button>
  @if (canEdit) {
    <button mat-raised-button color="primary" (click)="save()"
            [disabled]="!form.valid || !hasSelectedGroups()">
      {{ isEdit ? 'Speichern' : 'Erstellen' }}
    </button>
  }
</mat-dialog-actions>
```

- [ ] **Step 3: Create dialog component styles**

Create `frontend/src/app/cooking/cooking-duty-dialog.component.scss`:

```scss
.full-width {
  width: 100%;
}

.checkbox-group {
  margin: 16px 0;

  .group-label {
    display: block;
    font-size: 12px;
    color: rgba(0, 0, 0, 0.6);
    margin-bottom: 8px;
  }

  mat-checkbox {
    display: block;
    margin-bottom: 4px;
  }
}

.color-dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}

.food-icon {
  font-size: 18px;
  height: 18px;
  width: 18px;
  margin-right: 4px;
  vertical-align: middle;
}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/cooking/cooking-duty-dialog.*
git commit -m "feat: add CookingDutyDialogComponent for create/edit/delete"
```

---

## Task 11: Cooking calendar component

**Files:**
- Create: `frontend/src/app/cooking/cooking.component.ts`
- Create: `frontend/src/app/cooking/cooking.component.html`
- Create: `frontend/src/app/cooking/cooking.component.scss`

- [ ] **Step 1: Create cooking component TypeScript**

Create `frontend/src/app/cooking/cooking.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { CalendarEvent, CalendarModule, CalendarDateFormatter } from 'angular-calendar';
import { adapterFactory } from 'angular-calendar/date-adapters/date-fns';
import { Subject } from 'rxjs';
import { OrganisationService } from '../shared/services/organisation.service';
import { CookingDutyService } from './services/cooking-duty.service';
import { PersonService } from '../shared/services/person.service';
import { FieldInstanceService } from '../shared/services/field-instance.service';
import { FieldDefinition } from '../shared/models/field-definition.model';
import { CookingDutyDTO, OrganisationDTO } from '../shared/models/organisation.model';
import { PersonDTO, SectionInput } from '../shared/models/person.model';
import {
  CookingDutyDialogComponent,
  CookingDutyDialogData,
  CookingDutyDialogResult,
} from './cooking-duty-dialog.component';
import { format } from 'date-fns';

@Component({
  selector: 'app-cooking',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule, MatIconModule, MatCheckboxModule, MatDialogModule,
    CalendarModule,
  ],
  templateUrl: './cooking.component.html',
  styleUrl: './cooking.component.scss',
})
export class CookingComponent implements OnInit {
  viewDate = new Date();
  refresh = new Subject<void>();
  events: CalendarEvent[] = [];
  excludeDays: number[] = [0, 6]; // Exclude Saturday and Sunday

  groups: FieldDefinition[] = [];
  activeGroupIds: Set<string> = new Set();
  foodProperties: FieldDefinition[] = [];
  duties: CookingDutyDTO[] = [];

  // Current user family data
  familyParents: PersonDTO[] = [];
  currentFamilyId = '';
  currentPersonId = '';

  // Cooking duty FieldDefinition ID
  private cookingDutyDefId = '';

  constructor(
    private orgService: OrganisationService,
    private cookingDutyService: CookingDutyService,
    private personService: PersonService,
    private fieldInstanceService: FieldInstanceService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadOrganisationData();
  }

  private loadOrganisationData(): void {
    this.orgService.getByTag('groups').subscribe((org) => {
      this.groups = org.definitions;
      // Initially all groups active (later: filter by child group membership)
      this.groups.forEach((g) => this.activeGroupIds.add(g.id!));
      this.loadDuties();
    });

    this.orgService.getByTag('duty-settings').subscribe((org) => {
      const cooking = org.entries.find((e) => e.name === 'cooking');
      this.foodProperties = cooking?.definitions ?? [];
    });

    // TODO: Load current user's family and parents when auth is implemented
    // For now, load all persons as a fallback
    this.personService.list().subscribe((persons) => {
      // This will be refined when auth provides the current user's familyId
    });
  }

  loadDuties(): void {
    const month = format(this.viewDate, 'yyyy-MM');
    const activeGroups = Array.from(this.activeGroupIds);
    this.cookingDutyService.getByMonth(month, activeGroups).subscribe((duties) => {
      this.duties = duties;
      this.buildCalendarEvents();
    });
  }

  private buildCalendarEvents(): void {
    this.events = this.duties.map((duty) => {
      // Find the first group's color
      const firstGroupId = duty.groups[0];
      const group = this.groups.find((g) => g.id === firstGroupId);
      const color = (group?.properties?.['color'] as string) ?? '#999';

      // Build food property icons
      const icons = this.getFoodIcons(duty);
      const iconStr = icons.length > 0 ? ' ' + icons.join(' ') : '';

      return {
        start: new Date(duty.date + 'T00:00:00'),
        title: `${duty.personName} - ${duty.description || ''}${iconStr}`,
        color: { primary: color, secondary: color + '33' },
        meta: duty,
      } as CalendarEvent;
    });
    this.refresh.next();
  }

  getFoodIcons(duty: CookingDutyDTO): string[] {
    const icons: string[] = [];
    for (const fp of this.foodProperties) {
      if (duty.foodProperties[fp.id!]) {
        const icon = fp.properties?.['icon'] as string;
        if (icon) icons.push(icon);
      }
    }
    return icons;
  }

  toggleGroup(groupId: string): void {
    if (this.activeGroupIds.has(groupId)) {
      this.activeGroupIds.delete(groupId);
    } else {
      this.activeGroupIds.add(groupId);
    }
    this.loadDuties();
  }

  isGroupActive(groupId: string): boolean {
    return this.activeGroupIds.has(groupId);
  }

  previousMonth(): void {
    this.viewDate = new Date(this.viewDate.getFullYear(), this.viewDate.getMonth() - 1, 1);
    this.loadDuties();
  }

  nextMonth(): void {
    this.viewDate = new Date(this.viewDate.getFullYear(), this.viewDate.getMonth() + 1, 1);
    this.loadDuties();
  }

  today(): void {
    this.viewDate = new Date();
    this.loadDuties();
  }

  openCreateDialog(): void {
    this.openDialog();
  }

  onEventClicked(event: CalendarEvent): void {
    const duty = event.meta as CookingDutyDTO;
    const canEdit = duty.familyId === this.currentFamilyId; // TODO: also check admin role
    this.openDialog(duty, canEdit);
  }

  private openDialog(existingDuty?: CookingDutyDTO, canEdit = true): void {
    const data: CookingDutyDialogData = {
      groups: this.groups,
      foodProperties: this.foodProperties,
      familyParents: this.familyParents,
      currentUserId: this.currentPersonId,
      existingDuty,
      canEdit,
    };

    const dialogRef = this.dialog.open(CookingDutyDialogComponent, {
      width: '500px',
      data,
    });

    dialogRef.afterClosed().subscribe((result: CookingDutyDialogResult | undefined) => {
      if (!result) return;

      if (result.action === 'delete' && existingDuty) {
        this.deleteCookingDuty(existingDuty);
      } else if (result.action === 'save') {
        if (existingDuty) {
          this.updateCookingDuty(existingDuty, result);
        } else {
          this.createCookingDuty(result);
        }
      }
    });
  }

  private createCookingDuty(result: CookingDutyDialogResult): void {
    const value = {
      date: result.date,
      groups: result.groups,
      description: result.description,
      foodProperties: result.foodProperties,
    };

    // Create field instance
    this.fieldInstanceService.create({
      definitionId: this.cookingDutyDefId,
      value,
    }).subscribe((created) => {
      // Add to person's schedules
      this.personService.getFull(result.personId).subscribe((person) => {
        const scheduleInput: SectionInput = {
          definitionId: this.cookingDutyDefId,
          value,
        };
        // Reload duties after save
        this.loadDuties();
      });
    });
  }

  private updateCookingDuty(existing: CookingDutyDTO, result: CookingDutyDialogResult): void {
    const value = {
      date: result.date,
      groups: result.groups,
      description: result.description,
      foodProperties: result.foodProperties,
    };

    this.fieldInstanceService.update(existing.id, {
      definitionId: this.cookingDutyDefId,
      value,
    }).subscribe(() => {
      this.loadDuties();
    });
  }

  private deleteCookingDuty(duty: CookingDutyDTO): void {
    this.fieldInstanceService.delete(duty.id).subscribe(() => {
      // TODO: also remove FieldRef from person.schedules
      this.loadDuties();
    });
  }
}
```

- [ ] **Step 2: Create cooking component HTML**

Create `frontend/src/app/cooking/cooking.component.html`:

```html
<div class="cooking-container">
  <div class="header">
    <h2>Kochen</h2>
    <button mat-raised-button color="primary" (click)="openCreateDialog()">
      Neuen Kochdienst eintragen
    </button>
  </div>

  <div class="controls">
    <div class="navigation">
      <button mat-icon-button (click)="previousMonth()">
        <mat-icon>chevron_left</mat-icon>
      </button>
      <button mat-stroked-button (click)="today()">Heute</button>
      <button mat-icon-button (click)="nextMonth()">
        <mat-icon>chevron_right</mat-icon>
      </button>
      <span class="month-label">
        {{ viewDate | date:'MMMM yyyy' }}
      </span>
    </div>

    <div class="group-filters">
      @for (group of groups; track group.id) {
        <mat-checkbox
          [checked]="isGroupActive(group.id!)"
          (change)="toggleGroup(group.id!)"
          [style.--checkbox-color]="group.properties?.['color']">
          <span class="color-dot" [style.background-color]="group.properties?.['color']"></span>
          {{ group.label?.de }}
        </mat-checkbox>
      }
    </div>
  </div>

  <div class="calendar-wrapper">
    <mwl-calendar-month-view
      [viewDate]="viewDate"
      [events]="events"
      [excludeDays]="excludeDays"
      [refresh]="refresh"
      (eventClicked)="onEventClicked($event.event)"
      [locale]="'de'"
      [weekStartsOn]="1">
    </mwl-calendar-month-view>
  </div>
</div>
```

- [ ] **Step 3: Create cooking component styles**

Create `frontend/src/app/cooking/cooking.component.scss`:

```scss
.cooking-container {
  padding: 24px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.controls {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 16px;
}

.navigation {
  display: flex;
  align-items: center;
  gap: 8px;

  .month-label {
    font-size: 18px;
    font-weight: 500;
    margin-left: 8px;
  }
}

.group-filters {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.color-dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}

.calendar-wrapper {
  ::ng-deep {
    .cal-month-view {
      .cal-cell-top {
        min-height: 30px;
      }

      .cal-event {
        border-radius: 4px;
        padding: 2px 4px;
        font-size: 11px;
        margin-bottom: 2px;
        cursor: pointer;
      }

      .cal-day-number {
        font-size: 14px;
      }
    }
  }
}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/cooking/cooking.component.*
git commit -m "feat: add CookingComponent with angular-calendar month view"
```

---

## Task 12: Routing and sidebar navigation

**Files:**
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/app.component.html`

- [ ] **Step 1: Add routes**

In `frontend/src/app/app.routes.ts`, add the cooking route and organisation settings route. The full file should be:

```typescript
import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: 'cooking',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./cooking/cooking.component').then((m) => m.CookingComponent),
  },
  {
    path: 'administration',
    canActivate: [authGuard],
    children: [
      {
        path: 'families',
        loadComponent: () =>
          import('./administration/families/family-list/family-list.component').then(
            (m) => m.FamilyListComponent
          ),
      },
    ],
  },
  {
    path: 'settings',
    canActivate: [authGuard],
    children: [
      {
        path: 'organisation',
        loadComponent: () =>
          import('./settings/organisation/organisation.component').then(
            (m) => m.OrganisationComponent
          ),
      },
      {
        path: 'custom-fields',
        loadComponent: () =>
          import('./settings/custom-fields/custom-fields.component').then(
            (m) => m.CustomFieldsComponent
          ),
      },
      {
        path: 'permissions',
        loadComponent: () =>
          import('./settings/permissions/permissions.component').then(
            (m) => m.PermissionsComponent
          ),
      },
    ],
  },
  { path: '', redirectTo: 'cooking', pathMatch: 'full' },
];
```

Note: Default route changed from `administration/families` to `cooking` — this is the primary parent view now.

- [ ] **Step 2: Add sidebar links**

In `frontend/src/app/app.component.html`, add the "Kochen" link before the "Familien" link, and "Organisation" after the divider. Add both links in the `<mat-nav-list>`:

After `<mat-nav-list>`, add as the first item:
```html
      <a mat-list-item routerLink="/cooking" routerLinkActive="active">
        <mat-icon matListItemIcon>restaurant</mat-icon>
        <span matListItemTitle>Kochen</span>
      </a>
```

After the `<mat-divider>`, before the "Benutzerdefinierte Felder" link, add:
```html
      <a mat-list-item routerLink="/settings/organisation" routerLinkActive="active">
        <mat-icon matListItemIcon>business</mat-icon>
        <span matListItemTitle>Organisation</span>
      </a>
```

- [ ] **Step 3: Verify frontend compiles**

Run: `cd frontend && npx ng build --configuration development 2>&1 | tail -10`
Expected: Build should succeed

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/app.routes.ts frontend/src/app/app.component.html
git commit -m "feat: add cooking and organisation routes and sidebar navigation"
```

---

## Task 13: Angular Calendar module registration

**Files:**
- Modify: `frontend/src/app/app.config.ts`

- [ ] **Step 1: Register CalendarModule provider**

Read `frontend/src/app/app.config.ts` first to understand current structure.

Add the `angular-calendar` date adapter provider. Add to the `providers` array:

```typescript
import { CalendarModule, DateAdapter } from 'angular-calendar';
import { adapterFactory } from 'angular-calendar/date-adapters/date-fns';
```

Add to providers:
```typescript
...CalendarModule.forRoot({
  provide: DateAdapter,
  useFactory: adapterFactory,
}).providers ?? [],
```

Also add `angular-calendar` CSS to the project. In `frontend/angular.json`, add to the `styles` array:
```json
"node_modules/angular-calendar/css/angular-calendar.css"
```

- [ ] **Step 2: Verify frontend compiles**

Run: `cd frontend && npx ng build --configuration development 2>&1 | tail -10`
Expected: Build should succeed

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/app.config.ts frontend/angular.json
git commit -m "feat: register angular-calendar module and styles"
```

---

## Task 14: Frontend field-instance.service.ts — verify update/delete methods

**Files:**
- Possibly modify: `frontend/src/app/shared/services/field-instance.service.ts`

- [ ] **Step 1: Check existing service**

Read `frontend/src/app/shared/services/field-instance.service.ts`. The cooking component needs `create`, `update`, and `delete` methods. Verify they exist.

If `update` or `delete` methods are missing, add them following the existing pattern:

```typescript
update(id: string, fieldInstance: FieldInstance): Observable<FieldInstance> {
  return this.api.put<FieldInstance>(`/field-instances/${id}`, fieldInstance);
}

delete(id: string): Observable<void> {
  return this.api.delete(`/field-instances/${id}`);
}
```

- [ ] **Step 2: Commit if changes were needed**

```bash
git add frontend/src/app/shared/services/field-instance.service.ts
git commit -m "feat: add update/delete methods to FieldInstanceService"
```

---

## Task 15: End-to-end smoke test

- [ ] **Step 1: Start backend**

Run: `cd backend && ./mvnw quarkus:dev`

Verify:
- Seed migrations run (check logs for "OrganisationSeedMigration" and "FieldDefinitionSeedMigration")
- `GET http://localhost:8080/api/v1/organisation` returns 2 documents (groups + duty-settings)
- `GET http://localhost:8080/api/v1/organisation/groups` returns groups document with empty definitions
- `GET http://localhost:8080/api/v1/organisation/duty-settings` returns duty-settings with 6 food-property definitions under cooking
- `GET http://localhost:8080/api/v1/cooking-duties?month=2026-06` returns empty array

- [ ] **Step 2: Start frontend**

Run: `cd frontend && npm start`

Verify:
- Sidebar shows "Kochen" link with restaurant icon
- Sidebar shows "Organisation" link under settings
- Clicking "Kochen" shows calendar month view (Mo-Fr)
- Clicking "Organisation" shows admin page with Groups and Duty-Settings tabs
- Duty-Settings tab shows 6 seeded food-property entries with icons

- [ ] **Step 3: Manual CRUD test**

In Organisation admin:
1. Add a group "Kindergruppe" with color blue — verify it appears in table
2. Add a group "Vorschulgruppe" with color green — verify it appears
3. Switch to Kochen page — verify group checkboxes appear with correct colors
4. Click "Neuen Kochdienst eintragen" — verify dialog opens with groups and food properties
