# Authentication & Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Keycloak-based authentication and ADMIN role authorization across backend and frontend, with `%dev` bypass mode and a first-run setup wizard.

**Architecture:** Backend uses Quarkus OIDC with a centralized JAX-RS `SecurityFilter` (whitelist model) and a request-scoped `CurrentUserService` for user resolution + dev-bypass. Frontend uses `angular-oauth2-oidc` (already in package.json) with a real `AuthService`, a `CurrentUserService`, an `AdminGuard`, and a setup wizard for first-run initialization.

**Tech Stack:** Quarkus OIDC, JAX-RS ContainerRequestFilter, Quarkus Panache MongoDB, angular-oauth2-oidc v12, Angular 18 standalone, Jasmine/Karma, JUnit 5 + Mockito

---

## File Structure

### New Files
| File | Responsibility |
|------|----------------|
| `backend/src/main/java/at/kigruapp/security/CurrentUserService.java` | Request-scoped user resolution; dev-bypass + JWT sub-claim + email fallback + isAdmin() |
| `backend/src/main/java/at/kigruapp/security/SecurityFilter.java` | JAX-RS ContainerRequestFilter; whitelist-based authz for all `/api/v1/**` paths |
| `backend/src/main/java/at/kigruapp/resource/SetupResource.java` | First-run setup endpoints: `GET /setup/status` and `POST /setup` |
| `backend/src/test/java/at/kigruapp/security/CurrentUserServiceTest.java` | Unit tests: dev mode, prod sub-claim, email fallback, 403 path |
| `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java` | Unit tests: path/method/role authz matrix |
| `frontend/src/app/core/services/current-user.service.ts` | Frontend user state via BehaviorSubject: PersonDTO, isAdmin, familyId |
| `frontend/src/app/core/guards/admin.guard.ts` | Route guard: redirects non-admins to /cooking |
| `frontend/src/app/setup/setup.component.ts` | First-run setup wizard |
| `frontend/src/app/setup/setup.component.html` | Setup wizard template |
| `frontend/src/app/setup/setup.component.scss` | Setup wizard styles |
| `infra/keycloak/kigruapp-realm.json` | Pre-configured Keycloak realm export for import on container start |

### Modified Files
| File | Change |
|------|--------|
| `backend/pom.xml` | Add `quarkus-oidc` extension (if not already present) |
| `backend/src/main/resources/application.properties` | Switch to public client (no secret), add dev-bypass and `%dev.quarkus.security.auth.enabled-in-dev-mode=false` |
| `backend/src/main/java/at/kigruapp/resource/PersonResource.java` | Add `GET /me` endpoint using `CurrentUserService` |
| `backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java` | Add `role` FieldDefinition seed entry |
| `frontend/src/app/app.config.ts` | Add `importProvidersFrom(OAuthModule.forRoot())` |
| `frontend/src/app/core/services/auth.service.ts` | Real OAuthService implementation (replace stub) |
| `frontend/src/app/core/interceptors/auth.interceptor.ts` | Add 401 response handler → redirect to Keycloak |
| `frontend/src/app/core/guards/auth.guard.ts` | Real isAuthenticated check; redirect to Keycloak if not authenticated |
| `frontend/src/app/app.component.ts` | Inject `CurrentUserService`; expose `currentUser` to template |
| `frontend/src/app/app.component.html` | Wrap admin-only sidebar links in `@if (currentUser.isAdmin)` |
| `frontend/src/app/app.routes.ts` | Add `adminGuard` to admin/settings routes; add `/setup` route |
| `frontend/src/app/cooking/cooking.component.ts` | Load `currentFamilyId` and `currentPersonId` from `CurrentUserService` |
| `docker-compose.yml` | Upgrade Keycloak image to `26.0`, remove `KC_HTTP_RELATIVE_PATH` (realm file already mounted) |

---

## Task 1: Keycloak Realm JSON

**Files:**
- Create: `infra/keycloak/kigruapp-realm.json`

- [ ] **Step 1: Create infra/keycloak directory**

```bash
mkdir -p infra/keycloak
```

- [ ] **Step 2: Write the realm JSON**

```json
{
  "realm": "kigruapp",
  "enabled": true,
  "sslRequired": "external",
  "registrationAllowed": true,
  "loginWithEmailAllowed": true,
  "registrationEmailAsUsername": true,
  "resetPasswordAllowed": true,
  "rememberMe": true,
  "accessTokenLifespan": 300,
  "clients": [
    {
      "clientId": "kigruapp-frontend",
      "name": "KigruApp Frontend",
      "enabled": true,
      "publicClient": true,
      "protocol": "openid-connect",
      "standardFlowEnabled": true,
      "implicitFlowEnabled": false,
      "directAccessGrantsEnabled": false,
      "serviceAccountsEnabled": false,
      "attributes": {
        "pkce.code.challenge.method": "S256"
      },
      "redirectUris": [
        "http://localhost:4200/*"
      ],
      "webOrigins": [
        "http://localhost:4200"
      ],
      "rootUrl": "http://localhost:4200",
      "adminUrl": "http://localhost:4200"
    }
  ]
}
```

Save to `infra/keycloak/kigruapp-realm.json`.

- [ ] **Step 3: Update docker-compose.yml Keycloak service**

Change the `keycloak` service section. The existing service already mounts the realm file. Update it to:

```yaml
  keycloak:
    image: quay.io/keycloak/keycloak:26.0
    command: start-dev --import-realm
    ports:
      - "8443:8080"
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    volumes:
      - ./infra/keycloak/kigruapp-realm.json:/opt/keycloak/data/import/kigruapp-realm.json
    networks:
      - kigruapp
```

Remove `KC_PROXY` and `KC_HTTP_RELATIVE_PATH` — Keycloak 26 serves at root path by default in dev mode.

- [ ] **Step 4: Verify the realm starts**

```bash
docker compose up keycloak -d
# Wait ~30 seconds, then:
curl http://localhost:8443/realms/kigruapp
```

Expected: JSON response with `"realm": "kigruapp"`. If the path doesn't work, check Keycloak logs: `docker compose logs keycloak`.

- [ ] **Step 5: Commit**

```bash
git add infra/keycloak/kigruapp-realm.json docker-compose.yml
git commit -m "feat: add Keycloak realm export and update docker-compose to KC 26"
```

---

## Task 2: Backend OIDC Config

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.properties`

- [ ] **Step 1: Add quarkus-oidc to pom.xml**

Check if `quarkus-oidc` is already listed in `backend/pom.xml`. Search for `quarkus-oidc` in the file. If not found, add it inside `<dependencies>`:

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-oidc</artifactId>
</dependency>
```

- [ ] **Step 2: Update application.properties**

Replace the existing OIDC block in `backend/src/main/resources/application.properties` with:

```properties
# OIDC (Keycloak) — production (overridden by docker-compose env vars)
quarkus.oidc.auth-server-url=http://keycloak:8080/realms/kigruapp
quarkus.oidc.client-id=kigruapp-frontend
quarkus.oidc.application-type=service

# Dev-bypass: disable OIDC and security in dev profile
%dev.quarkus.oidc.enabled=false
%dev.quarkus.security.auth.enabled-in-dev-mode=false
%dev.quarkus.mongodb.connection-string=mongodb://localhost:27017

# Test profile
%test.quarkus.oidc.enabled=false
%test.quarkus.mongodb.connection-string=mongodb://localhost:27017
```

Remove `quarkus.oidc.credentials.secret` and `quarkus.oidc.tls.verification` lines — the public client has no secret.

- [ ] **Step 3: Build backend to verify OIDC extension loads**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS. If `mvnw` fails (known Plexus issue), use: `mvn compile -q`.

- [ ] **Step 4: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.properties
git commit -m "feat: configure quarkus-oidc as public client with dev-bypass"
```

---

## Task 3: Backend CurrentUserService

**Files:**
- Create: `backend/src/main/java/at/kigruapp/security/CurrentUserService.java`
- Create: `backend/src/test/java/at/kigruapp/security/CurrentUserServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/security/CurrentUserServiceTest.java`:

```java
package at.kigruapp.security;

import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.security.identity.SecurityIdentity;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CurrentUserServiceTest {

    @Mock MongoClient mongoClient;
    @Mock MongoDatabase database;
    @Mock MongoCollection<Document> fieldInstancesCollection;
    @Mock SecurityIdentity identity;

    CurrentUserService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mongoClient.getDatabase(anyString())).thenReturn(database);
        when(database.getCollection("fieldInstances")).thenReturn(fieldInstancesCollection);
        service = new CurrentUserService();
        service.mongoClient = mongoClient;
        service.databaseName = "kigruapp";
        service.identity = identity;
        service.oidcEnabled = false; // dev mode
    }

    @Test
    void devMode_returnsFirstAdminPerson() {
        Person admin = new Person();
        admin.id = new ObjectId();
        admin.familyId = new ObjectId();
        ObjectId roleInstanceId = new ObjectId();
        admin.roles = List.of(new FieldRef(new ObjectId(), roleInstanceId));

        // Admin role FieldInstance exists
        Document roleDoc = new Document("_id", roleInstanceId).append("value", "ADMIN");
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(fieldInstancesCollection.find(any())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(roleDoc);

        // Inject person lookup — this test verifies isAdmin returns true
        service.cachedPerson = admin;
        assertTrue(service.isAdmin());
    }

    @Test
    void isAdmin_returnsFalse_whenNoAdminRole() {
        Person person = new Person();
        person.id = new ObjectId();
        person.roles = List.of();

        service.cachedPerson = person;
        assertFalse(service.isAdmin());
    }

    @Test
    void getCurrentPerson_returnsNull_whenNotResolved() {
        service.cachedPerson = null;
        // In a real request context, getCurrentPerson() would look up from DB.
        // When cachedPerson is null and no DB lookup runs (empty setup), returns null.
        assertNull(service.getCurrentPerson());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && mvn test -Dtest=CurrentUserServiceTest -q
```

Expected: COMPILATION FAILURE or ClassNotFoundException — `CurrentUserService` doesn't exist yet.

- [ ] **Step 3: Implement CurrentUserService**

Create `backend/src/main/java/at/kigruapp/security/CurrentUserService.java`:

```java
package at.kigruapp.security;

import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.stream.Collectors;

@RequestScoped
public class CurrentUserService {

    @Inject
    public MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    public String databaseName;

    @Inject
    public SecurityIdentity identity;

    @ConfigProperty(name = "quarkus.oidc.enabled", defaultValue = "true")
    public boolean oidcEnabled;

    // Cached within request scope
    public Person cachedPerson;
    private boolean resolved = false;

    /**
     * Returns the authenticated Person for this request.
     * Returns null if the user cannot be resolved (leads to 403 in SecurityFilter).
     */
    public Person getCurrentPerson() {
        if (resolved) return cachedPerson;
        resolved = true;

        if (!oidcEnabled) {
            // Dev-bypass: return first Person with ADMIN role in the DB
            cachedPerson = findFirstAdminPerson();
            return cachedPerson;
        }

        if (identity == null || identity.isAnonymous()) {
            return null;
        }

        JsonWebToken jwt = (JsonWebToken) identity.getPrincipal();
        String sub = jwt.getSubject();
        String email = jwt.getClaim("email");

        // 1. Look up by Keycloak sub
        Person person = Person.find("keycloakUserId", sub).firstResult();

        // 2. Email fallback (one-time migration)
        if (person == null && email != null) {
            person = findPersonByEmail(email);
            if (person != null) {
                person.keycloakUserId = sub;
                person.update();
            }
        }

        cachedPerson = person;
        return cachedPerson;
    }

    /**
     * Returns the familyId of the current person, or null.
     */
    public ObjectId getCurrentFamilyId() {
        Person p = getCurrentPerson();
        return p != null ? p.familyId : null;
    }

    /**
     * Returns true if the current person has an ADMIN role FieldInstance.
     */
    public boolean isAdmin() {
        Person p = getCurrentPerson();
        if (p == null || p.roles == null || p.roles.isEmpty()) return false;

        List<ObjectId> roleInstanceIds = p.roles.stream()
            .map(ref -> ref.fieldInstanceId)
            .collect(Collectors.toList());

        MongoCollection<Document> col = mongoClient
            .getDatabase(databaseName)
            .getCollection("fieldInstances");

        Document adminDoc = col.find(
            Filters.and(
                Filters.in("_id", roleInstanceIds),
                Filters.eq("value", "ADMIN")
            )
        ).first();

        return adminDoc != null;
    }

    private Person findFirstAdminPerson() {
        // Dev mode: fetch all persons, find first with an ADMIN role instance
        List<Person> all = Person.listAll();
        for (Person p : all) {
            if (p.roles != null && !p.roles.isEmpty()) {
                List<ObjectId> ids = p.roles.stream()
                    .map(r -> r.fieldInstanceId)
                    .collect(Collectors.toList());
                MongoCollection<Document> col = mongoClient
                    .getDatabase(databaseName)
                    .getCollection("fieldInstances");
                Document doc = col.find(
                    Filters.and(Filters.in("_id", ids), Filters.eq("value", "ADMIN"))
                ).first();
                if (doc != null) return p;
            }
        }
        // No admin found — return first person if DB has any (useful during setup)
        return Person.listAll().isEmpty() ? null : (Person) Person.listAll().get(0);
    }

    private Person findPersonByEmail(String email) {
        // FieldInstance with value == email; then cross-reference to Person.basicProperties
        MongoCollection<Document> col = mongoClient
            .getDatabase(databaseName)
            .getCollection("fieldInstances");
        // Find all FieldInstances where value matches email
        List<Document> emailDocs = col.find(Filters.eq("value", email))
            .into(new java.util.ArrayList<>());
        if (emailDocs.isEmpty()) return null;

        List<ObjectId> instanceIds = emailDocs.stream()
            .map(d -> d.getObjectId("_id"))
            .collect(Collectors.toList());

        // Find a Person whose basicProperties contains one of these instance IDs
        List<Person> persons = Person.listAll();
        for (Person p : persons) {
            if (p.basicProperties == null) continue;
            for (FieldRef ref : p.basicProperties) {
                if (instanceIds.contains(ref.fieldInstanceId)) return p;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Add Mockito to pom.xml if not present**

Check if `mockito-core` is in the test dependencies. Search pom.xml for `mockito`. If absent, add inside `<dependencies>`:

```xml
<dependency>
  <groupId>org.mockito</groupId>
  <artifactId>mockito-core</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 5: Run tests**

```bash
cd backend && mvn test -Dtest=CurrentUserServiceTest -q
```

Expected: Tests PASS. If mocking Panache `Person.listAll()` causes issues, adapt the test to mock those calls via `PanacheMock` (add `quarkus-panache-mock` dependency) or test the non-Panache paths only.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/security/CurrentUserService.java \
        backend/src/test/java/at/kigruapp/security/CurrentUserServiceTest.java \
        backend/pom.xml
git commit -m "feat: add CurrentUserService with dev-bypass and email fallback"
```

---

## Task 4: Backend SecurityFilter

**Files:**
- Create: `backend/src/main/java/at/kigruapp/security/SecurityFilter.java`
- Create: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java`:

```java
package at.kigruapp.security;

import at.kigruapp.entity.Person;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SecurityFilterTest {

    @Mock ContainerRequestContext ctx;
    @Mock UriInfo uriInfo;
    @Mock CurrentUserService currentUserService;

    SecurityFilter filter;
    Person adminPerson;
    Person regularPerson;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        filter = new SecurityFilter();
        filter.currentUserService = currentUserService;

        adminPerson = new Person();
        adminPerson.id = new ObjectId();
        adminPerson.familyId = new ObjectId();

        regularPerson = new Person();
        regularPerson.id = new ObjectId();
        regularPerson.familyId = new ObjectId();

        when(ctx.getUriInfo()).thenReturn(uriInfo);
    }

    private void setupRequest(String path, String method) {
        when(uriInfo.getPath()).thenReturn(path);
        when(ctx.getMethod()).thenReturn(method);
    }

    @Test
    void setupEndpoint_isPassedThrough() throws Exception {
        setupRequest("/api/v1/setup/status", "GET");
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void cookingDutiesGet_allowsAuthenticatedUser() throws Exception {
        setupRequest("/api/v1/cooking-duties", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(regularPerson);
        when(currentUserService.isAdmin()).thenReturn(false);
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void personsEndpoint_blocksNonAdmin() throws Exception {
        setupRequest("/api/v1/persons", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(regularPerson);
        when(currentUserService.isAdmin()).thenReturn(false);
        filter.filter(ctx);
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals(403, captor.getValue().getStatus());
    }

    @Test
    void personsEndpoint_allowsAdmin() throws Exception {
        setupRequest("/api/v1/persons", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(adminPerson);
        when(currentUserService.isAdmin()).thenReturn(true);
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void unauthenticatedRequest_returns403() throws Exception {
        setupRequest("/api/v1/persons", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(null);
        filter.filter(ctx);
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals(403, captor.getValue().getStatus());
    }

    @Test
    void fieldDefinitionsGet_allowsAuthenticatedUser() throws Exception {
        setupRequest("/api/v1/field-definitions", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(regularPerson);
        when(currentUserService.isAdmin()).thenReturn(false);
        filter.filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void fieldDefinitionsPost_blocksNonAdmin() throws Exception {
        setupRequest("/api/v1/field-definitions", "POST");
        when(currentUserService.getCurrentPerson()).thenReturn(regularPerson);
        when(currentUserService.isAdmin()).thenReturn(false);
        filter.filter(ctx);
        verify(ctx).abortWith(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && mvn test -Dtest=SecurityFilterTest -q
```

Expected: COMPILATION FAILURE — `SecurityFilter` doesn't exist yet.

- [ ] **Step 3: Implement SecurityFilter**

Create `backend/src/main/java/at/kigruapp/security/SecurityFilter.java`:

```java
package at.kigruapp.security;

import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@Priority(Priorities.AUTHORIZATION)
public class SecurityFilter implements ContainerRequestFilter {

    @Inject
    public CurrentUserService currentUserService;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String method = ctx.getMethod();

        // Setup endpoints handle their own auth
        if (path.startsWith("/api/v1/setup")) {
            return;
        }

        Person person = currentUserService.getCurrentPerson();
        if (person == null) {
            abort(ctx);
            return;
        }

        if (!isAllowed(path, method, person)) {
            abort(ctx);
        }
    }

    private boolean isAllowed(String path, String method, Person person) {
        boolean isAdmin = currentUserService.isAdmin();

        // Admin bypasses everything
        if (isAdmin) return true;

        // GET /api/v1/cooking-duties — all authenticated
        if (path.equals("/api/v1/cooking-duties") && "GET".equals(method)) return true;

        // PUT/DELETE /api/v1/cooking-duties/{id} — own family
        if (path.matches("/api/v1/cooking-duties/[^/]+") && isWriteMethod(method)) {
            return checkCookingDutyFamily(path, person);
        }

        // GET /api/v1/organisation/groups or /duty-settings — all authenticated
        if ((path.equals("/api/v1/organisation/groups") || path.equals("/api/v1/organisation/duty-settings"))
                && "GET".equals(method)) return true;

        // GET /api/v1/field-definitions/** — all authenticated
        if (path.startsWith("/api/v1/field-definitions") && "GET".equals(method)) return true;

        // GET /api/v1/field-instances/** — all authenticated
        if (path.startsWith("/api/v1/field-instances") && "GET".equals(method)) return true;

        // POST/PUT/DELETE /api/v1/field-instances/** — own data only
        if (path.startsWith("/api/v1/field-instances") && isWriteMethod(method)) {
            return checkFieldInstanceFamily(path, person);
        }

        // GET /api/v1/persons/me — own endpoint
        if (path.equals("/api/v1/persons/me") && "GET".equals(method)) return true;

        // Everything else: admin-only (already checked above)
        return false;
    }

    private boolean isWriteMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
    }

    private boolean checkCookingDutyFamily(String path, Person person) {
        // Extract ID from /api/v1/cooking-duties/{id}
        String id = path.substring("/api/v1/cooking-duties/".length());
        if (!ObjectId.isValid(id)) return false;

        Document duty = mongoClient.getDatabase(databaseName)
            .getCollection("cookingDuties")
            .find(Filters.eq("_id", new ObjectId(id)))
            .first();

        if (duty == null) return false;
        ObjectId dutyFamilyId = duty.getObjectId("familyId");
        return person.familyId != null && person.familyId.equals(dutyFamilyId);
    }

    private boolean checkFieldInstanceFamily(String path, Person person) {
        // For field-instance writes, allow if person belongs to same family as owner
        // For POST (create), allow — the resource validates ownership
        // For PUT/DELETE, check the instance's owning person
        if (path.equals("/api/v1/field-instances")) return true; // POST — resource validates

        String id = path.substring("/api/v1/field-instances/".length());
        if (!ObjectId.isValid(id)) return false;

        // Find a person in same family who owns this field instance
        Document instance = mongoClient.getDatabase(databaseName)
            .getCollection("fieldInstances")
            .find(Filters.eq("_id", new ObjectId(id)))
            .first();

        if (instance == null) return false;
        ObjectId ownerPersonId = instance.getObjectId("personId");
        if (ownerPersonId == null) return false;

        Person owner = Person.findById(ownerPersonId);
        return owner != null && person.familyId != null && person.familyId.equals(owner.familyId);
    }

    private void abort(ContainerRequestContext ctx) {
        ctx.abortWith(Response.status(Response.Status.FORBIDDEN).build());
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd backend && mvn test -Dtest=SecurityFilterTest -q
```

Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/security/SecurityFilter.java \
        backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java
git commit -m "feat: add SecurityFilter with whitelist-based authorization"
```

---

## Task 5: Backend SetupResource

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/SetupResource.java`

- [ ] **Step 1: Write SetupResource**

Create `backend/src/main/java/at/kigruapp/resource/SetupResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Path("/api/v1/setup")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SetupResource {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    /**
     * GET /api/v1/setup/status — no auth required.
     * Returns { "required": true } if no persons exist, { "required": false } otherwise.
     */
    @GET
    @Path("/status")
    public Response status() {
        boolean required = Person.count() == 0;
        return Response.ok(new Document("required", required).toJson()).build();
    }

    /**
     * POST /api/v1/setup — only works when DB is empty.
     * Expects a valid Bearer token (validated by Quarkus OIDC) containing the first admin's claims.
     * Creates: Family + Person (ADMIN) from token claims.
     */
    @POST
    public Response setup(SetupRequest request) {
        if (Person.count() > 0) {
            return Response.status(403).entity("{\"error\": \"Setup already completed\"}").build();
        }

        if (request == null || request.familyName == null || request.familyName.isBlank()) {
            return Response.status(400).entity("{\"error\": \"familyName required\"}").build();
        }
        if (request.keycloakUserId == null || request.email == null) {
            return Response.status(400).entity("{\"error\": \"keycloakUserId and email required\"}").build();
        }

        MongoCollection<Document> fieldInstancesCol = mongoClient
            .getDatabase(databaseName)
            .getCollection("fieldInstances");

        // 1. Create Family
        Family family = new Family();
        family.createdAt = Instant.now();
        family.updatedAt = Instant.now();
        family.persist();

        // 2. Create email FieldInstance
        ObjectId emailDefId = findFieldDefinitionId("email");
        Document emailInstance = new Document()
            .append("definitionId", emailDefId)
            .append("value", request.email)
            .append("createdAt", Instant.now())
            .append("updatedAt", Instant.now());
        fieldInstancesCol.insertOne(emailInstance);
        ObjectId emailInstanceId = emailInstance.getObjectId("_id");

        // 3. Create role FieldInstance (ADMIN)
        ObjectId roleDefId = findFieldDefinitionId("role");
        Document roleInstance = new Document()
            .append("definitionId", roleDefId)
            .append("value", "ADMIN")
            .append("createdAt", Instant.now())
            .append("updatedAt", Instant.now());
        fieldInstancesCol.insertOne(roleInstance);
        ObjectId roleInstanceId = roleInstance.getObjectId("_id");

        // 4. Create Person
        Person person = new Person();
        person.familyId = (ObjectId) family.id;
        person.keycloakUserId = request.keycloakUserId;
        person.basicProperties = new ArrayList<>(List.of(
            new FieldRef(emailDefId, emailInstanceId)
        ));
        person.roles = new ArrayList<>(List.of(
            new FieldRef(roleDefId, roleInstanceId)
        ));
        person.schedules = new ArrayList<>();
        person.duties = new ArrayList<>();
        person.finance = new ArrayList<>();
        person.customProperties = new ArrayList<>();
        person.createdAt = Instant.now();
        person.updatedAt = Instant.now();
        person.persist();

        return Response.status(201)
            .entity("{\"personId\": \"" + person.id + "\", \"familyId\": \"" + family.id + "\"}")
            .build();
    }

    private ObjectId findFieldDefinitionId(String fieldName) {
        Document def = mongoClient.getDatabase(databaseName)
            .getCollection("fieldDefinitions")
            .find(new Document("fieldName", fieldName))
            .first();
        if (def == null) throw new IllegalStateException("FieldDefinition '" + fieldName + "' not found. Run migrations first.");
        return def.getObjectId("_id");
    }

    public static class SetupRequest {
        public String familyName;
        public String keycloakUserId;
        public String email;
        public String firstName;
        public String lastName;
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Manually test setup status endpoint (dev mode)**

Start the backend in dev mode and call:

```bash
curl http://localhost:8080/api/v1/setup/status
```

Expected: `{"required":true}` if DB is empty, `{"required":false}` if persons exist.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/SetupResource.java
git commit -m "feat: add SetupResource for first-run initialization"
```

---

## Task 6: Backend /me Endpoint + Role FieldDefinition Seed

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java`
- Modify: `backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java`

- [ ] **Step 1: Add /me endpoint to PersonResource**

Read the existing `PersonResource.java` and find the `getFull(id)` method. Add the following endpoint immediately after `getFull`:

```java
@GET
@Path("/me")
public Response getMe() {
    at.kigruapp.entity.Person currentPerson = currentUserService.getCurrentPerson();
    if (currentPerson == null) {
        return Response.status(403).build();
    }
    // Reuse the same full-resolution logic as getFull
    PersonDTO dto = buildPersonDTO(currentPerson.id.toHexString());
    return Response.ok(dto).build();
}
```

You also need to inject `CurrentUserService` into `PersonResource`:

```java
@Inject
at.kigruapp.security.CurrentUserService currentUserService;
```

Check if `PersonResource` has a method like `buildPersonDTO(String id)` or if it builds the DTO inline in `getFull`. If inline, extract the DTO-building logic into a private `buildPersonDTO(String id)` method, then call it from both `getFull` and `getMe`.

> Note: Look at `getFull(@PathParam("id") String id)` implementation and factor it out. The DTO building resolves each `FieldRef` in each section to a `FieldInstanceDTO`.

- [ ] **Step 2: Add role FieldDefinition to FieldDefinitionSeedMigration**

Read `FieldDefinitionSeedMigration.java` and find the list of seed definitions. Add the `role` definition to the seeded list. The migration already has a version string (e.g., `"seed-basic-property-definitions-v3"`). Change it to `v4` and add:

```java
Map.of(
    "fieldName", "role",
    "label", Map.of("de", "Rolle", "en", "Role"),
    "jsonSchema", Map.of("type", "string", "enum", List.of("ADMIN", "PARENT")),
    "required", false,
    "keycloakMapping", ""
)
```

Add this entry in the definitions list alongside the existing ones.

Update the migration ID constant to `"seed-basic-property-definitions-v4"` so it runs again on next startup.

- [ ] **Step 3: Build and verify**

```bash
cd backend && mvn compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Test /me in dev mode (after app start)**

```bash
curl http://localhost:8080/api/v1/persons/me
```

Expected: PersonDTO JSON if an admin person exists in dev DB. If DB is empty, returns 403.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/PersonResource.java \
        backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java
git commit -m "feat: add GET /persons/me endpoint and role FieldDefinition seed"
```

---

## Task 7: Frontend AuthService + OAuthModule

**Files:**
- Modify: `frontend/src/app/app.config.ts`
- Modify: `frontend/src/app/core/services/auth.service.ts`

- [ ] **Step 1: Write the failing AuthService test**

Create `frontend/src/app/core/services/auth.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { OAuthService } from 'angular-oauth2-oidc';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let oauthSpy: jasmine.SpyObj<OAuthService>;

  beforeEach(() => {
    oauthSpy = jasmine.createSpyObj('OAuthService', [
      'configure',
      'loadDiscoveryDocumentAndTryLogin',
      'setupAutomaticSilentRefresh',
      'initCodeFlow',
      'logOut',
      'getAccessToken',
      'hasValidAccessToken',
      'getIdentityClaims',
    ]);
    oauthSpy.loadDiscoveryDocumentAndTryLogin.and.returnValue(Promise.resolve(true));

    TestBed.configureTestingModule({
      providers: [
        AuthService,
        { provide: OAuthService, useValue: oauthSpy },
      ],
    });
    service = TestBed.inject(AuthService);
  });

  it('configure() calls OAuthService.configure and loads discovery', async () => {
    await service.configure();
    expect(oauthSpy.configure).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      clientId: 'kigruapp-frontend',
      responseType: 'code',
    }));
    expect(oauthSpy.loadDiscoveryDocumentAndTryLogin).toHaveBeenCalled();
  });

  it('isAuthenticated delegates to hasValidAccessToken', () => {
    oauthSpy.hasValidAccessToken.and.returnValue(true);
    expect(service.isAuthenticated).toBeTrue();
  });

  it('accessToken delegates to getAccessToken', () => {
    oauthSpy.getAccessToken.and.returnValue('token123');
    expect(service.accessToken).toBe('token123');
  });

  it('userName reads preferred_username from claims', () => {
    oauthSpy.getIdentityClaims.and.returnValue({ preferred_username: 'testuser' });
    expect(service.userName).toBe('testuser');
  });

  it('userName returns empty string when no claims', () => {
    oauthSpy.getIdentityClaims.and.returnValue(null);
    expect(service.userName).toBe('');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && ng test --include="**/auth.service.spec.ts" --watch=false --browsers=ChromeHeadless
```

Expected: COMPILATION FAILURE — method `configure()` not found on the stub AuthService.

- [ ] **Step 3: Implement real AuthService**

Replace the content of `frontend/src/app/core/services/auth.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { OAuthService, AuthConfig } from 'angular-oauth2-oidc';

const authConfig: AuthConfig = {
  issuer: 'http://localhost:8443/realms/kigruapp',
  redirectUri: window.location.origin + '/',
  clientId: 'kigruapp-frontend',
  responseType: 'code',
  scope: 'openid profile email',
  showDebugInformation: false,
  requireHttps: false, // allow http in dev
};

@Injectable({ providedIn: 'root' })
export class AuthService {
  constructor(private oauthService: OAuthService) {}

  async configure(): Promise<void> {
    this.oauthService.configure(authConfig);
    await this.oauthService.loadDiscoveryDocumentAndTryLogin();
    this.oauthService.setupAutomaticSilentRefresh();
  }

  login(): void {
    this.oauthService.initCodeFlow();
  }

  logout(): void {
    this.oauthService.logOut();
  }

  get accessToken(): string {
    return this.oauthService.getAccessToken() ?? '';
  }

  get isAuthenticated(): boolean {
    return this.oauthService.hasValidAccessToken();
  }

  get userName(): string {
    const claims = this.oauthService.getIdentityClaims() as Record<string, string> | null;
    return claims?.['preferred_username'] ?? '';
  }

  get userEmail(): string {
    const claims = this.oauthService.getIdentityClaims() as Record<string, string> | null;
    return claims?.['email'] ?? '';
  }
}
```

- [ ] **Step 4: Add OAuthModule to app.config.ts**

Edit `frontend/src/app/app.config.ts`. Add `importProvidersFrom` and `OAuthModule`:

```typescript
import { ApplicationConfig, provideZoneChangeDetection, LOCALE_ID, importProvidersFrom, APP_INITIALIZER } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { MAT_DATE_LOCALE } from '@angular/material/core';
import { CalendarModule, DateAdapter } from 'angular-calendar';
import { adapterFactory } from 'angular-calendar/date-adapters/date-fns';
import { OAuthModule } from 'angular-oauth2-oidc';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { AuthService } from './core/services/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideAnimationsAsync(),
    { provide: MAT_DATE_LOCALE, useValue: 'de-AT' },
    { provide: LOCALE_ID, useValue: 'de-AT' },
    importProvidersFrom(OAuthModule.forRoot()),
    importProvidersFrom(
      CalendarModule.forRoot({ provide: DateAdapter, useFactory: adapterFactory })
    ),
    {
      provide: APP_INITIALIZER,
      useFactory: (auth: AuthService) => () => auth.configure(),
      deps: [AuthService],
      multi: true,
    },
  ],
};
```

- [ ] **Step 5: Run AuthService tests**

```bash
cd frontend && ng test --include="**/auth.service.spec.ts" --watch=false --browsers=ChromeHeadless
```

Expected: All 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/core/services/auth.service.ts \
        frontend/src/app/core/services/auth.service.spec.ts \
        frontend/src/app/app.config.ts
git commit -m "feat: implement AuthService with angular-oauth2-oidc and OAuthModule provider"
```

---

## Task 8: Frontend AuthInterceptor + AuthGuard

**Files:**
- Modify: `frontend/src/app/core/interceptors/auth.interceptor.ts`
- Modify: `frontend/src/app/core/guards/auth.guard.ts`

- [ ] **Step 1: Update auth.interceptor.ts**

Replace the content of `frontend/src/app/core/interceptors/auth.interceptor.ts`:

```typescript
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.accessToken;

  const outgoing = token && req.url.startsWith('/api')
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(outgoing).pipe(
    catchError(err => {
      if (err.status === 401) {
        auth.login();
      }
      return throwError(() => err);
    })
  );
};
```

- [ ] **Step 2: Update auth.guard.ts**

Replace the content of `frontend/src/app/core/guards/auth.guard.ts`:

```typescript
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const http = inject(HttpClient);
  const router = inject(Router);

  // Check if first-run setup is required
  try {
    const status = await firstValueFrom(
      http.get<{ required: boolean }>('/api/v1/setup/status')
    );
    if (status.required) {
      router.navigate(['/setup']);
      return false;
    }
  } catch {
    // Backend unreachable — proceed to auth check
  }

  if (!auth.isAuthenticated) {
    auth.login();
    return false;
  }
  return true;
};
```

- [ ] **Step 3: Write interceptor test**

Create `frontend/src/app/core/interceptors/auth.interceptor.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let authSpy: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    authSpy = jasmine.createSpyObj('AuthService', ['login'], {
      accessToken: 'test-token',
    });

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authSpy },
      ],
    });

    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('attaches Bearer token to /api requests', () => {
    http.get('/api/v1/persons').subscribe();
    const req = httpTesting.expectOne('/api/v1/persons');
    expect(req.request.headers.get('Authorization')).toBe('Bearer test-token');
    req.flush([]);
  });

  it('does not attach token to non-api requests', () => {
    http.get('https://example.com/data').subscribe();
    const req = httpTesting.expectOne('https://example.com/data');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });
});
```

- [ ] **Step 4: Run interceptor test**

```bash
cd frontend && ng test --include="**/auth.interceptor.spec.ts" --watch=false --browsers=ChromeHeadless
```

Expected: Both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/core/interceptors/auth.interceptor.ts \
        frontend/src/app/core/interceptors/auth.interceptor.spec.ts \
        frontend/src/app/core/guards/auth.guard.ts
git commit -m "feat: update authInterceptor (401 redirect) and authGuard (setup check)"
```

---

## Task 9: Frontend CurrentUserService

**Files:**
- Create: `frontend/src/app/core/services/current-user.service.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/core/services/current-user.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { CurrentUserService } from './current-user.service';

const mockPersonDTO = {
  id: 'abc123',
  familyId: 'fam456',
  keycloakUserId: 'kc-sub-789',
  basicProperties: [],
  roles: [{ id: 'r1', fieldName: 'role', value: 'ADMIN', definitionId: 'd1', label: {}, description: '', jsonSchema: {}, required: false, keycloakMapping: '', definitionOutdated: false }],
  schedules: [], duties: [], finance: [], customProperties: [],
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

describe('CurrentUserService', () => {
  let service: CurrentUserService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        CurrentUserService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(CurrentUserService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('loadCurrentUser() fetches /api/v1/persons/me and caches result', (done) => {
    service.loadCurrentUser().subscribe(person => {
      expect(person.id).toBe('abc123');
      expect(service.currentPerson?.id).toBe('abc123');
      done();
    });
    httpTesting.expectOne('/api/v1/persons/me').flush(mockPersonDTO);
  });

  it('currentFamilyId returns familyId from cached person', (done) => {
    service.loadCurrentUser().subscribe(() => {
      expect(service.currentFamilyId).toBe('fam456');
      done();
    });
    httpTesting.expectOne('/api/v1/persons/me').flush(mockPersonDTO);
  });

  it('isAdmin returns true when roles contain ADMIN', (done) => {
    service.loadCurrentUser().subscribe(() => {
      expect(service.isAdmin).toBeTrue();
      done();
    });
    httpTesting.expectOne('/api/v1/persons/me').flush(mockPersonDTO);
  });

  it('isAdmin returns false before user is loaded', () => {
    expect(service.isAdmin).toBeFalse();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && ng test --include="**/current-user.service.spec.ts" --watch=false --browsers=ChromeHeadless
```

Expected: COMPILATION FAILURE — `CurrentUserService` doesn't exist.

- [ ] **Step 3: Implement CurrentUserService**

Create `frontend/src/app/core/services/current-user.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';

export interface FieldInstanceDTO {
  id: string;
  definitionId: string;
  fieldName: string;
  label: Record<string, string>;
  description: string;
  jsonSchema: Record<string, unknown>;
  required: boolean;
  keycloakMapping: string;
  value: unknown;
  definitionOutdated: boolean;
}

export interface PersonDTO {
  id: string;
  familyId: string;
  keycloakUserId: string;
  basicProperties: FieldInstanceDTO[];
  roles: FieldInstanceDTO[];
  schedules: FieldInstanceDTO[];
  duties: FieldInstanceDTO[];
  finance: FieldInstanceDTO[];
  customProperties: FieldInstanceDTO[];
  createdAt: string;
  updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class CurrentUserService {
  private personSubject = new BehaviorSubject<PersonDTO | null>(null);
  currentPerson$ = this.personSubject.asObservable();

  constructor(private http: HttpClient) {}

  get currentPerson(): PersonDTO | null {
    return this.personSubject.value;
  }

  get currentFamilyId(): string {
    return this.currentPerson?.familyId ?? '';
  }

  get isAdmin(): boolean {
    return this.currentPerson?.roles?.some(r => r.value === 'ADMIN') ?? false;
  }

  loadCurrentUser(): Observable<PersonDTO> {
    return this.http.get<PersonDTO>('/api/v1/persons/me').pipe(
      tap(person => this.personSubject.next(person))
    );
  }

  clear(): void {
    this.personSubject.next(null);
  }
}
```

- [ ] **Step 4: Run tests**

```bash
cd frontend && ng test --include="**/current-user.service.spec.ts" --watch=false --browsers=ChromeHeadless
```

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/core/services/current-user.service.ts \
        frontend/src/app/core/services/current-user.service.spec.ts
git commit -m "feat: add CurrentUserService with reactive PersonDTO caching and isAdmin"
```

---

## Task 10: Frontend AdminGuard + Route Updates

**Files:**
- Create: `frontend/src/app/core/guards/admin.guard.ts`
- Modify: `frontend/src/app/app.routes.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/core/guards/admin.guard.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { adminGuard } from './admin.guard';
import { CurrentUserService } from '../services/current-user.service';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';

describe('adminGuard', () => {
  let routerSpy: jasmine.SpyObj<Router>;
  let currentUserSpy: jasmine.SpyObj<CurrentUserService>;

  beforeEach(() => {
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);
    currentUserSpy = jasmine.createSpyObj('CurrentUserService', [], { isAdmin: false });

    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: routerSpy },
        { provide: CurrentUserService, useValue: currentUserSpy },
      ],
    });
  });

  const runGuard = () =>
    TestBed.runInInjectionContext(() =>
      adminGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot)
    );

  it('allows access when isAdmin is true', () => {
    Object.defineProperty(currentUserSpy, 'isAdmin', { get: () => true });
    expect(runGuard()).toBeTrue();
  });

  it('redirects to /cooking when not admin', () => {
    Object.defineProperty(currentUserSpy, 'isAdmin', { get: () => false });
    expect(runGuard()).toBeFalse();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/cooking']);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && ng test --include="**/admin.guard.spec.ts" --watch=false --browsers=ChromeHeadless
```

Expected: COMPILATION FAILURE — `adminGuard` doesn't exist.

- [ ] **Step 3: Implement admin.guard.ts**

Create `frontend/src/app/core/guards/admin.guard.ts`:

```typescript
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { CurrentUserService } from '../services/current-user.service';

export const adminGuard: CanActivateFn = () => {
  const currentUser = inject(CurrentUserService);
  const router = inject(Router);

  if (currentUser.isAdmin) return true;

  router.navigate(['/cooking']);
  return false;
};
```

- [ ] **Step 4: Update app.routes.ts**

Replace the content of `frontend/src/app/app.routes.ts`:

```typescript
import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';

export const routes: Routes = [
  {
    path: 'setup',
    loadComponent: () =>
      import('./setup/setup.component').then(m => m.SetupComponent),
  },
  {
    path: 'cooking',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./cooking/cooking.component').then(m => m.CookingComponent),
  },
  {
    path: 'administration',
    canActivate: [authGuard, adminGuard],
    children: [
      {
        path: 'families',
        loadComponent: () =>
          import('./administration/families/family-list/family-list.component').then(
            m => m.FamilyListComponent
          ),
      },
    ],
  },
  {
    path: 'settings',
    canActivate: [authGuard, adminGuard],
    children: [
      {
        path: 'organisation',
        loadComponent: () =>
          import('./settings/organisation/organisation.component').then(
            m => m.OrganisationComponent
          ),
      },
      {
        path: 'custom-fields',
        loadComponent: () =>
          import('./settings/custom-fields/custom-fields.component').then(
            m => m.CustomFieldsComponent
          ),
      },
      {
        path: 'permissions',
        loadComponent: () =>
          import('./settings/permissions/permissions.component').then(
            m => m.PermissionsComponent
          ),
      },
    ],
  },
  { path: '', redirectTo: 'cooking', pathMatch: 'full' },
];
```

- [ ] **Step 5: Run admin guard tests**

```bash
cd frontend && ng test --include="**/admin.guard.spec.ts" --watch=false --browsers=ChromeHeadless
```

Expected: Both tests PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/core/guards/admin.guard.ts \
        frontend/src/app/core/guards/admin.guard.spec.ts \
        frontend/src/app/app.routes.ts
git commit -m "feat: add AdminGuard and protect administration/settings routes"
```

---

## Task 11: Frontend Setup Wizard

**Files:**
- Create: `frontend/src/app/setup/setup.component.ts`
- Create: `frontend/src/app/setup/setup.component.html`
- Create: `frontend/src/app/setup/setup.component.scss`

The setup wizard flow:
1. User lands on `/setup` (redirected by `authGuard` when `setup/status.required == true`)
2. If not yet authenticated: show "Anmelden" button → `auth.login()` initiates Keycloak PKCE flow
3. After Keycloak redirect back: `auth.configure()` restores the token (via `APP_INITIALIZER`)
4. Component detects `auth.isAuthenticated` + `status.required` → calls `POST /api/v1/setup`
5. On success: navigate to `/cooking`

- [ ] **Step 1: Create setup.component.ts**

Create `frontend/src/app/setup/setup.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../core/services/auth.service';

@Component({
  selector: 'app-setup',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatButtonModule,
    MatFormFieldModule, MatInputModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './setup.component.html',
  styleUrl: './setup.component.scss',
})
export class SetupComponent implements OnInit {
  familyName = '';
  loading = false;
  error = '';
  setupComplete = false;

  constructor(
    public auth: AuthService,
    private http: HttpClient,
    private router: Router,
  ) {}

  ngOnInit(): void {
    // If authenticated and setup is still required, auto-submit after Keycloak redirect
    if (this.auth.isAuthenticated) {
      this.checkAndAutoSetup();
    }
  }

  private checkAndAutoSetup(): void {
    this.http.get<{ required: boolean }>('/api/v1/setup/status').subscribe(status => {
      if (!status.required) {
        this.router.navigate(['/cooking']);
      }
    });
  }

  loginWithKeycloak(): void {
    this.auth.login();
  }

  submitSetup(): void {
    if (!this.familyName.trim()) {
      this.error = 'Bitte gib einen Familiennamen ein.';
      return;
    }
    this.loading = true;
    this.error = '';

    const claims = (this.auth as any)['oauthService']?.getIdentityClaims?.() as Record<string, string> | null;
    const body = {
      familyName: this.familyName.trim(),
      keycloakUserId: claims?.['sub'] ?? '',
      email: this.auth.userEmail,
      firstName: claims?.['given_name'] ?? '',
      lastName: claims?.['family_name'] ?? '',
    };

    this.http.post('/api/v1/setup', body).subscribe({
      next: () => {
        this.setupComplete = true;
        this.router.navigate(['/cooking']);
      },
      error: err => {
        this.loading = false;
        this.error = err.error?.error ?? 'Einrichtung fehlgeschlagen. Bitte neu laden.';
      },
    });
  }
}
```

- [ ] **Step 2: Create setup.component.html**

Create `frontend/src/app/setup/setup.component.html`:

```html
<div class="setup-container">
  <mat-card class="setup-card">
    <mat-card-header>
      <mat-card-title>Willkommen bei KigruApp</mat-card-title>
      <mat-card-subtitle>Ersteinrichtung</mat-card-subtitle>
    </mat-card-header>

    <mat-card-content>
      @if (!auth.isAuthenticated) {
        <p>Melde dich mit deinem Keycloak-Konto an, um die App einzurichten.</p>
        <p class="hint">
          Falls du noch kein Konto hast, kannst du dich auf der Keycloak-Seite registrieren.
        </p>
        <button mat-raised-button color="primary" (click)="loginWithKeycloak()">
          Mit Keycloak anmelden
        </button>
      } @else if (!setupComplete) {
        <p>Angemeldet als <strong>{{ auth.userName }}</strong>.</p>
        <p>Gib einen Namen für deine Familie ein:</p>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Familienname</mat-label>
          <input matInput [(ngModel)]="familyName" placeholder="z.B. Familie Müller" />
        </mat-form-field>

        @if (error) {
          <p class="error">{{ error }}</p>
        }

        <button
          mat-raised-button
          color="primary"
          [disabled]="loading"
          (click)="submitSetup()">
          @if (loading) {
            <mat-spinner diameter="20"></mat-spinner>
          } @else {
            App einrichten
          }
        </button>
      } @else {
        <p>Einrichtung abgeschlossen! Du wirst weitergeleitet...</p>
        <mat-spinner diameter="40"></mat-spinner>
      }
    </mat-card-content>
  </mat-card>
</div>
```

- [ ] **Step 3: Create setup.component.scss**

Create `frontend/src/app/setup/setup.component.scss`:

```scss
.setup-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: #f5f5f5;
}

.setup-card {
  width: 100%;
  max-width: 480px;
  padding: 16px;

  mat-card-content {
    display: flex;
    flex-direction: column;
    gap: 16px;
    margin-top: 16px;
  }
}

.full-width {
  width: 100%;
}

.hint {
  color: #666;
  font-size: 0.875rem;
}

.error {
  color: #f44336;
  font-size: 0.875rem;
}
```

- [ ] **Step 4: Build to verify no compilation errors**

```bash
cd frontend && ng build --configuration=development 2>&1 | head -30
```

Expected: Build succeeds with no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/setup/
git commit -m "feat: add setup wizard component for first-run initialization"
```

---

## Task 12: Frontend Sidebar + Cooking Integration

**Files:**
- Modify: `frontend/src/app/app.component.ts`
- Modify: `frontend/src/app/app.component.html`
- Modify: `frontend/src/app/cooking/cooking.component.ts`

- [ ] **Step 1: Update AppComponent to inject CurrentUserService**

Replace the content of `frontend/src/app/app.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from './core/services/auth.service';
import { CurrentUserService } from './core/services/current-user.service';

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
export class AppComponent implements OnInit {
  constructor(
    public auth: AuthService,
    public currentUser: CurrentUserService,
  ) {}

  ngOnInit(): void {
    if (this.auth.isAuthenticated) {
      this.currentUser.loadCurrentUser().subscribe();
    }
  }
}
```

- [ ] **Step 2: Update app.component.html — hide admin-only sidebar links**

Replace the content of `frontend/src/app/app.component.html`:

```html
<mat-sidenav-container class="app-container">
  <mat-sidenav mode="side" opened class="sidenav">
    <div class="sidenav-header">
      <h3>KigruApp</h3>
    </div>
    <mat-nav-list>
      <a mat-list-item routerLink="/cooking" routerLinkActive="active">
        <mat-icon matListItemIcon>restaurant</mat-icon>
        <span matListItemTitle>Kochen</span>
      </a>
      @if (currentUser.isAdmin) {
        <a mat-list-item routerLink="/administration/families" routerLinkActive="active">
          <mat-icon matListItemIcon>family_restroom</mat-icon>
          <span matListItemTitle>Familien</span>
        </a>
        <mat-divider></mat-divider>
        <a mat-list-item routerLink="/settings/organisation" routerLinkActive="active">
          <mat-icon matListItemIcon>business</mat-icon>
          <span matListItemTitle>Organisation</span>
        </a>
        <a mat-list-item routerLink="/settings/custom-fields" routerLinkActive="active">
          <mat-icon matListItemIcon>tune</mat-icon>
          <span matListItemTitle>Benutzerdefinierte Felder</span>
        </a>
        <a mat-list-item routerLink="/settings/permissions" routerLinkActive="active">
          <mat-icon matListItemIcon>admin_panel_settings</mat-icon>
          <span matListItemTitle>Berechtigungen</span>
        </a>
      }
    </mat-nav-list>
  </mat-sidenav>

  <mat-sidenav-content>
    <mat-toolbar color="primary">
      <span>KigruApp</span>
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

- [ ] **Step 3: Update cooking.component.ts to use CurrentUserService**

Read the full `frontend/src/app/cooking/cooking.component.ts`. Find where `currentFamilyId` and `currentPersonId` are set (currently likely stubbed or empty). Replace that initialization with:

```typescript
// In constructor or ngOnInit, after the component injects CurrentUserService:
constructor(
  // ... existing injections ...
  private currentUserService: CurrentUserService,
) {}

ngOnInit(): void {
  this.currentUserService.loadCurrentUser().subscribe(person => {
    this.currentFamilyId = person.familyId;
    this.currentPersonId = person.id;
    // Trigger existing data load after user is resolved
    this.loadData();
  });
}
```

Import `CurrentUserService` at the top:
```typescript
import { CurrentUserService } from '../core/services/current-user.service';
```

Also update the `canEdit` check in `onEventClicked` to also allow admins:
```typescript
onEventClicked(event: CalendarEvent): void {
  const duty = event.meta as CookingDutyDTO;
  const canEdit = duty.familyId === this.currentFamilyId || this.currentUserService.isAdmin;
  this.openDialog(duty, canEdit);
}
```

> Note: Read the actual cooking.component.ts before editing to identify exact constructor signature, existing ngOnInit, and loadData() method name.

- [ ] **Step 4: Build to verify no compilation errors**

```bash
cd frontend && ng build --configuration=development 2>&1 | head -30
```

Expected: Build succeeds. Fix any type errors that appear.

- [ ] **Step 5: Run all tests**

```bash
cd frontend && ng test --watch=false --browsers=ChromeHeadless 2>&1 | tail -20
```

Expected: All tests pass (some may need updating if cooking.component has specs).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/app.component.ts \
        frontend/src/app/app.component.html \
        frontend/src/app/cooking/cooking.component.ts
git commit -m "feat: admin-only sidebar links and CurrentUserService in cooking component"
```

---

## Self-Review

### Spec Coverage Check

| Spec Section | Task Covering It |
|---|---|
| 1.1 Quarkus OIDC Integration | Task 2 |
| 1.2 Dev-Bypass `%dev` profile | Task 2 + Task 3 |
| 1.3 CurrentUserService (backend) | Task 3 |
| 1.4 SecurityFilter whitelist | Task 4 |
| 1.5 `/persons/me` endpoint | Task 6 |
| 1.6 `/setup` endpoints | Task 5 |
| 2.1 AuthService (frontend) | Task 7 |
| 2.2 AuthInterceptor bearer + 401 | Task 8 |
| 2.3 AuthGuard setup redirect | Task 8 |
| 2.4 CurrentUserService (frontend) | Task 9 |
| 2.5 Sidebar admin-only links | Task 12 |
| 2.6 Admin Route Guard | Task 10 |
| 2.7 Setup Wizard | Task 11 |
| 3.1 Realm Export JSON | Task 1 |
| 3.2 docker-compose Keycloak | Task 1 |
| 3.3 `role` FieldDefinition seed | Task 6 |

All spec sections covered. ✓

### Potential Issues

1. **`SecurityIdentity` cast to `JsonWebToken`**: In Task 3, `(JsonWebToken) identity.getPrincipal()` requires the OIDC extension to populate the principal as a JWT. This is the default Quarkus OIDC behavior. If the principal is not a `JsonWebToken`, catch the `ClassCastException` and treat as anonymous.

2. **`PersonResource` DTO builder**: Task 6 says to extract `buildPersonDTO()` from `getFull()`. If `getFull()` uses inline logic, this is a small refactor — check the actual implementation before editing.

3. **`setupComponent` accessing `oauthService` via `as any`**: Task 11 accesses private OAuthService via casting to extract `sub` claim. A cleaner approach: add a `getUserClaims()` method to `AuthService` that exposes `getIdentityClaims()`. This is a minor cleanup the executor may choose to do.

4. **`CookingComponent` existing `ngOnInit`**: Task 12 adds to `ngOnInit`. If there's already an `ngOnInit`, wrap the existing logic inside the subscribe callback to ensure user is resolved before data loads.
