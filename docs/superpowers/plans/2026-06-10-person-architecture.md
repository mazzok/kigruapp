# Person Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace separate `parents`/`children` collections with a unified `persons` collection using categorized field implementations (basicProperties, roles, schedules, duties, finance, customProperties), each referencing field_instances that hold actual values.

**Architecture:** Person documents hold references (`{definitionId, fieldInstanceId}`) grouped into fixed sections. Values live in `field_instances`. FieldDefinitions provide JSON Schema for validation and GUI rendering. Keycloak integration uses a `keycloakMapping` field on definitions.

**Tech Stack:** Quarkus 3.36 + MongoDB Panache (backend), Angular 18 + Material (frontend), JSON Schema v7 validation, Keycloak 25 admin client.

**Spec:** `docs/superpowers/specs/2026-06-10-person-architecture-design.md`

---

## File Structure

### Backend — Create

| File | Responsibility |
|------|----------------|
| `backend/src/main/java/at/kigruapp/entity/Person.java` | Person entity with section arrays |
| `backend/src/main/java/at/kigruapp/entity/FieldRef.java` | Embeddable `{definitionId, fieldInstanceId}` record |
| `backend/src/main/java/at/kigruapp/dto/PersonDTO.java` | Full person DTO with resolved field values |
| `backend/src/main/java/at/kigruapp/dto/PersonSectionDTO.java` | Section DTO with resolved FieldInstanceDTOs |
| `backend/src/main/java/at/kigruapp/resource/PersonResource.java` | REST endpoints for persons |
| `backend/src/main/java/at/kigruapp/migration/PersonArchitectureMigration.java` | Migrate parents+children to persons |
| `backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java` | Seed initial basicProperty definitions |
| `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java` | Person REST tests |

### Backend — Modify

| File | Change |
|------|--------|
| `backend/src/main/java/at/kigruapp/entity/FieldDefinition.java` | Remove `entity` field, add `keycloakMapping` |
| `backend/src/main/java/at/kigruapp/entity/FieldInstance.java` | Remove `entityType`, `entityId` fields |
| `backend/src/main/java/at/kigruapp/resource/FieldDefinitionResource.java` | Remove entity-based filtering, add keycloakMapping |
| `backend/src/main/java/at/kigruapp/resource/FieldInstanceResource.java` | Simplify — remove entity-based queries |
| `backend/src/main/java/at/kigruapp/dto/FieldInstanceDTO.java` | Add `keycloakMapping` field |
| `backend/src/main/java/at/kigruapp/resource/FamilyResource.java` | Replace `/parents` + `/children` with `/persons` |
| `backend/src/main/java/at/kigruapp/entity/EntityType.java` | Remove file (no longer needed) |
| `backend/src/main/java/at/kigruapp/security/KeycloakUserService.java` | No changes needed (already takes email/firstName/lastName) |

### Backend — Delete

| File | Reason |
|------|--------|
| `backend/src/main/java/at/kigruapp/entity/Parent.java` | Replaced by Person |
| `backend/src/main/java/at/kigruapp/entity/Child.java` | Replaced by Person |
| `backend/src/main/java/at/kigruapp/entity/Address.java` | Address becomes a JSON Schema object field |
| `backend/src/main/java/at/kigruapp/resource/ParentResource.java` | Replaced by PersonResource |
| `backend/src/main/java/at/kigruapp/resource/ChildResource.java` | Replaced by PersonResource |
| `backend/src/test/java/at/kigruapp/resource/ParentResourceTest.java` | Replaced by PersonResourceTest |
| `backend/src/test/java/at/kigruapp/resource/ChildResourceTest.java` | Replaced by PersonResourceTest |

### Frontend — Create

| File | Responsibility |
|------|----------------|
| `frontend/src/app/shared/models/person.model.ts` | Person + FieldRef + PersonDTO interfaces |
| `frontend/src/app/shared/services/person.service.ts` | PersonResource HTTP client |
| `frontend/src/app/shared/components/section-form/section-form.component.ts` | Renders one section's fields |

### Frontend — Modify

| File | Change |
|------|--------|
| `frontend/src/app/shared/models/field-definition.model.ts` | Remove EntityType, add keycloakMapping |
| `frontend/src/app/shared/models/field-instance.model.ts` | Remove entityType/entityId from FieldInstance |
| `frontend/src/app/shared/components/json-schema-field/json-schema-field.component.ts` | Add time + object/fieldgroup support |
| `frontend/src/app/shared/components/custom-fields-form/custom-fields-form.component.ts` | Refactor to use sections |
| `frontend/src/app/shared/services/field-instance.service.ts` | Remove entityType params |
| `frontend/src/app/settings/custom-fields/services/field-definition.service.ts` | Remove entity-based methods |
| `frontend/src/app/settings/custom-fields/custom-fields.component.ts` | Remove entity filter, add keycloakMapping |
| `frontend/src/app/administration/families/family-wizard/family-wizard.component.ts` | Use PersonService instead of Parent/ChildService |
| `frontend/src/app/administration/families/family-wizard/steps/child-step.component.ts` | Replace hardcoded form with SectionFormComponent |
| `frontend/src/app/administration/families/family-wizard/steps/parents-step.component.ts` | Replace hardcoded form with SectionFormComponent |
| `frontend/src/app/administration/families/family-list/family-list.component.ts` | Use PersonService for listing |

### Frontend — Delete

| File | Reason |
|------|--------|
| `frontend/src/app/shared/models/parent.model.ts` | Replaced by person.model.ts |
| `frontend/src/app/shared/models/child.model.ts` | Replaced by person.model.ts |
| `frontend/src/app/administration/families/services/parent.service.ts` | Replaced by person.service.ts |
| `frontend/src/app/administration/families/services/child.service.ts` | Replaced by person.service.ts |

---

## Task 1: Backend — FieldRef Embeddable and Person Entity

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/FieldRef.java`
- Create: `backend/src/main/java/at/kigruapp/entity/Person.java`

- [ ] **Step 1: Create FieldRef record**

```java
package at.kigruapp.entity;

import org.bson.types.ObjectId;

public class FieldRef {
    public ObjectId definitionId;
    public ObjectId fieldInstanceId;

    public FieldRef() {}

    public FieldRef(ObjectId definitionId, ObjectId fieldInstanceId) {
        this.definitionId = definitionId;
        this.fieldInstanceId = fieldInstanceId;
    }
}
```

- [ ] **Step 2: Create Person entity**

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "persons")
public class Person extends PanacheMongoEntity {
    public ObjectId familyId;
    public String keycloakUserId;
    public List<FieldRef> basicProperties = new ArrayList<>();
    public List<FieldRef> roles = new ArrayList<>();
    public List<FieldRef> schedules = new ArrayList<>();
    public List<FieldRef> duties = new ArrayList<>();
    public List<FieldRef> finance = new ArrayList<>();
    public List<FieldRef> customProperties = new ArrayList<>();
    public Instant createdAt;
    public Instant updatedAt;

    public static List<Person> findByFamilyId(ObjectId familyId) {
        return list("familyId", familyId);
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/FieldRef.java backend/src/main/java/at/kigruapp/entity/Person.java
git commit -m "feat: add Person entity and FieldRef embeddable"
```

---

## Task 2: Backend — Update FieldDefinition (remove entity, add keycloakMapping)

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/FieldDefinition.java`

- [ ] **Step 1: Update FieldDefinition.java**

Replace the entire file content with:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@MongoEntity(collection = "field_definitions")
public class FieldDefinition extends PanacheMongoEntity {
    public String fieldName;
    public Map<String, String> label;
    public String description;
    public Map<String, Object> jsonSchema;
    public boolean required;
    public String keycloakMapping;
    public Instant createdAt;
    public Instant outdatedAt;

    public static List<FieldDefinition> findActive() {
        return list("outdatedAt = null");
    }

    public static FieldDefinition findByKeycloakMapping(String mapping) {
        return find("keycloakMapping", mapping).firstResult();
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd backend && ./mvnw compile -q`
Expected: Compilation errors — this is expected. FieldDefinitionResource and FieldInstanceResource still reference the removed `entity` field and `EntityType`. We'll fix those in the next tasks.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/FieldDefinition.java
git commit -m "refactor: remove entity field from FieldDefinition, add keycloakMapping"
```

---

## Task 3: Backend — Update FieldInstance (remove entityType/entityId)

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/FieldInstance.java`

- [ ] **Step 1: Update FieldInstance.java**

Replace the entire file content with:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.List;

@MongoEntity(collection = "field_instances")
public class FieldInstance extends PanacheMongoEntity {
    public ObjectId definitionId;
    public Object value;
    public Instant createdAt;
    public Instant updatedAt;

    public static List<FieldInstance> findByDefinitionId(ObjectId definitionId) {
        return list("definitionId", definitionId);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/FieldInstance.java
git commit -m "refactor: simplify FieldInstance — remove entityType/entityId"
```

---

## Task 4: Backend — Update FieldInstanceDTO

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/dto/FieldInstanceDTO.java`

- [ ] **Step 1: Update FieldInstanceDTO.java**

Replace the entire file content with:

```java
package at.kigruapp.dto;

import java.util.Map;

public class FieldInstanceDTO {
    public String id;
    public String definitionId;
    public String fieldName;
    public Map<String, String> label;
    public String description;
    public Map<String, Object> jsonSchema;
    public boolean required;
    public String keycloakMapping;
    public Object value;
    public boolean definitionOutdated;
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/FieldInstanceDTO.java
git commit -m "refactor: add keycloakMapping to FieldInstanceDTO"
```

---

## Task 5: Backend — PersonDTO and PersonSectionDTO

**Files:**
- Create: `backend/src/main/java/at/kigruapp/dto/PersonDTO.java`
- Create: `backend/src/main/java/at/kigruapp/dto/PersonSectionDTO.java`

- [ ] **Step 1: Create PersonSectionDTO**

```java
package at.kigruapp.dto;

import java.util.List;

public class PersonSectionDTO {
    public String section;
    public List<FieldInstanceDTO> fields;

    public PersonSectionDTO() {}

    public PersonSectionDTO(String section, List<FieldInstanceDTO> fields) {
        this.section = section;
        this.fields = fields;
    }
}
```

- [ ] **Step 2: Create PersonDTO**

```java
package at.kigruapp.dto;

import java.util.List;

public class PersonDTO {
    public String id;
    public String familyId;
    public String keycloakUserId;
    public List<FieldInstanceDTO> basicProperties;
    public List<FieldInstanceDTO> roles;
    public List<FieldInstanceDTO> schedules;
    public List<FieldInstanceDTO> duties;
    public List<FieldInstanceDTO> finance;
    public List<FieldInstanceDTO> customProperties;
    public String createdAt;
    public String updatedAt;
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/PersonDTO.java backend/src/main/java/at/kigruapp/dto/PersonSectionDTO.java
git commit -m "feat: add PersonDTO and PersonSectionDTO"
```

---

## Task 6: Backend — Rewrite FieldDefinitionResource

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/FieldDefinitionResource.java`

- [ ] **Step 1: Rewrite FieldDefinitionResource.java**

Replace the entire file content with:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.service.JsonSchemaValidatorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.List;

@Path("/api/v1/field-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FieldDefinitionResource {

    @Inject
    JsonSchemaValidatorService schemaValidator;

    @GET
    public List<FieldDefinition> list(
            @QueryParam("active") @DefaultValue("false") boolean activeOnly) {
        if (activeOnly) {
            return FieldDefinition.findActive();
        }
        return FieldDefinition.listAll();
    }

    @GET
    @Path("/{id}")
    public FieldDefinition get(@PathParam("id") String id) {
        FieldDefinition def = FieldDefinition.findById(new ObjectId(id));
        if (def == null) {
            throw new NotFoundException();
        }
        return def;
    }

    @POST
    public Response create(FieldDefinition fieldDef) {
        if (fieldDef.jsonSchema == null || fieldDef.jsonSchema.isEmpty()) {
            return Response.status(400).entity("jsonSchema is required").build();
        }
        try {
            schemaValidator.validateSchema(fieldDef.jsonSchema);
        } catch (Exception e) {
            return Response.status(400).entity("Invalid JSON Schema: " + e.getMessage()).build();
        }
        fieldDef.createdAt = Instant.now();
        fieldDef.outdatedAt = null;
        fieldDef.persist();
        return Response.status(201).entity(fieldDef).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") String id, FieldDefinition update) {
        FieldDefinition fieldDef = FieldDefinition.findById(new ObjectId(id));
        if (fieldDef == null) {
            throw new NotFoundException();
        }
        if (update.jsonSchema != null && !update.jsonSchema.isEmpty()) {
            try {
                schemaValidator.validateSchema(update.jsonSchema);
            } catch (Exception e) {
                return Response.status(400).entity("Invalid JSON Schema: " + e.getMessage()).build();
            }
        }
        fieldDef.fieldName = update.fieldName;
        fieldDef.label = update.label;
        fieldDef.description = update.description;
        fieldDef.jsonSchema = update.jsonSchema;
        fieldDef.required = update.required;
        fieldDef.keycloakMapping = update.keycloakMapping;
        fieldDef.update();
        return Response.ok(fieldDef).build();
    }

    @PATCH
    @Path("/{id}/outdate")
    public Response outdate(@PathParam("id") String id) {
        FieldDefinition fieldDef = FieldDefinition.findById(new ObjectId(id));
        if (fieldDef == null) {
            throw new NotFoundException();
        }
        if (fieldDef.outdatedAt != null) {
            return Response.status(409).entity("Already outdated").build();
        }
        fieldDef.outdatedAt = Instant.now();
        fieldDef.update();
        return Response.ok(fieldDef).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        FieldDefinition fieldDef = FieldDefinition.findById(new ObjectId(id));
        if (fieldDef == null) {
            throw new NotFoundException();
        }
        fieldDef.outdatedAt = Instant.now();
        fieldDef.update();
        return Response.noContent().build();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/FieldDefinitionResource.java
git commit -m "refactor: remove entity-based filtering from FieldDefinitionResource"
```

---

## Task 7: Backend — Rewrite FieldInstanceResource

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/FieldInstanceResource.java`

- [ ] **Step 1: Rewrite FieldInstanceResource.java**

Replace the entire file content with:

```java
package at.kigruapp.resource;

import at.kigruapp.dto.FieldInstanceDTO;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.service.JsonSchemaValidatorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Path("/api/v1/field-instances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FieldInstanceResource {

    @Inject
    JsonSchemaValidatorService schemaValidator;

    @GET
    @Path("/{id}")
    public FieldInstanceDTO get(@PathParam("id") String id) {
        FieldInstance inst = FieldInstance.findById(new ObjectId(id));
        if (inst == null) {
            throw new NotFoundException();
        }
        FieldDefinition def = FieldDefinition.findById(inst.definitionId);
        return toDTO(def, inst);
    }

    public record BatchItem(String definitionId, Object value) {}

    @PUT
    @Path("/batch")
    public Response batchUpsert(List<BatchItem> items) {
        Instant now = Instant.now();
        List<FieldInstance> results = new ArrayList<>();

        for (BatchItem item : items) {
            ObjectId defId = new ObjectId(item.definitionId());
            FieldDefinition def = FieldDefinition.findById(defId);
            if (def == null) {
                return Response.status(400).entity("Definition not found: " + item.definitionId()).build();
            }
            if (def.outdatedAt != null) {
                return Response.status(400).entity("Definition is outdated: " + item.definitionId()).build();
            }
            if (item.value() != null) {
                try {
                    schemaValidator.validate(def.jsonSchema, item.value());
                } catch (JsonSchemaValidatorService.ValidationException e) {
                    return Response.status(400).entity(def.fieldName + ": " + e.getMessage()).build();
                }
            }

            FieldInstance inst = new FieldInstance();
            inst.definitionId = defId;
            inst.value = item.value();
            inst.createdAt = now;
            inst.updatedAt = now;
            inst.persist();
            results.add(inst);
        }
        return Response.ok(results).build();
    }

    private FieldInstanceDTO toDTO(FieldDefinition def, FieldInstance inst) {
        FieldInstanceDTO dto = new FieldInstanceDTO();
        dto.definitionId = def.id.toHexString();
        dto.fieldName = def.fieldName;
        dto.label = def.label;
        dto.description = def.description;
        dto.jsonSchema = def.jsonSchema;
        dto.required = def.required;
        dto.keycloakMapping = def.keycloakMapping;
        dto.definitionOutdated = def.outdatedAt != null;
        if (inst != null) {
            dto.id = inst.id.toHexString();
            dto.value = inst.value;
        }
        return dto;
    }
}
```

Key change: batch endpoint now always creates NEW instances (no upsert by entity), because each person gets its own instances. The person document tracks which instances belong to it.

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/FieldInstanceResource.java
git commit -m "refactor: simplify FieldInstanceResource — remove entity-based queries"
```

---

## Task 8: Backend — PersonResource

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/PersonResource.java`

- [ ] **Step 1: Create PersonResource.java**

```java
package at.kigruapp.resource;

import at.kigruapp.dto.FieldInstanceDTO;
import at.kigruapp.dto.PersonDTO;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import at.kigruapp.security.KeycloakUserService;
import at.kigruapp.service.JsonSchemaValidatorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Path("/api/v1/persons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PersonResource {

    @Inject
    KeycloakUserService keycloakUserService;

    @Inject
    JsonSchemaValidatorService schemaValidator;

    @GET
    public List<Person> list(
            @QueryParam("familyId") String familyId,
            @QueryParam("personType") String personType) {
        if (familyId != null) {
            return Person.findByFamilyId(new ObjectId(familyId));
        }
        return Person.listAll();
    }

    @GET
    @Path("/{id}")
    public Person get(@PathParam("id") String id) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) {
            throw new NotFoundException();
        }
        return person;
    }

    @GET
    @Path("/{id}/full")
    public PersonDTO getFull(@PathParam("id") String id) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) {
            throw new NotFoundException();
        }
        return toFullDTO(person);
    }

    public record CreatePersonRequest(
        String familyId,
        List<SectionInput> basicProperties,
        List<SectionInput> roles,
        List<SectionInput> schedules,
        List<SectionInput> duties,
        List<SectionInput> finance,
        List<SectionInput> customProperties
    ) {}

    public record SectionInput(String definitionId, Object value) {}

    @POST
    public Response create(CreatePersonRequest request) {
        Instant now = Instant.now();
        Person person = new Person();
        person.familyId = new ObjectId(request.familyId());
        person.createdAt = now;
        person.updatedAt = now;

        person.basicProperties = createFieldInstances(request.basicProperties(), now);
        person.roles = createFieldInstances(request.roles(), now);
        person.schedules = createFieldInstances(request.schedules(), now);
        person.duties = createFieldInstances(request.duties(), now);
        person.finance = createFieldInstances(request.finance(), now);
        person.customProperties = createFieldInstances(request.customProperties(), now);

        // Keycloak provisioning: find fields with keycloakMapping
        String email = null, firstName = null, lastName = null;
        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def != null && def.keycloakMapping != null) {
                FieldInstance inst = FieldInstance.findById(ref.fieldInstanceId);
                if (inst != null && inst.value != null) {
                    switch (def.keycloakMapping) {
                        case "email" -> email = inst.value.toString();
                        case "firstName" -> firstName = inst.value.toString();
                        case "lastName" -> lastName = inst.value.toString();
                    }
                }
            }
        }

        if (email != null && !email.isBlank()) {
            try {
                person.keycloakUserId = keycloakUserService.createUser(email, firstName, lastName);
            } catch (Exception e) {
                System.err.println("Keycloak user creation failed: " + e.getMessage());
            }
        }

        person.persist();
        return Response.status(201).entity(person).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") String id, Person update) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) {
            throw new NotFoundException();
        }
        person.basicProperties = update.basicProperties;
        person.roles = update.roles;
        person.schedules = update.schedules;
        person.duties = update.duties;
        person.finance = update.finance;
        person.customProperties = update.customProperties;
        person.updatedAt = Instant.now();
        person.update();
        return Response.ok(person).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) {
            throw new NotFoundException();
        }
        // Delete all associated field instances
        deleteFieldInstances(person.basicProperties);
        deleteFieldInstances(person.roles);
        deleteFieldInstances(person.schedules);
        deleteFieldInstances(person.duties);
        deleteFieldInstances(person.finance);
        deleteFieldInstances(person.customProperties);
        person.delete();
        return Response.noContent().build();
    }

    private List<FieldRef> createFieldInstances(List<SectionInput> inputs, Instant now) {
        if (inputs == null || inputs.isEmpty()) {
            return new ArrayList<>();
        }
        List<FieldRef> refs = new ArrayList<>();
        for (SectionInput input : inputs) {
            ObjectId defId = new ObjectId(input.definitionId());
            FieldDefinition def = FieldDefinition.findById(defId);
            if (def == null) {
                throw new BadRequestException("Definition not found: " + input.definitionId());
            }
            if (input.value() != null) {
                try {
                    schemaValidator.validate(def.jsonSchema, input.value());
                } catch (JsonSchemaValidatorService.ValidationException e) {
                    throw new BadRequestException(def.fieldName + ": " + e.getMessage());
                }
            }
            FieldInstance inst = new FieldInstance();
            inst.definitionId = defId;
            inst.value = input.value();
            inst.createdAt = now;
            inst.updatedAt = now;
            inst.persist();
            refs.add(new FieldRef(defId, inst.id));
        }
        return refs;
    }

    private void deleteFieldInstances(List<FieldRef> refs) {
        if (refs == null) return;
        for (FieldRef ref : refs) {
            FieldInstance inst = FieldInstance.findById(ref.fieldInstanceId);
            if (inst != null) {
                inst.delete();
            }
        }
    }

    private PersonDTO toFullDTO(Person person) {
        PersonDTO dto = new PersonDTO();
        dto.id = person.id.toHexString();
        dto.familyId = person.familyId.toHexString();
        dto.keycloakUserId = person.keycloakUserId;
        dto.basicProperties = resolveRefs(person.basicProperties);
        dto.roles = resolveRefs(person.roles);
        dto.schedules = resolveRefs(person.schedules);
        dto.duties = resolveRefs(person.duties);
        dto.finance = resolveRefs(person.finance);
        dto.customProperties = resolveRefs(person.customProperties);
        dto.createdAt = person.createdAt != null ? person.createdAt.toString() : null;
        dto.updatedAt = person.updatedAt != null ? person.updatedAt.toString() : null;
        return dto;
    }

    private List<FieldInstanceDTO> resolveRefs(List<FieldRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<FieldInstanceDTO> dtos = new ArrayList<>();
        for (FieldRef ref : refs) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            FieldInstance inst = FieldInstance.findById(ref.fieldInstanceId);
            if (def == null || inst == null) continue;

            FieldInstanceDTO dto = new FieldInstanceDTO();
            dto.id = inst.id.toHexString();
            dto.definitionId = def.id.toHexString();
            dto.fieldName = def.fieldName;
            dto.label = def.label;
            dto.description = def.description;
            dto.jsonSchema = def.jsonSchema;
            dto.required = def.required;
            dto.keycloakMapping = def.keycloakMapping;
            dto.value = inst.value;
            dto.definitionOutdated = def.outdatedAt != null;
            dtos.add(dto);
        }
        return dtos;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/PersonResource.java
git commit -m "feat: add PersonResource with section-based field management and Keycloak provisioning"
```

---

## Task 9: Backend — Update FamilyResource

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/FamilyResource.java`

- [ ] **Step 1: Read current FamilyResource.java**

Read the file to see existing endpoints.

- [ ] **Step 2: Replace `/parents` and `/children` endpoints with `/persons`**

Replace the family-member lookup methods. The existing `create`, `get`, `list`, `update`, `delete` for families stay. Replace child/parent member endpoints with:

```java
// Add this import at the top:
import at.kigruapp.entity.Person;

// Replace the existing getChildren and getParents methods with:
@GET
@Path("/{id}/persons")
public List<Person> getPersons(@PathParam("id") String id) {
    Family family = Family.findById(new ObjectId(id));
    if (family == null) {
        throw new NotFoundException();
    }
    return Person.findByFamilyId(family.id);
}
```

Remove any imports of `Parent`, `Child`, or `at.kigruapp.entity.Parent`/`Child`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/FamilyResource.java
git commit -m "refactor: replace /parents and /children endpoints with /persons on FamilyResource"
```

---

## Task 10: Backend — Delete old entities and resources

**Files:**
- Delete: `backend/src/main/java/at/kigruapp/entity/Parent.java`
- Delete: `backend/src/main/java/at/kigruapp/entity/Child.java`
- Delete: `backend/src/main/java/at/kigruapp/entity/Address.java`
- Delete: `backend/src/main/java/at/kigruapp/entity/EntityType.java`
- Delete: `backend/src/main/java/at/kigruapp/resource/ParentResource.java`
- Delete: `backend/src/main/java/at/kigruapp/resource/ChildResource.java`
- Delete: `backend/src/test/java/at/kigruapp/resource/ParentResourceTest.java`
- Delete: `backend/src/test/java/at/kigruapp/resource/ChildResourceTest.java`

- [ ] **Step 1: Delete all obsolete files**

```bash
rm backend/src/main/java/at/kigruapp/entity/Parent.java
rm backend/src/main/java/at/kigruapp/entity/Child.java
rm backend/src/main/java/at/kigruapp/entity/Address.java
rm backend/src/main/java/at/kigruapp/entity/EntityType.java
rm backend/src/main/java/at/kigruapp/resource/ParentResource.java
rm backend/src/main/java/at/kigruapp/resource/ChildResource.java
rm backend/src/test/java/at/kigruapp/resource/ParentResourceTest.java
rm backend/src/test/java/at/kigruapp/resource/ChildResourceTest.java
```

- [ ] **Step 2: Fix any remaining references to EntityType**

Grep for `EntityType` in the remaining Java files. The migration file (`CustomFieldsMigration.java`) uses string constants "PARENT"/"CHILD"/"FAMILY" directly — no enum reference. If any other file still imports EntityType, remove the import.

- [ ] **Step 3: Verify compilation**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A backend/src/main/java/at/kigruapp/entity/ backend/src/main/java/at/kigruapp/resource/ backend/src/test/
git commit -m "refactor: remove Parent, Child, Address, EntityType entities and their resources"
```

---

## Task 11: Backend — Seed Migration for Initial FieldDefinitions

**Files:**
- Create: `backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java`

- [ ] **Step 1: Create FieldDefinitionSeedMigration.java**

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
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Startup
public class FieldDefinitionSeedMigration {

    private static final String MIGRATION_ID = "seed-basic-property-definitions-v1";

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

        MongoCollection<Document> defs = db.getCollection("field_definitions");
        String now = Instant.now().toString();

        seedDef(defs, now, "personType",
                Map.of("de", "Personentyp", "en", "Person Type"),
                new Document("type", "string").append("enum", List.of("PARENT", "CHILD")),
                true, null);

        seedDef(defs, now, "firstName",
                Map.of("de", "Vorname", "en", "First Name"),
                new Document("type", "string"),
                true, "firstName");

        seedDef(defs, now, "lastName",
                Map.of("de", "Nachname", "en", "Last Name"),
                new Document("type", "string"),
                true, "lastName");

        seedDef(defs, now, "email",
                Map.of("de", "E-Mail", "en", "Email"),
                new Document("type", "string").append("format", "email"),
                false, "email");

        seedDef(defs, now, "phone",
                Map.of("de", "Telefon", "en", "Phone"),
                new Document("type", "string"),
                false, null);

        seedDef(defs, now, "dateOfBirth",
                Map.of("de", "Geburtsdatum", "en", "Date of Birth"),
                new Document("type", "string").append("format", "date"),
                false, null);

        seedDef(defs, now, "gender",
                Map.of("de", "Geschlecht", "en", "Gender"),
                new Document("type", "string").append("enum", List.of("male", "female", "diverse")),
                false, null);

        seedDef(defs, now, "entryDate",
                Map.of("de", "Eintrittsdatum", "en", "Entry Date"),
                new Document("type", "string").append("format", "date"),
                false, null);

        seedDef(defs, now, "exitDate",
                Map.of("de", "Austrittsdatum", "en", "Exit Date"),
                new Document("type", "string").append("format", "date"),
                false, null);

        seedDef(defs, now, "notes",
                Map.of("de", "Notizen", "en", "Notes"),
                new Document("type", "string"),
                false, null);

        seedDef(defs, now, "address",
                Map.of("de", "Adresse", "en", "Address"),
                new Document("type", "object")
                        .append("properties", new Document()
                                .append("street", new Document("type", "string"))
                                .append("zip", new Document("type", "string"))
                                .append("city", new Document("type", "string")))
                        .append("required", List.of("street", "zip", "city")),
                false, null);

        migrations.insertOne(new Document("_id", MIGRATION_ID)
                .append("executedAt", now));
    }

    private void seedDef(MongoCollection<Document> defs, String now,
                         String fieldName, Map<String, String> label,
                         Document jsonSchema, boolean required, String keycloakMapping) {
        // Skip if already exists (idempotent)
        if (defs.find(new Document("fieldName", fieldName)).first() != null) {
            return;
        }
        Document doc = new Document()
                .append("fieldName", fieldName)
                .append("label", new Document(label))
                .append("jsonSchema", jsonSchema)
                .append("required", required)
                .append("createdAt", now);
        if (keycloakMapping != null) {
            doc.append("keycloakMapping", keycloakMapping);
        }
        defs.insertOne(doc);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java
git commit -m "feat: add seed migration for initial basicProperty field definitions"
```

---

## Task 12: Backend — Person Architecture Migration

**Files:**
- Create: `backend/src/main/java/at/kigruapp/migration/PersonArchitectureMigration.java`

- [ ] **Step 1: Create PersonArchitectureMigration.java**

```java
package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Startup
public class PersonArchitectureMigration {

    private static final String MIGRATION_ID = "parents-children-to-persons-v1";

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

        MongoCollection<Document> defs = db.getCollection("field_definitions");
        MongoCollection<Document> instances = db.getCollection("field_instances");
        MongoCollection<Document> persons = db.getCollection("persons");
        String now = Instant.now().toString();

        // Migrate parents
        MongoCollection<Document> parents = db.getCollection("parents");
        for (Document parent : parents.find()) {
            List<Document> basicProps = new ArrayList<>();

            addFieldRef(basicProps, defs, instances, now, "personType", "PARENT");
            addFieldRef(basicProps, defs, instances, now, "firstName", parent.getString("firstName"));
            addFieldRef(basicProps, defs, instances, now, "lastName", parent.getString("lastName"));
            addFieldRef(basicProps, defs, instances, now, "email", parent.getString("email"));
            addFieldRef(basicProps, defs, instances, now, "phone", parent.getString("phone"));

            // Migrate address
            Object addressRaw = parent.get("address");
            if (addressRaw instanceof Document addr) {
                Document addrValue = new Document()
                        .append("street", addr.getString("street"))
                        .append("zip", addr.getString("zip"))
                        .append("city", addr.getString("city"));
                addFieldRef(basicProps, defs, instances, now, "address", addrValue);
            }

            // Migrate existing field_instances for this parent
            List<Document> migratedRefs = migrateExistingInstances(
                    instances, defs, parent.getObjectId("_id"), "PARENT");
            basicProps.addAll(migratedRefs);

            Document person = new Document()
                    .append("familyId", parent.getObjectId("familyId"))
                    .append("keycloakUserId", parent.getString("keycloakUserId"))
                    .append("basicProperties", basicProps)
                    .append("roles", new ArrayList<>())
                    .append("schedules", new ArrayList<>())
                    .append("duties", new ArrayList<>())
                    .append("finance", new ArrayList<>())
                    .append("customProperties", new ArrayList<>())
                    .append("createdAt", now)
                    .append("updatedAt", now);
            persons.insertOne(person);
        }

        // Migrate children
        MongoCollection<Document> children = db.getCollection("children");
        for (Document child : children.find()) {
            List<Document> basicProps = new ArrayList<>();

            addFieldRef(basicProps, defs, instances, now, "personType", "CHILD");
            addFieldRef(basicProps, defs, instances, now, "firstName", child.getString("firstName"));
            addFieldRef(basicProps, defs, instances, now, "lastName", child.getString("lastName"));
            addFieldRef(basicProps, defs, instances, now, "dateOfBirth",
                    child.get("dateOfBirth") != null ? child.get("dateOfBirth").toString() : null);
            addFieldRef(basicProps, defs, instances, now, "gender", child.getString("gender"));
            addFieldRef(basicProps, defs, instances, now, "entryDate",
                    child.get("entryDate") != null ? child.get("entryDate").toString() : null);
            addFieldRef(basicProps, defs, instances, now, "exitDate",
                    child.get("exitDate") != null ? child.get("exitDate").toString() : null);
            addFieldRef(basicProps, defs, instances, now, "notes", child.getString("notes"));

            // Migrate existing field_instances for this child
            List<Document> migratedRefs = migrateExistingInstances(
                    instances, defs, child.getObjectId("_id"), "CHILD");
            basicProps.addAll(migratedRefs);

            Document person = new Document()
                    .append("familyId", child.getObjectId("familyId"))
                    .append("basicProperties", basicProps)
                    .append("roles", new ArrayList<>())
                    .append("schedules", new ArrayList<>())
                    .append("duties", new ArrayList<>())
                    .append("finance", new ArrayList<>())
                    .append("customProperties", new ArrayList<>())
                    .append("createdAt", now)
                    .append("updatedAt", now);
            persons.insertOne(person);
        }

        // Clean up old field_instances (remove entityType/entityId fields)
        instances.updateMany(
                new Document("entityType", new Document("$exists", true)),
                new Document("$unset", new Document("entityType", "").append("entityId", ""))
        );

        // Update field_definitions: remove entity field, add keycloakMapping where needed
        defs.updateMany(
                new Document("entity", new Document("$exists", true)),
                new Document("$unset", new Document("entity", ""))
        );
        defs.updateOne(new Document("fieldName", "firstName"), new Document("$set", new Document("keycloakMapping", "firstName")));
        defs.updateOne(new Document("fieldName", "lastName"), new Document("$set", new Document("keycloakMapping", "lastName")));
        defs.updateOne(new Document("fieldName", "email"), new Document("$set", new Document("keycloakMapping", "email")));

        // Create indexes
        persons.createIndex(new Document("familyId", 1));

        migrations.insertOne(new Document("_id", MIGRATION_ID)
                .append("executedAt", now));
    }

    private void addFieldRef(List<Document> refs, MongoCollection<Document> defs,
                             MongoCollection<Document> instances, String now,
                             String fieldName, Object value) {
        if (value == null) return;
        Document defDoc = defs.find(new Document("fieldName", fieldName)).first();
        if (defDoc == null) return;

        ObjectId instId = new ObjectId();
        instances.insertOne(new Document("_id", instId)
                .append("definitionId", defDoc.getObjectId("_id"))
                .append("value", value)
                .append("createdAt", now)
                .append("updatedAt", now));

        refs.add(new Document()
                .append("definitionId", defDoc.getObjectId("_id"))
                .append("fieldInstanceId", instId));
    }

    private List<Document> migrateExistingInstances(MongoCollection<Document> instances,
                                                     MongoCollection<Document> defs,
                                                     ObjectId entityId, String entityType) {
        List<Document> refs = new ArrayList<>();
        for (Document inst : instances.find(new Document("entityType", entityType).append("entityId", entityId))) {
            ObjectId defId = inst.getObjectId("definitionId");
            refs.add(new Document()
                    .append("definitionId", defId)
                    .append("fieldInstanceId", inst.getObjectId("_id")));
        }
        return refs;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/at/kigruapp/migration/PersonArchitectureMigration.java
git commit -m "feat: add migration to convert parents/children to persons with field references"
```

---

## Task 13: Backend — PersonResource Tests

**Files:**
- Create: `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java`

- [ ] **Step 1: Create PersonResourceTest.java**

```java
package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class PersonResourceTest {

    @Test
    public void testListPersons() {
        given()
            .when().get("/api/v1/persons")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON);
    }

    @Test
    public void testGetNonExistentPerson() {
        given()
            .when().get("/api/v1/persons/000000000000000000000000")
            .then()
            .statusCode(404);
    }

    @Test
    public void testFieldDefinitionsList() {
        given()
            .when().get("/api/v1/field-definitions")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", greaterThan(0));
    }

    @Test
    public void testFieldDefinitionsActiveFilter() {
        given()
            .when().get("/api/v1/field-definitions?active=true")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON);
    }
}
```

- [ ] **Step 2: Run tests**

Run: `cd backend && ./mvnw test -q`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java
git commit -m "test: add PersonResource and FieldDefinition endpoint tests"
```

---

## Task 14: Frontend — Update models

**Files:**
- Create: `frontend/src/app/shared/models/person.model.ts`
- Modify: `frontend/src/app/shared/models/field-definition.model.ts`
- Modify: `frontend/src/app/shared/models/field-instance.model.ts`
- Delete: `frontend/src/app/shared/models/parent.model.ts`
- Delete: `frontend/src/app/shared/models/child.model.ts`

- [ ] **Step 1: Create person.model.ts**

```typescript
export interface FieldRef {
  definitionId: string;
  fieldInstanceId: string;
}

export interface Person {
  id?: string;
  familyId: string;
  keycloakUserId?: string;
  basicProperties: FieldRef[];
  roles: FieldRef[];
  schedules: FieldRef[];
  duties: FieldRef[];
  finance: FieldRef[];
  customProperties: FieldRef[];
  createdAt?: string;
  updatedAt?: string;
}

export interface CreatePersonRequest {
  familyId: string;
  basicProperties: SectionInput[];
  roles: SectionInput[];
  schedules: SectionInput[];
  duties: SectionInput[];
  finance: SectionInput[];
  customProperties: SectionInput[];
}

export interface SectionInput {
  definitionId: string;
  value: unknown;
}

export interface PersonDTO {
  id: string;
  familyId: string;
  keycloakUserId?: string;
  basicProperties: import('./field-instance.model').FieldInstanceDTO[];
  roles: import('./field-instance.model').FieldInstanceDTO[];
  schedules: import('./field-instance.model').FieldInstanceDTO[];
  duties: import('./field-instance.model').FieldInstanceDTO[];
  finance: import('./field-instance.model').FieldInstanceDTO[];
  customProperties: import('./field-instance.model').FieldInstanceDTO[];
  createdAt?: string;
  updatedAt?: string;
}
```

- [ ] **Step 2: Update field-definition.model.ts**

Replace the entire file with:

```typescript
export interface FieldDefinition {
  id?: string;
  fieldName: string;
  label: Record<string, string>;
  description?: string;
  jsonSchema: Record<string, unknown>;
  required: boolean;
  keycloakMapping?: string | null;
  createdAt?: string;
  outdatedAt?: string | null;
}
```

- [ ] **Step 3: Update field-instance.model.ts**

Replace the entire file with:

```typescript
export interface FieldInstance {
  id?: string;
  definitionId: string;
  value: unknown;
  createdAt?: string;
  updatedAt?: string;
}

export interface FieldInstanceDTO {
  id?: string;
  definitionId: string;
  fieldName: string;
  label: Record<string, string>;
  description?: string;
  jsonSchema: Record<string, unknown>;
  required: boolean;
  keycloakMapping?: string | null;
  value: unknown;
  definitionOutdated: boolean;
}
```

- [ ] **Step 4: Delete old model files**

```bash
rm frontend/src/app/shared/models/parent.model.ts
rm frontend/src/app/shared/models/child.model.ts
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/models/
git commit -m "refactor: replace Parent/Child models with Person model, update field models"
```

---

## Task 15: Frontend — PersonService and update FieldDefinitionService

**Files:**
- Create: `frontend/src/app/shared/services/person.service.ts`
- Modify: `frontend/src/app/settings/custom-fields/services/field-definition.service.ts`
- Modify: `frontend/src/app/shared/services/field-instance.service.ts`
- Delete: `frontend/src/app/administration/families/services/parent.service.ts`
- Delete: `frontend/src/app/administration/families/services/child.service.ts`

- [ ] **Step 1: Create person.service.ts**

```typescript
import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { Person, CreatePersonRequest, PersonDTO } from '../models/person.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class PersonService {
  constructor(private api: ApiService) {}

  list(familyId?: string): Observable<Person[]> {
    const params = familyId ? `?familyId=${familyId}` : '';
    return this.api.get<Person[]>(`/persons${params}`);
  }

  get(id: string): Observable<Person> {
    return this.api.get<Person>(`/persons/${id}`);
  }

  getFull(id: string): Observable<PersonDTO> {
    return this.api.get<PersonDTO>(`/persons/${id}/full`);
  }

  create(request: CreatePersonRequest): Observable<Person> {
    return this.api.post<Person>('/persons', request);
  }

  update(id: string, person: Person): Observable<Person> {
    return this.api.put<Person>(`/persons/${id}`, person);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/persons/${id}`);
  }
}
```

- [ ] **Step 2: Update field-definition.service.ts**

Replace the entire file with:

```typescript
import { Injectable } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { FieldDefinition } from '../../../shared/models/field-definition.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class FieldDefinitionService {
  constructor(private api: ApiService) {}

  list(): Observable<FieldDefinition[]> {
    return this.api.get<FieldDefinition[]>('/field-definitions');
  }

  listActive(): Observable<FieldDefinition[]> {
    return this.api.get<FieldDefinition[]>('/field-definitions?active=true');
  }

  create(fieldDef: FieldDefinition): Observable<FieldDefinition> {
    return this.api.post<FieldDefinition>('/field-definitions', fieldDef);
  }

  update(id: string, fieldDef: FieldDefinition): Observable<FieldDefinition> {
    return this.api.put<FieldDefinition>(`/field-definitions/${id}`, fieldDef);
  }

  outdate(id: string): Observable<FieldDefinition> {
    return this.api.patch<FieldDefinition>(`/field-definitions/${id}/outdate`);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/field-definitions/${id}`);
  }
}
```

- [ ] **Step 3: Update field-instance.service.ts**

Replace the entire file with:

```typescript
import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { FieldInstanceDTO } from '../models/field-instance.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class FieldInstanceService {
  constructor(private api: ApiService) {}

  get(id: string): Observable<FieldInstanceDTO> {
    return this.api.get<FieldInstanceDTO>(`/field-instances/${id}`);
  }

  batchSave(instances: { definitionId: string; value: unknown }[]): Observable<unknown> {
    return this.api.put('/field-instances/batch', instances);
  }
}
```

- [ ] **Step 4: Delete old services**

```bash
rm frontend/src/app/administration/families/services/parent.service.ts
rm frontend/src/app/administration/families/services/child.service.ts
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/services/ frontend/src/app/settings/custom-fields/services/ frontend/src/app/administration/families/services/
git commit -m "refactor: add PersonService, simplify FieldDefinition/FieldInstance services"
```

---

## Task 16: Frontend — JsonSchemaFieldComponent (add time + object support)

**Files:**
- Modify: `frontend/src/app/shared/components/json-schema-field/json-schema-field.component.ts`

- [ ] **Step 1: Rewrite json-schema-field.component.ts**

Replace the entire file with:

```typescript
import { Component, Input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatCardModule } from '@angular/material/card';
import { FieldInstanceDTO } from '../../models/field-instance.model';

@Component({
  selector: 'app-json-schema-field',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDatepickerModule,
    MatSlideToggleModule,
    MatCardModule,
  ],
  template: `
    @if (fieldType === 'boolean') {
      <mat-slide-toggle [formControl]="control">
        {{ label }}
      </mat-slide-toggle>
    } @else if (fieldType === 'select') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ label }}</mat-label>
        <mat-select [formControl]="control">
          @for (opt of enumOptions; track opt) {
            <mat-option [value]="opt">{{ opt }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    } @else if (fieldType === 'date') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ label }}</mat-label>
        <input matInput [matDatepicker]="picker" [formControl]="control">
        <mat-datepicker-toggle matIconSuffix [for]="picker"></mat-datepicker-toggle>
        <mat-datepicker #picker></mat-datepicker>
      </mat-form-field>
    } @else if (fieldType === 'time') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ label }}</mat-label>
        <input matInput type="time" [formControl]="control">
      </mat-form-field>
    } @else if (fieldType === 'number') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ label }}</mat-label>
        <input matInput type="number" [formControl]="control">
      </mat-form-field>
    } @else if (fieldType === 'object') {
      <mat-card class="fieldgroup">
        <mat-card-header>
          <mat-card-title>{{ label }}</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          @for (prop of objectProperties; track prop.key) {
            <app-json-schema-field
              [dto]="prop.dto"
              [control]="getObjectControl(prop.key)"
            ></app-json-schema-field>
          }
        </mat-card-content>
      </mat-card>
    } @else {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>{{ label }}</mat-label>
        <input matInput [formControl]="control">
      </mat-form-field>
    }
  `,
  styles: [`
    .full-width { width: 100%; }
    .fieldgroup { margin: 8px 0; }
    .fieldgroup mat-card-content { padding: 16px; }
  `],
})
export class JsonSchemaFieldComponent {
  @Input({ required: true }) dto!: FieldInstanceDTO;
  @Input({ required: true }) control!: FormControl;

  get label(): string {
    return this.dto.label['de'] || this.dto.fieldName;
  }

  get fieldType(): string {
    const schema = this.dto.jsonSchema;
    if (!schema) return 'text';

    const type = schema['type'] as string;
    if (type === 'boolean') return 'boolean';
    if (type === 'number' || type === 'integer') return 'number';
    if (type === 'object' && schema['properties']) return 'object';
    if (type === 'string') {
      if (schema['enum']) return 'select';
      if (schema['format'] === 'date') return 'date';
      if (schema['format'] === 'time') return 'time';
    }
    return 'text';
  }

  get enumOptions(): string[] {
    return (this.dto.jsonSchema?.['enum'] as string[]) ?? [];
  }

  get objectProperties(): { key: string; dto: FieldInstanceDTO }[] {
    const props = this.dto.jsonSchema?.['properties'] as Record<string, Record<string, unknown>> | undefined;
    if (!props) return [];
    return Object.entries(props).map(([key, schema]) => ({
      key,
      dto: {
        definitionId: this.dto.definitionId,
        fieldName: key,
        label: { de: key.charAt(0).toUpperCase() + key.slice(1) },
        jsonSchema: schema,
        required: false,
        value: null,
        definitionOutdated: false,
      } as FieldInstanceDTO,
    }));
  }

  getObjectControl(key: string): FormControl {
    const group = this.control as unknown as FormGroup;
    if (!group.contains(key)) {
      group.addControl(key, new FormControl(null));
    }
    return group.get(key) as FormControl;
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/shared/components/json-schema-field/json-schema-field.component.ts
git commit -m "feat: add time and object/fieldgroup support to JsonSchemaFieldComponent"
```

---

## Task 17: Frontend — SectionFormComponent

**Files:**
- Create: `frontend/src/app/shared/components/section-form/section-form.component.ts`

- [ ] **Step 1: Create section-form.component.ts**

```typescript
import { Component, Input, OnInit } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { FieldDefinition } from '../../models/field-definition.model';
import { FieldInstanceDTO } from '../../models/field-instance.model';
import { SectionInput } from '../../models/person.model';
import { JsonSchemaFieldComponent } from '../json-schema-field/json-schema-field.component';

@Component({
  selector: 'app-section-form',
  standalone: true,
  imports: [JsonSchemaFieldComponent],
  template: `
    @for (dto of fieldDTOs; track dto.definitionId) {
      <app-json-schema-field
        [dto]="dto"
        [control]="controls[dto.definitionId]"
      ></app-json-schema-field>
    }
  `,
})
export class SectionFormComponent implements OnInit {
  @Input({ required: true }) definitions!: FieldDefinition[];
  @Input() existingFields: FieldInstanceDTO[] = [];

  fieldDTOs: FieldInstanceDTO[] = [];
  controls: Record<string, FormControl> = {};
  form = new FormGroup({});

  ngOnInit(): void {
    this.buildForm();
  }

  get isValid(): boolean {
    return this.form.valid;
  }

  getValues(): SectionInput[] {
    return this.fieldDTOs
      .filter((dto) => !dto.definitionOutdated)
      .map((dto) => ({
        definitionId: dto.definitionId,
        value: this.controls[dto.definitionId]?.value ?? null,
      }));
  }

  private buildForm(): void {
    const existingByDefId = new Map(
      this.existingFields.map((f) => [f.definitionId, f])
    );

    this.fieldDTOs = this.definitions.map((def) => {
      const existing = existingByDefId.get(def.id!);
      return {
        definitionId: def.id!,
        fieldName: def.fieldName,
        label: def.label,
        description: def.description,
        jsonSchema: def.jsonSchema,
        required: def.required,
        keycloakMapping: def.keycloakMapping,
        value: existing?.value ?? null,
        definitionOutdated: def.outdatedAt != null,
      } as FieldInstanceDTO;
    });

    this.controls = {};
    this.form = new FormGroup({});

    for (const dto of this.fieldDTOs) {
      const validators = dto.required ? [Validators.required] : [];
      const isObject = dto.jsonSchema?.['type'] === 'object';
      const control = isObject
        ? new FormGroup({}) as unknown as FormControl
        : new FormControl(dto.value, validators);

      if (isObject && dto.value && typeof dto.value === 'object') {
        const group = control as unknown as FormGroup;
        for (const [key, val] of Object.entries(dto.value as Record<string, unknown>)) {
          group.addControl(key, new FormControl(val));
        }
      }

      if (dto.definitionOutdated) {
        control.disable();
      }
      this.controls[dto.definitionId] = control;
      this.form.addControl(dto.definitionId, control);
    }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/shared/components/section-form/section-form.component.ts
git commit -m "feat: add SectionFormComponent for rendering categorized field sections"
```

---

## Task 18: Frontend — Update CustomFieldsFormComponent

**Files:**
- Modify: `frontend/src/app/shared/components/custom-fields-form/custom-fields-form.component.ts`

- [ ] **Step 1: Rewrite custom-fields-form.component.ts**

Replace the entire file with:

```typescript
import { Component, Input, OnInit, ViewChild } from '@angular/core';
import { FieldDefinition } from '../../models/field-definition.model';
import { FieldInstanceDTO } from '../../models/field-instance.model';
import { SectionInput } from '../../models/person.model';
import { FieldDefinitionService } from '../../../settings/custom-fields/services/field-definition.service';
import { SectionFormComponent } from '../section-form/section-form.component';

@Component({
  selector: 'app-custom-fields-form',
  standalone: true,
  imports: [SectionFormComponent],
  template: `
    @if (definitions.length > 0) {
      <app-section-form
        #sectionForm
        [definitions]="definitions"
        [existingFields]="existingFields"
      ></app-section-form>
    }
  `,
})
export class CustomFieldsFormComponent implements OnInit {
  @Input() existingFields: FieldInstanceDTO[] = [];
  @ViewChild('sectionForm') sectionForm?: SectionFormComponent;

  definitions: FieldDefinition[] = [];

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.fieldDefService.listActive().subscribe((defs) => {
      this.definitions = defs;
    });
  }

  get isValid(): boolean {
    return this.sectionForm?.isValid ?? true;
  }

  getValues(): SectionInput[] {
    return this.sectionForm?.getValues() ?? [];
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/shared/components/custom-fields-form/custom-fields-form.component.ts
git commit -m "refactor: simplify CustomFieldsFormComponent to use SectionFormComponent"
```

---

## Task 19: Frontend — Update Wizard Steps

**Files:**
- Modify: `frontend/src/app/administration/families/family-wizard/steps/child-step.component.ts`
- Modify: `frontend/src/app/administration/families/family-wizard/steps/parents-step.component.ts`
- Modify: `frontend/src/app/administration/families/family-wizard/steps/family-step.component.ts`

- [ ] **Step 1: Rewrite child-step.component.ts**

Replace the entire file with:

```typescript
import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FieldDefinition } from '../../../../shared/models/field-definition.model';
import { SectionInput } from '../../../../shared/models/person.model';
import { FieldDefinitionService } from '../../../../settings/custom-fields/services/field-definition.service';
import { SectionFormComponent } from '../../../../shared/components/section-form/section-form.component';

@Component({
  selector: 'app-child-step',
  standalone: true,
  imports: [CommonModule, SectionFormComponent],
  template: `
    <h3>Kind</h3>
    @if (definitions.length > 0) {
      <app-section-form
        #sectionForm
        [definitions]="definitions"
      ></app-section-form>
    }
  `,
})
export class ChildStepComponent implements OnInit {
  @ViewChild('sectionForm') sectionForm?: SectionFormComponent;

  definitions: FieldDefinition[] = [];

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.fieldDefService.listActive().subscribe((defs) => {
      this.definitions = defs;
    });
  }

  get isValid(): boolean {
    return this.sectionForm?.isValid ?? true;
  }

  getBasicProperties(): SectionInput[] {
    return this.sectionForm?.getValues() ?? [];
  }
}
```

- [ ] **Step 2: Rewrite parents-step.component.ts**

Replace the entire file with:

```typescript
import { Component, OnInit, QueryList, ViewChildren } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { FieldDefinition } from '../../../../shared/models/field-definition.model';
import { SectionInput } from '../../../../shared/models/person.model';
import { FieldDefinitionService } from '../../../../settings/custom-fields/services/field-definition.service';
import { SectionFormComponent } from '../../../../shared/components/section-form/section-form.component';

@Component({
  selector: 'app-parents-step',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatCheckboxModule,
    SectionFormComponent,
  ],
  template: `
    <h3>Elternteile</h3>
    @for (idx of parentIndices; track idx) {
      <div class="parent-block">
        <h4>
          Elternteil {{ idx + 1 }}
          @if (idx > 0) {
            <button mat-icon-button (click)="removeParent(idx)">
              <mat-icon>delete</mat-icon>
            </button>
          }
        </h4>
        @if (definitions.length > 0) {
          <app-section-form
            #parentForm
            [definitions]="definitions"
          ></app-section-form>
        }
      </div>
    }
    <button mat-stroked-button (click)="addParent()">
      <mat-icon>add</mat-icon> Elternteil hinzufuegen
    </button>
  `,
  styles: [`.parent-block { margin-bottom: 24px; border-bottom: 1px solid #ccc; padding-bottom: 16px; }`],
})
export class ParentsStepComponent implements OnInit {
  @ViewChildren('parentForm') parentForms!: QueryList<SectionFormComponent>;

  definitions: FieldDefinition[] = [];
  parentIndices: number[] = [0];

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.fieldDefService.listActive().subscribe((defs) => {
      this.definitions = defs;
    });
  }

  addParent(): void {
    this.parentIndices.push(this.parentIndices.length);
  }

  removeParent(index: number): void {
    this.parentIndices.splice(index, 1);
    this.parentIndices = this.parentIndices.map((_, i) => i);
  }

  get isValid(): boolean {
    return this.parentForms?.length > 0 &&
      this.parentForms.toArray().every((f) => f.isValid);
  }

  getParentsBasicProperties(): SectionInput[][] {
    return this.parentForms.toArray().map((f) => f.getValues());
  }
}
```

- [ ] **Step 3: Family step stays mostly unchanged**

The family-step.component.ts still handles new/existing family selection. Remove the `CustomFieldsFormComponent` import and reference since families don't use the person model. Update the template reference if needed. The file already works for family creation — no custom fields for families in the person architecture.

Replace the entire file with:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { FamilyService } from '../../services/family.service';
import { Family } from '../../../../shared/models/family.model';

@Component({
  selector: 'app-family-step',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatRadioModule, MatSelectModule, MatFormFieldModule,
  ],
  templateUrl: './family-step.component.html',
})
export class FamilyStepComponent implements OnInit {
  form = new FormGroup({
    mode: new FormControl<'new' | 'existing'>('new', Validators.required),
    existingFamilyId: new FormControl<string>(''),
  });

  existingFamilies: Family[] = [];

  constructor(private familyService: FamilyService) {}

  ngOnInit(): void {
    this.familyService.list().subscribe((families) => {
      this.existingFamilies = families;
    });
  }

  get isValid(): boolean {
    if (this.form.value.mode === 'existing') {
      return !!this.form.value.existingFamilyId;
    }
    return true;
  }

  get isNewFamily(): boolean {
    return this.form.value.mode === 'new';
  }

  get selectedFamilyId(): string | null {
    return this.form.value.mode === 'existing' ? this.form.value.existingFamilyId ?? null : null;
  }
}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/administration/families/family-wizard/steps/
git commit -m "refactor: rewrite wizard steps to use SectionFormComponent with dynamic fields"
```

---

## Task 20: Frontend — Update FamilyWizardComponent

**Files:**
- Modify: `frontend/src/app/administration/families/family-wizard/family-wizard.component.ts`

- [ ] **Step 1: Rewrite family-wizard.component.ts**

Replace the entire file with:

```typescript
import { Component, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatStepperModule, MatStepper } from '@angular/material/stepper';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { FamilyStepComponent } from './steps/family-step.component';
import { ChildStepComponent } from './steps/child-step.component';
import { ParentsStepComponent } from './steps/parents-step.component';
import { FamilyService } from '../services/family.service';
import { PersonService } from '../../../shared/services/person.service';
import { CreatePersonRequest } from '../../../shared/models/person.model';
import { lastValueFrom } from 'rxjs';

@Component({
  selector: 'app-family-wizard',
  standalone: true,
  imports: [
    CommonModule,
    MatStepperModule, MatButtonModule, MatDialogModule,
    FamilyStepComponent, ChildStepComponent, ParentsStepComponent,
  ],
  templateUrl: './family-wizard.component.html',
  styleUrl: './family-wizard.component.scss',
})
export class FamilyWizardComponent {
  @ViewChild('stepper') stepper!: MatStepper;
  @ViewChild(FamilyStepComponent) familyStep!: FamilyStepComponent;
  @ViewChild(ChildStepComponent) childStep!: ChildStepComponent;
  @ViewChild(ParentsStepComponent) parentsStep!: ParentsStepComponent;

  submitting = false;

  constructor(
    private dialogRef: MatDialogRef<FamilyWizardComponent>,
    private familyService: FamilyService,
    private personService: PersonService,
  ) {}

  cancel(): void {
    this.dialogRef.close(false);
  }

  async submit(): Promise<void> {
    this.submitting = true;

    try {
      // Step 1: Get or create family
      let familyId: string;
      if (this.familyStep.isNewFamily) {
        // Use child's lastName from basicProperties to name the family
        const childProps = this.childStep.getBasicProperties();
        const lastNameProp = childProps.find((p) => {
          // We'll use the value if it looks like a lastName
          return true; // Family name will be set separately
        });
        const family = await lastValueFrom(
          this.familyService.create({ name: 'Neue Familie' })
        );
        familyId = family.id!;
      } else {
        familyId = this.familyStep.selectedFamilyId!;
      }

      // Step 2: Create child person
      const childRequest: CreatePersonRequest = {
        familyId,
        basicProperties: this.childStep.getBasicProperties(),
        roles: [],
        schedules: [],
        duties: [],
        finance: [],
        customProperties: [],
      };
      await lastValueFrom(this.personService.create(childRequest));

      // Step 3: Create parent persons
      const parentsProps = this.parentsStep.getParentsBasicProperties();
      for (const parentProps of parentsProps) {
        const parentRequest: CreatePersonRequest = {
          familyId,
          basicProperties: parentProps,
          roles: [],
          schedules: [],
          duties: [],
          finance: [],
          customProperties: [],
        };
        await lastValueFrom(this.personService.create(parentRequest));
      }

      this.dialogRef.close(true);
    } catch (err) {
      console.error('Wizard failed:', err);
      this.submitting = false;
    }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/administration/families/family-wizard/family-wizard.component.ts
git commit -m "refactor: update FamilyWizard to use PersonService with CreatePersonRequest"
```

---

## Task 21: Frontend — Update CustomFieldsComponent (Admin UI)

**Files:**
- Modify: `frontend/src/app/settings/custom-fields/custom-fields.component.ts`

- [ ] **Step 1: Read current file**

Read `frontend/src/app/settings/custom-fields/custom-fields.component.ts` to understand the exact template and logic.

- [ ] **Step 2: Update the component**

Remove entity-type dropdown from the create form (no longer needed). Add keycloakMapping field. Remove entity column from the table. The component already handles fieldName, labels, schemaType, options — those stay.

Key changes:
- Remove `EntityType` import and entity form control
- Remove entity column from table
- Add optional `keycloakMapping` text input
- Update `create()` method to not send entity
- `buildJsonSchema()` stays the same

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/settings/custom-fields/custom-fields.component.ts
git commit -m "refactor: remove entity filter from CustomFieldsComponent, add keycloakMapping"
```

---

## Task 22: Frontend — Update FamilyListComponent

**Files:**
- Modify: `frontend/src/app/administration/families/family-list/family-list.component.ts`

- [ ] **Step 1: Read current file**

Read `frontend/src/app/administration/families/family-list/family-list.component.ts` to see how it lists families and their members.

- [ ] **Step 2: Update to use PersonService**

Replace `ChildService`/`ParentService` imports with `PersonService`. Update the member loading logic to call `personService.list(familyId)` instead of separate child/parent calls.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/administration/families/family-list/family-list.component.ts
git commit -m "refactor: update FamilyListComponent to use PersonService"
```

---

## Task 23: Full Build Verification

- [ ] **Step 1: Backend compile and test**

Run: `cd backend && ./mvnw clean test -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 2: Frontend compile**

Run: `cd frontend && npx ng build`
Expected: Build succeeds with no errors

- [ ] **Step 3: Fix any compilation errors**

If either build fails, read the error output and fix the issues. Common issues:
- Missing imports for new types
- Template references to old components/properties
- Remaining references to `EntityType`, `Parent`, `Child` in any file

Use grep to find remaining references:
```bash
grep -r "EntityType\|import.*Parent\|import.*Child\|ChildService\|ParentService" frontend/src/app/ --include="*.ts" -l
grep -r "EntityType\|import.*Parent\|import.*Child" backend/src/main/java/ --include="*.java" -l
```

- [ ] **Step 4: Final commit if fixes were needed**

```bash
git add -A
git commit -m "fix: resolve remaining compilation errors after person architecture refactor"
```
