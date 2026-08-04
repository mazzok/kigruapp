# Konfigurierbare Startseite — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Administratoren gestalten in einem Editor eine Übersichtsseite mit Rich-Text, HTML-Quelltext und Platzhaltern; alle eingeloggten Nutzer sehen sie beim Betreten der App auf Route `''`.

**Architecture:** Das Backend hält den Inhalt als Singleton-Dokument und liefert zwei Dinge: das Roh-HTML mit `{{…}}`-Tokens und eine Werte-Map für den aufrufenden Nutzer. Die Token-Ersetzung passiert im Frontend, sodass Editor-Vorschau und Nutzeransicht denselben Code-Pfad nutzen. Die Token-Werte stammen aus austauschbaren `LandingTokenProvider`-Beans (person, stunden, kochdienst).

**Tech Stack:** Quarkus + MongoDB Panache + RESTEasy + OWASP HTML Sanitizer (Backend, Java 17); Angular Standalone Components + Angular Material + ngx-quill (Frontend); JUnit5 + RestAssured + Mockito (Backend-Tests), Karma/Jasmine (Frontend-Tests).

**Spec:** `docs/superpowers/specs/2026-08-02-startseite-design.md`

## Global Constraints

- Backend-Verzeichnis: `backend/`. Testbefehl: `.\mvnw.cmd test -Dtest=<TestKlasse>` (aus `backend/`, PowerShell). Voller Lauf: `.\mvnw.cmd test`.
- Frontend-Verzeichnis: `frontend/`. Testbefehl: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/<datei>.spec.ts` (aus `frontend/`). Voller Lauf ohne `--include`.
- API-Präfix ist `/api/v1/`. Frontend-Services rufen über `ApiService` mit Pfaden **ohne** dieses Präfix auf (z. B. `this.api.get('/landing-page')`).
- Der `SecurityFilter` gilt per Default-Deny: jeder nicht explizit freigeschaltete Pfad ist admin-only. Neue Endpunkte für Nicht-Admins müssen dort eingetragen werden.
- Alle Benutzertexte auf Deutsch.
- `main` hat laut Projektnotiz vorbestehende rote Tests. Maßgeblich ist ausschließlich, dass die in diesem Plan genannten Tests grün sind und keine zuvor grüne Datei rot wird.
- Es gibt **keine** `{{org.*}}`-Tokens (siehe Spec: es existieren keine Organisations-Stammdaten).
- Commits auf einem Feature-Branch `feature/startseite`. Kein Push, kein Merge ohne Rückfrage.

---

## Dateiübersicht

**Backend (neu)**

| Datei | Verantwortung |
|---|---|
| `entity/LandingPage.java` | Singleton-Dokument mit `bodyHtml` |
| `resource/LandingPageResource.java` | GET/PUT Inhalt, GET `/placeholders`, GET `/context`, Sanitizing |
| `service/landing/LandingPlaceholder.java` | Record für eine Platzhalter-Kachel |
| `service/landing/LandingTokenProvider.java` | Interface: Kacheln + Werte |
| `service/landing/PersonTokenProvider.java` | `{{person.*}}` |
| `service/landing/HoursTokenProvider.java` | `{{stunden.*}}` |
| `service/landing/CookingTokenProvider.java` | `{{kochdienst.*}}` |
| `service/FamilyHoursTotalsService.java` | Soll/Ist-Minuten einer Familie im Semester |

**Backend (geändert)**

| Datei | Änderung |
|---|---|
| `security/SecurityFilter.java` | `GET /landing-page` und `GET /landing-page/context` für Nicht-Admins freischalten |

**Frontend (neu)**

| Datei | Verantwortung |
|---|---|
| `shared/models/landing-page.model.ts` | Interfaces `LandingPage`, `LandingPlaceholder` |
| `shared/services/landing-page.service.ts` | HTTP-Zugriffe |
| `shared/landing-token.util.ts` | Token ⇄ Pill, Ersetzung mit Kontext |
| `landing/landing.component.{ts,html,scss}` | Nutzeransicht auf Route `''` |
| `settings/landing-page/landing-page-editor.component.{ts,html,scss}` | Admin-Editor |
| `settings/landing-page/quill-web.config.ts` | Quill-Toolbar für Web-Inhalte |

**Frontend (geändert)**

| Datei | Änderung |
|---|---|
| `app.routes.ts:110` | Route `''` lädt `LandingComponent` statt Redirect auf `cooking` |
| `app.routes.ts:103-107` | Neue Kind-Route `settings/landing-page` |
| `app.component.html:63-66` | Neuer Menüpunkt „Startseite" im Einstellungs-Block |

---

## Task 1: LandingPage-Entity und CRUD-Endpunkt mit Web-Sanitizer

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/LandingPage.java`
- Create: `backend/src/main/java/at/kigruapp/resource/LandingPageResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/LandingPageResourceTest.java`

**Interfaces:**
- Produces: `LandingPage` (Felder `bodyHtml`, `updatedAt`), statisch `LandingPage.findSingleton()`; `LandingPageResource.LandingPageDto(String bodyHtml, Instant updatedAt)`; Endpunkte `GET /api/v1/landing-page` und `PUT /api/v1/landing-page`; package-sichtbare Konstante `LandingPageResource.WEB_HTML_POLICY`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/resource/LandingPageResourceTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.LandingPage;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LandingPageResourceTest {

    @BeforeEach
    void cleanup() {
        LandingPage.deleteAll();
    }

    private String put(String bodyHtml) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"bodyHtml\":" + jsonString(bodyHtml) + "}")
                .when().put("/api/v1/landing-page")
                .then().statusCode(200)
                .extract().path("bodyHtml");
    }

    /** Minimaler JSON-String-Escaper für die Testfixtures. */
    private String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void getReturnsEmptyContentWhenNothingSavedYet() {
        given()
                .when().get("/api/v1/landing-page")
                .then().statusCode(200)
                .body("bodyHtml", is(""));
    }

    @Test
    void putThenGetReturnsSavedContent() {
        put("<p>Willkommen</p>");

        given()
                .when().get("/api/v1/landing-page")
                .then().statusCode(200)
                .body("bodyHtml", is("<p>Willkommen</p>"));
    }

    @Test
    void putOverwritesSingletonInsteadOfCreatingSecondRow() {
        put("<p>eins</p>");
        put("<p>zwei</p>");

        assertEquals(1, LandingPage.count());
        given()
                .when().get("/api/v1/landing-page")
                .then().body("bodyHtml", is("<p>zwei</p>"));
    }

    @Test
    void sanitizerDropsScriptAndIframeButKeepsTablesImagesAndStyles() {
        String saved = put("<script>alert(1)</script>"
                + "<iframe src=\"https://evil.example\"></iframe>"
                + "<table><tbody><tr><td style=\"color:red\">Zelle</td></tr></tbody></table>"
                + "<img src=\"https://example.org/logo.png\" alt=\"Logo\">");

        assertFalse(saved.contains("<script"), saved);
        assertFalse(saved.contains("<iframe"), saved);
        assertTrue(saved.contains("<table"), saved);
        assertTrue(saved.contains("style="), saved);
        assertTrue(saved.contains("https://example.org/logo.png"), saved);
    }

    @Test
    void sanitizerDropsDataUriImageButKeepsHttpsImage() {
        String saved = put("<img src=\"data:image/png;base64,AAAA\">"
                + "<img src=\"https://example.org/ok.png\">");

        assertFalse(saved.contains("data:image"), saved);
        assertTrue(saved.contains("https://example.org/ok.png"), saved);
    }

    @Test
    void sanitizerKeepsPlaceholderTokensIntact() {
        String saved = put("<p>Hallo {{person.firstName}}, Bilanz {{stunden.bilanz}}</p>");

        assertTrue(saved.contains("{{person.firstName}}"), saved);
        assertTrue(saved.contains("{{stunden.bilanz}}"), saved);
        assertFalse(saved.contains("<!--"), saved);
    }

    @Test
    void putRejectsNullBody() {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().put("/api/v1/landing-page")
                .then().statusCode(400);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=LandingPageResourceTest` (aus `backend/`)
Expected: Kompilierfehler — `at.kigruapp.entity.LandingPage` existiert nicht.

- [ ] **Step 3: Write the entity**

Create `backend/src/main/java/at/kigruapp/entity/LandingPage.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;

/**
 * Inhalt der Startseite. Bewusst ein Singleton: es gibt genau eine Startseite,
 * die erste Zeile der Collection ist maßgeblich.
 */
@MongoEntity(collection = "landing_page")
public class LandingPage extends PanacheMongoEntity {
    public String bodyHtml;
    public Instant updatedAt;

    public static LandingPage findSingleton() {
        return findAll().firstResult();
    }
}
```

- [ ] **Step 4: Write the resource**

Create `backend/src/main/java/at/kigruapp/resource/LandingPageResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.LandingPage;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.time.Instant;

/**
 * Inhalt der Startseite. GET ist für alle angemeldeten Nutzer freigeschaltet
 * (siehe SecurityFilter), PUT bleibt durch das Default-Deny admin-only.
 */
@Path("/api/v1/landing-page")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LandingPageResource {

    /**
     * Gegenüber der Mail-Policy bewusst gelockert: die Startseite wird im
     * Browser gerendert, nicht in einem Mail-Client, daher sind Überschriften,
     * Tabellen und Bilder erlaubt. {@code script} und {@code iframe} fehlen in
     * der Elementliste und werden dadurch entfernt.
     *
     * {@code allowUrlProtocols("http", "https")} greift auch für
     * {@code img/src} und wirft damit {@code data:}-URIs weg — sonst könnte ein
     * eingebettetes Bild das Dokument um Hunderte Kilobyte aufblähen.
     */
    static final PolicyFactory WEB_HTML_POLICY = new HtmlPolicyBuilder()
            .allowElements(
                    "p", "br", "hr", "b", "strong", "i", "em", "u", "s",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "ol", "ul", "li", "blockquote", "pre", "code",
                    "a", "span", "div", "img",
                    "table", "thead", "tbody", "tr", "th", "td")
            .allowAttributes("href", "target").onElements("a")
            .allowAttributes("src", "alt", "width", "height").onElements("img")
            .allowAttributes("colspan", "rowspan").onElements("td", "th")
            .allowAttributes("style", "class").globally()
            .allowUrlProtocols("http", "https")
            .toFactory();

    /**
     * Sanitisieren, dann die Leerkommentare entfernen, die der Sanitizer
     * zwischen die Klammern schiebt (z.&nbsp;B. {@code {<!-- -->{}). Ohne diesen
     * Schritt zerfallen die gespeicherten {@code {{…}}}-Tokens und weder die
     * Ersetzung noch die Token-zu-Pill-Umwandlung im Editor findet sie wieder.
     */
    static String sanitizeBody(String bodyHtml) {
        return WEB_HTML_POLICY.sanitize(bodyHtml).replaceAll("<!--\\s*-->", "");
    }

    public record LandingPageDto(String bodyHtml, Instant updatedAt) {}

    @GET
    public LandingPageDto get() {
        LandingPage page = LandingPage.findSingleton();
        if (page == null) {
            return new LandingPageDto("", null);
        }
        return new LandingPageDto(page.bodyHtml == null ? "" : page.bodyHtml, page.updatedAt);
    }

    @PUT
    public LandingPageDto save(LandingPageDto request) {
        if (request == null || request.bodyHtml() == null) {
            throw new BadRequestException("bodyHtml is required");
        }
        LandingPage page = LandingPage.findSingleton();
        boolean isNew = page == null;
        if (isNew) {
            page = new LandingPage();
        }
        page.bodyHtml = sanitizeBody(request.bodyHtml());
        page.updatedAt = Instant.now();
        if (isNew) {
            page.persist();
        } else {
            page.update();
        }
        return new LandingPageDto(page.bodyHtml, page.updatedAt);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=LandingPageResourceTest`
Expected: PASS, 7 Tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/LandingPage.java backend/src/main/java/at/kigruapp/resource/LandingPageResource.java backend/src/test/java/at/kigruapp/resource/LandingPageResourceTest.java
git commit -m "feat(be): Startseiten-Inhalt als Singleton mit Web-HTML-Sanitizer"
```

---

## Task 2: Token-Provider-Gerüst und {{person.*}}

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/landing/LandingPlaceholder.java`
- Create: `backend/src/main/java/at/kigruapp/service/landing/LandingTokenProvider.java`
- Create: `backend/src/main/java/at/kigruapp/service/landing/PersonTokenProvider.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/LandingPageResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/LandingPageTokensTest.java`

**Interfaces:**
- Consumes: `LandingPageResource` aus Task 1.
- Produces: `LandingPlaceholder(String token, String label, String group)`; `LandingTokenProvider` mit `List<LandingPlaceholder> placeholders()` und `Map<String, String> values(Person person)`; Endpunkte `GET /api/v1/landing-page/placeholders` (Liste von `LandingPlaceholder`) und `GET /api/v1/landing-page/context` (`Map<String, String>`, Schlüssel sind vollständige Tokens inklusive geschweifter Klammern).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/resource/LandingPageTokensTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import io.quarkus.test.junit.QuarkusTest;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class LandingPageTokensTest {

    @BeforeEach
    void cleanup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        FieldInstance.deleteAll();
    }

    private FieldDefinition persistDefinition(String fieldName, String labelDe) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.label = Map.of("de", labelDe, "en", labelDe);
        def.createdAt = java.time.Instant.now();
        def.persist();
        return def;
    }

    /** Legt eine Person mit einem befüllten Basisfeld an. */
    private Person persistPersonWithField(FieldDefinition def, String value) {
        FieldInstance inst = new FieldInstance();
        inst.definitionId = def.id;
        inst.value = value;
        inst.persist();

        FieldRef ref = new FieldRef();
        ref.definitionId = def.id;
        ref.fieldInstanceId = inst.id;

        Person person = new Person();
        person.familyId = new ObjectId();
        person.basicProperties = new ArrayList<>();
        person.basicProperties.add(ref);
        person.persist();
        return person;
    }

    @Test
    void placeholdersContainAllowlistedPersonFieldsOnly() {
        persistDefinition("firstName", "Vorname");
        persistDefinition("group", "Gruppe");

        given()
                .when().get("/api/v1/landing-page/placeholders")
                .then().statusCode(200)
                .body("token", hasItem("{{person.firstName}}"))
                .body("token", not(hasItem("{{person.group}}")));
    }

    @Test
    void placeholdersCarryGermanLabelAndGroup() {
        persistDefinition("firstName", "Vorname");

        given()
                .when().get("/api/v1/landing-page/placeholders")
                .then().statusCode(200)
                .body("find { it.token == '{{person.firstName}}' }.label", is("Vorname"))
                .body("find { it.token == '{{person.firstName}}' }.group", is("person"));
    }

    @Test
    void contextResolvesPersonTokenForCurrentUser() {
        FieldDefinition firstName = persistDefinition("firstName", "Vorname");
        persistPersonWithField(firstName, "Anna");

        given()
                .when().get("/api/v1/landing-page/context")
                .then().statusCode(200)
                .body("'{{person.firstName}}'", is("Anna"));
    }

    @Test
    void placeholdersStayAvailableWhenNoDefinitionsExist() {
        given()
                .when().get("/api/v1/landing-page/placeholders")
                .then().statusCode(200);
    }
}
```

> Hinweis für die Umsetzung: In Tests ist OIDC deaktiviert, `CurrentUserService`
> liefert dann die erste Person der Datenbank. Deshalb genügt es, genau eine
> Person anzulegen, um „der aktuelle Nutzer" zu sein.
>
> Zweiter Hinweis: Vor dem Schreiben der Fixtures kurz
> `backend/src/main/java/at/kigruapp/entity/FieldInstance.java` und `FieldRef.java`
> öffnen und die Feldnamen (`definitionId`, `value`, `fieldInstanceId`) sowie den
> Konstruktor abgleichen. Sollte `FieldInstance` kein Panache-`persist()`
> anbieten, die Instanz stattdessen wie in `CookingDutyResource` direkt über die
> Collection `field_instances` schreiben.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=LandingPageTokensTest`
Expected: FAIL — 404 auf `/placeholders`, die Endpunkte existieren noch nicht.

- [ ] **Step 3: Write the placeholder record and provider interface**

Create `backend/src/main/java/at/kigruapp/service/landing/LandingPlaceholder.java`:

```java
package at.kigruapp.service.landing;

/**
 * Eine im Editor einfügbare Kachel.
 *
 * @param token vollständiger Token inklusive Klammern, z.B. {@code {{person.firstName}}}
 * @param label deutsche Beschriftung der Kachel
 * @param group Familie des Tokens ("person", "stunden", "kochdienst") — das Frontend gruppiert danach
 */
public record LandingPlaceholder(String token, String label, String group) {}
```

Create `backend/src/main/java/at/kigruapp/service/landing/LandingTokenProvider.java`:

```java
package at.kigruapp.service.landing;

import at.kigruapp.entity.Person;

import java.util.List;
import java.util.Map;

/**
 * Liefert eine Familie von Startseiten-Platzhaltern. Eine neue Familie ist eine
 * weitere {@code @ApplicationScoped}-Implementierung — die Resource iteriert
 * nur über alle Beans und muss dafür nicht angefasst werden.
 */
public interface LandingTokenProvider {

    /** Kacheln, die der Editor anbietet. */
    List<LandingPlaceholder> placeholders();

    /**
     * Werte für den angegebenen Nutzer, Schlüssel sind vollständige Tokens.
     * Fehlen die zugrundeliegenden Daten (kein Semester, kein Dienst), gibt die
     * Implementierung einen leeren Wert zurück statt zu werfen — die Startseite
     * darf an einem fehlenden Datensatz nicht scheitern.
     */
    Map<String, String> values(Person person);
}
```

- [ ] **Step 4: Write the person provider**

Create `backend/src/main/java/at/kigruapp/service/landing/PersonTokenProvider.java`:

```java
package at.kigruapp.service.landing;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.Person;
import at.kigruapp.service.PersonPropertyResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code {{person.*}}} — dieselbe Allowlist wie bei den Mail-Platzhaltern und
 * im PersonPropertyResolver; alle drei beschreiben denselben Begriff
 * "skalare Personeneigenschaft".
 */
@ApplicationScoped
public class PersonTokenProvider implements LandingTokenProvider {

    private static final Set<String> SCALAR_PERSON_FIELD_ALLOWLIST = Set.of(
            "firstName", "lastName", "email", "phone", "dateOfBirth", "gender", "entryDate", "exitDate", "notes"
    );

    @Inject
    PersonPropertyResolver personPropertyResolver;

    @Override
    public List<LandingPlaceholder> placeholders() {
        return FieldDefinition.findActive().stream()
                .filter(def -> SCALAR_PERSON_FIELD_ALLOWLIST.contains(def.fieldName))
                .map(def -> new LandingPlaceholder(
                        "{{person." + def.fieldName + "}}", labelDe(def), "person"))
                .sorted(Comparator.comparing(LandingPlaceholder::label))
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, String> values(Person person) {
        Map<String, String> properties = personPropertyResolver.resolve(List.of(person))
                .getOrDefault(person.id, Map.of());
        Map<String, String> result = new HashMap<>();
        properties.forEach((fieldName, value) -> {
            if (SCALAR_PERSON_FIELD_ALLOWLIST.contains(fieldName)) {
                result.put("{{person." + fieldName + "}}", value);
            }
        });
        return result;
    }

    private String labelDe(FieldDefinition def) {
        if (def.label == null) {
            return def.fieldName;
        }
        String de = def.label.get("de");
        return de != null ? de : def.fieldName;
    }
}
```

- [ ] **Step 5: Wire the endpoints into the resource**

In `backend/src/main/java/at/kigruapp/resource/LandingPageResource.java` ergänzen — Imports oben hinzufügen:

```java
import at.kigruapp.entity.Person;
import at.kigruapp.security.CurrentUserService;
import at.kigruapp.service.landing.LandingPlaceholder;
import at.kigruapp.service.landing.LandingTokenProvider;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
```

und im Klassenkörper (nach `sanitizeBody`) einfügen:

```java
    @Inject
    Instance<LandingTokenProvider> tokenProviders;

    @Inject
    CurrentUserService currentUserService;

    @GET
    @Path("/placeholders")
    public List<LandingPlaceholder> placeholders() {
        List<LandingPlaceholder> tiles = new ArrayList<>();
        for (LandingTokenProvider provider : tokenProviders) {
            tiles.addAll(provider.placeholders());
        }
        return tiles;
    }

    @GET
    @Path("/context")
    public Map<String, String> context() {
        Person person = currentUserService.getCurrentPerson();
        if (person == null) {
            throw new ForbiddenException();
        }
        Map<String, String> values = new HashMap<>();
        for (LandingTokenProvider provider : tokenProviders) {
            values.putAll(provider.values(person));
        }
        return values;
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=LandingPageTokensTest`
Expected: PASS, 4 Tests.

- [ ] **Step 7: Run the Task 1 test again to check for regressions**

Run: `.\mvnw.cmd test -Dtest=LandingPageResourceTest`
Expected: PASS, 7 Tests.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/landing backend/src/main/java/at/kigruapp/resource/LandingPageResource.java backend/src/test/java/at/kigruapp/resource/LandingPageTokensTest.java
git commit -m "feat(be): Platzhalter- und Kontext-Endpunkt der Startseite mit person-Tokens"
```

---

## Task 3: Familien-Stundensummen als eigener Service

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/FamilyHoursTotalsService.java`
- Test: `backend/src/test/java/at/kigruapp/service/FamilyHoursTotalsServiceTest.java`

**Interfaces:**
- Produces: `FamilyHoursTotalsService.Totals(int sollMinutes, int istMinutes)` und `Totals totalsFor(Person person, ObjectId semesterId)`; `ObjectId latestSemesterId()` (liefert `null`, wenn es kein Semester gibt).

**Warum ein eigener Service:** `HourEntryResource.our()` berechnet dieselben
Summen, aber eingebettet in den Aufbau eines großen DTOs mit privaten Helfern.
`/our` wird hier bewusst **nicht** umgebaut — das Feature ist ausgeliefert und
getestet, ein Umbau brächte Risiko ohne Gegenwert für diese Aufgabe. Stattdessen
pinnt Task 4 die Gleichheit beider Wege mit einem Test fest.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/service/FamilyHoursTotalsServiceTest.java`:

```java
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
        semester.name = "2026/27";
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=FamilyHoursTotalsServiceTest`
Expected: Kompilierfehler — `FamilyHoursTotalsService` existiert nicht.

- [ ] **Step 3: Write the service**

Create `backend/src/main/java/at/kigruapp/service/FamilyHoursTotalsService.java`:

```java
package at.kigruapp.service;

import at.kigruapp.entity.AliquotConfig;
import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoClient;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Soll- und Ist-Minuten einer Familie in einem Semester. Nutzt dieselben
 * HoursBalanceService-Bausteine wie {@code HourEntryResource.our()}; ein Test
 * hält beide Wege auf demselben Ergebnis fest.
 */
@ApplicationScoped
public class FamilyHoursTotalsService {

    public record Totals(int sollMinutes, int istMinutes) {}

    @Inject
    HoursBalanceService hoursBalanceService;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    /** Jüngstes Semester, oder {@code null}, wenn noch keines angelegt ist. */
    public ObjectId latestSemesterId() {
        List<Semester> latest = Semester.listAll(Sort.descending("createdAt"));
        return latest.isEmpty() ? null : latest.get(0).id;
    }

    public Totals totalsFor(Person person, ObjectId semesterId) {
        if (person == null || semesterId == null) {
            return new Totals(0, 0);
        }
        Semester semester = Semester.findById(semesterId);

        List<Person> members = person.familyId == null
                ? List.of(person)
                : Person.findByFamilyId(person.familyId);

        RequiredHours cfg = RequiredHours.findBySemesterId(semesterId);
        AliquotConfig aliquotCfg = AliquotConfig.findBySemesterId(semesterId);
        AliquotMode mode = AliquotMode.fromString(aliquotCfg != null ? aliquotCfg.stundenMode : null);

        Map<String, Integer> sollByMonth = hoursBalanceService.familySollByMonth(
                cfg, mode, semester, placements(members, semesterId));
        int soll = sollByMonth.values().stream().mapToInt(Integer::intValue).sum();

        int ist = 0;
        for (Person member : members) {
            List<HourEntry> entries = HourEntry.<HourEntry>find(
                    "personId = ?1 and semesterId = ?2", member.id, semesterId).list();
            for (HourEntry entry : entries) {
                ist += entry.minutes;
            }
        }
        return new Totals(soll, ist);
    }

    /** Ein Placement pro platziertem Kind der Familie, mit Ein-/Austrittsdatum. */
    private List<HoursBalanceService.ChildPlacement> placements(List<Person> members, ObjectId semesterId) {
        List<HoursBalanceService.ChildPlacement> placements = new ArrayList<>();
        if (members.isEmpty()) {
            return placements;
        }
        List<ObjectId> memberIds = new ArrayList<>();
        for (Person member : members) {
            memberIds.add(member.id);
        }
        Document filter = new Document("semesterId", semesterId)
                .append("section", "group")
                .append("personId", new Document("$in", memberIds));
        MongoCollection<Document> assignments =
                mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
        Set<ObjectId> seen = new HashSet<>();
        for (Document doc : assignments.find(filter)) {
            ObjectId personId = doc.getObjectId("personId");
            if (personId == null || !seen.add(personId)) {
                continue;
            }
            HoursBalanceService.ChildPlacement placement = new HoursBalanceService.ChildPlacement();
            placement.childId = personId.toHexString();
            placement.entryDate = doc.getString("entryDate");
            placement.exitDate = doc.getString("exitDate");
            placements.add(placement);
        }
        return placements;
    }
}
```

> Falls `AliquotMode` in einem anderen Package liegt als `at.kigruapp.service`,
> den Import entsprechend anpassen — die Klasse wird in
> `HourEntryResource` bereits verwendet, dort steht der korrekte Import.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=FamilyHoursTotalsServiceTest`
Expected: PASS, 5 Tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/FamilyHoursTotalsService.java backend/src/test/java/at/kigruapp/service/FamilyHoursTotalsServiceTest.java
git commit -m "feat(be): Service fuer Soll-/Ist-Minuten einer Familie im Semester"
```

---

## Task 4: {{stunden.*}}-Provider

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/landing/HoursTokenProvider.java`
- Test: `backend/src/test/java/at/kigruapp/service/landing/HoursTokenProviderTest.java`

**Interfaces:**
- Consumes: `LandingTokenProvider`, `LandingPlaceholder` (Task 2); `FamilyHoursTotalsService.Totals` und `latestSemesterId()` (Task 3).
- Produces: Tokens `{{stunden.geleistet}}`, `{{stunden.soll}}`, `{{stunden.bilanz}}`, jeweils als Stundenwert mit einer Nachkommastelle und deutschem Dezimalkomma (z. B. `2,5`); Bilanz = geleistet − soll, mit Vorzeichen bei negativem Wert.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/service/landing/HoursTokenProviderTest.java`:

```java
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
        semester.name = "2026/27";
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=HoursTokenProviderTest`
Expected: Kompilierfehler — `HoursTokenProvider` existiert nicht.

- [ ] **Step 3: Write the provider**

Create `backend/src/main/java/at/kigruapp/service/landing/HoursTokenProvider.java`:

```java
package at.kigruapp.service.landing;

import at.kigruapp.entity.Person;
import at.kigruapp.service.FamilyHoursTotalsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code {{stunden.*}}} — Soll, Ist und Bilanz der Familie im jüngsten Semester. */
@ApplicationScoped
public class HoursTokenProvider implements LandingTokenProvider {

    private static final String GELEISTET = "{{stunden.geleistet}}";
    private static final String SOLL = "{{stunden.soll}}";
    private static final String BILANZ = "{{stunden.bilanz}}";

    @Inject
    FamilyHoursTotalsService familyHoursTotalsService;

    @Override
    public List<LandingPlaceholder> placeholders() {
        return List.of(
                new LandingPlaceholder(GELEISTET, "Geleistete Stunden", "stunden"),
                new LandingPlaceholder(SOLL, "Soll-Stunden", "stunden"),
                new LandingPlaceholder(BILANZ, "Stunden-Bilanz", "stunden"));
    }

    @Override
    public Map<String, String> values(Person person) {
        Map<String, String> values = new LinkedHashMap<>();
        ObjectId semesterId = familyHoursTotalsService.latestSemesterId();
        if (semesterId == null) {
            values.put(GELEISTET, "");
            values.put(SOLL, "");
            values.put(BILANZ, "");
            return values;
        }
        FamilyHoursTotalsService.Totals totals = familyHoursTotalsService.totalsFor(person, semesterId);
        values.put(GELEISTET, formatHours(totals.istMinutes()));
        values.put(SOLL, formatHours(totals.sollMinutes()));
        values.put(BILANZ, formatHours(totals.istMinutes() - totals.sollMinutes()));
        return values;
    }

    /** Minuten als Stunden mit einer Nachkommastelle und deutschem Dezimalkomma. */
    private String formatHours(int minutes) {
        return String.format(java.util.Locale.GERMAN, "%.1f", minutes / 60.0);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=HoursTokenProviderTest`
Expected: PASS, 5 Tests.

- [ ] **Step 5: Write the divergence-pinning test**

Append to `backend/src/test/java/at/kigruapp/service/landing/HoursTokenProviderTest.java` (Imports `io.restassured.RestAssured.given` und `org.hamcrest.Matchers.is` oben ergänzen):

```java
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
```

- [ ] **Step 6: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=HoursTokenProviderTest`
Expected: PASS, 6 Tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/landing/HoursTokenProvider.java backend/src/test/java/at/kigruapp/service/landing/HoursTokenProviderTest.java
git commit -m "feat(be): stunden-Tokens fuer die Startseite"
```

---

## Task 5: {{kochdienst.*}}-Provider

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/landing/CookingTokenProvider.java`
- Test: `backend/src/test/java/at/kigruapp/service/landing/CookingTokenProviderTest.java`

**Interfaces:**
- Consumes: `LandingTokenProvider`, `LandingPlaceholder` (Task 2).
- Produces: Token `{{kochdienst.naechsterTermin}}`, Wert im Format `dd.MM.yyyy`; leerer String, wenn kein künftiger Dienst existiert.

**Datenquelle:** Kochdienste hängen als `FieldRef` in `Person.schedules` mit der
Definition `fieldName = "cookingDuty"`. Der zugehörige `FieldInstance.value` ist
ein Dokument mit dem Feld `date` im Format `yyyy-MM-dd`. Genau so liest es
`CookingDutyResource.list()`.

Für die Fixtures gilt derselbe Hinweis wie in Task 2: die Feldnamen von
`FieldInstance`/`FieldRef` vorher gegen die Entity-Klassen abgleichen.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/service/landing/CookingTokenProviderTest.java`:

```java
package at.kigruapp.service.landing;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CookingTokenProviderTest {

    @Inject
    CookingTokenProvider provider;

    @BeforeEach
    void cleanup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        FieldInstance.deleteAll();
    }

    private FieldDefinition persistCookingDutyDefinition() {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = "cookingDuty";
        def.label = Map.of("de", "Kochdienst", "en", "Cooking duty");
        def.createdAt = java.time.Instant.now();
        def.persist();
        return def;
    }

    /** Person mit beliebig vielen Kochdienst-Terminen (Datum im Format yyyy-MM-dd). */
    private Person persistPersonWithDuties(ObjectId familyId, FieldDefinition def, String... dates) {
        Person person = new Person();
        person.familyId = familyId;
        person.schedules = new ArrayList<>();
        for (String date : dates) {
            FieldInstance inst = new FieldInstance();
            inst.definitionId = def.id;
            inst.value = new Document("date", date);
            inst.persist();

            FieldRef ref = new FieldRef();
            ref.definitionId = def.id;
            ref.fieldInstanceId = inst.id;
            person.schedules.add(ref);
        }
        person.persist();
        return person;
    }

    @Test
    void placeholderIsGroupedAsKochdienst() {
        assertEquals(1, provider.placeholders().size());
        assertEquals("{{kochdienst.naechsterTermin}}", provider.placeholders().get(0).token());
        assertEquals("kochdienst", provider.placeholders().get(0).group());
    }

    @Test
    void naechsterKuenftigerTerminWirdDeutschFormatiert() {
        FieldDefinition def = persistCookingDutyDefinition();
        String soon = LocalDate.now().plusDays(3).toString();
        Person person = persistPersonWithDuties(new ObjectId(), def, soon);

        String expected = LocalDate.parse(soon)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        assertEquals(expected, provider.values(person).get("{{kochdienst.naechsterTermin}}"));
    }

    @Test
    void vergangeneTermineWerdenIgnoriert() {
        FieldDefinition def = persistCookingDutyDefinition();
        Person person = persistPersonWithDuties(new ObjectId(), def,
                LocalDate.now().minusDays(5).toString());

        assertEquals("", provider.values(person).get("{{kochdienst.naechsterTermin}}"));
    }

    @Test
    void vonMehrerenKuenftigenTerminenGewinntDerFruehste() {
        FieldDefinition def = persistCookingDutyDefinition();
        String later = LocalDate.now().plusDays(20).toString();
        String earlier = LocalDate.now().plusDays(4).toString();
        Person person = persistPersonWithDuties(new ObjectId(), def, later, earlier);

        String expected = LocalDate.parse(earlier)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        assertEquals(expected, provider.values(person).get("{{kochdienst.naechsterTermin}}"));
    }

    @Test
    void terminEinesFamilienmitgliedsZaehltEbenfalls() {
        FieldDefinition def = persistCookingDutyDefinition();
        ObjectId familyId = new ObjectId();
        Person me = persistPersonWithDuties(familyId, def);
        String date = LocalDate.now().plusDays(2).toString();
        persistPersonWithDuties(familyId, def, date);

        String expected = LocalDate.parse(date)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        assertEquals(expected, provider.values(me).get("{{kochdienst.naechsterTermin}}"));
    }

    @Test
    void ohneKochdienstDefinitionLiefertLeerenWertStattFehler() {
        Person person = new Person();
        person.familyId = new ObjectId();
        person.persist();

        assertTrue(provider.values(person).get("{{kochdienst.naechsterTermin}}").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingTokenProviderTest`
Expected: Kompilierfehler — `CookingTokenProvider` existiert nicht.

- [ ] **Step 3: Write the provider**

Create `backend/src/main/java/at/kigruapp/service/landing/CookingTokenProvider.java`:

```java
package at.kigruapp.service.landing;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * {@code {{kochdienst.naechsterTermin}}} — der nächste künftige Kochdienst der
 * eigenen Familie. Die Termine hängen als FieldRef in {@code Person.schedules};
 * der FieldInstance-Wert ist ein Dokument mit {@code date} (yyyy-MM-dd).
 */
@ApplicationScoped
public class CookingTokenProvider implements LandingTokenProvider {

    private static final String NEXT_DUTY = "{{kochdienst.naechsterTermin}}";
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Override
    public List<LandingPlaceholder> placeholders() {
        return List.of(new LandingPlaceholder(NEXT_DUTY, "Nächster Kochdienst", "kochdienst"));
    }

    @Override
    public Map<String, String> values(Person person) {
        return Map.of(NEXT_DUTY, nextDutyDate(person));
    }

    private String nextDutyDate(Person person) {
        FieldDefinition cookingDutyDef = FieldDefinition.find("fieldName", "cookingDuty").firstResult();
        if (cookingDutyDef == null) {
            return "";
        }
        List<Person> members = person.familyId == null
                ? List.of(person)
                : Person.findByFamilyId(person.familyId);

        MongoCollection<Document> instances =
                mongoClient.getDatabase(databaseName).getCollection("field_instances");
        String today = LocalDate.now().toString();
        String earliest = null;

        for (Person member : members) {
            if (member.schedules == null) {
                continue;
            }
            for (FieldRef ref : member.schedules) {
                if (!cookingDutyDef.id.equals(ref.definitionId)) {
                    continue;
                }
                Document doc = instances.find(new Document("_id", ref.fieldInstanceId)).first();
                if (doc == null) {
                    continue;
                }
                FieldInstance inst = FieldInstance.fromDocument(doc);
                if (!(inst.value instanceof Document valueDoc)) {
                    continue;
                }
                String date = valueDoc.getString("date");
                // ISO-Datumsstrings sind lexikografisch vergleichbar.
                if (date == null || date.compareTo(today) < 0) {
                    continue;
                }
                if (earliest == null || date.compareTo(earliest) < 0) {
                    earliest = date;
                }
            }
        }
        return earliest == null ? "" : LocalDate.parse(earliest).format(GERMAN_DATE);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=CookingTokenProviderTest`
Expected: PASS, 6 Tests.

- [ ] **Step 5: Verify the provider shows up in the endpoints**

Run: `.\mvnw.cmd test -Dtest=LandingPageTokensTest`
Expected: PASS, 4 Tests (die Provider werden automatisch eingesammelt).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/landing/CookingTokenProvider.java backend/src/test/java/at/kigruapp/service/landing/CookingTokenProviderTest.java
git commit -m "feat(be): kochdienst-Token fuer die Startseite"
```

---

## Task 6: SecurityFilter für Nicht-Admins freischalten

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/security/SecurityFilter.java:103` (unmittelbar vor dem Default-Deny-Kommentar)
- Test: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java` (anhängen)

**Interfaces:**
- Consumes: Endpunkte aus Task 1 und 2.
- Produces: Nicht-Admins dürfen `GET /api/v1/landing-page` und `GET /api/v1/landing-page/context`; `PUT` bleibt admin-only.

- [ ] **Step 1: Write the failing test**

Append to `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java` (vor der schließenden Klammer):

```java
    // Startseite: Inhalt und Kontext dürfen von allen Angemeldeten gelesen werden.
    @Test
    void getLandingPage_nonAdmin_allowed() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/landing-page", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    @Test
    void getLandingPageContext_nonAdmin_allowed() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/landing-page/context", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    @Test
    void putLandingPage_nonAdmin_returns403() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/landing-page", "PUT");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }

    @Test
    void getLandingPagePlaceholders_nonAdmin_returns403() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/landing-page/placeholders", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=SecurityFilterTest`
Expected: FAIL — `getLandingPage_nonAdmin_allowed` und
`getLandingPageContext_nonAdmin_allowed` schlagen fehl (Default-Deny greift),
die beiden 403-Tests sind bereits grün.

- [ ] **Step 3: Add the whitelist entries**

In `backend/src/main/java/at/kigruapp/security/SecurityFilter.java` in
`isAllowed(...)` direkt vor dem Kommentar `// Default: admin-only` einfügen:

```java
        // Startseite: Inhalt und Kontext für alle Angemeldeten lesbar.
        // /placeholders bleibt bewusst admin-only — es ist reines Editor-Zubehör.
        if (path.equals("/api/v1/landing-page") && "GET".equals(method)) return true;
        if (path.equals("/api/v1/landing-page/context") && "GET".equals(method)) return true;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=SecurityFilterTest`
Expected: Die vier neuen Tests sind grün. Vorbestehende Fehlschläge in dieser
Datei bleiben unverändert; kein zuvor grüner Test wird rot.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/security/SecurityFilter.java backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java
git commit -m "feat(be): Startseiten-Lesezugriff fuer Nicht-Admins freigeschaltet"
```

---

## Task 7: Frontend-Token-Utility

**Files:**
- Create: `frontend/src/app/shared/models/landing-page.model.ts`
- Create: `frontend/src/app/shared/landing-token.util.ts`
- Test: `frontend/src/app/shared/landing-token.util.spec.ts`

**Interfaces:**
- Produces:
  - `interface LandingPage { bodyHtml: string; updatedAt: string | null; }`
  - `interface LandingPlaceholder { token: string; label: string; group: string; }`
  - `type LandingContext = Record<string, string>`
  - `TOKEN_RE: RegExp`
  - `pillSpan(token: string, label: string): string`
  - `tokensToPills(html: string, placeholders: LandingPlaceholder[]): string`
  - `pillsToTokens(html: string): string`
  - `renderWithContext(html: string, context: LandingContext): string`

**Hinweis zur Wiederverwendung:** Der Quill-Blot aus
`settings/mail/mail-template-editor/mail-token.blot.ts` ist bereits generisch
(er trägt nur `data-token`/`data-label`). Task 11 verwendet ihn unverändert
mit, statt eine zweite Kopie anzulegen; deshalb erzeugt `pillSpan` hier
ebenfalls die Klasse `mail-token`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/landing-token.util.spec.ts`:

```typescript
import {
  TOKEN_RE,
  pillSpan,
  tokensToPills,
  pillsToTokens,
  renderWithContext,
} from './landing-token.util';
import { LandingPlaceholder } from './models/landing-page.model';

const PLACEHOLDERS: LandingPlaceholder[] = [
  { token: '{{person.firstName}}', label: 'Vorname', group: 'person' },
  { token: '{{stunden.bilanz}}', label: 'Stunden-Bilanz', group: 'stunden' },
];

describe('landing-token.util', () => {
  beforeEach(() => (TOKEN_RE.lastIndex = 0));

  it('erkennt Tokens aller Familien', () => {
    const found = '<p>{{person.firstName}} {{stunden.bilanz}} {{kochdienst.naechsterTermin}}</p>'
      .match(TOKEN_RE);
    expect(found).toEqual([
      '{{person.firstName}}',
      '{{stunden.bilanz}}',
      '{{kochdienst.naechsterTermin}}',
    ]);
  });

  it('erzeugt eine Pille mit Token und Beschriftung', () => {
    expect(pillSpan('{{person.firstName}}', 'Vorname'))
      .toBe('<span class="mail-token" data-token="{{person.firstName}}">Vorname</span>');
  });

  it('wandelt Tokens in Pillen mit deutscher Beschriftung', () => {
    const result = tokensToPills('<p>Hallo {{person.firstName}}</p>', PLACEHOLDERS);
    expect(result).toContain('data-token="{{person.firstName}}"');
    expect(result).toContain('>Vorname<');
  });

  it('nutzt den Token als Beschriftung, wenn keine Kachel dazu bekannt ist', () => {
    const result = tokensToPills('<p>{{kochdienst.naechsterTermin}}</p>', PLACEHOLDERS);
    expect(result).toContain('>{{kochdienst.naechsterTermin}}<');
  });

  it('wandelt Pillen zurück in Tokens', () => {
    const html = '<p>Hallo <span class="mail-token" data-token="{{person.firstName}}">Vorname</span></p>';
    expect(pillsToTokens(html)).toBe('<p>Hallo {{person.firstName}}</p>');
  });

  it('ist über den Roundtrip Token → Pille → Token verlustfrei', () => {
    const original = '<p>Hallo {{person.firstName}}, Bilanz {{stunden.bilanz}}</p>';
    expect(pillsToTokens(tokensToPills(original, PLACEHOLDERS))).toBe(original);
  });

  it('ersetzt Tokens durch Kontextwerte', () => {
    const html = '<p>Hallo {{person.firstName}}</p>';
    expect(renderWithContext(html, { '{{person.firstName}}': 'Anna' }))
      .toBe('<p>Hallo Anna</p>');
  });

  it('ersetzt unbekannte Tokens durch einen Gedankenstrich', () => {
    expect(renderWithContext('<p>{{person.firstName}}</p>', {}))
      .toBe('<p>–</p>');
  });

  it('ersetzt leere Kontextwerte ebenfalls durch einen Gedankenstrich', () => {
    expect(renderWithContext('<p>{{kochdienst.naechsterTermin}}</p>', { '{{kochdienst.naechsterTermin}}': '' }))
      .toBe('<p>–</p>');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-token.util.spec.ts` (aus `frontend/`)
Expected: FAIL — das Modul `./landing-token.util` existiert nicht.

- [ ] **Step 3: Write the model**

Create `frontend/src/app/shared/models/landing-page.model.ts`:

```typescript
/** Inhalt der Startseite, wie ihn das Backend liefert (Roh-HTML mit Tokens). */
export interface LandingPage {
  bodyHtml: string;
  updatedAt: string | null;
}

/** Eine im Editor einfügbare Platzhalter-Kachel. */
export interface LandingPlaceholder {
  token: string;
  label: string;
  group: string;
}

/** Token → Wert für den angemeldeten Nutzer. */
export type LandingContext = Record<string, string>;
```

- [ ] **Step 4: Write the utility**

Create `frontend/src/app/shared/landing-token.util.ts`:

```typescript
import { LandingContext, LandingPlaceholder } from './models/landing-page.model';

/** Ein Token beliebiger Familie, z.B. {{person.firstName}} oder {{stunden.bilanz}}. */
export const TOKEN_RE = /\{\{[a-z]+\.[A-Za-z]+\}\}/g;

/** Zeichen, das für einen fehlenden oder leeren Wert steht. */
const MISSING = '–';

/**
 * Editor-Darstellung eines Platzhalters. Klasse und data-Attribut sind
 * bewusst dieselben wie beim Mail-Editor: beide nutzen denselben Quill-Blot.
 */
export function pillSpan(token: string, label: string): string {
  return `<span class="mail-token" data-token="${token}">${label}</span>`;
}

/** Gespeichertes HTML (rohe Tokens) -> Editor-HTML (Pillen). */
export function tokensToPills(html: string, placeholders: LandingPlaceholder[]): string {
  const labels = new Map<string, string>();
  placeholders.forEach((p) => labels.set(p.token, p.label));
  return html.replace(TOKEN_RE, (token) => pillSpan(token, labels.get(token) ?? token));
}

/**
 * Editor-HTML -> gespeichertes HTML mit rohen Tokens. DOM-basiert, weil Quill
 * die Pille in verschachtelte Spans mit FEFF-Schutzzeichen einbettet, die ein
 * regulärer Ausdruck nicht zuverlässig zurückbaut.
 */
export function pillsToTokens(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  doc.querySelectorAll('[data-token]').forEach((el) => {
    el.replaceWith(doc.createTextNode(el.getAttribute('data-token') ?? ''));
  });
  return doc.body.innerHTML.replace(/﻿/g, '');
}

/**
 * Gespeichertes HTML -> Anzeige-HTML. Fehlende und leere Werte werden zu einem
 * Gedankenstrich: die Startseite soll auch dann lesbar bleiben, wenn ein
 * Datensatz fehlt oder der Kontext gar nicht geladen werden konnte.
 */
export function renderWithContext(html: string, context: LandingContext): string {
  return html.replace(TOKEN_RE, (token) => {
    const value = context[token];
    return value ? value : MISSING;
  });
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-token.util.spec.ts`
Expected: PASS, 9 Tests.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/models/landing-page.model.ts frontend/src/app/shared/landing-token.util.ts frontend/src/app/shared/landing-token.util.spec.ts
git commit -m "feat(fe): Token-Utility fuer die Startseite"
```

---

## Task 8: LandingPageService

**Files:**
- Create: `frontend/src/app/shared/services/landing-page.service.ts`
- Test: `frontend/src/app/shared/services/landing-page.service.spec.ts`

**Interfaces:**
- Consumes: `LandingPage`, `LandingPlaceholder`, `LandingContext` (Task 7); `ApiService` aus `core/services/api.service`.
- Produces: `LandingPageService` mit `get(): Observable<LandingPage>`, `save(bodyHtml: string): Observable<LandingPage>`, `placeholders(): Observable<LandingPlaceholder[]>`, `context(): Observable<LandingContext>`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/services/landing-page.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { LandingPageService } from './landing-page.service';
import { ApiService } from '../../core/services/api.service';

describe('LandingPageService', () => {
  let service: LandingPageService;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', ['get', 'put']);
    TestBed.configureTestingModule({
      providers: [LandingPageService, { provide: ApiService, useValue: api }],
    });
    service = TestBed.inject(LandingPageService);
  });

  it('liest den Inhalt von /landing-page', () => {
    api.get.and.returnValue(of({ bodyHtml: '<p>x</p>', updatedAt: null }));

    service.get().subscribe();

    expect(api.get).toHaveBeenCalledWith('/landing-page');
  });

  it('speichert den Inhalt per PUT auf /landing-page', () => {
    api.put.and.returnValue(of({ bodyHtml: '<p>neu</p>', updatedAt: null }));

    service.save('<p>neu</p>').subscribe();

    expect(api.put).toHaveBeenCalledWith('/landing-page', { bodyHtml: '<p>neu</p>' });
  });

  it('liest die Kacheln von /landing-page/placeholders', () => {
    api.get.and.returnValue(of([]));

    service.placeholders().subscribe();

    expect(api.get).toHaveBeenCalledWith('/landing-page/placeholders');
  });

  it('liest die Werte von /landing-page/context', () => {
    api.get.and.returnValue(of({}));

    service.context().subscribe();

    expect(api.get).toHaveBeenCalledWith('/landing-page/context');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-page.service.spec.ts`
Expected: FAIL — das Modul `./landing-page.service` existiert nicht.

- [ ] **Step 3: Write the service**

Create `frontend/src/app/shared/services/landing-page.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { LandingContext, LandingPage, LandingPlaceholder } from '../models/landing-page.model';

@Injectable({ providedIn: 'root' })
export class LandingPageService {
  constructor(private api: ApiService) {}

  get(): Observable<LandingPage> {
    return this.api.get<LandingPage>('/landing-page');
  }

  save(bodyHtml: string): Observable<LandingPage> {
    return this.api.put<LandingPage>('/landing-page', { bodyHtml });
  }

  placeholders(): Observable<LandingPlaceholder[]> {
    return this.api.get<LandingPlaceholder[]>('/landing-page/placeholders');
  }

  context(): Observable<LandingContext> {
    return this.api.get<LandingContext>('/landing-page/context');
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-page.service.spec.ts`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/services/landing-page.service.ts frontend/src/app/shared/services/landing-page.service.spec.ts
git commit -m "feat(fe): Service fuer die Startseiten-Endpunkte"
```

---

## Task 9: Nutzeransicht auf Route ''

**Files:**
- Create: `frontend/src/app/landing/landing.component.ts`
- Create: `frontend/src/app/landing/landing.component.html`
- Create: `frontend/src/app/landing/landing.component.scss`
- Modify: `frontend/src/app/app.routes.ts:110`
- Test: `frontend/src/app/landing/landing.component.spec.ts`

**Interfaces:**
- Consumes: `LandingPageService` (Task 8), `renderWithContext` (Task 7).
- Produces: `LandingComponent` mit den öffentlichen Feldern `renderedHtml: SafeHtml`, `isEmpty: boolean`, `loading: boolean`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/landing/landing.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { LandingComponent } from './landing.component';
import { LandingPageService } from '../shared/services/landing-page.service';

describe('LandingComponent', () => {
  let fixture: ComponentFixture<LandingComponent>;
  let service: jasmine.SpyObj<LandingPageService>;

  function setup(): void {
    TestBed.configureTestingModule({
      imports: [LandingComponent],
      providers: [{ provide: LandingPageService, useValue: service }],
    });
    fixture = TestBed.createComponent(LandingComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj('LandingPageService', ['get', 'context']);
  });

  it('zeigt den Inhalt mit ersetzten Tokens', () => {
    service.get.and.returnValue(of({ bodyHtml: '<p>Hallo {{person.firstName}}</p>', updatedAt: null }));
    service.context.and.returnValue(of({ '{{person.firstName}}': 'Anna' }));

    setup();

    expect(fixture.nativeElement.textContent).toContain('Hallo Anna');
  });

  it('zeigt den Leerzustand, wenn kein Inhalt gepflegt ist', () => {
    service.get.and.returnValue(of({ bodyHtml: '', updatedAt: null }));
    service.context.and.returnValue(of({}));

    setup();

    expect(fixture.componentInstance.isEmpty).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Noch keine Startseite');
  });

  it('zeigt den Inhalt auch dann, wenn der Kontext fehlschlägt', () => {
    service.get.and.returnValue(of({ bodyHtml: '<p>Fix {{person.firstName}}</p>', updatedAt: null }));
    service.context.and.returnValue(throwError(() => new Error('boom')));

    setup();

    expect(fixture.nativeElement.textContent).toContain('Fix');
    expect(fixture.nativeElement.textContent).toContain('–');
  });

  it('zeigt den Leerzustand, wenn der Inhalt nicht geladen werden kann', () => {
    service.get.and.returnValue(throwError(() => new Error('boom')));
    service.context.and.returnValue(of({}));

    setup();

    expect(fixture.componentInstance.isEmpty).toBeTrue();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing.component.spec.ts`
Expected: FAIL — das Modul `./landing.component` existiert nicht.

- [ ] **Step 3: Write the component**

Create `frontend/src/app/landing/landing.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { catchError, forkJoin, of } from 'rxjs';
import { LandingPageService } from '../shared/services/landing-page.service';
import { renderWithContext } from '../shared/landing-token.util';
import { LandingContext, LandingPage } from '../shared/models/landing-page.model';

@Component({
  selector: 'app-landing',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './landing.component.html',
  styleUrl: './landing.component.scss',
})
export class LandingComponent implements OnInit {
  renderedHtml: SafeHtml;
  isEmpty = false;
  loading = true;

  constructor(
    private landingPageService: LandingPageService,
    private sanitizer: DomSanitizer,
  ) {
    this.renderedHtml = this.sanitizer.bypassSecurityTrustHtml('');
  }

  ngOnInit(): void {
    // Beide Fehler werden aufgefangen: die Startseite ist der Einstiegspunkt in
    // die App und darf an einem fehlgeschlagenen Request nicht scheitern.
    forkJoin({
      page: this.landingPageService.get().pipe(
        catchError(() => of<LandingPage>({ bodyHtml: '', updatedAt: null })),
      ),
      context: this.landingPageService.context().pipe(
        catchError(() => of<LandingContext>({})),
      ),
    }).subscribe(({ page, context }) => {
      const body = page.bodyHtml ?? '';
      this.isEmpty = body.trim().length === 0;
      // Das Backend hat beim Speichern bereits sanitisiert.
      this.renderedHtml = this.sanitizer.bypassSecurityTrustHtml(
        renderWithContext(body, context),
      );
      this.loading = false;
    });
  }
}
```

Create `frontend/src/app/landing/landing.component.html`:

```html
<div class="landing">
  @if (loading) {
    <mat-spinner diameter="32"></mat-spinner>
  } @else if (isEmpty) {
    <div class="empty-state">
      <mat-icon>article</mat-icon>
      <p>Noch keine Startseite gestaltet.</p>
      <p class="hint">Ein Administrator kann sie unter Einstellungen → Startseite anlegen.</p>
    </div>
  } @else {
    <div class="content" [innerHTML]="renderedHtml"></div>
  }
</div>
```

Create `frontend/src/app/landing/landing.component.scss`:

```scss
.landing {
  padding: 1.5rem;
  max-width: 60rem;
  margin: 0 auto;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 3rem 1rem;
  color: rgba(0, 0, 0, 0.6);
  text-align: center;

  mat-icon {
    font-size: 2.5rem;
    width: 2.5rem;
    height: 2.5rem;
  }

  .hint {
    font-size: 0.85rem;
  }
}

.content {
  // Redaktioneller Inhalt: Bilder und Tabellen dürfen den Rahmen nicht sprengen.
  img {
    max-width: 100%;
    height: auto;
  }

  table {
    width: 100%;
    border-collapse: collapse;
  }

  td,
  th {
    border: 1px solid #e2e2e6;
    padding: 0.35rem 0.5rem;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing.component.spec.ts`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Point the root route at the component**

In `frontend/src/app/app.routes.ts` Zeile 110 ersetzen:

```typescript
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./landing/landing.component').then(m => m.LandingComponent),
  },
```

- [ ] **Step 6: Run the whole frontend suite to check for regressions**

Run: `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS. Der `cooking`-Menüpunkt und dessen Route bleiben unverändert.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/landing frontend/src/app/app.routes.ts
git commit -m "feat(fe): Startseite als Einstiegsansicht auf Route ''"
```

---

## Task 10: Editor-Grundgerüst mit Tabs, Quill, Laden und Speichern

**Files:**
- Create: `frontend/src/app/settings/landing-page/quill-web.config.ts`
- Create: `frontend/src/app/settings/landing-page/landing-page-editor.component.ts`
- Create: `frontend/src/app/settings/landing-page/landing-page-editor.component.html`
- Create: `frontend/src/app/settings/landing-page/landing-page-editor.component.scss`
- Modify: `frontend/src/app/app.routes.ts` (Kind-Route unter `settings`)
- Modify: `frontend/src/app/app.component.html:63-66` (Menüpunkt darunter einfügen)
- Test: `frontend/src/app/settings/landing-page/landing-page-editor.component.spec.ts`

**Interfaces:**
- Consumes: `LandingPageService` (Task 8), `tokensToPills`/`pillsToTokens` (Task 7), `NotificationService` aus `shared/services/notification.service`.
- Produces: `LandingPageEditorComponent` mit `form: FormGroup` (Control `bodyHtml`), `placeholders: LandingPlaceholder[]`, `save(): void`, `onEditorCreated(editor: any): void`; Konstanten `WEB_QUILL_TOOLBAR` und `configureQuillForWebOutput()`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/settings/landing-page/landing-page-editor.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { LandingPageEditorComponent } from './landing-page-editor.component';
import { LandingPageService } from '../../shared/services/landing-page.service';
import { NotificationService } from '../../shared/services/notification.service';

describe('LandingPageEditorComponent', () => {
  let fixture: ComponentFixture<LandingPageEditorComponent>;
  let component: LandingPageEditorComponent;
  let service: jasmine.SpyObj<LandingPageService>;
  let notify: jasmine.SpyObj<NotificationService>;

  beforeEach(() => {
    service = jasmine.createSpyObj('LandingPageService', ['get', 'save', 'placeholders', 'context']);
    notify = jasmine.createSpyObj('NotificationService', ['success', 'error', 'extractError']);
    service.get.and.returnValue(of({ bodyHtml: '<p>Hallo {{person.firstName}}</p>', updatedAt: null }));
    service.placeholders.and.returnValue(of([
      { token: '{{person.firstName}}', label: 'Vorname', group: 'person' },
    ]));
    service.context.and.returnValue(of({ '{{person.firstName}}': 'Anna' }));
    service.save.and.returnValue(of({ bodyHtml: '<p>x</p>', updatedAt: null }));

    TestBed.configureTestingModule({
      imports: [LandingPageEditorComponent, NoopAnimationsModule],
      providers: [
        { provide: LandingPageService, useValue: service },
        { provide: NotificationService, useValue: notify },
      ],
    });
    fixture = TestBed.createComponent(LandingPageEditorComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('lädt den Inhalt und wandelt Tokens in Pillen', () => {
    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
    expect(component.form.value.bodyHtml).toContain('>Vorname<');
  });

  it('lädt die Platzhalter-Kacheln', () => {
    expect(component.placeholders.length).toBe(1);
    expect(component.placeholders[0].token).toBe('{{person.firstName}}');
  });

  it('speichert mit zurückgewandelten Tokens statt Pillen', () => {
    component.save();

    expect(service.save).toHaveBeenCalledWith('<p>Hallo {{person.firstName}}</p>');
  });

  it('meldet den Erfolg über den Notification-Service', () => {
    component.save();

    expect(notify.success).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-page-editor.component.spec.ts`
Expected: FAIL — das Modul `./landing-page-editor.component` existiert nicht.

- [ ] **Step 3: Write the Quill configuration**

Create `frontend/src/app/settings/landing-page/quill-web.config.ts`:

```typescript
import Quill from 'quill';
import { registerMailTokenBlot } from '../mail/mail-template-editor/mail-token.blot';

/**
 * Die Startseite wird im Browser gerendert, nicht in einem Mail-Client — die
 * Toolbar darf daher mehr anbieten als EMAIL_SAFE_QUILL_TOOLBAR: Überschriften,
 * Listen und Einzug funktionieren hier über Quills eigene Stylesheet-Klassen.
 *
 * Der Platzhalter-Blot ist derselbe wie beim Mail-Editor: er ist rein
 * data-getrieben und kennt keine Mail-Besonderheiten.
 */
let configured = false;

export function configureQuillForWebOutput(): void {
  if (configured) {
    return;
  }
  configured = true;
  registerMailTokenBlot();
}

export const WEB_QUILL_TOOLBAR = [
  [{ header: [1, 2, 3, false] }],
  ['bold', 'italic', 'underline', 'strike'],
  [{ color: [] }, { background: [] }],
  [{ list: 'ordered' }, { list: 'bullet' }],
  [{ indent: '-1' }, { indent: '+1' }],
  [{ align: [] }],
  ['blockquote'],
  ['link', 'image'],
  ['clean'],
];
```

- [ ] **Step 4: Write the component**

Create `frontend/src/app/settings/landing-page/landing-page-editor.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { QuillModule } from 'ngx-quill';
import { LandingPageService } from '../../shared/services/landing-page.service';
import { NotificationService } from '../../shared/services/notification.service';
import { LandingPlaceholder } from '../../shared/models/landing-page.model';
import { pillsToTokens, tokensToPills } from '../../shared/landing-token.util';
import { WEB_QUILL_TOOLBAR, configureQuillForWebOutput } from './quill-web.config';

@Component({
  selector: 'app-landing-page-editor',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatButtonModule, MatIconModule, MatTabsModule, MatTooltipModule,
    QuillModule,
  ],
  templateUrl: './landing-page-editor.component.html',
  styleUrl: './landing-page-editor.component.scss',
})
export class LandingPageEditorComponent implements OnInit {
  readonly quillModules = { toolbar: WEB_QUILL_TOOLBAR };

  placeholders: LandingPlaceholder[] = [];
  quillInstance: any = null;

  form = new FormGroup({
    bodyHtml: new FormControl(''),
  });

  constructor(
    private landingPageService: LandingPageService,
    private notify: NotificationService,
  ) {
    configureQuillForWebOutput();
  }

  ngOnInit(): void {
    // Erst die Kacheln, dann der Inhalt: tokensToPills braucht die
    // Beschriftungen, sonst stünde der rohe Token in der Pille.
    this.landingPageService.placeholders().subscribe((tiles) => {
      this.placeholders = tiles;
      this.loadContent();
    });
  }

  private loadContent(): void {
    this.landingPageService.get().subscribe((page) => {
      this.form.patchValue({
        bodyHtml: tokensToPills(page.bodyHtml ?? '', this.placeholders),
      });
    });
  }

  onEditorCreated(editor: any): void {
    this.quillInstance = editor;
  }

  save(): void {
    const bodyHtml = pillsToTokens(this.form.value.bodyHtml ?? '');
    this.landingPageService.save(bodyHtml).subscribe({
      next: () => this.notify.success('Startseite gespeichert'),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
```

Create `frontend/src/app/settings/landing-page/landing-page-editor.component.html`:

```html
<div class="editor-page">
  <div class="header">
    <h2>Startseite</h2>
    <button mat-flat-button color="primary" (click)="save()">
      <mat-icon>save</mat-icon>
      Speichern
    </button>
  </div>

  <mat-tab-group>
    <mat-tab label="Bearbeiten">
      <div class="tab-body">
        <form [formGroup]="form">
          <quill-editor
            formControlName="bodyHtml"
            [modules]="quillModules"
            (onEditorCreated)="onEditorCreated($event)"
          ></quill-editor>
        </form>
      </div>
    </mat-tab>
    <mat-tab label="Vorschau">
      <div class="tab-body">
        <p class="hint">Die Vorschau folgt in einem späteren Schritt.</p>
      </div>
    </mat-tab>
  </mat-tab-group>
</div>
```

Create `frontend/src/app/settings/landing-page/landing-page-editor.component.scss`:

```scss
.editor-page {
  padding: 1rem;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 0.5rem;
}

.tab-body {
  padding-top: 1rem;
}

.hint {
  color: rgba(0, 0, 0, 0.6);
  font-size: 0.85rem;
}

// Gleiche Pillen-Darstellung wie im Mail-Editor — beide nutzen denselben Blot.
::ng-deep .mail-token {
  background: #eef0fb;
  color: #3f51b5;
  border: 1px solid #c9cff2;
  border-radius: 12px;
  padding: 0.05rem 0.5rem;
  font-size: 0.85em;
  font-weight: 600;
  white-space: nowrap;
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-page-editor.component.spec.ts`
Expected: PASS, 4 Tests.

- [ ] **Step 6: Add the route**

In `frontend/src/app/app.routes.ts` im `settings`-Kinderblock nach dem
`mail`-Eintrag ergänzen:

```typescript
      {
        path: 'landing-page',
        loadComponent: () =>
          import('./settings/landing-page/landing-page-editor.component').then(
            m => m.LandingPageEditorComponent
          ),
      },
```

- [ ] **Step 7: Add the menu entry**

In `frontend/src/app/app.component.html` direkt nach dem Mail-Eintrag
(Zeile 66) einfügen:

```html
            <a mat-list-item routerLink="/settings/landing-page" routerLinkActive="active">
              <mat-icon matListItemIcon>home</mat-icon>
              <span matListItemTitle>Startseite</span>
            </a>
```

- [ ] **Step 8: Run the whole frontend suite**

Run: `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/settings/landing-page frontend/src/app/app.routes.ts frontend/src/app/app.component.html
git commit -m "feat(fe): Editor-Grundgeruest fuer die Startseite"
```

---

## Task 11: HTML-Quelltext-Umschalter

**Files:**
- Modify: `frontend/src/app/settings/landing-page/landing-page-editor.component.ts`
- Modify: `frontend/src/app/settings/landing-page/landing-page-editor.component.html`
- Modify: `frontend/src/app/settings/landing-page/landing-page-editor.component.scss`
- Test: `frontend/src/app/settings/landing-page/landing-page-editor.component.spec.ts` (anhängen)

**Interfaces:**
- Consumes: `LandingPageEditorComponent` aus Task 10.
- Produces: `sourceMode: boolean`, `sourceHtml: string`, `toggleSourceMode(): void`. Im Quelltextmodus enthält `sourceHtml` rohe `{{…}}`-Tokens; beim Zurückschalten werden daraus wieder Pillen und `form.bodyHtml` wird aktualisiert.

- [ ] **Step 1: Write the failing test**

Append to `frontend/src/app/settings/landing-page/landing-page-editor.component.spec.ts` (innerhalb des `describe`-Blocks):

```typescript
  it('zeigt im Quelltextmodus rohe Tokens statt Pillen', () => {
    component.toggleSourceMode();

    expect(component.sourceMode).toBeTrue();
    expect(component.sourceHtml).toBe('<p>Hallo {{person.firstName}}</p>');
  });

  it('übernimmt Änderungen aus dem Quelltext zurück in den Editor', () => {
    component.toggleSourceMode();
    component.sourceHtml = '<p>Neu {{person.firstName}}</p>';
    component.toggleSourceMode();

    expect(component.sourceMode).toBeFalse();
    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
    expect(component.form.value.bodyHtml).toContain('Neu');
  });

  it('speichert den im Quelltext bearbeiteten Inhalt korrekt', () => {
    component.toggleSourceMode();
    component.sourceHtml = '<p>Aus dem Quelltext</p>';
    component.toggleSourceMode();

    component.save();

    expect(service.save).toHaveBeenCalledWith('<p>Aus dem Quelltext</p>');
  });

  it('speichert auch dann korrekt, wenn der Quelltextmodus noch aktiv ist', () => {
    component.toggleSourceMode();
    component.sourceHtml = '<p>Direkt gespeichert</p>';

    component.save();

    expect(service.save).toHaveBeenCalledWith('<p>Direkt gespeichert</p>');
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-page-editor.component.spec.ts`
Expected: FAIL — `toggleSourceMode` ist keine Funktion.

- [ ] **Step 3: Implement the toggle**

In `landing-page-editor.component.ts` ergänzen — Felder nach `quillInstance`:

```typescript
  sourceMode = false;
  sourceHtml = '';
```

Methode nach `onEditorCreated` einfügen:

```typescript
  /**
   * Wechselt zwischen WYSIWYG und Quelltext. Beide Ansichten arbeiten auf
   * demselben Inhalt, nur in unterschiedlicher Darstellung: im Editor als
   * Pillen, im Quelltext als rohe Tokens.
   */
  toggleSourceMode(): void {
    if (this.sourceMode) {
      this.form.patchValue({ bodyHtml: tokensToPills(this.sourceHtml, this.placeholders) });
      this.sourceMode = false;
    } else {
      this.sourceHtml = pillsToTokens(this.form.value.bodyHtml ?? '');
      this.sourceMode = true;
    }
  }
```

und `save()` ersetzen, damit der Quelltextmodus nicht umgangen werden kann:

```typescript
  save(): void {
    // Im Quelltextmodus ist sourceHtml die Wahrheit, sonst das Formularfeld.
    const bodyHtml = this.sourceMode
      ? this.sourceHtml
      : pillsToTokens(this.form.value.bodyHtml ?? '');
    this.landingPageService.save(bodyHtml).subscribe({
      next: () => this.notify.success('Startseite gespeichert'),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
```

- [ ] **Step 4: Update the template**

In `landing-page-editor.component.html` den Inhalt des Bearbeiten-Tabs ersetzen:

```html
    <mat-tab label="Bearbeiten">
      <div class="tab-body">
        <div class="mode-bar">
          <button mat-stroked-button (click)="toggleSourceMode()">
            <mat-icon>{{ sourceMode ? 'visibility' : 'code' }}</mat-icon>
            {{ sourceMode ? 'Zurück zum Editor' : 'HTML-Quelltext' }}
          </button>
        </div>

        @if (sourceMode) {
          <textarea
            class="source-area"
            spellcheck="false"
            [(ngModel)]="sourceHtml"
            [ngModelOptions]="{ standalone: true }"
          ></textarea>
        } @else {
          <form [formGroup]="form">
            <quill-editor
              formControlName="bodyHtml"
              [modules]="quillModules"
              (onEditorCreated)="onEditorCreated($event)"
            ></quill-editor>
          </form>
        }
      </div>
    </mat-tab>
```

Dafür in `landing-page-editor.component.ts` `FormsModule` importieren:

```typescript
import { FormControl, FormGroup, FormsModule, ReactiveFormsModule } from '@angular/forms';
```

und in der `imports`-Liste der Komponente `FormsModule` ergänzen.

- [ ] **Step 5: Add the textarea styling**

In `landing-page-editor.component.scss` ergänzen:

```scss
.mode-bar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 0.5rem;
}

.source-area {
  width: 100%;
  min-height: 20rem;
  font-family: ui-monospace, "Cascadia Mono", Consolas, monospace;
  font-size: 0.85rem;
  line-height: 1.5;
  padding: 0.75rem;
  border: 1px solid #e2e2e6;
  border-radius: 4px;
  resize: vertical;
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-page-editor.component.spec.ts`
Expected: PASS, 8 Tests.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/settings/landing-page
git commit -m "feat(fe): HTML-Quelltext-Umschalter im Startseiten-Editor"
```

---

## Task 12: Platzhalter-Kacheln mit Klick-Einfügen und Drag&Drop

**Files:**
- Modify: `frontend/src/app/settings/landing-page/landing-page-editor.component.ts`
- Modify: `frontend/src/app/settings/landing-page/landing-page-editor.component.html`
- Modify: `frontend/src/app/settings/landing-page/landing-page-editor.component.scss`
- Test: `frontend/src/app/settings/landing-page/landing-page-editor.component.spec.ts` (anhängen)

**Interfaces:**
- Consumes: `LandingPageEditorComponent` aus Task 11.
- Produces: `groupedPlaceholders: { group: string; label: string; tiles: LandingPlaceholder[] }[]`, `insertPlaceholder(tile: LandingPlaceholder): void`, `onChipDragStart(event: DragEvent, tile: LandingPlaceholder): void`, `onEditorDragOver(event: DragEvent): void`, `onEditorDrop(event: DragEvent): void`.

- [ ] **Step 1: Write the failing test**

Append to `frontend/src/app/settings/landing-page/landing-page-editor.component.spec.ts` (innerhalb des `describe`-Blocks):

```typescript
  it('gruppiert die Kacheln nach Familie mit deutscher Überschrift', () => {
    expect(component.groupedPlaceholders.length).toBe(1);
    expect(component.groupedPlaceholders[0].group).toBe('person');
    expect(component.groupedPlaceholders[0].label).toBe('Person');
    expect(component.groupedPlaceholders[0].tiles.length).toBe(1);
  });

  it('hängt einen Platzhalter an, wenn noch kein Quill-Editor existiert', () => {
    component.quillInstance = null;
    component.form.patchValue({ bodyHtml: '<p>Text</p>' });

    component.insertPlaceholder({ token: '{{person.firstName}}', label: 'Vorname', group: 'person' });

    expect(component.form.value.bodyHtml).toContain('data-token="{{person.firstName}}"');
  });

  it('fügt einen Platzhalter an der Cursorposition ein', () => {
    const insertEmbed = jasmine.createSpy('insertEmbed');
    component.quillInstance = {
      insertEmbed,
      setSelection: jasmine.createSpy('setSelection'),
      getSelection: () => ({ index: 3, length: 0 }),
      getLength: () => 10,
      root: { innerHTML: '<p>x</p>' },
    };

    component.insertPlaceholder({ token: '{{stunden.bilanz}}', label: 'Bilanz', group: 'stunden' });

    expect(insertEmbed).toHaveBeenCalledWith(3, 'mail-token', {
      token: '{{stunden.bilanz}}',
      label: 'Bilanz',
    });
  });

  it('legt beim Drag den Token in die DataTransfer-Nutzlast', () => {
    const setData = jasmine.createSpy('setData');
    const event = { dataTransfer: { setData, effectAllowed: '' } } as unknown as DragEvent;

    component.onChipDragStart(event, { token: '{{person.firstName}}', label: 'Vorname', group: 'person' });

    expect(setData).toHaveBeenCalledWith('application/x-landing-token', '{{person.firstName}}');
  });

  it('ignoriert einen Drop ohne passenden Token', () => {
    const insertEmbed = jasmine.createSpy('insertEmbed');
    component.quillInstance = { insertEmbed, setSelection: () => {}, getLength: () => 1, root: { innerHTML: '' } };
    const event = {
      dataTransfer: { getData: () => '{{unbekannt.feld}}' },
      preventDefault: () => {},
      clientX: 0,
      clientY: 0,
    } as unknown as DragEvent;

    component.onEditorDrop(event);

    expect(insertEmbed).not.toHaveBeenCalled();
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-page-editor.component.spec.ts`
Expected: FAIL — `groupedPlaceholders` ist undefined.

- [ ] **Step 3: Implement grouping and insertion**

In `landing-page-editor.component.ts` die bestehende Import-Zeile für
`landing-token.util` durch die folgende **ersetzen** und `Quill` zusätzlich
importieren (keine doppelte Import-Anweisung anlegen):

```typescript
import Quill from 'quill';
import { pillSpan, pillsToTokens, tokensToPills } from '../../shared/landing-token.util';

const DRAG_MIME = 'application/x-landing-token';

/** Deutsche Überschriften der Token-Familien. */
const GROUP_LABELS: Record<string, string> = {
  person: 'Person',
  stunden: 'Stunden',
  kochdienst: 'Kochdienst',
};
```

Feld ergänzen:

```typescript
  groupedPlaceholders: { group: string; label: string; tiles: LandingPlaceholder[] }[] = [];
```

In `ngOnInit` nach `this.placeholders = tiles;` einfügen:

```typescript
      this.groupedPlaceholders = this.groupTiles(tiles);
```

Und diese Methoden ergänzen:

```typescript
  private groupTiles(tiles: LandingPlaceholder[]): { group: string; label: string; tiles: LandingPlaceholder[] }[] {
    const byGroup = new Map<string, LandingPlaceholder[]>();
    tiles.forEach((tile) => {
      const list = byGroup.get(tile.group) ?? [];
      list.push(tile);
      byGroup.set(tile.group, list);
    });
    return Array.from(byGroup.entries()).map(([group, groupTiles]) => ({
      group,
      label: GROUP_LABELS[group] ?? group,
      tiles: groupTiles,
    }));
  }

  private insertPillAt(index: number, tile: LandingPlaceholder): void {
    this.quillInstance.insertEmbed(index, 'mail-token', { token: tile.token, label: tile.label });
    this.quillInstance.setSelection(index + 1, 0);
    this.form.patchValue({ bodyHtml: this.quillInstance.root?.innerHTML ?? '' });
  }

  /** Einfügen an der Cursorposition; ohne lebenden Editor wird angehängt. */
  insertPlaceholder(tile: LandingPlaceholder): void {
    if (this.quillInstance) {
      const selection = this.quillInstance.getSelection?.();
      const index = selection ? selection.index : this.quillInstance.getLength();
      this.insertPillAt(index, tile);
    } else {
      const current = this.form.value.bodyHtml ?? '';
      this.form.patchValue({ bodyHtml: current + pillSpan(tile.token, tile.label) });
    }
  }

  onChipDragStart(event: DragEvent, tile: LandingPlaceholder): void {
    event.dataTransfer?.setData(DRAG_MIME, tile.token);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'copy';
    }
  }

  onEditorDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  onEditorDrop(event: DragEvent): void {
    const token = event.dataTransfer?.getData(DRAG_MIME);
    if (!token || !this.quillInstance) {
      return;
    }
    const tile = this.placeholders.find((p) => p.token === token);
    if (!tile) {
      return;
    }
    event.preventDefault();
    this.insertPillAt(this.dropIndex(event), tile);
  }

  /** Bestmögliche Cursorposition aus dem Drop-Punkt; sonst ans Dokumentende. */
  private dropIndex(event: DragEvent): number {
    const end = Math.max(0, this.quillInstance.getLength() - 1);
    try {
      const doc: any = document;
      const range = doc.caretRangeFromPoint?.(event.clientX, event.clientY);
      if (!range) {
        return end;
      }
      const blot = Quill.find(range.startContainer, true);
      if (!blot) {
        return end;
      }
      return this.quillInstance.getIndex(blot) + range.startOffset;
    } catch {
      return end;
    }
  }
```

- [ ] **Step 4: Add the tiles to the template**

In `landing-page-editor.component.html` den Inhalt des Bearbeiten-Tabs in ein
zweispaltiges Layout setzen: die `mode-bar` bleibt oben, darunter

```html
        <div class="edit-layout">
          <aside class="tiles">
            @for (group of groupedPlaceholders; track group.group) {
              <h4>{{ group.label }}</h4>
              <div class="chips">
                @for (tile of group.tiles; track tile.token) {
                  <button
                    type="button"
                    class="chip"
                    draggable="true"
                    [matTooltip]="tile.token"
                    (dragstart)="onChipDragStart($event, tile)"
                    (click)="insertPlaceholder(tile)"
                  >
                    {{ tile.label }}
                  </button>
                }
              </div>
            }
          </aside>

          <div class="editor-area" (dragover)="onEditorDragOver($event)" (drop)="onEditorDrop($event)">
            @if (sourceMode) {
              <textarea
                class="source-area"
                spellcheck="false"
                [(ngModel)]="sourceHtml"
                [ngModelOptions]="{ standalone: true }"
              ></textarea>
            } @else {
              <form [formGroup]="form">
                <quill-editor
                  formControlName="bodyHtml"
                  [modules]="quillModules"
                  (onEditorCreated)="onEditorCreated($event)"
                ></quill-editor>
              </form>
            }
          </div>
        </div>
```

- [ ] **Step 5: Add the layout styling**

In `landing-page-editor.component.scss` ergänzen:

```scss
.edit-layout {
  display: flex;
  gap: 1rem;
  align-items: flex-start;
}

.tiles {
  flex: 0 0 12rem;

  h4 {
    margin: 0.75rem 0 0.35rem;
    font-size: 0.8rem;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: rgba(0, 0, 0, 0.6);
  }
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.chip {
  background: #eef0fb;
  color: #3f51b5;
  border: 1px solid #c9cff2;
  border-radius: 12px;
  padding: 0.15rem 0.6rem;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: grab;
}

.editor-area {
  flex: 1 1 auto;
  min-width: 0;
}

// Auf schmalen Geräten stehen die Kacheln über dem Editor.
@media (max-width: 700px) {
  .edit-layout {
    flex-direction: column;
  }

  .tiles {
    flex: 1 1 auto;
    width: 100%;
  }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-page-editor.component.spec.ts`
Expected: PASS, 13 Tests.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/settings/landing-page
git commit -m "feat(fe): Platzhalter-Kacheln im Startseiten-Editor"
```

---

## Task 13: Vorschau-Tab mit echten Werten

**Files:**
- Modify: `frontend/src/app/settings/landing-page/landing-page-editor.component.ts`
- Modify: `frontend/src/app/settings/landing-page/landing-page-editor.component.html`
- Modify: `frontend/src/app/settings/landing-page/landing-page-editor.component.scss`
- Test: `frontend/src/app/settings/landing-page/landing-page-editor.component.spec.ts` (anhängen)

**Interfaces:**
- Consumes: `LandingPageEditorComponent` aus Task 12, `LandingPageService.context()` (Task 8), `renderWithContext` (Task 7).
- Produces: `previewHtml: SafeHtml`, `context: LandingContext`, `refreshPreview(): void`.

- [ ] **Step 1: Write the failing test**

Append to `frontend/src/app/settings/landing-page/landing-page-editor.component.spec.ts` (innerhalb des `describe`-Blocks):

```typescript
  it('lädt die Kontextwerte des angemeldeten Nutzers', () => {
    expect(service.context).toHaveBeenCalled();
    expect(component.context['{{person.firstName}}']).toBe('Anna');
  });

  it('rendert die Vorschau mit den echten Werten', () => {
    component.refreshPreview();
    fixture.detectChanges();

    const preview: HTMLElement = fixture.nativeElement.querySelector('.preview-box');
    expect(preview.textContent).toContain('Hallo Anna');
  });

  it('rendert die Vorschau aus dem Quelltext, wenn dieser aktiv ist', () => {
    component.toggleSourceMode();
    component.sourceHtml = '<p>Quelltext {{person.firstName}}</p>';

    component.refreshPreview();
    fixture.detectChanges();

    const preview: HTMLElement = fixture.nativeElement.querySelector('.preview-box');
    expect(preview.textContent).toContain('Quelltext Anna');
  });
```

> Der Vorschau-Tab ist nur gerendert, wenn er aktiv ist. Damit der Test das
> `.preview-box`-Element findet, muss der Tab-Inhalt eifrig gerendert werden —
> im Template daher **kein** `ng-template matTabContent` verwenden.

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-page-editor.component.spec.ts`
Expected: FAIL — `refreshPreview` ist keine Funktion.

- [ ] **Step 3: Implement the preview**

In `landing-page-editor.component.ts` die beiden bestehenden Import-Zeilen für
`landing-page.model` und `landing-token.util` durch die folgenden **ersetzen**
und den Sanitizer-Import ergänzen (keine doppelten Import-Anweisungen anlegen):

```typescript
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { LandingContext, LandingPlaceholder } from '../../shared/models/landing-page.model';
import { pillSpan, pillsToTokens, renderWithContext, tokensToPills } from '../../shared/landing-token.util';
```

Felder ergänzen:

```typescript
  context: LandingContext = {};
  previewHtml: SafeHtml;
```

Konstruktor um den Sanitizer erweitern und `previewHtml` initialisieren:

```typescript
  constructor(
    private landingPageService: LandingPageService,
    private notify: NotificationService,
    private sanitizer: DomSanitizer,
  ) {
    configureQuillForWebOutput();
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml('');
  }
```

In `ngOnInit` ergänzen:

```typescript
    // Fällt der Kontext aus, bleibt die Vorschau nutzbar — renderWithContext
    // setzt für fehlende Werte einen Gedankenstrich.
    this.landingPageService.context().subscribe({
      next: (values) => (this.context = values),
      error: () => (this.context = {}),
    });
```

Und die Methode ergänzen:

```typescript
  /** Baut die Vorschau aus dem aktuell bearbeiteten Inhalt neu auf. */
  refreshPreview(): void {
    const stored = this.sourceMode
      ? this.sourceHtml
      : pillsToTokens(this.form.value.bodyHtml ?? '');
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml(
      renderWithContext(stored, this.context),
    );
  }
```

- [ ] **Step 4: Update the template**

In `landing-page-editor.component.html` den Vorschau-Tab ersetzen:

```html
    <mat-tab label="Vorschau">
      <div class="tab-body">
        <p class="hint">So sieht die Startseite mit Ihren eigenen Werten aus.</p>
        <div class="preview-box" [innerHTML]="previewHtml"></div>
      </div>
    </mat-tab>
```

und die Tab-Gruppe die Vorschau bei jedem Tabwechsel neu aufbauen lassen:

```html
  <mat-tab-group (selectedTabChange)="refreshPreview()">
```

- [ ] **Step 5: Add the preview styling**

In `landing-page-editor.component.scss` ergänzen:

```scss
.preview-box {
  border: 1px solid #e2e2e6;
  border-radius: 4px;
  padding: 1rem;
  min-height: 12rem;
  background: #fff;

  img {
    max-width: 100%;
    height: auto;
  }

  table {
    width: 100%;
    border-collapse: collapse;
  }

  td,
  th {
    border: 1px solid #e2e2e6;
    padding: 0.35rem 0.5rem;
  }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/landing-page-editor.component.spec.ts`
Expected: PASS, 16 Tests.

- [ ] **Step 7: Run the full frontend suite**

Run: `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 8: Run the full backend suite**

Run: `.\mvnw.cmd test` (aus `backend/`)
Expected: Die in diesem Plan angelegten Testklassen sind grün; die laut
Projektnotiz vorbestehenden Fehlschläge bleiben unverändert.

- [ ] **Step 9: Build the frontend**

Run: `npm run build` (aus `frontend/`)
Expected: Build ohne Fehler.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/app/settings/landing-page
git commit -m "feat(fe): Vorschau-Tab der Startseite mit echten Nutzerwerten"
```

---

## Manueller Abnahmetest

Nach Task 13, mit laufendem Backend und Frontend:

1. Als Admin anmelden → Einstellungen → Startseite.
2. Text schreiben, eine Überschrift setzen, eine Kachel je Familie einfügen
   (Klick und Drag&Drop je einmal ausprobieren).
3. Auf HTML-Quelltext umschalten: Die Tokens erscheinen als `{{…}}`, das
   Markup ist lesbar. Eine Zeile ergänzen, zurückschalten — die Änderung ist im
   Editor sichtbar, die Tokens wieder Pillen.
4. Vorschau-Tab: Der eigene Vorname, die eigene Stundenbilanz und der eigene
   nächste Kochdienst stehen im Text.
5. Speichern → Erfolgsmeldung.
6. Auf `/` navigieren: dieselbe Seite erscheint.
7. Mit einem Nicht-Admin-Konto anmelden: Die Seite erscheint mit **dessen**
   Werten, der Menüpunkt „Startseite" unter Einstellungen fehlt.
8. Fensterbreite auf Handygröße ziehen: Kacheln stehen über dem Editor, keine
   horizontale Scrollleiste.
