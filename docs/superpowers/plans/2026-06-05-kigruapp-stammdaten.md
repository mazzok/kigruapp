# KigruApp Stammdaten Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Stammdaten (master data) module for KigruApp — families, children, parents CRUD with a 3-step creation wizard, dynamic custom fields, Keycloak auth, running as 4 Docker containers.

**Architecture:** Quarkus REST API backed by MongoDB, Angular + Angular Material SPA served via Nginx reverse proxy, Keycloak for authentication. Nginx is the only externally exposed container; API, DB, and Keycloak are internal to the Docker network. Wizards are frontend-only — they collect data across steps and call standard CRUD endpoints.

**Tech Stack:** Java 21, Quarkus 3.x, MongoDB with Panache, Angular 18+, Angular Material, Keycloak 25+, Nginx, Docker Compose

---

## File Structure

### Backend (`backend/`)

```
backend/
  pom.xml
  src/main/java/at/kigruapp/
    entity/
      Family.java
      Child.java
      Parent.java
      Address.java              — embedded document
      FieldDefinition.java
      FieldType.java            — enum: TEXT, DATE, SELECT, BOOLEAN
      EntityType.java           — enum: CHILD, PARENT, FAMILY
    repository/
      FamilyRepository.java
      ChildRepository.java
      ParentRepository.java
      FieldDefinitionRepository.java
    resource/
      FamilyResource.java
      ChildResource.java
      ParentResource.java
      FieldDefinitionResource.java
    security/
      PermissionChecker.java
      KeycloakUserService.java
  src/main/resources/
    application.properties
  src/test/java/at/kigruapp/
    resource/
      FamilyResourceTest.java
      ChildResourceTest.java
      ParentResourceTest.java
      FieldDefinitionResourceTest.java
```

### Frontend (`frontend/`)

```
frontend/
  angular.json
  package.json
  src/
    app/
      app.component.ts
      app.config.ts
      app.routes.ts
      core/
        services/
          auth.service.ts
          api.service.ts
        interceptors/
          auth.interceptor.ts
        guards/
          auth.guard.ts
      shared/
        models/
          family.model.ts
          child.model.ts
          parent.model.ts
          field-definition.model.ts
      administration/
        families/
          family-list/
            family-list.component.ts
            family-list.component.html
            family-list.component.scss
          family-wizard/
            family-wizard.component.ts
            family-wizard.component.html
            family-wizard.component.scss
            steps/
              family-step.component.ts
              family-step.component.html
              child-step.component.ts
              child-step.component.html
              parents-step.component.ts
              parents-step.component.html
          services/
            family.service.ts
            child.service.ts
            parent.service.ts
      settings/
        custom-fields/
          custom-fields.component.ts
          custom-fields.component.html
          custom-fields.component.scss
          services/
            field-definition.service.ts
        permissions/
          permissions.component.ts
          permissions.component.html
          permissions.component.scss
    locale/
      messages.de.json
      messages.en.json
  Dockerfile
  nginx.conf                    — SPA fallback config for dev
```

### Infrastructure (`infra/`)

```
docker-compose.yml
infra/
  nginx/
    nginx.conf                  — reverse proxy config
    Dockerfile
  keycloak/
    kigruapp-realm.json         — realm import
```

---

## Task 1: Quarkus Backend Scaffolding

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/resources/application.properties`
- Create: `backend/.dockerignore`
- Create: `backend/src/main/docker/Dockerfile.jvm`

### Steps

- [ ] **Step 1: Generate Quarkus project**

Run from repo root:

```bash
mvn io.quarkus.platform:quarkus-maven-plugin:3.17.8:create \
  -DprojectGroupId=at.kigruapp \
  -DprojectArtifactId=backend \
  -Dextensions="rest-jackson,mongodb-panache,oidc,smallrye-health" \
  -DnoCode
```

- [ ] **Step 2: Verify project structure exists**

```bash
ls backend/pom.xml backend/src/main/resources/application.properties
```

Expected: both files exist.

- [ ] **Step 3: Configure application.properties**

Replace `backend/src/main/resources/application.properties` with:

```properties
# MongoDB
quarkus.mongodb.connection-string=mongodb://mongodb:27017
quarkus.mongodb.database=kigruapp

# OIDC (Keycloak)
quarkus.oidc.auth-server-url=http://keycloak:8443/realms/kigruapp
quarkus.oidc.client-id=kigruapp-api
quarkus.oidc.credentials.secret=${OIDC_CLIENT_SECRET:secret}
quarkus.oidc.tls.verification=none

# CORS (for Angular dev)
quarkus.http.cors=true
quarkus.http.cors.origins=http://localhost:4200
quarkus.http.cors.methods=GET,POST,PUT,DELETE,OPTIONS
quarkus.http.cors.headers=Content-Type,Authorization

# Dev profile: disable auth for easier testing
%dev.quarkus.oidc.enabled=false
%dev.quarkus.mongodb.connection-string=mongodb://localhost:27017

%test.quarkus.oidc.enabled=false
%test.quarkus.mongodb.connection-string=mongodb://localhost:27017
```

- [ ] **Step 4: Verify it compiles**

```bash
cd backend && mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/
git commit -m "feat: scaffold Quarkus backend with MongoDB + OIDC extensions"
```

---

## Task 2: MongoDB Entities

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/EntityType.java`
- Create: `backend/src/main/java/at/kigruapp/entity/FieldType.java`
- Create: `backend/src/main/java/at/kigruapp/entity/Address.java`
- Create: `backend/src/main/java/at/kigruapp/entity/Family.java`
- Create: `backend/src/main/java/at/kigruapp/entity/Child.java`
- Create: `backend/src/main/java/at/kigruapp/entity/Parent.java`
- Create: `backend/src/main/java/at/kigruapp/entity/FieldDefinition.java`

### Steps

- [ ] **Step 1: Create EntityType enum**

Create `backend/src/main/java/at/kigruapp/entity/EntityType.java`:

```java
package at.kigruapp.entity;

public enum EntityType {
    CHILD,
    PARENT,
    FAMILY
}
```

- [ ] **Step 2: Create FieldType enum**

Create `backend/src/main/java/at/kigruapp/entity/FieldType.java`:

```java
package at.kigruapp.entity;

public enum FieldType {
    TEXT,
    DATE,
    SELECT,
    BOOLEAN
}
```

- [ ] **Step 3: Create Address embedded document**

Create `backend/src/main/java/at/kigruapp/entity/Address.java`:

```java
package at.kigruapp.entity;

public class Address {
    public String street;
    public String zip;
    public String city;
}
```

- [ ] **Step 4: Create Family entity**

Create `backend/src/main/java/at/kigruapp/entity/Family.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.time.Instant;
import java.util.Map;

@MongoEntity(collection = "families")
public class Family extends PanacheMongoEntity {
    public String name;
    public Instant createdAt;
    public Map<String, Object> customFields;
}
```

- [ ] **Step 5: Create Child entity**

Create `backend/src/main/java/at/kigruapp/entity/Child.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;
import java.time.LocalDate;
import java.util.Map;

@MongoEntity(collection = "children")
public class Child extends PanacheMongoEntity {
    public ObjectId familyId;
    public String firstName;
    public String lastName;
    public LocalDate dateOfBirth;
    public String gender;
    public LocalDate entryDate;
    public LocalDate exitDate;
    public String notes;
    public Map<String, Object> customFields;

    public static java.util.List<Child> findByFamilyId(ObjectId familyId) {
        return list("familyId", familyId);
    }
}
```

- [ ] **Step 6: Create Parent entity**

Create `backend/src/main/java/at/kigruapp/entity/Parent.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;
import java.util.List;
import java.util.Map;

@MongoEntity(collection = "parents")
public class Parent extends PanacheMongoEntity {
    public ObjectId familyId;
    public String firstName;
    public String lastName;
    public String email;
    public String phone;
    public Address address;
    public String keycloakUserId;
    public List<String> permissions;
    public Map<String, Object> customFields;

    public static java.util.List<Parent> findByFamilyId(ObjectId familyId) {
        return list("familyId", familyId);
    }
}
```

- [ ] **Step 7: Create FieldDefinition entity**

Create `backend/src/main/java/at/kigruapp/entity/FieldDefinition.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.util.List;
import java.util.Map;

@MongoEntity(collection = "field_definitions")
public class FieldDefinition extends PanacheMongoEntity {
    public EntityType entity;
    public String fieldName;
    public Map<String, String> label;  // { "de": "...", "en": "..." }
    public FieldType type;
    public List<String> options;       // for SELECT type
    public boolean required;

    public static List<FieldDefinition> findByEntity(EntityType entity) {
        return list("entity", entity);
    }
}
```

- [ ] **Step 8: Verify compilation**

```bash
cd backend && mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/
git commit -m "feat: add MongoDB entity classes for families, children, parents, field definitions"
```

---

## Task 3: Family REST Resource + Tests

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/FamilyResource.java`
- Create: `backend/src/test/java/at/kigruapp/resource/FamilyResourceTest.java`

### Steps

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/resource/FamilyResourceTest.java`:

```java
package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.Family;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class FamilyResourceTest {

    @BeforeEach
    void cleanup() {
        Family.deleteAll();
    }

    @Test
    void listFamilies_empty() {
        given()
            .when().get("/api/v1/families")
            .then()
            .statusCode(200)
            .body("$.size()", is(0));
    }

    @Test
    void createAndGetFamily() {
        // Create
        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Mueller\"}")
            .when().post("/api/v1/families")
            .then()
            .statusCode(201)
            .body("name", is("Mueller"))
            .body("id", notNullValue())
            .extract().path("id");

        // Get by ID
        given()
            .when().get("/api/v1/families/" + id)
            .then()
            .statusCode(200)
            .body("name", is("Mueller"));
    }

    @Test
    void updateFamily() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Mueller\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Schmidt\"}")
            .when().put("/api/v1/families/" + id)
            .then()
            .statusCode(200)
            .body("name", is("Schmidt"));
    }

    @Test
    void deleteFamily() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Mueller\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .when().delete("/api/v1/families/" + id)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/families/" + id)
            .then().statusCode(404);
    }

    @Test
    void getFamily_notFound() {
        given()
            .when().get("/api/v1/families/000000000000000000000000")
            .then().statusCode(404);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && mvn test -Dtest=FamilyResourceTest
```

Expected: FAIL — endpoint `/api/v1/families` not found (404).

- [ ] **Step 3: Implement FamilyResource**

Create `backend/src/main/java/at/kigruapp/resource/FamilyResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.Family;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.List;

@Path("/api/v1/families")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FamilyResource {

    @GET
    public List<Family> list() {
        return Family.listAll();
    }

    @GET
    @Path("/{id}")
    public Family get(@PathParam("id") String id) {
        Family family = Family.findById(new ObjectId(id));
        if (family == null) {
            throw new NotFoundException();
        }
        return family;
    }

    @POST
    public Response create(Family family) {
        family.createdAt = Instant.now();
        family.persist();
        return Response.status(201).entity(family).build();
    }

    @PUT
    @Path("/{id}")
    public Family update(@PathParam("id") String id, Family update) {
        Family family = Family.findById(new ObjectId(id));
        if (family == null) {
            throw new NotFoundException();
        }
        family.name = update.name;
        family.customFields = update.customFields;
        family.update();
        return family;
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        Family family = Family.findById(new ObjectId(id));
        if (family == null) {
            throw new NotFoundException();
        }
        family.delete();
        return Response.noContent().build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && mvn test -Dtest=FamilyResourceTest
```

Expected: All 5 tests PASS. Requires a running MongoDB on localhost:27017 (use `docker run -d -p 27017:27017 mongo:7` if not running).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/FamilyResource.java \
       backend/src/test/java/at/kigruapp/resource/FamilyResourceTest.java
git commit -m "feat: add Family REST resource with CRUD endpoints and tests"
```

---

## Task 4: Child REST Resource + Tests

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/ChildResource.java`
- Create: `backend/src/test/java/at/kigruapp/resource/ChildResourceTest.java`

### Steps

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/resource/ChildResourceTest.java`:

```java
package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.Child;
import at.kigruapp.entity.Family;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class ChildResourceTest {

    @BeforeEach
    void cleanup() {
        Child.deleteAll();
        Family.deleteAll();
    }

    private String createFamily(String name) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"" + name + "\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");
    }

    @Test
    void createAndGetChild() {
        String familyId = createFamily("Mueller");

        String childId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Anna\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2020-03-15\", \"gender\": \"female\"}")
            .when().post("/api/v1/children")
            .then()
            .statusCode(201)
            .body("firstName", is("Anna"))
            .body("lastName", is("Mueller"))
            .body("gender", is("female"))
            .extract().path("id");

        given()
            .when().get("/api/v1/children/" + childId)
            .then()
            .statusCode(200)
            .body("firstName", is("Anna"));
    }

    @Test
    void listChildrenByFamily() {
        String familyId = createFamily("Mueller");

        given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Anna\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2020-03-15\", \"gender\": \"female\"}")
            .when().post("/api/v1/children")
            .then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Max\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2022-07-01\", \"gender\": \"male\"}")
            .when().post("/api/v1/children")
            .then().statusCode(201);

        given()
            .when().get("/api/v1/families/" + familyId + "/children")
            .then()
            .statusCode(200)
            .body("$.size()", is(2));
    }

    @Test
    void updateChild() {
        String familyId = createFamily("Mueller");

        String childId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Anna\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2020-03-15\", \"gender\": \"female\"}")
            .when().post("/api/v1/children")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"firstName\": \"Marie\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2020-03-15\", \"gender\": \"female\"}")
            .when().put("/api/v1/children/" + childId)
            .then()
            .statusCode(200)
            .body("firstName", is("Marie"));
    }

    @Test
    void deleteChild() {
        String familyId = createFamily("Mueller");

        String childId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Anna\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2020-03-15\", \"gender\": \"female\"}")
            .when().post("/api/v1/children")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .when().delete("/api/v1/children/" + childId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/children/" + childId)
            .then().statusCode(404);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && mvn test -Dtest=ChildResourceTest
```

Expected: FAIL — endpoint not found.

- [ ] **Step 3: Implement ChildResource**

Create `backend/src/main/java/at/kigruapp/resource/ChildResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.Child;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.util.List;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChildResource {

    @GET
    @Path("/children")
    public List<Child> list() {
        return Child.listAll();
    }

    @GET
    @Path("/children/{id}")
    public Child get(@PathParam("id") String id) {
        Child child = Child.findById(new ObjectId(id));
        if (child == null) {
            throw new NotFoundException();
        }
        return child;
    }

    @GET
    @Path("/families/{familyId}/children")
    public List<Child> listByFamily(@PathParam("familyId") String familyId) {
        return Child.findByFamilyId(new ObjectId(familyId));
    }

    @POST
    @Path("/children")
    public Response create(Child child) {
        child.persist();
        return Response.status(201).entity(child).build();
    }

    @PUT
    @Path("/children/{id}")
    public Child update(@PathParam("id") String id, Child update) {
        Child child = Child.findById(new ObjectId(id));
        if (child == null) {
            throw new NotFoundException();
        }
        child.firstName = update.firstName;
        child.lastName = update.lastName;
        child.dateOfBirth = update.dateOfBirth;
        child.gender = update.gender;
        child.entryDate = update.entryDate;
        child.exitDate = update.exitDate;
        child.notes = update.notes;
        child.customFields = update.customFields;
        child.update();
        return child;
    }

    @DELETE
    @Path("/children/{id}")
    public Response delete(@PathParam("id") String id) {
        Child child = Child.findById(new ObjectId(id));
        if (child == null) {
            throw new NotFoundException();
        }
        child.delete();
        return Response.noContent().build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && mvn test -Dtest=ChildResourceTest
```

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/ChildResource.java \
       backend/src/test/java/at/kigruapp/resource/ChildResourceTest.java
git commit -m "feat: add Child REST resource with CRUD + family lookup and tests"
```

---

## Task 5: Parent REST Resource + Tests

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/ParentResource.java`
- Create: `backend/src/test/java/at/kigruapp/resource/ParentResourceTest.java`

### Steps

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/resource/ParentResourceTest.java`:

```java
package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.Parent;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class ParentResourceTest {

    @BeforeEach
    void cleanup() {
        Parent.deleteAll();
        Family.deleteAll();
    }

    private String createFamily(String name) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"" + name + "\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");
    }

    @Test
    void createAndGetParent() {
        String familyId = createFamily("Mueller");

        String parentId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Hans\", \"lastName\": \"Mueller\", \"email\": \"hans@example.com\", \"phone\": \"+43 664 1234567\", \"address\": {\"street\": \"Hauptstr. 1\", \"zip\": \"1010\", \"city\": \"Wien\"}}")
            .when().post("/api/v1/parents")
            .then()
            .statusCode(201)
            .body("firstName", is("Hans"))
            .body("email", is("hans@example.com"))
            .body("address.city", is("Wien"))
            .extract().path("id");

        given()
            .when().get("/api/v1/parents/" + parentId)
            .then()
            .statusCode(200)
            .body("firstName", is("Hans"));
    }

    @Test
    void listParentsByFamily() {
        String familyId = createFamily("Mueller");

        given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Hans\", \"lastName\": \"Mueller\"}")
            .when().post("/api/v1/parents").then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Maria\", \"lastName\": \"Mueller\"}")
            .when().post("/api/v1/parents").then().statusCode(201);

        given()
            .when().get("/api/v1/families/" + familyId + "/parents")
            .then()
            .statusCode(200)
            .body("$.size()", is(2));
    }

    @Test
    void updateParent() {
        String familyId = createFamily("Mueller");

        String parentId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Hans\", \"lastName\": \"Mueller\"}")
            .when().post("/api/v1/parents")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"firstName\": \"Johann\", \"lastName\": \"Mueller\", \"email\": \"johann@example.com\"}")
            .when().put("/api/v1/parents/" + parentId)
            .then()
            .statusCode(200)
            .body("firstName", is("Johann"))
            .body("email", is("johann@example.com"));
    }

    @Test
    void deleteParent() {
        String familyId = createFamily("Mueller");

        String parentId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Hans\", \"lastName\": \"Mueller\"}")
            .when().post("/api/v1/parents")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .when().delete("/api/v1/parents/" + parentId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/parents/" + parentId)
            .then().statusCode(404);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && mvn test -Dtest=ParentResourceTest
```

Expected: FAIL.

- [ ] **Step 3: Implement ParentResource**

Create `backend/src/main/java/at/kigruapp/resource/ParentResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.Parent;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.util.List;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ParentResource {

    @GET
    @Path("/parents")
    public List<Parent> list() {
        return Parent.listAll();
    }

    @GET
    @Path("/parents/{id}")
    public Parent get(@PathParam("id") String id) {
        Parent parent = Parent.findById(new ObjectId(id));
        if (parent == null) {
            throw new NotFoundException();
        }
        return parent;
    }

    @GET
    @Path("/families/{familyId}/parents")
    public List<Parent> listByFamily(@PathParam("familyId") String familyId) {
        return Parent.findByFamilyId(new ObjectId(familyId));
    }

    @POST
    @Path("/parents")
    public Response create(Parent parent) {
        parent.persist();
        return Response.status(201).entity(parent).build();
    }

    @PUT
    @Path("/parents/{id}")
    public Parent update(@PathParam("id") String id, Parent update) {
        Parent parent = Parent.findById(new ObjectId(id));
        if (parent == null) {
            throw new NotFoundException();
        }
        parent.firstName = update.firstName;
        parent.lastName = update.lastName;
        parent.email = update.email;
        parent.phone = update.phone;
        parent.address = update.address;
        parent.permissions = update.permissions;
        parent.customFields = update.customFields;
        parent.update();
        return parent;
    }

    @DELETE
    @Path("/parents/{id}")
    public Response delete(@PathParam("id") String id) {
        Parent parent = Parent.findById(new ObjectId(id));
        if (parent == null) {
            throw new NotFoundException();
        }
        parent.delete();
        return Response.noContent().build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && mvn test -Dtest=ParentResourceTest
```

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/ParentResource.java \
       backend/src/test/java/at/kigruapp/resource/ParentResourceTest.java
git commit -m "feat: add Parent REST resource with CRUD + family lookup and tests"
```

---

## Task 6: FieldDefinition REST Resource + Tests

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/FieldDefinitionResource.java`
- Create: `backend/src/test/java/at/kigruapp/resource/FieldDefinitionResourceTest.java`

### Steps

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/resource/FieldDefinitionResourceTest.java`:

```java
package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.FieldDefinition;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class FieldDefinitionResourceTest {

    @BeforeEach
    void cleanup() {
        FieldDefinition.deleteAll();
    }

    @Test
    void createAndListFieldDefinitions() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"entity\": \"CHILD\", \"fieldName\": \"svnr\", \"label\": {\"de\": \"Sozialversicherungsnummer\", \"en\": \"Social security number\"}, \"type\": \"TEXT\", \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then()
            .statusCode(201)
            .body("fieldName", is("svnr"))
            .body("entity", is("CHILD"))
            .body("label.de", is("Sozialversicherungsnummer"));

        given()
            .when().get("/api/v1/field-definitions")
            .then()
            .statusCode(200)
            .body("$.size()", is(1));
    }

    @Test
    void createSelectFieldWithOptions() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"entity\": \"CHILD\", \"fieldName\": \"kaliumjodid\", \"label\": {\"de\": \"Kaliumjodid-Tabletten\", \"en\": \"Potassium iodide tablets\"}, \"type\": \"SELECT\", \"options\": [\"Ja\", \"Nein\"], \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then()
            .statusCode(201)
            .body("type", is("SELECT"))
            .body("options.size()", is(2));
    }

    @Test
    void updateFieldDefinition() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"entity\": \"CHILD\", \"fieldName\": \"svnr\", \"label\": {\"de\": \"SVNr\", \"en\": \"SSN\"}, \"type\": \"TEXT\", \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"entity\": \"CHILD\", \"fieldName\": \"svnr\", \"label\": {\"de\": \"Sozialversicherungsnummer\", \"en\": \"Social security number\"}, \"type\": \"TEXT\", \"required\": true}")
            .when().put("/api/v1/field-definitions/" + id)
            .then()
            .statusCode(200)
            .body("label.de", is("Sozialversicherungsnummer"))
            .body("required", is(true));
    }

    @Test
    void deleteFieldDefinition() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"entity\": \"CHILD\", \"fieldName\": \"svnr\", \"label\": {\"de\": \"SVNr\", \"en\": \"SSN\"}, \"type\": \"TEXT\", \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .when().delete("/api/v1/field-definitions/" + id)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/field-definitions")
            .then().body("$.size()", is(0));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && mvn test -Dtest=FieldDefinitionResourceTest
```

Expected: FAIL.

- [ ] **Step 3: Implement FieldDefinitionResource**

Create `backend/src/main/java/at/kigruapp/resource/FieldDefinitionResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.util.List;

@Path("/api/v1/field-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FieldDefinitionResource {

    @GET
    public List<FieldDefinition> list() {
        return FieldDefinition.listAll();
    }

    @POST
    public Response create(FieldDefinition fieldDef) {
        fieldDef.persist();
        return Response.status(201).entity(fieldDef).build();
    }

    @PUT
    @Path("/{id}")
    public FieldDefinition update(@PathParam("id") String id, FieldDefinition update) {
        FieldDefinition fieldDef = FieldDefinition.findById(new ObjectId(id));
        if (fieldDef == null) {
            throw new NotFoundException();
        }
        fieldDef.entity = update.entity;
        fieldDef.fieldName = update.fieldName;
        fieldDef.label = update.label;
        fieldDef.type = update.type;
        fieldDef.options = update.options;
        fieldDef.required = update.required;
        fieldDef.update();
        return fieldDef;
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        FieldDefinition fieldDef = FieldDefinition.findById(new ObjectId(id));
        if (fieldDef == null) {
            throw new NotFoundException();
        }
        fieldDef.delete();
        return Response.noContent().build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && mvn test -Dtest=FieldDefinitionResourceTest
```

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/FieldDefinitionResource.java \
       backend/src/test/java/at/kigruapp/resource/FieldDefinitionResourceTest.java
git commit -m "feat: add FieldDefinition REST resource for custom field management"
```

---

## Task 7: Angular Project Scaffolding

**Files:**
- Create: `frontend/` (via Angular CLI)
- Modify: `frontend/angular.json`
- Create: `frontend/src/app/app.routes.ts`

### Steps

- [ ] **Step 1: Generate Angular project**

```bash
cd D:/GIT/kigruapp
npx @angular/cli@18 new frontend --routing --style=scss --ssr=false --skip-git
```

- [ ] **Step 2: Add Angular Material**

```bash
cd frontend && npx ng add @angular/material --theme=indigo-pink --typography=true --animations=true --skip-confirmation
```

- [ ] **Step 3: Install oauth library**

```bash
cd frontend && npm install angular-oauth2-oidc
```

- [ ] **Step 4: Verify it builds**

```bash
cd frontend && npx ng build
```

Expected: Build succeeds.

- [ ] **Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: scaffold Angular 18 frontend with Angular Material and OAuth"
```

---

## Task 8: Angular Core Module — Auth Service, Interceptor, Guard

**Files:**
- Create: `frontend/src/app/core/services/auth.service.ts`
- Create: `frontend/src/app/core/interceptors/auth.interceptor.ts`
- Create: `frontend/src/app/core/guards/auth.guard.ts`
- Modify: `frontend/src/app/app.config.ts`

### Steps

- [ ] **Step 1: Create AuthService**

Create `frontend/src/app/core/services/auth.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { OAuthService, AuthConfig } from 'angular-oauth2-oidc';

const authConfig: AuthConfig = {
  issuer: '/auth/realms/kigruapp',
  redirectUri: window.location.origin,
  clientId: 'kigruapp-frontend',
  responseType: 'code',
  scope: 'openid profile email',
  showDebugInformation: false,
};

@Injectable({ providedIn: 'root' })
export class AuthService {
  constructor(private oauthService: OAuthService) {
    this.oauthService.configure(authConfig);
    this.oauthService.setupAutomaticSilentRefresh();
  }

  async login(): Promise<boolean> {
    await this.oauthService.loadDiscoveryDocumentAndTryLogin();
    if (!this.oauthService.hasValidAccessToken()) {
      this.oauthService.initCodeFlow();
      return false;
    }
    return true;
  }

  logout(): void {
    this.oauthService.logOut();
  }

  get accessToken(): string {
    return this.oauthService.getAccessToken();
  }

  get isAuthenticated(): boolean {
    return this.oauthService.hasValidAccessToken();
  }

  get userName(): string {
    const claims = this.oauthService.getIdentityClaims() as any;
    return claims?.preferred_username ?? '';
  }
}
```

- [ ] **Step 2: Create Auth Interceptor**

Create `frontend/src/app/core/interceptors/auth.interceptor.ts`:

```typescript
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  if (auth.accessToken && req.url.startsWith('/api')) {
    const authReq = req.clone({
      setHeaders: { Authorization: `Bearer ${auth.accessToken}` },
    });
    return next(authReq);
  }
  return next(req);
};
```

- [ ] **Step 3: Create Auth Guard**

Create `frontend/src/app/core/guards/auth.guard.ts`:

```typescript
import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AuthService } from '../services/auth.service';

export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  return auth.login();
};
```

- [ ] **Step 4: Configure app.config.ts with interceptor and OAuth**

Replace `frontend/src/app/app.config.ts`:

```typescript
import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideOAuthClient } from 'angular-oauth2-oidc';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideAnimationsAsync(),
    provideOAuthClient(),
  ],
};
```

- [ ] **Step 5: Set up routes**

Replace `frontend/src/app/app.routes.ts`:

```typescript
import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
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
  { path: '', redirectTo: 'administration/families', pathMatch: 'full' },
];
```

- [ ] **Step 6: Verify compilation**

```bash
cd frontend && npx ng build 2>&1 | tail -5
```

Expected: Build will fail because lazy-loaded components don't exist yet. That's OK — we'll create them in the next tasks. Verify that `app.config.ts` and routes compile without syntax errors by checking the error messages only mention missing component files.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/core/ frontend/src/app/app.config.ts frontend/src/app/app.routes.ts
git commit -m "feat: add Angular auth service, interceptor, guard, and route config"
```

---

## Task 9: Angular Shared Models

**Files:**
- Create: `frontend/src/app/shared/models/family.model.ts`
- Create: `frontend/src/app/shared/models/child.model.ts`
- Create: `frontend/src/app/shared/models/parent.model.ts`
- Create: `frontend/src/app/shared/models/field-definition.model.ts`

### Steps

- [ ] **Step 1: Create all model interfaces**

Create `frontend/src/app/shared/models/family.model.ts`:

```typescript
export interface Family {
  id?: string;
  name: string;
  createdAt?: string;
  customFields?: Record<string, unknown>;
}
```

Create `frontend/src/app/shared/models/child.model.ts`:

```typescript
export interface Child {
  id?: string;
  familyId: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: string;
  entryDate?: string;
  exitDate?: string;
  notes?: string;
  customFields?: Record<string, unknown>;
}
```

Create `frontend/src/app/shared/models/parent.model.ts`:

```typescript
export interface Address {
  street: string;
  zip: string;
  city: string;
}

export interface Parent {
  id?: string;
  familyId: string;
  firstName: string;
  lastName: string;
  email?: string;
  phone?: string;
  address?: Address;
  keycloakUserId?: string;
  permissions?: string[];
  customFields?: Record<string, unknown>;
}
```

Create `frontend/src/app/shared/models/field-definition.model.ts`:

```typescript
export type EntityType = 'CHILD' | 'PARENT' | 'FAMILY';
export type FieldType = 'TEXT' | 'DATE' | 'SELECT' | 'BOOLEAN';

export interface FieldDefinition {
  id?: string;
  entity: EntityType;
  fieldName: string;
  label: Record<string, string>;
  type: FieldType;
  options?: string[];
  required: boolean;
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/shared/
git commit -m "feat: add TypeScript model interfaces for family, child, parent, field definition"
```

---

## Task 10: Angular API Services

**Files:**
- Create: `frontend/src/app/core/services/api.service.ts`
- Create: `frontend/src/app/administration/families/services/family.service.ts`
- Create: `frontend/src/app/administration/families/services/child.service.ts`
- Create: `frontend/src/app/administration/families/services/parent.service.ts`
- Create: `frontend/src/app/settings/custom-fields/services/field-definition.service.ts`

### Steps

- [ ] **Step 1: Create base API service**

Create `frontend/src/app/core/services/api.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private baseUrl = '/api/v1';

  constructor(private http: HttpClient) {}

  get<T>(path: string): Observable<T> {
    return this.http.get<T>(`${this.baseUrl}${path}`);
  }

  post<T>(path: string, body: unknown): Observable<T> {
    return this.http.post<T>(`${this.baseUrl}${path}`, body);
  }

  put<T>(path: string, body: unknown): Observable<T> {
    return this.http.put<T>(`${this.baseUrl}${path}`, body);
  }

  delete(path: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}${path}`);
  }
}
```

- [ ] **Step 2: Create FamilyService**

Create `frontend/src/app/administration/families/services/family.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { Family } from '../../../shared/models/family.model';
import { Child } from '../../../shared/models/child.model';
import { Parent } from '../../../shared/models/parent.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class FamilyService {
  constructor(private api: ApiService) {}

  list(): Observable<Family[]> {
    return this.api.get<Family[]>('/families');
  }

  get(id: string): Observable<Family> {
    return this.api.get<Family>(`/families/${id}`);
  }

  create(family: Family): Observable<Family> {
    return this.api.post<Family>('/families', family);
  }

  update(id: string, family: Family): Observable<Family> {
    return this.api.put<Family>(`/families/${id}`, family);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/families/${id}`);
  }

  getChildren(familyId: string): Observable<Child[]> {
    return this.api.get<Child[]>(`/families/${familyId}/children`);
  }

  getParents(familyId: string): Observable<Parent[]> {
    return this.api.get<Parent[]>(`/families/${familyId}/parents`);
  }
}
```

- [ ] **Step 3: Create ChildService**

Create `frontend/src/app/administration/families/services/child.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { Child } from '../../../shared/models/child.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ChildService {
  constructor(private api: ApiService) {}

  create(child: Child): Observable<Child> {
    return this.api.post<Child>('/children', child);
  }

  update(id: string, child: Child): Observable<Child> {
    return this.api.put<Child>(`/children/${id}`, child);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/children/${id}`);
  }
}
```

- [ ] **Step 4: Create ParentService**

Create `frontend/src/app/administration/families/services/parent.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { Parent } from '../../../shared/models/parent.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ParentService {
  constructor(private api: ApiService) {}

  create(parent: Parent): Observable<Parent> {
    return this.api.post<Parent>('/parents', parent);
  }

  update(id: string, parent: Parent): Observable<Parent> {
    return this.api.put<Parent>(`/parents/${id}`, parent);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/parents/${id}`);
  }
}
```

- [ ] **Step 5: Create FieldDefinitionService**

Create `frontend/src/app/settings/custom-fields/services/field-definition.service.ts`:

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

  create(fieldDef: FieldDefinition): Observable<FieldDefinition> {
    return this.api.post<FieldDefinition>('/field-definitions', fieldDef);
  }

  update(id: string, fieldDef: FieldDefinition): Observable<FieldDefinition> {
    return this.api.put<FieldDefinition>(`/field-definitions/${id}`, fieldDef);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/field-definitions/${id}`);
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/core/services/api.service.ts \
       frontend/src/app/administration/ \
       frontend/src/app/settings/custom-fields/services/
git commit -m "feat: add Angular API services for families, children, parents, field definitions"
```

---

## Task 11: Family List Component

**Files:**
- Create: `frontend/src/app/administration/families/family-list/family-list.component.ts`
- Create: `frontend/src/app/administration/families/family-list/family-list.component.html`
- Create: `frontend/src/app/administration/families/family-list/family-list.component.scss`

### Steps

- [ ] **Step 1: Create FamilyListComponent class**

Create `frontend/src/app/administration/families/family-list/family-list.component.ts`:

```typescript
import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatSortModule, MatSort } from '@angular/material/sort';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { FamilyService } from '../services/family.service';
import { Family } from '../../../shared/models/family.model';
import { Child } from '../../../shared/models/child.model';
import { Parent } from '../../../shared/models/parent.model';
import { FamilyWizardComponent } from '../family-wizard/family-wizard.component';
import { forkJoin } from 'rxjs';

interface FamilyRow {
  type: 'Kind' | 'Elternteil';
  name: string;
  email: string;
  phone: string;
  street: string;
  zip: string;
  city: string;
  dateOfBirth: string;
  familyName: string;
  exitDate: string;
}

@Component({
  selector: 'app-family-list',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule,
    MatSortModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
  ],
  templateUrl: './family-list.component.html',
  styleUrl: './family-list.component.scss',
})
export class FamilyListComponent implements OnInit {
  displayedColumns: string[] = [
    'type', 'name', 'email', 'phone', 'street', 'zip', 'city',
    'dateOfBirth', 'familyName', 'exitDate',
  ];
  dataSource = new MatTableDataSource<FamilyRow>();

  @ViewChild(MatSort) sort!: MatSort;

  constructor(
    private familyService: FamilyService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  ngAfterViewInit(): void {
    this.dataSource.sort = this.sort;
  }

  applyFilter(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.dataSource.filter = value.trim().toLowerCase();
  }

  openWizard(): void {
    const dialogRef = this.dialog.open(FamilyWizardComponent, {
      width: '700px',
      maxWidth: '95vw',
      disableClose: true,
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (result) {
        this.loadData();
      }
    });
  }

  private loadData(): void {
    this.familyService.list().subscribe((families) => {
      if (families.length === 0) {
        this.dataSource.data = [];
        return;
      }

      const requests = families.map((f) =>
        forkJoin({
          family: [f],
          children: this.familyService.getChildren(f.id!),
          parents: this.familyService.getParents(f.id!),
        })
      );

      forkJoin(requests).subscribe((results) => {
        const rows: FamilyRow[] = [];
        for (const { family, children, parents } of results) {
          for (const child of children) {
            rows.push({
              type: 'Kind',
              name: `${child.lastName} ${child.firstName}`,
              email: '',
              phone: '',
              street: '',
              zip: '',
              city: '',
              dateOfBirth: child.dateOfBirth,
              familyName: family.name,
              exitDate: child.exitDate ?? '',
            });
          }
          for (const parent of parents) {
            rows.push({
              type: 'Elternteil',
              name: `${parent.lastName} ${parent.firstName}`,
              email: parent.email ?? '',
              phone: parent.phone ?? '',
              street: parent.address?.street ?? '',
              zip: parent.address?.zip ?? '',
              city: parent.address?.city ?? '',
              dateOfBirth: '',
              familyName: family.name,
              exitDate: '',
            });
          }
        }
        this.dataSource.data = rows;
      });
    });
  }
}
```

- [ ] **Step 2: Create template**

Create `frontend/src/app/administration/families/family-list/family-list.component.html`:

```html
<div class="family-list-container">
  <div class="header">
    <h2 i18n>Familien</h2>
    <div class="actions">
      <mat-form-field appearance="outline" class="filter-field">
        <mat-label i18n>Filter</mat-label>
        <input matInput (keyup)="applyFilter($event)" placeholder="Suchen..." i18n-placeholder>
      </mat-form-field>
      <button mat-raised-button color="primary" (click)="openWizard()">
        <mat-icon>add</mat-icon>
        <span i18n>Kind erstellen</span>
      </button>
    </div>
  </div>

  <table mat-table [dataSource]="dataSource" matSort class="mat-elevation-z2">
    <ng-container matColumnDef="type">
      <th mat-header-cell *matHeaderCellDef mat-sort-header i18n>Typ</th>
      <td mat-cell *matCellDef="let row">{{ row.type }}</td>
    </ng-container>

    <ng-container matColumnDef="name">
      <th mat-header-cell *matHeaderCellDef mat-sort-header i18n>Name</th>
      <td mat-cell *matCellDef="let row">{{ row.name }}</td>
    </ng-container>

    <ng-container matColumnDef="email">
      <th mat-header-cell *matHeaderCellDef mat-sort-header i18n>Email</th>
      <td mat-cell *matCellDef="let row">{{ row.email }}</td>
    </ng-container>

    <ng-container matColumnDef="phone">
      <th mat-header-cell *matHeaderCellDef mat-sort-header i18n>Telefon</th>
      <td mat-cell *matCellDef="let row">{{ row.phone }}</td>
    </ng-container>

    <ng-container matColumnDef="street">
      <th mat-header-cell *matHeaderCellDef mat-sort-header i18n>Strasse</th>
      <td mat-cell *matCellDef="let row">{{ row.street }}</td>
    </ng-container>

    <ng-container matColumnDef="zip">
      <th mat-header-cell *matHeaderCellDef mat-sort-header i18n>PLZ</th>
      <td mat-cell *matCellDef="let row">{{ row.zip }}</td>
    </ng-container>

    <ng-container matColumnDef="city">
      <th mat-header-cell *matHeaderCellDef mat-sort-header i18n>Ort</th>
      <td mat-cell *matCellDef="let row">{{ row.city }}</td>
    </ng-container>

    <ng-container matColumnDef="dateOfBirth">
      <th mat-header-cell *matHeaderCellDef mat-sort-header i18n>Geburtsdatum</th>
      <td mat-cell *matCellDef="let row">{{ row.dateOfBirth }}</td>
    </ng-container>

    <ng-container matColumnDef="familyName">
      <th mat-header-cell *matHeaderCellDef mat-sort-header i18n>Familie</th>
      <td mat-cell *matCellDef="let row">{{ row.familyName }}</td>
    </ng-container>

    <ng-container matColumnDef="exitDate">
      <th mat-header-cell *matHeaderCellDef mat-sort-header i18n>Austrittsdatum</th>
      <td mat-cell *matCellDef="let row">{{ row.exitDate }}</td>
    </ng-container>

    <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
    <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
  </table>
</div>
```

- [ ] **Step 3: Create styles**

Create `frontend/src/app/administration/families/family-list/family-list.component.scss`:

```scss
.family-list-container {
  padding: 16px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 16px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.filter-field {
  min-width: 200px;
}

table {
  width: 100%;
}

@media (max-width: 768px) {
  .header {
    flex-direction: column;
    align-items: stretch;
  }

  .actions {
    flex-direction: column;
  }

  table {
    display: block;
    overflow-x: auto;
  }
}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/administration/families/family-list/
git commit -m "feat: add family list component with MatTable, sorting, and filtering"
```

---

## Task 12: Family Wizard — Step Components

**Files:**
- Create: `frontend/src/app/administration/families/family-wizard/family-wizard.component.ts`
- Create: `frontend/src/app/administration/families/family-wizard/family-wizard.component.html`
- Create: `frontend/src/app/administration/families/family-wizard/family-wizard.component.scss`
- Create: `frontend/src/app/administration/families/family-wizard/steps/family-step.component.ts`
- Create: `frontend/src/app/administration/families/family-wizard/steps/family-step.component.html`
- Create: `frontend/src/app/administration/families/family-wizard/steps/child-step.component.ts`
- Create: `frontend/src/app/administration/families/family-wizard/steps/child-step.component.html`
- Create: `frontend/src/app/administration/families/family-wizard/steps/parents-step.component.ts`
- Create: `frontend/src/app/administration/families/family-wizard/steps/parents-step.component.html`

### Steps

- [ ] **Step 1: Create FamilyStepComponent**

Create `frontend/src/app/administration/families/family-wizard/steps/family-step.component.ts`:

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
  imports: [CommonModule, ReactiveFormsModule, MatRadioModule, MatSelectModule, MatFormFieldModule],
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

Create `frontend/src/app/administration/families/family-wizard/steps/family-step.component.html`:

```html
<div [formGroup]="form">
  <mat-radio-group formControlName="mode">
    <mat-radio-button value="new" i18n>Neue Familie erstellen</mat-radio-button>
    <mat-radio-button value="existing" i18n>Bestehende Familie verwenden</mat-radio-button>
  </mat-radio-group>

  @if (form.value.mode === 'existing') {
    <mat-form-field appearance="outline" class="full-width">
      <mat-label i18n>Familie auswählen</mat-label>
      <mat-select formControlName="existingFamilyId">
        @for (family of existingFamilies; track family.id) {
          <mat-option [value]="family.id">{{ family.name }}</mat-option>
        }
      </mat-select>
    </mat-form-field>
  }
</div>
```

- [ ] **Step 2: Create ChildStepComponent**

Create `frontend/src/app/administration/families/family-wizard/steps/child-step.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { FieldDefinitionService } from '../../../../settings/custom-fields/services/field-definition.service';
import { FieldDefinition } from '../../../../shared/models/field-definition.model';

@Component({
  selector: 'app-child-step',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatDatepickerModule, MatNativeDateModule,
  ],
  templateUrl: './child-step.component.html',
})
export class ChildStepComponent implements OnInit {
  form = new FormGroup({
    firstName: new FormControl('', Validators.required),
    lastName: new FormControl('', Validators.required),
    dateOfBirth: new FormControl<Date | null>(null, Validators.required),
    gender: new FormControl('', Validators.required),
    entryDate: new FormControl<Date | null>(null),
    notes: new FormControl(''),
  });

  customFieldDefs: FieldDefinition[] = [];
  customFieldControls: Record<string, FormControl> = {};

  genderOptions = [
    { value: 'male', label: $localize`männlich` },
    { value: 'female', label: $localize`weiblich` },
    { value: 'diverse', label: $localize`divers` },
  ];

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.fieldDefService.list().subscribe((defs) => {
      this.customFieldDefs = defs.filter((d) => d.entity === 'CHILD');
      for (const def of this.customFieldDefs) {
        const control = new FormControl('', def.required ? Validators.required : []);
        this.customFieldControls[def.fieldName] = control;
        this.form.addControl(`custom_${def.fieldName}`, control);
      }
    });
  }

  get isValid(): boolean {
    return this.form.valid;
  }

  getChildData(): Record<string, unknown> {
    const val = this.form.value;
    const customFields: Record<string, unknown> = {};
    for (const def of this.customFieldDefs) {
      customFields[def.fieldName] = this.customFieldControls[def.fieldName].value;
    }
    return {
      firstName: val.firstName,
      lastName: val.lastName,
      dateOfBirth: val.dateOfBirth?.toISOString().split('T')[0],
      gender: val.gender,
      entryDate: val.entryDate?.toISOString().split('T')[0] ?? null,
      notes: val.notes,
      customFields,
    };
  }
}
```

Create `frontend/src/app/administration/families/family-wizard/steps/child-step.component.html`:

```html
<div [formGroup]="form">
  <div class="row">
    <mat-form-field appearance="outline">
      <mat-label i18n>Vorname</mat-label>
      <input matInput formControlName="firstName">
    </mat-form-field>
    <mat-form-field appearance="outline">
      <mat-label i18n>Nachname</mat-label>
      <input matInput formControlName="lastName">
    </mat-form-field>
  </div>

  <div class="row">
    <mat-form-field appearance="outline">
      <mat-label i18n>Geburtsdatum</mat-label>
      <input matInput [matDatepicker]="dobPicker" formControlName="dateOfBirth">
      <mat-datepicker-toggle matIconSuffix [for]="dobPicker"></mat-datepicker-toggle>
      <mat-datepicker #dobPicker></mat-datepicker>
    </mat-form-field>
    <mat-form-field appearance="outline">
      <mat-label i18n>Geschlecht</mat-label>
      <mat-select formControlName="gender">
        @for (opt of genderOptions; track opt.value) {
          <mat-option [value]="opt.value">{{ opt.label }}</mat-option>
        }
      </mat-select>
    </mat-form-field>
  </div>

  <div class="row">
    <mat-form-field appearance="outline">
      <mat-label i18n>Eintrittsdatum</mat-label>
      <input matInput [matDatepicker]="entryPicker" formControlName="entryDate">
      <mat-datepicker-toggle matIconSuffix [for]="entryPicker"></mat-datepicker-toggle>
      <mat-datepicker #entryPicker></mat-datepicker>
    </mat-form-field>
  </div>

  <mat-form-field appearance="outline" class="full-width">
    <mat-label i18n>Notizen</mat-label>
    <textarea matInput formControlName="notes" rows="3"></textarea>
  </mat-form-field>

  @for (def of customFieldDefs; track def.fieldName) {
    <mat-form-field appearance="outline" class="full-width">
      <mat-label>{{ def.label['de'] }}</mat-label>
      @if (def.type === 'SELECT') {
        <mat-select [formControl]="customFieldControls[def.fieldName]">
          @for (opt of def.options; track opt) {
            <mat-option [value]="opt">{{ opt }}</mat-option>
          }
        </mat-select>
      } @else {
        <input matInput [formControl]="customFieldControls[def.fieldName]">
      }
    </mat-form-field>
  }
</div>
```

- [ ] **Step 3: Create ParentsStepComponent**

Create `frontend/src/app/administration/families/family-wizard/steps/parents-step.component.ts`:

```typescript
import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-parents-step',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatButtonModule,
    MatCheckboxModule, MatIconModule,
  ],
  templateUrl: './parents-step.component.html',
})
export class ParentsStepComponent {
  parentsArray = new FormArray<FormGroup>([]);
  reuseAddress: Record<number, boolean> = {};

  constructor() {
    this.addParent();
  }

  addParent(): void {
    const group = new FormGroup({
      firstName: new FormControl('', Validators.required),
      lastName: new FormControl('', Validators.required),
      email: new FormControl(''),
      phone: new FormControl(''),
      street: new FormControl(''),
      zip: new FormControl(''),
      city: new FormControl(''),
    });
    this.parentsArray.push(group);
  }

  removeParent(index: number): void {
    this.parentsArray.removeAt(index);
    delete this.reuseAddress[index];
  }

  onReuseAddress(index: number, checked: boolean): void {
    this.reuseAddress[index] = checked;
    if (checked && index > 0) {
      const first = this.parentsArray.at(0).value;
      const current = this.parentsArray.at(index);
      current.patchValue({
        street: first.street,
        zip: first.zip,
        city: first.city,
      });
    }
  }

  get isValid(): boolean {
    return this.parentsArray.valid && this.parentsArray.length > 0;
  }

  getParentsData(): Record<string, unknown>[] {
    return this.parentsArray.controls.map((group) => {
      const val = group.value;
      return {
        firstName: val.firstName,
        lastName: val.lastName,
        email: val.email || null,
        phone: val.phone || null,
        address: {
          street: val.street || '',
          zip: val.zip || '',
          city: val.city || '',
        },
      };
    });
  }
}
```

Create `frontend/src/app/administration/families/family-wizard/steps/parents-step.component.html`:

```html
@for (parent of parentsArray.controls; track $index; let i = $index) {
  <div class="parent-section">
    <div class="parent-header">
      <h4 i18n>Elternteil {{ i + 1 }}</h4>
      @if (i > 0) {
        <button mat-icon-button color="warn" (click)="removeParent(i)">
          <mat-icon>delete</mat-icon>
        </button>
      }
    </div>

    <div [formGroup]="parent">
      <div class="row">
        <mat-form-field appearance="outline">
          <mat-label i18n>Vorname</mat-label>
          <input matInput formControlName="firstName">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label i18n>Nachname</mat-label>
          <input matInput formControlName="lastName">
        </mat-form-field>
      </div>
      <div class="row">
        <mat-form-field appearance="outline">
          <mat-label i18n>Email</mat-label>
          <input matInput formControlName="email" type="email">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label i18n>Telefon</mat-label>
          <input matInput formControlName="phone">
        </mat-form-field>
      </div>

      @if (i > 0) {
        <mat-checkbox
          [checked]="reuseAddress[i] ?? false"
          (change)="onReuseAddress(i, $event.checked)"
          i18n>
          Adresse wiederverwenden
        </mat-checkbox>
      }

      <div class="row">
        <mat-form-field appearance="outline" class="street-field">
          <mat-label i18n>Strasse</mat-label>
          <input matInput formControlName="street">
        </mat-form-field>
        <mat-form-field appearance="outline" class="zip-field">
          <mat-label i18n>PLZ</mat-label>
          <input matInput formControlName="zip">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label i18n>Ort</mat-label>
          <input matInput formControlName="city">
        </mat-form-field>
      </div>
    </div>
  </div>
}

<button mat-stroked-button (click)="addParent()">
  <mat-icon>person_add</mat-icon>
  <span i18n>Elternteil hinzufügen</span>
</button>
```

- [ ] **Step 4: Create FamilyWizardComponent (orchestrator)**

Create `frontend/src/app/administration/families/family-wizard/family-wizard.component.ts`:

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
import { ChildService } from '../services/child.service';
import { ParentService } from '../services/parent.service';
import { Family } from '../../../shared/models/family.model';
import { Child } from '../../../shared/models/child.model';
import { Parent } from '../../../shared/models/parent.model';
import { switchMap, concatMap, from, lastValueFrom, toArray } from 'rxjs';

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
    private childService: ChildService,
    private parentService: ParentService,
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
        const childData = this.childStep.getChildData();
        const family = await lastValueFrom(
          this.familyService.create({ name: childData['lastName'] as string })
        );
        familyId = family.id!;
      } else {
        familyId = this.familyStep.selectedFamilyId!;
      }

      // Step 2: Create child
      const childData = this.childStep.getChildData();
      await lastValueFrom(
        this.childService.create({ ...childData, familyId } as Child)
      );

      // Step 3: Create parents
      const parentsData = this.parentsStep.getParentsData();
      for (const parentData of parentsData) {
        await lastValueFrom(
          this.parentService.create({ ...parentData, familyId } as Parent)
        );
      }

      this.dialogRef.close(true);
    } catch (err) {
      console.error('Wizard failed:', err);
      this.submitting = false;
    }
  }
}
```

Create `frontend/src/app/administration/families/family-wizard/family-wizard.component.html`:

```html
<h2 mat-dialog-title i18n>Neues Kind</h2>

<mat-dialog-content>
  <mat-stepper #stepper linear>
    <mat-step [completed]="familyStep?.isValid ?? false">
      <ng-template matStepLabel i18n>Familie</ng-template>
      <app-family-step></app-family-step>
    </mat-step>

    <mat-step [completed]="childStep?.isValid ?? false">
      <ng-template matStepLabel i18n>Kind</ng-template>
      <app-child-step></app-child-step>
    </mat-step>

    <mat-step [completed]="parentsStep?.isValid ?? false">
      <ng-template matStepLabel i18n>Eltern</ng-template>
      <app-parents-step></app-parents-step>
    </mat-step>
  </mat-stepper>
</mat-dialog-content>

<mat-dialog-actions align="end">
  <button mat-button (click)="cancel()" i18n>Abbrechen</button>

  @if (stepper?.selectedIndex > 0) {
    <button mat-button matStepperPrevious i18n>Zurück</button>
  }

  @if (stepper?.selectedIndex < 2) {
    <button mat-raised-button color="primary" matStepperNext
      [disabled]="(stepper?.selectedIndex === 0 && !familyStep?.isValid) ||
                  (stepper?.selectedIndex === 1 && !childStep?.isValid)"
      i18n>Weiter</button>
  } @else {
    <button mat-raised-button color="primary"
      [disabled]="!parentsStep?.isValid || submitting"
      (click)="submit()" i18n>Familie erstellen</button>
  }
</mat-dialog-actions>
```

Create `frontend/src/app/administration/families/family-wizard/family-wizard.component.scss`:

```scss
:host {
  display: block;
}

.row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.row mat-form-field {
  flex: 1;
  min-width: 200px;
}

.full-width {
  width: 100%;
}

.parent-section {
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
}

.parent-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.street-field {
  flex: 2;
}

.zip-field {
  flex: 0.5;
  min-width: 100px;
}
```

- [ ] **Step 5: Verify build compiles**

```bash
cd frontend && npx ng build
```

Expected: Build SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/administration/families/family-wizard/
git commit -m "feat: add 3-step family creation wizard with MatStepper dialog"
```

---

## Task 13: Settings — Custom Fields Component

**Files:**
- Create: `frontend/src/app/settings/custom-fields/custom-fields.component.ts`
- Create: `frontend/src/app/settings/custom-fields/custom-fields.component.html`
- Create: `frontend/src/app/settings/custom-fields/custom-fields.component.scss`

### Steps

- [ ] **Step 1: Create CustomFieldsComponent**

Create `frontend/src/app/settings/custom-fields/custom-fields.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { FieldDefinitionService } from './services/field-definition.service';
import { FieldDefinition, EntityType, FieldType } from '../../shared/models/field-definition.model';

@Component({
  selector: 'app-custom-fields',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatTableModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule, MatCheckboxModule,
  ],
  templateUrl: './custom-fields.component.html',
  styleUrl: './custom-fields.component.scss',
})
export class CustomFieldsComponent implements OnInit {
  displayedColumns = ['entity', 'fieldName', 'labelDe', 'labelEn', 'type', 'required', 'actions'];
  dataSource = new MatTableDataSource<FieldDefinition>();

  entityTypes: EntityType[] = ['CHILD', 'PARENT', 'FAMILY'];
  fieldTypes: FieldType[] = ['TEXT', 'DATE', 'SELECT', 'BOOLEAN'];

  form = new FormGroup({
    entity: new FormControl<EntityType>('CHILD', Validators.required),
    fieldName: new FormControl('', Validators.required),
    labelDe: new FormControl('', Validators.required),
    labelEn: new FormControl('', Validators.required),
    type: new FormControl<FieldType>('TEXT', Validators.required),
    options: new FormControl(''),
    required: new FormControl(false),
  });

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.fieldDefService.list().subscribe((defs) => {
      this.dataSource.data = defs;
    });
  }

  addField(): void {
    if (!this.form.valid) return;

    const val = this.form.value;
    const fieldDef: FieldDefinition = {
      entity: val.entity!,
      fieldName: val.fieldName!,
      label: { de: val.labelDe!, en: val.labelEn! },
      type: val.type!,
      options: val.type === 'SELECT' ? val.options!.split(',').map((o) => o.trim()) : undefined,
      required: val.required!,
    };

    this.fieldDefService.create(fieldDef).subscribe(() => {
      this.form.reset({ entity: 'CHILD', type: 'TEXT', required: false });
      this.loadData();
    });
  }

  deleteField(id: string): void {
    this.fieldDefService.delete(id).subscribe(() => this.loadData());
  }
}
```

Create `frontend/src/app/settings/custom-fields/custom-fields.component.html`:

```html
<div class="custom-fields-container">
  <h2 i18n>Benutzerdefinierte Felder</h2>

  <form [formGroup]="form" (ngSubmit)="addField()" class="add-form">
    <div class="row">
      <mat-form-field appearance="outline">
        <mat-label i18n>Entität</mat-label>
        <mat-select formControlName="entity">
          @for (e of entityTypes; track e) {
            <mat-option [value]="e">{{ e }}</mat-option>
          }
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label i18n>Feldname</mat-label>
        <input matInput formControlName="fieldName">
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label i18n>Label (DE)</mat-label>
        <input matInput formControlName="labelDe">
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label i18n>Label (EN)</mat-label>
        <input matInput formControlName="labelEn">
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label i18n>Typ</mat-label>
        <mat-select formControlName="type">
          @for (t of fieldTypes; track t) {
            <mat-option [value]="t">{{ t }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </div>

    @if (form.value.type === 'SELECT') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label i18n>Optionen (kommagetrennt)</mat-label>
        <input matInput formControlName="options" placeholder="Ja, Nein">
      </mat-form-field>
    }

    <div class="row">
      <mat-checkbox formControlName="required" i18n>Pflichtfeld</mat-checkbox>
      <button mat-raised-button color="primary" type="submit" [disabled]="!form.valid" i18n>
        Feld hinzufügen
      </button>
    </div>
  </form>

  <table mat-table [dataSource]="dataSource" class="mat-elevation-z2">
    <ng-container matColumnDef="entity">
      <th mat-header-cell *matHeaderCellDef i18n>Entität</th>
      <td mat-cell *matCellDef="let row">{{ row.entity }}</td>
    </ng-container>
    <ng-container matColumnDef="fieldName">
      <th mat-header-cell *matHeaderCellDef i18n>Feldname</th>
      <td mat-cell *matCellDef="let row">{{ row.fieldName }}</td>
    </ng-container>
    <ng-container matColumnDef="labelDe">
      <th mat-header-cell *matHeaderCellDef i18n>Label (DE)</th>
      <td mat-cell *matCellDef="let row">{{ row.label?.de }}</td>
    </ng-container>
    <ng-container matColumnDef="labelEn">
      <th mat-header-cell *matHeaderCellDef i18n>Label (EN)</th>
      <td mat-cell *matCellDef="let row">{{ row.label?.en }}</td>
    </ng-container>
    <ng-container matColumnDef="type">
      <th mat-header-cell *matHeaderCellDef i18n>Typ</th>
      <td mat-cell *matCellDef="let row">{{ row.type }}</td>
    </ng-container>
    <ng-container matColumnDef="required">
      <th mat-header-cell *matHeaderCellDef i18n>Pflicht</th>
      <td mat-cell *matCellDef="let row">{{ row.required ? '✓' : '' }}</td>
    </ng-container>
    <ng-container matColumnDef="actions">
      <th mat-header-cell *matHeaderCellDef></th>
      <td mat-cell *matCellDef="let row">
        <button mat-icon-button color="warn" (click)="deleteField(row.id)">
          <mat-icon>delete</mat-icon>
        </button>
      </td>
    </ng-container>

    <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
    <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
  </table>
</div>
```

Create `frontend/src/app/settings/custom-fields/custom-fields.component.scss`:

```scss
.custom-fields-container {
  padding: 16px;
}

.add-form {
  margin-bottom: 24px;
}

.row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  align-items: center;
}

.row mat-form-field {
  flex: 1;
  min-width: 150px;
}

.full-width {
  width: 100%;
}

table {
  width: 100%;
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/settings/custom-fields/
git commit -m "feat: add custom fields settings component for managing dynamic field definitions"
```

---

## Task 14: Settings — Permissions Component

**Files:**
- Create: `frontend/src/app/settings/permissions/permissions.component.ts`
- Create: `frontend/src/app/settings/permissions/permissions.component.html`
- Create: `frontend/src/app/settings/permissions/permissions.component.scss`

### Steps

- [ ] **Step 1: Create PermissionsComponent**

Create `frontend/src/app/settings/permissions/permissions.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { ParentService } from '../../administration/families/services/parent.service';
import { ApiService } from '../../core/services/api.service';
import { Parent } from '../../shared/models/parent.model';

const AVAILABLE_PERMISSIONS = [
  'families.read',
  'families.write',
  'settings.admin',
  'permissions.manage',
];

@Component({
  selector: 'app-permissions',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatCheckboxModule, MatButtonModule],
  templateUrl: './permissions.component.html',
  styleUrl: './permissions.component.scss',
})
export class PermissionsComponent implements OnInit {
  displayedColumns = ['name', ...AVAILABLE_PERMISSIONS, 'actions'];
  availablePermissions = AVAILABLE_PERMISSIONS;
  dataSource = new MatTableDataSource<Parent>();

  constructor(private api: ApiService, private parentService: ParentService) {}

  ngOnInit(): void {
    this.loadParents();
  }

  loadParents(): void {
    this.api.get<Parent[]>('/parents').subscribe((parents) => {
      this.dataSource.data = parents;
    });
  }

  hasPermission(parent: Parent, perm: string): boolean {
    return parent.permissions?.includes(perm) ?? false;
  }

  togglePermission(parent: Parent, perm: string, checked: boolean): void {
    const perms = new Set(parent.permissions ?? []);
    if (checked) {
      perms.add(perm);
    } else {
      perms.delete(perm);
    }
    parent.permissions = [...perms];
  }

  save(parent: Parent): void {
    this.parentService.update(parent.id!, parent).subscribe();
  }
}
```

Create `frontend/src/app/settings/permissions/permissions.component.html`:

```html
<div class="permissions-container">
  <h2 i18n>Berechtigungen</h2>

  <table mat-table [dataSource]="dataSource" class="mat-elevation-z2">
    <ng-container matColumnDef="name">
      <th mat-header-cell *matHeaderCellDef i18n>Name</th>
      <td mat-cell *matCellDef="let row">{{ row.lastName }} {{ row.firstName }}</td>
    </ng-container>

    @for (perm of availablePermissions; track perm) {
      <ng-container [matColumnDef]="perm">
        <th mat-header-cell *matHeaderCellDef>{{ perm }}</th>
        <td mat-cell *matCellDef="let row">
          <mat-checkbox
            [checked]="hasPermission(row, perm)"
            (change)="togglePermission(row, perm, $event.checked)">
          </mat-checkbox>
        </td>
      </ng-container>
    }

    <ng-container matColumnDef="actions">
      <th mat-header-cell *matHeaderCellDef></th>
      <td mat-cell *matCellDef="let row">
        <button mat-button color="primary" (click)="save(row)" i18n>Speichern</button>
      </td>
    </ng-container>

    <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
    <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
  </table>
</div>
```

Create `frontend/src/app/settings/permissions/permissions.component.scss`:

```scss
.permissions-container {
  padding: 16px;
}

table {
  width: 100%;
}
```

- [ ] **Step 2: Verify full Angular build**

```bash
cd frontend && npx ng build
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/settings/permissions/
git commit -m "feat: add permissions settings component with per-parent permission matrix"
```

---

## Task 15: Keycloak Realm Config + User Provisioning

**Files:**
- Create: `infra/keycloak/kigruapp-realm.json`
- Create: `backend/src/main/java/at/kigruapp/security/KeycloakUserService.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/ParentResource.java`
- Modify: `backend/pom.xml`

### Steps

- [ ] **Step 1: Create Keycloak realm import JSON**

Create `infra/keycloak/kigruapp-realm.json`:

```json
{
  "realm": "kigruapp",
  "enabled": true,
  "registrationAllowed": false,
  "loginWithEmailAllowed": true,
  "resetPasswordAllowed": true,
  "sslRequired": "none",
  "clients": [
    {
      "clientId": "kigruapp-frontend",
      "enabled": true,
      "publicClient": true,
      "redirectUris": ["*"],
      "webOrigins": ["*"],
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": false
    },
    {
      "clientId": "kigruapp-api",
      "enabled": true,
      "publicClient": false,
      "secret": "secret",
      "bearerOnly": true,
      "standardFlowEnabled": false,
      "serviceAccountsEnabled": true
    }
  ]
}
```

- [ ] **Step 2: Add Keycloak admin client dependency to pom.xml**

Add this dependency inside the `<dependencies>` block of `backend/pom.xml`:

```xml
    <dependency>
      <groupId>org.keycloak</groupId>
      <artifactId>keycloak-admin-client</artifactId>
      <version>25.0.6</version>
    </dependency>
```

- [ ] **Step 3: Create KeycloakUserService**

Create `backend/src/main/java/at/kigruapp/security/KeycloakUserService.java`:

```java
package at.kigruapp.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

@ApplicationScoped
public class KeycloakUserService {

    @ConfigProperty(name = "kigruapp.keycloak.server-url", defaultValue = "http://keycloak:8443")
    String serverUrl;

    @ConfigProperty(name = "kigruapp.keycloak.realm", defaultValue = "kigruapp")
    String realm;

    @ConfigProperty(name = "kigruapp.keycloak.admin-username", defaultValue = "admin")
    String adminUsername;

    @ConfigProperty(name = "kigruapp.keycloak.admin-password", defaultValue = "admin")
    String adminPassword;

    public String createUser(String email, String firstName, String lastName) {
        try (Keycloak keycloak = KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm("master")
                .clientId("admin-cli")
                .username(adminUsername)
                .password(adminPassword)
                .build()) {

            UserRepresentation user = new UserRepresentation();
            user.setEnabled(true);
            user.setUsername(email);
            user.setEmail(email);
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setEmailVerified(false);
            user.setRequiredActions(List.of("UPDATE_PASSWORD"));

            var response = keycloak.realm(realm).users().create(user);
            String userId = response.getLocation().getPath()
                .replaceAll(".*/([^/]+)$", "$1");

            // Set temporary password
            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setTemporary(true);
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue("changeme");
            keycloak.realm(realm).users().get(userId).resetPassword(cred);

            return userId;
        }
    }
}
```

- [ ] **Step 4: Wire KeycloakUserService into ParentResource**

Modify `backend/src/main/java/at/kigruapp/resource/ParentResource.java` — add Keycloak user creation on parent create. Add the injection and modify the `create` method:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.Parent;
import at.kigruapp.security.KeycloakUserService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.util.List;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ParentResource {

    @Inject
    KeycloakUserService keycloakUserService;

    @GET
    @Path("/parents")
    public List<Parent> list() {
        return Parent.listAll();
    }

    @GET
    @Path("/parents/{id}")
    public Parent get(@PathParam("id") String id) {
        Parent parent = Parent.findById(new ObjectId(id));
        if (parent == null) {
            throw new NotFoundException();
        }
        return parent;
    }

    @GET
    @Path("/families/{familyId}/parents")
    public List<Parent> listByFamily(@PathParam("familyId") String familyId) {
        return Parent.findByFamilyId(new ObjectId(familyId));
    }

    @POST
    @Path("/parents")
    public Response create(Parent parent) {
        if (parent.email != null && !parent.email.isBlank()) {
            try {
                String keycloakId = keycloakUserService.createUser(
                    parent.email, parent.firstName, parent.lastName
                );
                parent.keycloakUserId = keycloakId;
            } catch (Exception e) {
                // Log but don't fail — Keycloak might not be available in dev/test
                System.err.println("Keycloak user creation failed: " + e.getMessage());
            }
        }
        parent.persist();
        return Response.status(201).entity(parent).build();
    }

    @PUT
    @Path("/parents/{id}")
    public Parent update(@PathParam("id") String id, Parent update) {
        Parent parent = Parent.findById(new ObjectId(id));
        if (parent == null) {
            throw new NotFoundException();
        }
        parent.firstName = update.firstName;
        parent.lastName = update.lastName;
        parent.email = update.email;
        parent.phone = update.phone;
        parent.address = update.address;
        parent.permissions = update.permissions;
        parent.customFields = update.customFields;
        parent.update();
        return parent;
    }

    @DELETE
    @Path("/parents/{id}")
    public Response delete(@PathParam("id") String id) {
        Parent parent = Parent.findById(new ObjectId(id));
        if (parent == null) {
            throw new NotFoundException();
        }
        parent.delete();
        return Response.noContent().build();
    }
}
```

- [ ] **Step 5: Verify backend compiles**

```bash
cd backend && mvn compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add infra/keycloak/ \
       backend/pom.xml \
       backend/src/main/java/at/kigruapp/security/ \
       backend/src/main/java/at/kigruapp/resource/ParentResource.java
git commit -m "feat: add Keycloak realm config and user provisioning on parent creation"
```

---

## Task 16: Docker Compose + Nginx Reverse Proxy

**Files:**
- Create: `docker-compose.yml`
- Create: `infra/nginx/nginx.conf`
- Create: `infra/nginx/Dockerfile`
- Create: `frontend/Dockerfile`

### Steps

- [ ] **Step 1: Create Nginx config**

Create `infra/nginx/nginx.conf`:

```nginx
server {
    listen 80;
    server_name _;

    # Angular SPA
    location / {
        proxy_pass http://frontend:80;
        proxy_set_header Host $host;
    }

    # Quarkus API
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Keycloak
    location /auth/ {
        proxy_pass http://keycloak:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffer_size 128k;
        proxy_buffers 4 256k;
    }
}
```

- [ ] **Step 2: Create Nginx Dockerfile**

Create `infra/nginx/Dockerfile`:

```dockerfile
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
```

- [ ] **Step 3: Create Angular Dockerfile**

Create `frontend/Dockerfile`:

```dockerfile
# Build stage
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npx ng build --configuration=production

# Serve stage
FROM nginx:alpine
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
# SPA fallback
RUN echo 'server { listen 80; location / { root /usr/share/nginx/html; try_files $uri $uri/ /index.html; } }' > /etc/nginx/conf.d/default.conf
```

- [ ] **Step 4: Create docker-compose.yml**

Create `docker-compose.yml`:

```yaml
version: "3.8"

services:
  nginx:
    build: ./infra/nginx
    ports:
      - "80:80"
    depends_on:
      - frontend
      - backend
      - keycloak
    networks:
      - kigruapp

  frontend:
    build: ./frontend
    expose:
      - "80"
    networks:
      - kigruapp

  backend:
    image: eclipse-temurin:21-jre-alpine
    working_dir: /app
    volumes:
      - ./backend/target/quarkus-app:/app
    command: java -jar quarkus-run.jar
    expose:
      - "8080"
    environment:
      QUARKUS_MONGODB_CONNECTION_STRING: mongodb://mongodb:27017
      QUARKUS_MONGODB_DATABASE: kigruapp
      QUARKUS_OIDC_AUTH_SERVER_URL: http://keycloak:8080/realms/kigruapp
      QUARKUS_OIDC_CLIENT_ID: kigruapp-api
      OIDC_CLIENT_SECRET: secret
    depends_on:
      - mongodb
      - keycloak
    networks:
      - kigruapp

  mongodb:
    image: mongo:7
    expose:
      - "27017"
    volumes:
      - mongodb_data:/data/db
    networks:
      - kigruapp

  keycloak:
    image: quay.io/keycloak/keycloak:25.0
    command: start-dev --import-realm
    expose:
      - "8080"
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
      KC_PROXY: edge
      KC_HTTP_RELATIVE_PATH: /auth
    volumes:
      - ./infra/keycloak/kigruapp-realm.json:/opt/keycloak/data/import/kigruapp-realm.json
    networks:
      - kigruapp

networks:
  kigruapp:

volumes:
  mongodb_data:
```

- [ ] **Step 5: Verify compose config**

```bash
docker compose config
```

Expected: Outputs valid, merged YAML without errors.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml infra/nginx/ frontend/Dockerfile
git commit -m "feat: add docker-compose setup with Nginx reverse proxy, MongoDB, Keycloak"
```

---

## Task 17: i18n Setup

**Files:**
- Create: `frontend/src/locale/messages.de.json`
- Create: `frontend/src/locale/messages.en.json`
- Modify: `frontend/angular.json`

### Steps

- [ ] **Step 1: Add localize package**

```bash
cd frontend && npx ng add @angular/localize --skip-confirmation
```

- [ ] **Step 2: Create German translation file**

Create `frontend/src/locale/messages.de.json`:

```json
{
  "locale": "de",
  "translations": {
    "Familien": "Familien",
    "Filter": "Filter",
    "Kind erstellen": "Kind erstellen",
    "Typ": "Typ",
    "Name": "Name",
    "Email": "Email",
    "Telefon": "Telefon",
    "Strasse": "Strasse",
    "PLZ": "PLZ",
    "Ort": "Ort",
    "Geburtsdatum": "Geburtsdatum",
    "Familie": "Familie",
    "Austrittsdatum": "Austrittsdatum",
    "Neues Kind": "Neues Kind",
    "Neue Familie erstellen": "Neue Familie erstellen",
    "Bestehende Familie verwenden": "Bestehende Familie verwenden",
    "Familie auswählen": "Familie auswählen",
    "Vorname": "Vorname",
    "Nachname": "Nachname",
    "Geschlecht": "Geschlecht",
    "Eintrittsdatum": "Eintrittsdatum",
    "Notizen": "Notizen",
    "Abbrechen": "Abbrechen",
    "Zurück": "Zurück",
    "Weiter": "Weiter",
    "Familie erstellen": "Familie erstellen",
    "Elternteil hinzufügen": "Elternteil hinzufügen",
    "Adresse wiederverwenden": "Adresse wiederverwenden",
    "Benutzerdefinierte Felder": "Benutzerdefinierte Felder",
    "Berechtigungen": "Berechtigungen",
    "Speichern": "Speichern",
    "Pflichtfeld": "Pflichtfeld",
    "Feld hinzufügen": "Feld hinzufügen"
  }
}
```

- [ ] **Step 3: Create English translation file**

Create `frontend/src/locale/messages.en.json`:

```json
{
  "locale": "en",
  "translations": {
    "Familien": "Families",
    "Filter": "Filter",
    "Kind erstellen": "Create Child",
    "Typ": "Type",
    "Name": "Name",
    "Email": "Email",
    "Telefon": "Phone",
    "Strasse": "Street",
    "PLZ": "Zip",
    "Ort": "City",
    "Geburtsdatum": "Date of Birth",
    "Familie": "Family",
    "Austrittsdatum": "Exit Date",
    "Neues Kind": "New Child",
    "Neue Familie erstellen": "Create New Family",
    "Bestehende Familie verwenden": "Use Existing Family",
    "Familie auswählen": "Select Family",
    "Vorname": "First Name",
    "Nachname": "Last Name",
    "Geschlecht": "Gender",
    "Eintrittsdatum": "Entry Date",
    "Notizen": "Notes",
    "Abbrechen": "Cancel",
    "Zurück": "Back",
    "Weiter": "Next",
    "Familie erstellen": "Create Family",
    "Elternteil hinzufügen": "Add Parent",
    "Adresse wiederverwenden": "Reuse Address",
    "Benutzerdefinierte Felder": "Custom Fields",
    "Berechtigungen": "Permissions",
    "Speichern": "Save",
    "Pflichtfeld": "Required",
    "Feld hinzufügen": "Add Field"
  }
}
```

- [ ] **Step 4: Configure i18n in angular.json**

Add i18n config to the `projects.frontend` section of `frontend/angular.json`. Under `"projects" > "frontend"`, add at the same level as `"architect"`:

```json
"i18n": {
  "sourceLocale": "de",
  "locales": {
    "en": "src/locale/messages.en.json"
  }
}
```

- [ ] **Step 5: Verify build**

```bash
cd frontend && npx ng build
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/locale/ frontend/angular.json frontend/package.json frontend/package-lock.json
git commit -m "feat: add i18n setup with German (default) and English translations"
```

---

## Task 18: App Shell — Navigation + Layout

**Files:**
- Modify: `frontend/src/app/app.component.ts`
- Create: `frontend/src/app/app.component.html` (replace)
- Create: `frontend/src/app/app.component.scss` (replace)

### Steps

- [ ] **Step 1: Update AppComponent with sidenav layout**

Replace `frontend/src/app/app.component.ts`:

```typescript
import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from './core/services/auth.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, RouterModule,
    MatSidenavModule, MatToolbarModule, MatListModule,
    MatIconModule, MatButtonModule,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent {
  constructor(public auth: AuthService) {}
}
```

Replace `frontend/src/app/app.component.html`:

```html
<mat-sidenav-container class="app-container">
  <mat-sidenav mode="side" opened class="sidenav">
    <div class="sidenav-header">
      <h3 i18n>KigruApp</h3>
    </div>
    <mat-nav-list>
      <a mat-list-item routerLink="/administration/families" routerLinkActive="active">
        <mat-icon matListItemIcon>family_restroom</mat-icon>
        <span matListItemTitle i18n>Familien</span>
      </a>
      <mat-divider></mat-divider>
      <a mat-list-item routerLink="/settings/custom-fields" routerLinkActive="active">
        <mat-icon matListItemIcon>tune</mat-icon>
        <span matListItemTitle i18n>Benutzerdefinierte Felder</span>
      </a>
      <a mat-list-item routerLink="/settings/permissions" routerLinkActive="active">
        <mat-icon matListItemIcon>admin_panel_settings</mat-icon>
        <span matListItemTitle i18n>Berechtigungen</span>
      </a>
    </mat-nav-list>
  </mat-sidenav>

  <mat-sidenav-content>
    <mat-toolbar color="primary">
      <span i18n>KigruApp</span>
      <span class="spacer"></span>
      @if (auth.isAuthenticated) {
        <span>{{ auth.userName }}</span>
        <button mat-icon-button (click)="auth.logout()">
          <mat-icon>logout</mat-icon>
        </button>
      }
    </mat-toolbar>
    <main>
      <router-outlet></router-outlet>
    </main>
  </mat-sidenav-content>
</mat-sidenav-container>
```

Replace `frontend/src/app/app.component.scss`:

```scss
.app-container {
  height: 100vh;
}

.sidenav {
  width: 250px;
}

.sidenav-header {
  padding: 16px;
  text-align: center;
  border-bottom: 1px solid #e0e0e0;
}

.spacer {
  flex: 1;
}

main {
  padding: 0;
}

.active {
  background-color: rgba(0, 0, 0, 0.04);
}

@media (max-width: 768px) {
  .sidenav {
    width: 200px;
  }
}
```

- [ ] **Step 2: Verify final build**

```bash
cd frontend && npx ng build
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/app.component.*
git commit -m "feat: add app shell with sidenav navigation and responsive layout"
```

---

## Task 19: End-to-End Smoke Test

**Files:** None created — verification only.

### Steps

- [ ] **Step 1: Build backend**

```bash
cd backend && mvn package -DskipTests
```

Expected: BUILD SUCCESS, jar in `target/quarkus-app/`.

- [ ] **Step 2: Build frontend**

```bash
cd frontend && npx ng build --configuration=production
```

Expected: BUILD SUCCESS, output in `dist/frontend/browser/`.

- [ ] **Step 3: Start all containers**

```bash
docker compose up --build -d
```

Expected: All 5 containers start (nginx, frontend, backend, mongodb, keycloak).

- [ ] **Step 4: Verify health**

```bash
# Wait for services
sleep 15

# Check backend health
curl -s http://localhost/api/v1/families | head

# Check frontend serves
curl -s http://localhost/ | head

# Check Keycloak
curl -s http://localhost/auth/realms/kigruapp/.well-known/openid-configuration | head
```

Expected: Backend returns `[]`, frontend returns HTML, Keycloak returns OIDC config JSON.

- [ ] **Step 5: Stop containers**

```bash
docker compose down
```

- [ ] **Step 6: Commit (if any fixes were needed)**

```bash
git add -A
git commit -m "fix: adjust configs for docker-compose integration"
```
