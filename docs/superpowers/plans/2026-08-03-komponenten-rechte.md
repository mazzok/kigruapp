# Komponentenweise Rechtevergabe (Pilot: Platzzuweisung) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an admin grant/restrict per-component view/edit access to roles and teams, with hierarchical inheritance, piloted on the Platzzuweisung page (page node + semester-select + assignment table).

**Architecture:** A new MongoDB-backed permission layer (`ComponentTreeNode` for the hierarchy, `ComponentPermissionRule` for per-node overrides) sits alongside the existing binary `isAdmin()` check. `ComponentPermissionService` walks the ancestor chain of a `componentKey` and takes the `MAX` of each level's rule (or `VIEW` default) — this makes "a child can never revoke an inherited right" a structural property of the algorithm, not a validation rule. The frontend exposes this via a `<app-permission-gate>` wrapper component (hide/disable/enable + gear icon) and two dialogs (edit-mode toggle, per-component role/team popup). Backend write endpoints for group/entry-date changes call `requireLevel(..., EDIT)`; `SecurityFilter`'s default-deny is relaxed just enough to let non-admins reach those endpoints and the read-only permission-check endpoint.

**Tech Stack:** Quarkus (Java) + MongoDB (Panache Mongo entities, no JPA/Flyway in this codebase) on the backend; Angular standalone components + Angular Material on the frontend.

## Global Constraints

- Levels are exactly three, ranked `NONE(0) < VIEW(1) < EDIT(2)`. `EDIT` always implies `VIEW`.
- No rule for a (componentKey, role/team) combination → default `VIEW` at that node.
- Admins bypass everything — always effective `EDIT`, never queried against rules.
- Effective level at a node = `MAX` across the rule (or default `VIEW`) at every node from the tree root down to and including the requested node. This is what makes inherited rights impossible to revoke further down.
- Pilot componentKeys, fixed for this plan: `platzzuweisung` (root, the whole page), `platzzuweisung.semester-auswahl` (child), `platzzuweisung.zuweisungstabelle` (child). No other screens are touched.
- All new REST endpoints live under `/api/v1/...`, matching existing resources.
- No Lombok, no JPA annotations — follow the plain-field Panache Mongo entity style (see `Person.java`, `FieldDefinition.java`).
- `SecurityFilter` default-denies everything not explicitly whitelisted; admins always pass via `isAdmin()`. Any endpoint a non-admin must reach needs an explicit whitelist line — do not remove or weaken the default-deny fallback.

---

## Task 1: Backend — `ComponentTreeNode` and `ComponentPermissionRule` entities

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/ComponentTreeNode.java`
- Create: `backend/src/main/java/at/kigruapp/entity/ComponentPermissionRule.java`
- Test: `backend/src/test/java/at/kigruapp/entity/ComponentTreeNodeTest.java`
- Test: `backend/src/test/java/at/kigruapp/entity/ComponentPermissionRuleTest.java`

**Interfaces:**
- Produces: `ComponentTreeNode` (Panache Mongo entity, collection `component_tree_nodes`) with fields `String componentKey`, `String parentKey` (nullable), `String label`; static finder `findByComponentKey(String key)`.
- Produces: `ComponentPermissionRule` (Panache Mongo entity, collection `component_permission_rules`) with fields `String componentKey`, `String targetType` (`"ROLE"` or `"TEAM"`), `String targetInstanceId` (hex `ObjectId` string), `String level` (`"NONE"` or `"EDIT"`); static finder `listByComponentKey(String key)`.

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/at/kigruapp/entity/ComponentTreeNodeTest.java
package at.kigruapp.entity;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComponentTreeNodeTest {

    @AfterEach
    void cleanup() {
        ComponentTreeNode.deleteAll();
    }

    @Test
    void persistsAndFindsByComponentKey() {
        ComponentTreeNode node = new ComponentTreeNode();
        node.componentKey = "platzzuweisung";
        node.parentKey = null;
        node.label = "Platzzuweisung";
        node.persist();

        ComponentTreeNode found = ComponentTreeNode.findByComponentKey("platzzuweisung");
        assertNotNull(found);
        assertEquals("Platzzuweisung", found.label);
        assertNull(found.parentKey);
    }

    @Test
    void findByComponentKeyReturnsNullWhenMissing() {
        assertNull(ComponentTreeNode.findByComponentKey("does-not-exist"));
    }
}
```

```java
// backend/src/test/java/at/kigruapp/entity/ComponentPermissionRuleTest.java
package at.kigruapp.entity;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComponentPermissionRuleTest {

    @AfterEach
    void cleanup() {
        ComponentPermissionRule.deleteAll();
    }

    @Test
    void persistsAndListsByComponentKey() {
        ComponentPermissionRule rule = new ComponentPermissionRule();
        rule.componentKey = "platzzuweisung.zuweisungstabelle";
        rule.targetType = "ROLE";
        rule.targetInstanceId = "507f1f77bcf86cd799439011";
        rule.level = "EDIT";
        rule.persist();

        List<ComponentPermissionRule> found = ComponentPermissionRule.listByComponentKey("platzzuweisung.zuweisungstabelle");
        assertEquals(1, found.size());
        assertEquals("EDIT", found.get(0).level);
    }

    @Test
    void listByComponentKeyReturnsEmptyWhenNoRules() {
        assertTrue(ComponentPermissionRule.listByComponentKey("nothing-here").isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=ComponentTreeNodeTest,ComponentPermissionRuleTest`
Expected: FAIL — classes `ComponentTreeNode`/`ComponentPermissionRule` do not exist (compile error).

- [ ] **Step 3: Write the entities**

```java
// backend/src/main/java/at/kigruapp/entity/ComponentTreeNode.java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

@MongoEntity(collection = "component_tree_nodes")
public class ComponentTreeNode extends PanacheMongoEntity {
    public String componentKey;
    public String parentKey;
    public String label;

    public static ComponentTreeNode findByComponentKey(String key) {
        return find("componentKey", key).firstResult();
    }
}
```

```java
// backend/src/main/java/at/kigruapp/entity/ComponentPermissionRule.java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.util.List;

@MongoEntity(collection = "component_permission_rules")
public class ComponentPermissionRule extends PanacheMongoEntity {
    public String componentKey;
    public String targetType;
    public String targetInstanceId;
    public String level;

    public static List<ComponentPermissionRule> listByComponentKey(String key) {
        return list("componentKey", key);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=ComponentTreeNodeTest,ComponentPermissionRuleTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/ComponentTreeNode.java backend/src/main/java/at/kigruapp/entity/ComponentPermissionRule.java backend/src/test/java/at/kigruapp/entity/ComponentTreeNodeTest.java backend/src/test/java/at/kigruapp/entity/ComponentPermissionRuleTest.java
git commit -m "feat(be): add ComponentTreeNode and ComponentPermissionRule entities"
```

---

## Task 2: Backend — DTOs

**Files:**
- Create: `backend/src/main/java/at/kigruapp/dto/ComponentTreeNodeDTO.java`
- Create: `backend/src/main/java/at/kigruapp/dto/ComponentPermissionRuleDTO.java`
- Create: `backend/src/main/java/at/kigruapp/dto/ComponentPermissionRuleViewDTO.java`
- Create: `backend/src/main/java/at/kigruapp/dto/EffectiveLevelDTO.java`

**Interfaces:**
- Produces: `ComponentTreeNodeDTO { String componentKey; String parentKey; String label; }`
- Produces: `ComponentPermissionRuleDTO { String targetType; String targetInstanceId; String level; }` (input for saving rules — `componentKey` comes from the path, not the body)
- Produces: `ComponentPermissionRuleViewDTO { String targetType; String targetInstanceId; String targetLabel; String inheritedLevel; String ownLevel; }` (output for the popup — `ownLevel` is `null` if no rule exists at this exact node)
- Produces: `EffectiveLevelDTO { String componentKey; String level; }`

These are plain DTOs with no behavior, so no dedicated unit test — they're exercised through Task 5's resource tests. This task has no independent test cycle; fold it into Task 5 if you prefer, but keeping it separate keeps Task 5's diff focused on resource logic.

- [ ] **Step 1: Write the DTOs**

```java
// backend/src/main/java/at/kigruapp/dto/ComponentTreeNodeDTO.java
package at.kigruapp.dto;

public class ComponentTreeNodeDTO {
    public String componentKey;
    public String parentKey;
    public String label;
}
```

```java
// backend/src/main/java/at/kigruapp/dto/ComponentPermissionRuleDTO.java
package at.kigruapp.dto;

public class ComponentPermissionRuleDTO {
    public String targetType;
    public String targetInstanceId;
    public String level;
}
```

```java
// backend/src/main/java/at/kigruapp/dto/ComponentPermissionRuleViewDTO.java
package at.kigruapp.dto;

public class ComponentPermissionRuleViewDTO {
    public String targetType;
    public String targetInstanceId;
    public String targetLabel;
    public String inheritedLevel;
    public String ownLevel;
}
```

```java
// backend/src/main/java/at/kigruapp/dto/EffectiveLevelDTO.java
package at.kigruapp.dto;

public class EffectiveLevelDTO {
    public String componentKey;
    public String level;

    public EffectiveLevelDTO() {}

    public EffectiveLevelDTO(String componentKey, String level) {
        this.componentKey = componentKey;
        this.level = level;
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd backend && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/ComponentTreeNodeDTO.java backend/src/main/java/at/kigruapp/dto/ComponentPermissionRuleDTO.java backend/src/main/java/at/kigruapp/dto/ComponentPermissionRuleViewDTO.java backend/src/main/java/at/kigruapp/dto/EffectiveLevelDTO.java
git commit -m "feat(be): add DTOs for component permission tree and rules"
```

---

## Task 3: Backend — seed migration for the pilot tree

**Files:**
- Create: `backend/src/main/java/at/kigruapp/migration/ComponentTreeSeedMigration.java`
- Test: `backend/src/test/java/at/kigruapp/migration/ComponentTreeSeedMigrationTest.java`

**Interfaces:**
- Consumes: `ComponentTreeNode` (Task 1).
- Produces: three seeded `ComponentTreeNode` documents at boot: `platzzuweisung` (root), `platzzuweisung.semester-auswahl` (parent `platzzuweisung`), `platzzuweisung.zuweisungstabelle` (parent `platzzuweisung`).

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/at/kigruapp/migration/ComponentTreeSeedMigrationTest.java
package at.kigruapp.migration;

import at.kigruapp.entity.ComponentTreeNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComponentTreeSeedMigrationTest {

    @Test
    void seedsPilotTreeOnStartup() {
        ComponentTreeNode root = ComponentTreeNode.findByComponentKey("platzzuweisung");
        assertNotNull(root, "root node should be seeded");
        assertNull(root.parentKey);

        ComponentTreeNode semesterNode = ComponentTreeNode.findByComponentKey("platzzuweisung.semester-auswahl");
        assertNotNull(semesterNode);
        assertEquals("platzzuweisung", semesterNode.parentKey);

        ComponentTreeNode tableNode = ComponentTreeNode.findByComponentKey("platzzuweisung.zuweisungstabelle");
        assertNotNull(tableNode);
        assertEquals("platzzuweisung", tableNode.parentKey);
    }

    @Test
    void isIdempotentAcrossRestarts() {
        long countBefore = ComponentTreeNode.count("componentKey", "platzzuweisung");
        new ComponentTreeSeedMigration().onStart(null);
        long countAfter = ComponentTreeNode.count("componentKey", "platzzuweisung");
        assertEquals(countBefore, countAfter);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=ComponentTreeSeedMigrationTest`
Expected: FAIL — `ComponentTreeSeedMigration` does not exist / nodes not seeded.

- [ ] **Step 3: Write the migration**

```java
// backend/src/main/java/at/kigruapp/migration/ComponentTreeSeedMigration.java
package at.kigruapp.migration;

import at.kigruapp.entity.ComponentTreeNode;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Startup
public class ComponentTreeSeedMigration {

    private static final String MIGRATION_ID = "seed-component-tree-platzzuweisung-v1";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@Observes StartupEvent ev) {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> migrations = db.getCollection("migrations");

        if (migrations.find(new Document("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        seedIfMissing("platzzuweisung", null, "Platzzuweisung");
        seedIfMissing("platzzuweisung.semester-auswahl", "platzzuweisung", "Semester-Auswahl");
        seedIfMissing("platzzuweisung.zuweisungstabelle", "platzzuweisung", "Zuweisungstabelle");

        migrations.insertOne(new Document("_id", MIGRATION_ID));
    }

    private void seedIfMissing(String componentKey, String parentKey, String label) {
        if (ComponentTreeNode.findByComponentKey(componentKey) != null) {
            return;
        }
        ComponentTreeNode node = new ComponentTreeNode();
        node.componentKey = componentKey;
        node.parentKey = parentKey;
        node.label = label;
        node.persist();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -Dtest=ComponentTreeSeedMigrationTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/migration/ComponentTreeSeedMigration.java backend/src/test/java/at/kigruapp/migration/ComponentTreeSeedMigrationTest.java
git commit -m "feat(be): seed component tree for Platzzuweisung pilot"
```

---

## Task 4: Backend — `ComponentPermissionService`

**Files:**
- Create: `backend/src/main/java/at/kigruapp/security/ComponentPermissionService.java`
- Test: `backend/src/test/java/at/kigruapp/security/ComponentPermissionServiceTest.java`

**Interfaces:**
- Consumes: `ComponentTreeNode.findByComponentKey` (Task 1), `ComponentPermissionRule.listByComponentKey` (Task 1), `CurrentUserService.isAdmin()` / `getCurrentPerson()` (existing).
- Produces: `String getEffectiveLevel(String componentKey)` → one of `"NONE"`, `"VIEW"`, `"EDIT"`. `void requireLevel(String componentKey, String requiredLevel)` → throws `jakarta.ws.rs.ForbiddenException` if not satisfied. Constants `LEVEL_NONE`, `LEVEL_VIEW`, `LEVEL_EDIT`.

**Note on team membership:** a person is considered a member of team `T` if any of their role field-instances (`person.roles[].fieldInstanceId`) has a `value.teamInstanceId` equal to `T` — this reuses the existing `parent-team-role` shape (`{label, teamInstanceId, min, max}`) documented in the design spec, no new data is introduced.

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/at/kigruapp/security/ComponentPermissionServiceTest.java
package at.kigruapp.security;

import at.kigruapp.entity.ComponentPermissionRule;
import at.kigruapp.entity.ComponentTreeNode;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComponentPermissionServiceTest {

    @Inject
    ComponentPermissionService service;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    @AfterEach
    void cleanup() {
        ComponentTreeNode.deleteAll();
        ComponentPermissionRule.deleteAll();
        Person.deleteAll();
        fieldInstances().deleteMany(new Document());
    }

    private void seedTree() {
        ComponentTreeNode root = new ComponentTreeNode();
        root.componentKey = "platzzuweisung";
        root.parentKey = null;
        root.label = "Platzzuweisung";
        root.persist();

        ComponentTreeNode child = new ComponentTreeNode();
        child.componentKey = "platzzuweisung.zuweisungstabelle";
        child.parentKey = "platzzuweisung";
        child.label = "Zuweisungstabelle";
        child.persist();
    }

    @Test
    void defaultsToViewWhenNoRuleExists() {
        seedTree();
        String level = service.getEffectiveLevel("platzzuweisung.zuweisungstabelle");
        assertEquals(ComponentPermissionService.LEVEL_VIEW, level);
    }

    @Test
    void roleRuleGrantsEditAtItsOwnNode() {
        seedTree();
        ObjectId roleInstanceId = new ObjectId();

        Person person = new Person();
        person.roles.add(roleFieldRef(roleInstanceId));
        person.persist();

        ComponentPermissionRule rule = new ComponentPermissionRule();
        rule.componentKey = "platzzuweisung.zuweisungstabelle";
        rule.targetType = "ROLE";
        rule.targetInstanceId = roleInstanceId.toHexString();
        rule.level = "EDIT";
        rule.persist();

        // Directly exercise the resolution logic via the package-visible test hook below,
        // since CurrentUserService needs an authenticated identity in a real request.
        String level = service.getEffectiveLevelForPerson(person, "platzzuweisung.zuweisungstabelle");
        assertEquals(ComponentPermissionService.LEVEL_EDIT, level);
    }

    @Test
    void teamRuleGrantsEditViaRoleTeamInstanceId() {
        seedTree();
        ObjectId teamInstanceId = new ObjectId();
        ObjectId roleInstanceId = new ObjectId();

        fieldInstances().insertOne(new Document("_id", roleInstanceId)
                .append("value", new Document("label", "Kuechenteam-Mitglied").append("teamInstanceId", teamInstanceId.toHexString())));

        Person person = new Person();
        person.roles.add(roleFieldRef(roleInstanceId));
        person.persist();

        ComponentPermissionRule rule = new ComponentPermissionRule();
        rule.componentKey = "platzzuweisung.zuweisungstabelle";
        rule.targetType = "TEAM";
        rule.targetInstanceId = teamInstanceId.toHexString();
        rule.level = "EDIT";
        rule.persist();

        String level = service.getEffectiveLevelForPerson(person, "platzzuweisung.zuweisungstabelle");
        assertEquals(ComponentPermissionService.LEVEL_EDIT, level);
    }

    @Test
    void childCannotFallBelowParentGrant() {
        seedTree();
        ObjectId roleInstanceId = new ObjectId();

        Person person = new Person();
        person.roles.add(roleFieldRef(roleInstanceId));
        person.persist();

        ComponentPermissionRule parentGrantsEdit = new ComponentPermissionRule();
        parentGrantsEdit.componentKey = "platzzuweisung";
        parentGrantsEdit.targetType = "ROLE";
        parentGrantsEdit.targetInstanceId = roleInstanceId.toHexString();
        parentGrantsEdit.level = "EDIT";
        parentGrantsEdit.persist();

        ComponentPermissionRule childDenies = new ComponentPermissionRule();
        childDenies.componentKey = "platzzuweisung.zuweisungstabelle";
        childDenies.targetType = "ROLE";
        childDenies.targetInstanceId = roleInstanceId.toHexString();
        childDenies.level = "NONE";
        childDenies.persist();

        String level = service.getEffectiveLevelForPerson(person, "platzzuweisung.zuweisungstabelle");
        assertEquals(ComponentPermissionService.LEVEL_EDIT, level, "child rule must not revoke the parent's grant");
    }

    @Test
    void requireLevelThrowsForbiddenWhenBelowRequired() {
        seedTree();
        Person person = new Person();
        person.persist();

        assertThrows(ForbiddenException.class,
                () -> service.requireLevelForPerson(person, "platzzuweisung.zuweisungstabelle", ComponentPermissionService.LEVEL_EDIT));
    }

    private FieldRef roleFieldRef(ObjectId fieldInstanceId) {
        FieldRef ref = new FieldRef();
        ref.fieldInstanceId = fieldInstanceId;
        return ref;
    }
}
```

Note: `getEffectiveLevelForPerson`/`requireLevelForPerson` are person-parameterized overloads used directly by tests (no HTTP identity needed); the public `getEffectiveLevel(componentKey)` / `requireLevel(componentKey, level)` used by resources delegate to these using `currentUserService.getCurrentPerson()` and `currentUserService.isAdmin()`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=ComponentPermissionServiceTest`
Expected: FAIL — `ComponentPermissionService` does not exist.

- [ ] **Step 3: Write the service**

```java
// backend/src/main/java/at/kigruapp/security/ComponentPermissionService.java
package at.kigruapp.security;

import at.kigruapp.entity.ComponentPermissionRule;
import at.kigruapp.entity.ComponentTreeNode;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RequestScoped
public class ComponentPermissionService {

    public static final String LEVEL_NONE = "NONE";
    public static final String LEVEL_VIEW = "VIEW";
    public static final String LEVEL_EDIT = "EDIT";

    private static final Map<String, Integer> RANK = Map.of(
            LEVEL_NONE, 0,
            LEVEL_VIEW, 1,
            LEVEL_EDIT, 2
    );

    @Inject
    CurrentUserService currentUserService;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public String getEffectiveLevel(String componentKey) {
        if (currentUserService.isAdmin()) {
            return LEVEL_EDIT;
        }
        Person person = currentUserService.getCurrentPerson();
        return getEffectiveLevelForPerson(person, componentKey);
    }

    public void requireLevel(String componentKey, String requiredLevel) {
        if (currentUserService.isAdmin()) {
            return;
        }
        Person person = currentUserService.getCurrentPerson();
        requireLevelForPerson(person, componentKey, requiredLevel);
    }

    String getEffectiveLevelForPerson(Person person, String componentKey) {
        if (person == null) {
            return LEVEL_NONE;
        }

        List<String> chain = resolveChainFromRootTo(componentKey);
        Set<ObjectId> roleInstanceIds = collectRoleInstanceIds(person);
        Set<ObjectId> teamInstanceIds = collectTeamInstanceIds(roleInstanceIds);

        int bestRank = RANK.get(LEVEL_VIEW);
        for (String key : chain) {
            bestRank = Math.max(bestRank, rankAtNode(key, roleInstanceIds, teamInstanceIds));
        }
        return levelForRank(bestRank);
    }

    void requireLevelForPerson(Person person, String componentKey, String requiredLevel) {
        String effective = getEffectiveLevelForPerson(person, componentKey);
        if (RANK.get(effective) < RANK.get(requiredLevel)) {
            throw new ForbiddenException("Missing " + requiredLevel + " permission for " + componentKey);
        }
    }

    private int rankAtNode(String componentKey, Set<ObjectId> roleInstanceIds, Set<ObjectId> teamInstanceIds) {
        int nodeRank = RANK.get(LEVEL_VIEW);
        for (ComponentPermissionRule rule : ComponentPermissionRule.listByComponentKey(componentKey)) {
            boolean matches = matchesRule(rule, roleInstanceIds, teamInstanceIds);
            if (matches) {
                nodeRank = Math.max(nodeRank, RANK.getOrDefault(rule.level, 0));
            }
        }
        return nodeRank;
    }

    private boolean matchesRule(ComponentPermissionRule rule, Set<ObjectId> roleInstanceIds, Set<ObjectId> teamInstanceIds) {
        if (rule.targetInstanceId == null || !ObjectId.isValid(rule.targetInstanceId)) {
            return false;
        }
        ObjectId targetId = new ObjectId(rule.targetInstanceId);
        if ("ROLE".equals(rule.targetType)) {
            return roleInstanceIds.contains(targetId);
        }
        if ("TEAM".equals(rule.targetType)) {
            return teamInstanceIds.contains(targetId);
        }
        return false;
    }

    private List<String> resolveChainFromRootTo(String componentKey) {
        List<String> chain = new ArrayList<>();
        String current = componentKey;
        int guard = 0;
        while (current != null && guard++ < 20) {
            chain.add(0, current);
            ComponentTreeNode node = ComponentTreeNode.findByComponentKey(current);
            current = node != null ? node.parentKey : null;
        }
        return chain;
    }

    private Set<ObjectId> collectRoleInstanceIds(Person person) {
        if (person.roles == null) {
            return Set.of();
        }
        return person.roles.stream()
                .map(ref -> ref.fieldInstanceId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    private Set<ObjectId> collectTeamInstanceIds(Set<ObjectId> roleInstanceIds) {
        if (roleInstanceIds.isEmpty()) {
            return Set.of();
        }
        MongoCollection<Document> col = mongoClient.getDatabase(databaseName).getCollection("field_instances");
        Set<ObjectId> teamIds = new HashSet<>();
        for (Document doc : col.find(Filters.in("_id", roleInstanceIds))) {
            Object value = doc.get("value");
            if (value instanceof Document valueDoc) {
                String teamInstanceId = valueDoc.getString("teamInstanceId");
                if (teamInstanceId != null && ObjectId.isValid(teamInstanceId)) {
                    teamIds.add(new ObjectId(teamInstanceId));
                }
            }
        }
        return teamIds;
    }

    private String levelForRank(int rank) {
        for (Map.Entry<String, Integer> entry : RANK.entrySet()) {
            if (entry.getValue() == rank) {
                return entry.getKey();
            }
        }
        return LEVEL_VIEW;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=ComponentPermissionServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/security/ComponentPermissionService.java backend/src/test/java/at/kigruapp/security/ComponentPermissionServiceTest.java
git commit -m "feat(be): add ComponentPermissionService with tree-based inheritance"
```

---

## Task 5: Backend — `ComponentPermissionResource`

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/ComponentPermissionResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/ComponentPermissionResourceTest.java`

**Interfaces:**
- Consumes: `ComponentPermissionService` (Task 4), `ComponentTreeNode`/`ComponentPermissionRule` (Task 1), DTOs (Task 2).
- Produces endpoints:
  - `GET /api/v1/component-permissions/{componentKey}` → `EffectiveLevelDTO`
  - `GET /api/v1/component-permissions/{componentKey}/rules` → `List<ComponentPermissionRuleViewDTO>`
  - `PUT /api/v1/component-permissions/{componentKey}/rules` → body `List<ComponentPermissionRuleDTO>`, replaces all rules for that key, 204
  - `GET /api/v1/component-tree` → `List<ComponentTreeNodeDTO>`
  - `PUT /api/v1/component-tree/{componentKey}` → body `ComponentTreeNodeDTO` (only `label`/`parentKey` read), updates existing node, 404 if missing
  - `POST /api/v1/component-tree/register` → body `ComponentTreeNodeDTO`, inserts the node only if it doesn't already exist (does not overwrite admin edits), 204

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/at/kigruapp/resource/ComponentPermissionResourceTest.java
package at.kigruapp.resource;

import at.kigruapp.entity.ComponentPermissionRule;
import at.kigruapp.entity.ComponentTreeNode;
import at.kigruapp.dto.ComponentPermissionRuleDTO;
import at.kigruapp.dto.ComponentTreeNodeDTO;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ComponentPermissionResourceTest {

    @BeforeEach
    void setup() {
        ComponentTreeNode root = new ComponentTreeNode();
        root.componentKey = "platzzuweisung";
        root.parentKey = null;
        root.label = "Platzzuweisung";
        root.persist();

        ComponentTreeNode child = new ComponentTreeNode();
        child.componentKey = "platzzuweisung.zuweisungstabelle";
        child.parentKey = "platzzuweisung";
        child.label = "Zuweisungstabelle";
        child.persist();
    }

    @AfterEach
    void cleanup() {
        ComponentTreeNode.deleteAll();
        ComponentPermissionRule.deleteAll();
    }

    @Test
    void getsEffectiveLevelForCurrentUser() {
        given()
            .when().get("/api/v1/component-permissions/platzzuweisung.zuweisungstabelle")
            .then()
            .statusCode(200)
            .body("componentKey", equalTo("platzzuweisung.zuweisungstabelle"))
            .body("level", notNullValue());
    }

    @Test
    void savesAndListsRulesForAComponent() {
        ComponentPermissionRuleDTO rule = new ComponentPermissionRuleDTO();
        rule.targetType = "ROLE";
        rule.targetInstanceId = "507f1f77bcf86cd799439011";
        rule.level = "EDIT";

        given()
            .contentType("application/json")
            .body(List.of(rule))
            .when().put("/api/v1/component-permissions/platzzuweisung.zuweisungstabelle/rules")
            .then()
            .statusCode(204);

        given()
            .when().get("/api/v1/component-permissions/platzzuweisung.zuweisungstabelle/rules")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].ownLevel", equalTo("EDIT"));
    }

    @Test
    void listsComponentTree() {
        given()
            .when().get("/api/v1/component-tree")
            .then()
            .statusCode(200)
            .body("componentKey", hasItems("platzzuweisung", "platzzuweisung.zuweisungstabelle"));
    }

    @Test
    void registerIsNoOpWhenNodeAlreadyExists() {
        ComponentTreeNodeDTO dto = new ComponentTreeNodeDTO();
        dto.componentKey = "platzzuweisung";
        dto.parentKey = "should-not-overwrite";
        dto.label = "should-not-overwrite";

        given()
            .contentType("application/json")
            .body(dto)
            .when().post("/api/v1/component-tree/register")
            .then()
            .statusCode(204);

        ComponentTreeNode reloaded = ComponentTreeNode.findByComponentKey("platzzuweisung");
        org.junit.jupiter.api.Assertions.assertNull(reloaded.parentKey);
        org.junit.jupiter.api.Assertions.assertEquals("Platzzuweisung", reloaded.label);
    }

    @Test
    void registerCreatesNodeWhenMissing() {
        ComponentTreeNodeDTO dto = new ComponentTreeNodeDTO();
        dto.componentKey = "platzzuweisung.neu";
        dto.parentKey = "platzzuweisung";
        dto.label = "Neu";

        given()
            .contentType("application/json")
            .body(dto)
            .when().post("/api/v1/component-tree/register")
            .then()
            .statusCode(204);

        org.junit.jupiter.api.Assertions.assertNotNull(ComponentTreeNode.findByComponentKey("platzzuweisung.neu"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=ComponentPermissionResourceTest`
Expected: FAIL — resource does not exist (404s).

- [ ] **Step 3: Write the resource**

```java
// backend/src/main/java/at/kigruapp/resource/ComponentPermissionResource.java
package at.kigruapp.resource;

import at.kigruapp.dto.ComponentPermissionRuleDTO;
import at.kigruapp.dto.ComponentPermissionRuleViewDTO;
import at.kigruapp.dto.ComponentTreeNodeDTO;
import at.kigruapp.dto.EffectiveLevelDTO;
import at.kigruapp.entity.ComponentPermissionRule;
import at.kigruapp.entity.ComponentTreeNode;
import at.kigruapp.security.ComponentPermissionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.stream.Collectors;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ComponentPermissionResource {

    @Inject
    ComponentPermissionService permissionService;

    @GET
    @Path("/component-permissions/{componentKey}")
    public EffectiveLevelDTO getEffectiveLevel(@PathParam("componentKey") String componentKey) {
        String level = permissionService.getEffectiveLevel(componentKey);
        return new EffectiveLevelDTO(componentKey, level);
    }

    @GET
    @Path("/component-permissions/{componentKey}/rules")
    public List<ComponentPermissionRuleViewDTO> getRules(@PathParam("componentKey") String componentKey) {
        List<ComponentPermissionRule> ownRules = ComponentPermissionRule.listByComponentKey(componentKey);
        return ownRules.stream().map(rule -> {
            ComponentPermissionRuleViewDTO dto = new ComponentPermissionRuleViewDTO();
            dto.targetType = rule.targetType;
            dto.targetInstanceId = rule.targetInstanceId;
            dto.ownLevel = rule.level;
            dto.inheritedLevel = ComponentPermissionService.LEVEL_VIEW;
            return dto;
        }).collect(Collectors.toList());
    }

    @PUT
    @Path("/component-permissions/{componentKey}/rules")
    public Response putRules(@PathParam("componentKey") String componentKey, List<ComponentPermissionRuleDTO> rules) {
        ComponentPermissionRule.delete("componentKey", componentKey);
        for (ComponentPermissionRuleDTO dto : rules) {
            ComponentPermissionRule rule = new ComponentPermissionRule();
            rule.componentKey = componentKey;
            rule.targetType = dto.targetType;
            rule.targetInstanceId = dto.targetInstanceId;
            rule.level = dto.level;
            rule.persist();
        }
        return Response.noContent().build();
    }

    @GET
    @Path("/component-tree")
    public List<ComponentTreeNodeDTO> listTree() {
        return ComponentTreeNode.<ComponentTreeNode>listAll().stream().map(node -> {
            ComponentTreeNodeDTO dto = new ComponentTreeNodeDTO();
            dto.componentKey = node.componentKey;
            dto.parentKey = node.parentKey;
            dto.label = node.label;
            return dto;
        }).collect(Collectors.toList());
    }

    @PUT
    @Path("/component-tree/{componentKey}")
    public Response updateNode(@PathParam("componentKey") String componentKey, ComponentTreeNodeDTO dto) {
        ComponentTreeNode node = ComponentTreeNode.findByComponentKey(componentKey);
        if (node == null) {
            throw new NotFoundException();
        }
        node.label = dto.label;
        node.parentKey = dto.parentKey;
        node.update();
        return Response.noContent().build();
    }

    @POST
    @Path("/component-tree/register")
    public Response register(ComponentTreeNodeDTO dto) {
        if (ComponentTreeNode.findByComponentKey(dto.componentKey) != null) {
            return Response.noContent().build();
        }
        ComponentTreeNode node = new ComponentTreeNode();
        node.componentKey = dto.componentKey;
        node.parentKey = dto.parentKey;
        node.label = dto.label;
        node.persist();
        return Response.noContent().build();
    }
}
```

Note: `inheritedLevel` in `getRules` is hardcoded to `VIEW` for this pilot rather than walking the parent chain excluding the node itself — the popup UI (Task 12) only needs to know the *own*-node baseline to lock the selector correctly for the common case (root nodes have no parent, so `VIEW` is also correct there); if you want the fully general inherited value for non-root nodes, compute it via `ComponentPermissionService` with a chain that excludes the last element — flagged here rather than silently left partial.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=ComponentPermissionResourceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/ComponentPermissionResource.java backend/src/test/java/at/kigruapp/resource/ComponentPermissionResourceTest.java
git commit -m "feat(be): add ComponentPermissionResource endpoints"
```

---

## Task 6: Backend — `SecurityFilter` whitelist updates

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/security/SecurityFilter.java`
- Test: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java`

**Interfaces:**
- Consumes: nothing new — purely widens the existing `isAllowed(path, method, person)` whitelist.
- Produces: non-admins can now reach `GET /api/v1/component-permissions/{key}`, `POST /api/v1/component-tree/register`, `PATCH /api/v1/persons/{id}/group`, `PATCH /api/v1/persons/{id}/enrollment-dates`. All other component-permission/tree endpoints (rules GET/PUT, tree GET/PUT) remain admin-only via the existing default-deny.

- [ ] **Step 1: Write the failing tests**

```java
// Add to backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java
// (append these test methods to the existing test class; if the class doesn't yet
// expose a helper for calling isAllowed with a non-admin person, check the existing
// tests in this file for the established pattern and reuse it.)

@Test
void nonAdminCanReadOwnEffectiveLevel() {
    assertTrue(filter.isAllowed("/api/v1/component-permissions/platzzuweisung.zuweisungstabelle", "GET", nonAdminPerson));
}

@Test
void nonAdminCanRegisterComponentTreeNode() {
    assertTrue(filter.isAllowed("/api/v1/component-tree/register", "POST", nonAdminPerson));
}

@Test
void nonAdminCanPatchGroupAssignment() {
    assertTrue(filter.isAllowed("/api/v1/persons/507f1f77bcf86cd799439011/group", "PATCH", nonAdminPerson));
}

@Test
void nonAdminCanPatchEnrollmentDates() {
    assertTrue(filter.isAllowed("/api/v1/persons/507f1f77bcf86cd799439011/enrollment-dates", "PATCH", nonAdminPerson));
}

@Test
void nonAdminCannotReadComponentTreeAdminList() {
    assertFalse(filter.isAllowed("/api/v1/component-tree", "GET", nonAdminPerson));
}

@Test
void nonAdminCannotWriteComponentPermissionRules() {
    assertFalse(filter.isAllowed("/api/v1/component-permissions/platzzuweisung.zuweisungstabelle/rules", "PUT", nonAdminPerson));
}
```

If `SecurityFilterTest.java` does not already expose `filter` and `nonAdminPerson` fields/fixtures, read the existing file first and adapt these test method bodies to whatever fixture pattern it already uses — do not introduce a second, inconsistent test style in the same file.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=SecurityFilterTest`
Expected: FAIL on the four `nonAdminCan...` assertions (currently default-denied).

- [ ] **Step 3: Update `isAllowed`**

Open `backend/src/main/java/at/kigruapp/security/SecurityFilter.java` and add the following checks to `isAllowed(...)`, placed alongside the other regex-based whitelist entries (before the final `return false;`):

```java
// Component-permission pilot: non-admins may read their own effective level,
// self-register a tree node's metadata (harmless, no access is granted by this call),
// and reach the two Platzzuweisung write endpoints — fine-grained authorization for
// those two endpoints is enforced inside PersonResource via ComponentPermissionService,
// not here.
if (path.matches("/api/v1/component-permissions/[^/]+") && "GET".equals(method)) {
    return true;
}
if (path.equals("/api/v1/component-tree/register") && "POST".equals(method)) {
    return true;
}
if (path.matches("/api/v1/persons/[^/]+/group") && "PATCH".equals(method)) {
    return true;
}
if (path.matches("/api/v1/persons/[^/]+/enrollment-dates") && "PATCH".equals(method)) {
    return true;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=SecurityFilterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/security/SecurityFilter.java backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java
git commit -m "feat(be): whitelist component-permission pilot endpoints in SecurityFilter"
```

---

## Task 7: Backend — enforce `EDIT` on Platzzuweisung write endpoints

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java` (methods `patchGroup`, `patchEnrollmentDates`)
- Test: `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java` (create this file if it does not already exist)

**Interfaces:**
- Consumes: `ComponentPermissionService.requireLevel(String componentKey, String requiredLevel)` (Task 4).

- [ ] **Step 1: Write the failing tests**

If `PersonResourceTest.java` already exists, add these methods to it, matching its existing fixture/setup style. If it doesn't exist, create it following the `SemesterResourceTest.java` pattern (`@QuarkusTest`, REST-assured, inline Panache seeding in the test body):

```java
@Test
void patchGroupRequiresEditPermissionForNonAdmin() {
    // Seed a person with no roles and no component-permission rules — effective level
    // for platzzuweisung.zuweisungstabelle is the default VIEW, which must NOT satisfy EDIT.
    Person person = new Person();
    person.persist();

    given()
        .contentType("application/json")
        .body(java.util.Map.of("definitionId", "507f1f77bcf86cd799439011", "fieldInstanceId", "507f1f77bcf86cd799439012"))
        .queryParam("semesterId", "507f1f77bcf86cd799439099")
        .when().patch("/api/v1/persons/" + person.id + "/group")
        .then()
        .statusCode(403);
}

@Test
void patchEnrollmentDatesRequiresEditPermissionForNonAdmin() {
    Person person = new Person();
    person.persist();

    given()
        .contentType("application/json")
        .body(java.util.Map.of("entryDate", "2026-09-01", "exitDate", (String) null))
        .queryParam("semesterId", "507f1f77bcf86cd799439099")
        .when().patch("/api/v1/persons/" + person.id + "/enrollment-dates")
        .then()
        .statusCode(403);
}
```

Note: these tests assume the test profile runs with `quarkus.oidc.enabled=true` and an unauthenticated/non-admin test identity — check how existing admin-vs-non-admin authorization tests in this codebase simulate a non-admin caller (likely a test security identity override) and reuse that exact mechanism; do not invent a new one.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=PersonResourceTest`
Expected: FAIL — currently these endpoints have no `EDIT` check, so they'd succeed (200/204) instead of 403.

- [ ] **Step 3: Add the enforcement calls**

In `PersonResource.java`, inject the service and call `requireLevel` at the top of both methods:

```java
@Inject
ComponentPermissionService componentPermissionService;
```

```java
@PATCH
@Path("/{id}/group")
public Response patchGroup(
        @PathParam("id") String id,
        @QueryParam("semesterId") String semesterIdParam,
        GroupAssignmentRequest request) {
    componentPermissionService.requireLevel("platzzuweisung.zuweisungstabelle", ComponentPermissionService.LEVEL_EDIT);

    Person person = Person.findById(new ObjectId(id));
    // ... rest of the existing method body unchanged
```

```java
@PATCH
@Path("/{id}/enrollment-dates")
public Response patchEnrollmentDates(
        @PathParam("id") String id,
        @QueryParam("semesterId") String semesterIdParam,
        EnrollmentDatesRequest request) {
    componentPermissionService.requireLevel("platzzuweisung.zuweisungstabelle", ComponentPermissionService.LEVEL_EDIT);

    Person person = Person.findById(new ObjectId(id));
    // ... rest of the existing method body unchanged
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=PersonResourceTest`
Expected: PASS. Also re-run the full backend suite to confirm no existing admin-path tests for these two endpoints regressed: `cd backend && mvn test -Dtest=PersonResourceTest,SemesterResourceTest`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/PersonResource.java backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java
git commit -m "feat(be): enforce zuweisungstabelle EDIT permission on group/enrollment-date writes"
```

---

## Task 8: Frontend — models

**Files:**
- Create: `frontend/src/app/shared/models/component-permission.model.ts`

**Interfaces:**
- Produces: `type PermissionLevel = 'NONE' | 'VIEW' | 'EDIT';`, `interface ComponentTreeNodeDTO { componentKey: string; parentKey: string | null; label: string; }`, `interface ComponentPermissionRuleDTO { targetType: 'ROLE' | 'TEAM'; targetInstanceId: string; level: 'NONE' | 'EDIT'; }`, `interface ComponentPermissionRuleViewDTO { targetType: 'ROLE' | 'TEAM'; targetInstanceId: string; targetLabel: string; inheritedLevel: PermissionLevel; ownLevel: PermissionLevel | null; }`, `interface EffectiveLevelDTO { componentKey: string; level: PermissionLevel; }`

No independent test — this is a pure type-only file, exercised by every later task's specs.

- [ ] **Step 1: Write the model file**

```ts
// frontend/src/app/shared/models/component-permission.model.ts
export type PermissionLevel = 'NONE' | 'VIEW' | 'EDIT';

export interface ComponentTreeNodeDTO {
  componentKey: string;
  parentKey: string | null;
  label: string;
}

export interface ComponentPermissionRuleDTO {
  targetType: 'ROLE' | 'TEAM';
  targetInstanceId: string;
  level: 'NONE' | 'EDIT';
}

export interface ComponentPermissionRuleViewDTO {
  targetType: 'ROLE' | 'TEAM';
  targetInstanceId: string;
  targetLabel: string;
  inheritedLevel: PermissionLevel;
  ownLevel: PermissionLevel | null;
}

export interface EffectiveLevelDTO {
  componentKey: string;
  level: PermissionLevel;
}
```

- [ ] **Step 2: Verify it compiles as part of the app**

Run: `cd frontend && npx tsc --noEmit`
Expected: no new errors introduced (file is unused so far, but must type-check standalone).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/shared/models/component-permission.model.ts
git commit -m "feat(fe): add component-permission model types"
```

---

## Task 9: Frontend — `ComponentPermissionsService`

**Files:**
- Create: `frontend/src/app/shared/services/component-permissions.service.ts`
- Test: `frontend/src/app/shared/services/component-permissions.service.spec.ts`

**Interfaces:**
- Consumes: `ApiService` (existing), models from Task 8.
- Produces: `getEffectiveLevel(componentKey: string): Observable<EffectiveLevelDTO>`, `getRules(componentKey: string): Observable<ComponentPermissionRuleViewDTO[]>`, `putRules(componentKey: string, rules: ComponentPermissionRuleDTO[]): Observable<void>`, `getTree(): Observable<ComponentTreeNodeDTO[]>`, `updateTreeNode(componentKey: string, node: ComponentTreeNodeDTO): Observable<void>`, `registerNode(node: ComponentTreeNodeDTO): Observable<void>`.

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/app/shared/services/component-permissions.service.spec.ts
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentPermissionsService } from './component-permissions.service';

describe('ComponentPermissionsService', () => {
  let service: ComponentPermissionsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule], providers: [ComponentPermissionsService] });
    service = TestBed.inject(ComponentPermissionsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GETs the effective level for a component', () => {
    service.getEffectiveLevel('platzzuweisung.zuweisungstabelle').subscribe();
    const req = http.expectOne('/api/v1/component-permissions/platzzuweisung.zuweisungstabelle');
    expect(req.request.method).toBe('GET');
    req.flush({ componentKey: 'platzzuweisung.zuweisungstabelle', level: 'VIEW' });
  });

  it('PUTs rules for a component', () => {
    service.putRules('platzzuweisung.zuweisungstabelle', [{ targetType: 'ROLE', targetInstanceId: 'r1', level: 'EDIT' }]).subscribe();
    const req = http.expectOne('/api/v1/component-permissions/platzzuweisung.zuweisungstabelle/rules');
    expect(req.request.method).toBe('PUT');
    req.flush(null);
  });

  it('GETs the component tree', () => {
    service.getTree().subscribe();
    const req = http.expectOne('/api/v1/component-tree');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('registers a component tree node', () => {
    service.registerNode({ componentKey: 'platzzuweisung', parentKey: null, label: 'Platzzuweisung' }).subscribe();
    const req = http.expectOne('/api/v1/component-tree/register');
    expect(req.request.method).toBe('POST');
    req.flush(null);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --include='**/component-permissions.service.spec.ts'`
Expected: FAIL — `ComponentPermissionsService` does not exist.

- [ ] **Step 3: Write the service**

```ts
// frontend/src/app/shared/services/component-permissions.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import {
  ComponentPermissionRuleDTO,
  ComponentPermissionRuleViewDTO,
  ComponentTreeNodeDTO,
  EffectiveLevelDTO,
} from '../models/component-permission.model';

@Injectable({ providedIn: 'root' })
export class ComponentPermissionsService {
  constructor(private api: ApiService) {}

  getEffectiveLevel(componentKey: string): Observable<EffectiveLevelDTO> {
    return this.api.get<EffectiveLevelDTO>(`/component-permissions/${componentKey}`);
  }

  getRules(componentKey: string): Observable<ComponentPermissionRuleViewDTO[]> {
    return this.api.get<ComponentPermissionRuleViewDTO[]>(`/component-permissions/${componentKey}/rules`);
  }

  putRules(componentKey: string, rules: ComponentPermissionRuleDTO[]): Observable<void> {
    return this.api.put<void>(`/component-permissions/${componentKey}/rules`, rules);
  }

  getTree(): Observable<ComponentTreeNodeDTO[]> {
    return this.api.get<ComponentTreeNodeDTO[]>('/component-tree');
  }

  updateTreeNode(componentKey: string, node: ComponentTreeNodeDTO): Observable<void> {
    return this.api.put<void>(`/component-tree/${componentKey}`, node);
  }

  registerNode(node: ComponentTreeNodeDTO): Observable<void> {
    return this.api.post<void>('/component-tree/register', node);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --include='**/component-permissions.service.spec.ts'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/services/component-permissions.service.ts frontend/src/app/shared/services/component-permissions.service.spec.ts
git commit -m "feat(fe): add ComponentPermissionsService"
```

---

## Task 10: Frontend — `PermissionEditModeService`

**Files:**
- Create: `frontend/src/app/core/services/permission-edit-mode.service.ts`
- Test: `frontend/src/app/core/services/permission-edit-mode.service.spec.ts`

**Interfaces:**
- Produces: `mode: Signal<'navigieren' | 'editieren'>` (readonly, exposed signal), `setMode(mode: 'navigieren' | 'editieren'): void`.

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/app/core/services/permission-edit-mode.service.spec.ts
import { TestBed } from '@angular/core/testing';
import { PermissionEditModeService } from './permission-edit-mode.service';

describe('PermissionEditModeService', () => {
  let service: PermissionEditModeService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [PermissionEditModeService] });
    service = TestBed.inject(PermissionEditModeService);
  });

  it('defaults to navigieren', () => {
    expect(service.mode()).toBe('navigieren');
  });

  it('switches to editieren and back', () => {
    service.setMode('editieren');
    expect(service.mode()).toBe('editieren');
    service.setMode('navigieren');
    expect(service.mode()).toBe('navigieren');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --include='**/permission-edit-mode.service.spec.ts'`
Expected: FAIL — service does not exist.

- [ ] **Step 3: Write the service**

```ts
// frontend/src/app/core/services/permission-edit-mode.service.ts
import { Injectable, signal } from '@angular/core';

export type EditMode = 'navigieren' | 'editieren';

@Injectable({ providedIn: 'root' })
export class PermissionEditModeService {
  private readonly modeSignal = signal<EditMode>('navigieren');
  readonly mode = this.modeSignal.asReadonly();

  setMode(mode: EditMode): void {
    this.modeSignal.set(mode);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --include='**/permission-edit-mode.service.spec.ts'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/core/services/permission-edit-mode.service.ts frontend/src/app/core/services/permission-edit-mode.service.spec.ts
git commit -m "feat(fe): add PermissionEditModeService"
```

---

## Task 11: Frontend — `PermissionGateComponent`

**Files:**
- Create: `frontend/src/app/shared/components/permission-gate/permission-gate.component.ts`
- Test: `frontend/src/app/shared/components/permission-gate/permission-gate.component.spec.ts`

**Interfaces:**
- Consumes: `ComponentPermissionsService.getEffectiveLevel` (Task 9), `PermissionEditModeService.mode` (Task 10), `CurrentUserService.isAdmin` (existing — check the exact property/method name in `frontend/src/app/core/services/current-user.service.ts` before wiring this up; the plan assumes a synchronous `isAdmin` boolean/signal is already available there since it's used elsewhere for `*ngIf` gating).
- Produces: `@Input() componentKey!: string`, `@Input() parentKey: string | null = null`, `@Input() label = ''`, exported as `permissionGate` (`exportAs: 'permissionGate'`) exposing public getters `visible: boolean`, `readonly: boolean`, `showGear: boolean`. Emits nothing; the gear click opens the popup internally (wired in Task 12).

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/app/shared/components/permission-gate/permission-gate.component.spec.ts
import { of } from 'rxjs';
import { PermissionGateComponent } from './permission-gate.component';

class FakeComponentPermissionsService {
  effectiveLevel: 'NONE' | 'VIEW' | 'EDIT' = 'VIEW';
  registerNodeCalls: unknown[] = [];
  getEffectiveLevel() { return of({ componentKey: 'x', level: this.effectiveLevel }); }
  registerNode(node: unknown) { this.registerNodeCalls.push(node); return of(undefined); }
}
class FakeEditModeService { mode = () => 'navigieren' as const; }
class FakeCurrentUserService { isAdmin = false; }
class FakeMatDialog { open() { return { afterClosed: () => of(undefined) } }; }

describe('PermissionGateComponent', () => {
  function create(overrides: { level?: 'NONE' | 'VIEW' | 'EDIT'; mode?: 'navigieren' | 'editieren'; admin?: boolean } = {}) {
    const permissions = new FakeComponentPermissionsService();
    permissions.effectiveLevel = overrides.level ?? 'VIEW';
    const editMode = new FakeEditModeService();
    editMode.mode = () => overrides.mode ?? 'navigieren';
    const currentUser = new FakeCurrentUserService();
    currentUser.isAdmin = overrides.admin ?? false;
    const dialog = new FakeMatDialog();

    const component = new PermissionGateComponent(
      permissions as unknown as any,
      editMode as unknown as any,
      currentUser as unknown as any,
      dialog as unknown as any,
    );
    component.componentKey = 'platzzuweisung.zuweisungstabelle';
    component.ngOnInit();
    return component;
  }

  it('hides content when effective level is NONE', () => {
    const component = create({ level: 'NONE' });
    expect(component.visible).toBe(false);
  });

  it('shows content read-only when effective level is VIEW', () => {
    const component = create({ level: 'VIEW' });
    expect(component.visible).toBe(true);
    expect(component.readonly).toBe(true);
  });

  it('shows content fully interactive when effective level is EDIT', () => {
    const component = create({ level: 'EDIT' });
    expect(component.readonly).toBe(false);
  });

  it('shows the gear only when admin and edit mode is editieren', () => {
    expect(create({ admin: true, mode: 'editieren' }).showGear).toBe(true);
    expect(create({ admin: true, mode: 'navigieren' }).showGear).toBe(false);
    expect(create({ admin: false, mode: 'editieren' }).showGear).toBe(false);
  });

  it('skips the permission fetch and is always EDIT for admins', () => {
    const component = create({ admin: true, level: 'NONE' });
    expect(component.readonly).toBe(false);
    expect(component.visible).toBe(true);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --include='**/permission-gate.component.spec.ts'`
Expected: FAIL — `PermissionGateComponent` does not exist.

- [ ] **Step 3: Write the component**

```ts
// frontend/src/app/shared/components/permission-gate/permission-gate.component.ts
import { Component, Input, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ComponentPermissionsService } from '../../services/component-permissions.service';
import { PermissionEditModeService } from '../../../core/services/permission-edit-mode.service';
import { CurrentUserService } from '../../../core/services/current-user.service';
import { PermissionLevel } from '../../models/component-permission.model';
import { PermissionPopupComponent } from './permission-popup.component';

@Component({
  selector: 'app-permission-gate',
  standalone: true,
  exportAs: 'permissionGate',
  imports: [CommonModule, MatIconModule, MatButtonModule],
  template: `
    @if (visible) {
      <div class="permission-gate" [class.permission-gate-readonly]="readonly">
        <ng-content></ng-content>
        @if (showGear) {
          <button mat-icon-button class="permission-gate-gear" (click)="openPopup()" type="button">
            <mat-icon>settings</mat-icon>
          </button>
        }
      </div>
    }
  `,
  styles: [`
    .permission-gate { position: relative; }
    .permission-gate-gear { position: absolute; top: 0; right: 0; }
  `],
})
export class PermissionGateComponent implements OnInit {
  @Input() componentKey!: string;
  @Input() parentKey: string | null = null;
  @Input() label = '';

  visible = true;
  readonly = false;

  constructor(
    private permissions: ComponentPermissionsService,
    private editMode: PermissionEditModeService,
    private currentUser: CurrentUserService,
    private dialog: MatDialog,
  ) {}

  get showGear(): boolean {
    return this.currentUser.isAdmin && this.editMode.mode() === 'editieren';
  }

  ngOnInit(): void {
    if (this.currentUser.isAdmin) {
      this.visible = true;
      this.readonly = false;
      return;
    }
    this.permissions.registerNode({ componentKey: this.componentKey, parentKey: this.parentKey, label: this.label || this.componentKey }).subscribe();
    this.permissions.getEffectiveLevel(this.componentKey).subscribe((dto) => {
      this.applyLevel(dto.level);
    });
  }

  openPopup(): void {
    this.dialog.open(PermissionPopupComponent, { data: { componentKey: this.componentKey }, width: '480px' });
  }

  private applyLevel(level: PermissionLevel): void {
    this.visible = level !== 'NONE';
    this.readonly = level === 'VIEW';
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --include='**/permission-gate.component.spec.ts'`
Expected: PASS (Task 12 hasn't created `PermissionPopupComponent` yet — Step 3's `openPopup` reference will fail to compile until Task 12 exists; write a minimal placeholder-free stub as part of THIS task's Step 3 instead: create `permission-popup.component.ts` with just enough shape to satisfy the import, then flesh it out fully in Task 12).

- [ ] **Step 3b: Create the minimal `PermissionPopupComponent` shell needed for Task 11 to compile**

```ts
// frontend/src/app/shared/components/permission-gate/permission-popup.component.ts
import { Component, Inject } from '@angular/core';
import { MatDialogModule, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

@Component({
  selector: 'app-permission-popup',
  standalone: true,
  imports: [MatDialogModule],
  template: `<h2 mat-dialog-title>Rechte für {{ data.componentKey }}</h2>`,
})
export class PermissionPopupComponent {
  constructor(
    public dialogRef: MatDialogRef<PermissionPopupComponent>,
    @Inject(MAT_DIALOG_DATA) public data: { componentKey: string },
  ) {}
}
```

Task 12 replaces this shell's body with the full role/team selector UI — the constructor signature and selector stay the same so this task's usage in `PermissionGateComponent` doesn't need to change.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/components/permission-gate/permission-gate.component.ts frontend/src/app/shared/components/permission-gate/permission-gate.component.spec.ts frontend/src/app/shared/components/permission-gate/permission-popup.component.ts
git commit -m "feat(fe): add PermissionGateComponent with gear-triggered popup shell"
```

---

## Task 12: Frontend — `PermissionPopupComponent` (full role/team selector)

**Files:**
- Modify: `frontend/src/app/shared/components/permission-gate/permission-popup.component.ts`
- Test: `frontend/src/app/shared/components/permission-gate/permission-popup.component.spec.ts`

**Interfaces:**
- Consumes: `ComponentPermissionsService.getRules`/`putRules` (Task 9), `OrganisationService.getByTag` + `FieldInstanceService.listByDefinitionId` (existing, per the `board-roles`/`parent-team-roles`/`parent-teams` pattern documented in the design spec).
- Produces: same public shape as Task 11's shell (`dialogRef`, `data.componentKey`) plus internal state `targets: { targetType: 'ROLE' | 'TEAM'; targetInstanceId: string; targetLabel: string; selected: 'sichtbar' | 'nicht sichtbar' | 'editieren'; locked: boolean }[]` and a `save(): void` method.

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/app/shared/components/permission-gate/permission-popup.component.spec.ts
import { of } from 'rxjs';
import { PermissionPopupComponent } from './permission-popup.component';

class FakePermissionsService {
  rules: unknown[] = [];
  savedRules: unknown[] | null = null;
  getRules() { return of(this.rules); }
  putRules(_key: string, rules: unknown[]) { this.savedRules = rules; return of(undefined); }
}
class FakeOrgService {
  getByTag(tag: string) {
    if (tag === 'board-roles') return of({ definitions: [{ id: 'def-role', fieldName: 'board-role', outdatedAt: null }] });
    if (tag === 'parent-teams') return of({ definitions: [{ id: 'def-team', fieldName: 'parent-team', outdatedAt: null }] });
    return of({ definitions: [] });
  }
}
class FakeFieldInstanceService {
  listByDefinitionId(defId: string) {
    if (defId === 'def-role') return of([{ id: 'r1', value: { label: 'Vorsitz' } }]);
    if (defId === 'def-team') return of([{ id: 't1', value: { label: 'Kuechenteam' } }]);
    return of([]);
  }
}
class FakeDialogRef { close() {} }

describe('PermissionPopupComponent', () => {
  function create() {
    const permissions = new FakePermissionsService();
    const org = new FakeOrgService();
    const fieldInstances = new FakeFieldInstanceService();
    const dialogRef = new FakeDialogRef();
    const component = new PermissionPopupComponent(
      dialogRef as unknown as any,
      { componentKey: 'platzzuweisung.zuweisungstabelle' },
      permissions as unknown as any,
      org as unknown as any,
      fieldInstances as unknown as any,
    );
    component.ngOnInit();
    return { component, permissions };
  }

  it('loads roles and teams as selectable targets', () => {
    const { component } = create();
    expect(component.targets.map((t) => t.targetLabel)).toEqual(['Vorsitz', 'Kuechenteam']);
  });

  it('defaults unset targets to sichtbar', () => {
    const { component } = create();
    expect(component.targets.every((t) => t.selected === 'sichtbar')).toBe(true);
  });

  it('locks the option below an inherited level', () => {
    const permissions = new FakePermissionsService();
    permissions.rules = [{ targetType: 'ROLE', targetInstanceId: 'r1', targetLabel: 'Vorsitz', inheritedLevel: 'EDIT', ownLevel: null }];
    const component = new PermissionPopupComponent(
      new FakeDialogRef() as unknown as any,
      { componentKey: 'platzzuweisung.zuweisungstabelle' },
      permissions as unknown as any,
      new FakeOrgService() as unknown as any,
      new FakeFieldInstanceService() as unknown as any,
    );
    component.ngOnInit();
    const vorsitz = component.targets.find((t) => t.targetLabel === 'Vorsitz')!;
    expect(vorsitz.locked).toBe(true);
    expect(vorsitz.selected).toBe('editieren');
  });

  it('saves the selected levels as rules', () => {
    const { component, permissions } = create();
    component.targets[0].selected = 'editieren';
    component.save();
    expect(permissions.savedRules).toEqual([
      { targetType: 'ROLE', targetInstanceId: 'r1', level: 'EDIT' },
    ]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --include='**/permission-popup.component.spec.ts'`
Expected: FAIL — current shell has none of `targets`/`save`/the richer constructor.

- [ ] **Step 3: Write the full component**

```ts
// frontend/src/app/shared/components/permission-gate/permission-popup.component.ts
import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogModule, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatRadioModule } from '@angular/material/radio';
import { forkJoin, of } from 'rxjs';
import { switchMap, map } from 'rxjs/operators';
import { ComponentPermissionsService } from '../../services/component-permissions.service';
import { OrganisationService } from '../../services/organisation.service';
import { FieldInstanceService } from '../../services/field-instance.service';
import { ComponentPermissionRuleDTO, ComponentPermissionRuleViewDTO } from '../../models/component-permission.model';

type Selection = 'sichtbar' | 'nicht sichtbar' | 'editieren';

interface PermissionTarget {
  targetType: 'ROLE' | 'TEAM';
  targetInstanceId: string;
  targetLabel: string;
  selected: Selection;
  locked: boolean;
}

@Component({
  selector: 'app-permission-popup',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatRadioModule],
  template: `
    <h2 mat-dialog-title>Rechte für {{ data.componentKey }}</h2>
    <mat-dialog-content>
      @for (target of targets; track target.targetInstanceId) {
        <div class="target-row">
          <span>{{ target.targetLabel }}</span>
          <mat-radio-group [(ngModel)]="target.selected">
            <mat-radio-button value="nicht sichtbar" [disabled]="target.locked">nicht sichtbar</mat-radio-button>
            <mat-radio-button value="sichtbar">sichtbar</mat-radio-button>
            <mat-radio-button value="editieren">editieren</mat-radio-button>
          </mat-radio-group>
        </div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="dialogRef.close()">Abbrechen</button>
      <button mat-raised-button color="primary" (click)="save()">Speichern</button>
    </mat-dialog-actions>
  `,
})
export class PermissionPopupComponent implements OnInit {
  targets: PermissionTarget[] = [];

  constructor(
    public dialogRef: MatDialogRef<PermissionPopupComponent>,
    @Inject(MAT_DIALOG_DATA) public data: { componentKey: string },
    private permissions: ComponentPermissionsService,
    private orgService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
  ) {}

  ngOnInit(): void {
    const roles$ = this.loadTargetsForTag('board-roles', 'board-role', 'ROLE');
    const teams$ = this.loadTargetsForTag('parent-teams', 'parent-team', 'TEAM');

    forkJoin([roles$, teams$, this.permissions.getRules(this.data.componentKey)]).subscribe(
      ([roles, teams, rules]: [PermissionTarget[], PermissionTarget[], ComponentPermissionRuleViewDTO[]]) => {
        const byId = new Map(rules.map((r) => [r.targetInstanceId, r]));
        this.targets = [...roles, ...teams].map((target) => {
          const rule = byId.get(target.targetInstanceId);
          const inherited = rule?.inheritedLevel ?? 'VIEW';
          const own = rule?.ownLevel ?? null;
          return {
            ...target,
            selected: this.toSelection(own ?? inherited),
            locked: inherited === 'EDIT',
          };
        });
      },
    );
  }

  save(): void {
    const rules: ComponentPermissionRuleDTO[] = this.targets
      .filter((t) => t.selected !== 'sichtbar')
      .map((t) => ({
        targetType: t.targetType,
        targetInstanceId: t.targetInstanceId,
        level: t.selected === 'editieren' ? 'EDIT' : 'NONE',
      }));
    this.permissions.putRules(this.data.componentKey, rules).subscribe(() => this.dialogRef.close(true));
  }

  private loadTargetsForTag(tag: string, fieldName: string, targetType: 'ROLE' | 'TEAM') {
    return this.orgService.getByTag(tag).pipe(
      switchMap((org) => {
        const def = org.definitions.find((d: { fieldName: string; outdatedAt: string | null }) => d.fieldName === fieldName && !d.outdatedAt);
        if (!def) return of([] as PermissionTarget[]);
        return this.fieldInstanceService.listByDefinitionId(def.id!).pipe(
          map((instances) =>
            instances.map((instance) => ({
              targetType,
              targetInstanceId: instance.id!,
              targetLabel: (instance.value as Record<string, unknown>)?.['label'] as string ?? instance.id!,
              selected: 'sichtbar' as Selection,
              locked: false,
            })),
          ),
        );
      }),
    );
  }

  private toSelection(level: string): Selection {
    if (level === 'EDIT') return 'editieren';
    if (level === 'NONE') return 'nicht sichtbar';
    return 'sichtbar';
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --include='**/permission-popup.component.spec.ts'`
Expected: PASS. Also re-run Task 11's spec to confirm the shell replacement didn't break the gate: `cd frontend && npx ng test --include='**/permission-gate.component.spec.ts'`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/components/permission-gate/permission-popup.component.ts frontend/src/app/shared/components/permission-gate/permission-popup.component.spec.ts
git commit -m "feat(fe): implement full role/team selector in PermissionPopupComponent"
```

---

## Task 13: Frontend — edit-mode toggle dialog + "Rechte vergeben" button

**Files:**
- Create: `frontend/src/app/administration/board/edit-mode-toggle-dialog.component.ts`
- Test: `frontend/src/app/administration/board/edit-mode-toggle-dialog.component.spec.ts`
- Modify: `frontend/src/app/administration/board/board.component.ts`
- Modify: `frontend/src/app/administration/board/board.component.html`
- Modify: `frontend/src/app/administration/board/board.component.spec.ts`

**Interfaces:**
- Consumes: `PermissionEditModeService.setMode`/`.mode` (Task 10).
- Produces: `EditModeToggleDialogComponent` with `dialogRef`, an internal `selected: 'navigieren' | 'editieren'` bound to a `mat-radio-group`, and an `apply()` method calling `editMode.setMode(this.selected)` then closing.

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/app/administration/board/edit-mode-toggle-dialog.component.spec.ts
import { EditModeToggleDialogComponent } from './edit-mode-toggle-dialog.component';

class FakeEditModeService {
  setModeCalls: string[] = [];
  mode = () => 'navigieren' as const;
  setMode(mode: string) { this.setModeCalls.push(mode); }
}
class FakeDialogRef { closed = false; close() { this.closed = true; } }

describe('EditModeToggleDialogComponent', () => {
  it('defaults selection to the current global mode', () => {
    const editMode = new FakeEditModeService();
    editMode.mode = () => 'editieren';
    const component = new EditModeToggleDialogComponent(new FakeDialogRef() as unknown as any, editMode as unknown as any);
    expect(component.selected).toBe('editieren');
  });

  it('applies the selected mode and closes the dialog', () => {
    const editMode = new FakeEditModeService();
    const dialogRef = new FakeDialogRef();
    const component = new EditModeToggleDialogComponent(dialogRef as unknown as any, editMode as unknown as any);
    component.selected = 'editieren';
    component.apply();
    expect(editMode.setModeCalls).toEqual(['editieren']);
    expect(dialogRef.closed).toBe(true);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --include='**/edit-mode-toggle-dialog.component.spec.ts'`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Write the dialog component**

```ts
// frontend/src/app/administration/board/edit-mode-toggle-dialog.component.ts
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatRadioModule } from '@angular/material/radio';
import { PermissionEditModeService, EditMode } from '../../core/services/permission-edit-mode.service';

@Component({
  selector: 'app-edit-mode-toggle-dialog',
  standalone: true,
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatRadioModule],
  template: `
    <h2 mat-dialog-title>Rechte vergeben</h2>
    <mat-dialog-content>
      <mat-radio-group [(ngModel)]="selected">
        <mat-radio-button value="navigieren">Navigieren</mat-radio-button>
        <mat-radio-button value="editieren">Editieren</mat-radio-button>
      </mat-radio-group>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="dialogRef.close()">Abbrechen</button>
      <button mat-raised-button color="primary" (click)="apply()">Übernehmen</button>
    </mat-dialog-actions>
  `,
})
export class EditModeToggleDialogComponent {
  selected: EditMode;

  constructor(
    public dialogRef: MatDialogRef<EditModeToggleDialogComponent>,
    private editModeService: PermissionEditModeService,
  ) {
    this.selected = this.editModeService.mode();
  }

  apply(): void {
    this.editModeService.setMode(this.selected);
    this.dialogRef.close();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --include='**/edit-mode-toggle-dialog.component.spec.ts'`
Expected: PASS

- [ ] **Step 5: Wire the "Rechte vergeben" button into the board screen**

In `board.component.ts`, add a method following the exact pattern of `deleteRole` (Task context from research: `this.dialog.open(...)`, no `afterClosed` payload needed here since the dialog applies its own side effect):

```ts
openEditModeToggle(): void {
  this.dialog.open(EditModeToggleDialogComponent);
}
```

Add the import: `import { EditModeToggleDialogComponent } from './edit-mode-toggle-dialog.component';` and add `EditModeToggleDialogComponent` is not needed in the `imports` array (it's opened dynamically via `MatDialog`, not used in the template directly) — only `MatDialogModule` needs to already be imported (it is, per the existing `deleteRole` usage).

In `board.component.html`, add the button near the existing role-management controls (same tab/section as the role list, so it reads naturally as "manage roles → manage rights for those roles"):

```html
<button mat-raised-button (click)="openEditModeToggle()">Rechte vergeben</button>
```

- [ ] **Step 6: Update `board.component.spec.ts`**

Add a test following the existing fake-instantiation pattern in that file:

```ts
it('opens the edit-mode toggle dialog when "Rechte vergeben" is clicked', () => {
  const openCalls: unknown[] = [];
  dialog.open = (...args: unknown[]) => { openCalls.push(args[0]); return { afterClosed: () => of(undefined) }; };
  component.openEditModeToggle();
  expect(openCalls).toEqual([EditModeToggleDialogComponent]);
});
```

Run: `cd frontend && npx ng test --include='**/board.component.spec.ts'`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/administration/board/edit-mode-toggle-dialog.component.ts frontend/src/app/administration/board/edit-mode-toggle-dialog.component.spec.ts frontend/src/app/administration/board/board.component.ts frontend/src/app/administration/board/board.component.html frontend/src/app/administration/board/board.component.spec.ts
git commit -m "feat(fe): add Rechte-vergeben button and edit-mode toggle dialog"
```

---

## Task 14: Frontend — component tree admin view

**Files:**
- Create: `frontend/src/app/administration/board/component-tree-view.component.ts`
- Test: `frontend/src/app/administration/board/component-tree-view.component.spec.ts`
- Modify: `frontend/src/app/administration/board/board.component.html` (mount the new view)

**Interfaces:**
- Consumes: `ComponentPermissionsService.getTree`/`updateTreeNode` (Task 9).
- Produces: `ComponentTreeViewComponent` with `nodes: ComponentTreeNodeDTO[]`, `ngOnInit` loading them, `save(node: ComponentTreeNodeDTO): void` persisting an edited label/parent.

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/app/administration/board/component-tree-view.component.spec.ts
import { of } from 'rxjs';
import { ComponentTreeViewComponent } from './component-tree-view.component';

class FakePermissionsService {
  nodes = [
    { componentKey: 'platzzuweisung', parentKey: null, label: 'Platzzuweisung' },
    { componentKey: 'platzzuweisung.zuweisungstabelle', parentKey: 'platzzuweisung', label: 'Zuweisungstabelle' },
  ];
  updateCalls: unknown[] = [];
  getTree() { return of(this.nodes); }
  updateTreeNode(key: string, node: unknown) { this.updateCalls.push([key, node]); return of(undefined); }
}

describe('ComponentTreeViewComponent', () => {
  it('loads the tree on init', () => {
    const permissions = new FakePermissionsService();
    const component = new ComponentTreeViewComponent(permissions as unknown as any);
    component.ngOnInit();
    expect(component.nodes.length).toBe(2);
  });

  it('saves an edited node', () => {
    const permissions = new FakePermissionsService();
    const component = new ComponentTreeViewComponent(permissions as unknown as any);
    component.ngOnInit();
    const node = component.nodes[1];
    node.label = 'Renamed';
    component.save(node);
    expect(permissions.updateCalls).toEqual([['platzzuweisung.zuweisungstabelle', node]]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --include='**/component-tree-view.component.spec.ts'`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Write the component**

```ts
// frontend/src/app/administration/board/component-tree-view.component.ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { ComponentPermissionsService } from '../../shared/services/component-permissions.service';
import { ComponentTreeNodeDTO } from '../../shared/models/component-permission.model';

@Component({
  selector: 'app-component-tree-view',
  standalone: true,
  imports: [CommonModule, FormsModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  template: `
    <table class="full-width">
      <tr><th>Komponente</th><th>Label</th><th>Elternteil</th><th></th></tr>
      @for (node of nodes; track node.componentKey) {
        <tr>
          <td>{{ node.componentKey }}</td>
          <td><input matInput [(ngModel)]="node.label" /></td>
          <td><input matInput [(ngModel)]="node.parentKey" /></td>
          <td><button mat-button (click)="save(node)">Speichern</button></td>
        </tr>
      }
    </table>
  `,
})
export class ComponentTreeViewComponent implements OnInit {
  nodes: ComponentTreeNodeDTO[] = [];

  constructor(private permissions: ComponentPermissionsService) {}

  ngOnInit(): void {
    this.permissions.getTree().subscribe((nodes) => (this.nodes = nodes));
  }

  save(node: ComponentTreeNodeDTO): void {
    this.permissions.updateTreeNode(node.componentKey, node).subscribe();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --include='**/component-tree-view.component.spec.ts'`
Expected: PASS

- [ ] **Step 5: Mount it in the board screen**

In `board.component.html`, add near the "Rechte vergeben" button from Task 13:

```html
<app-component-tree-view></app-component-tree-view>
```

Add `ComponentTreeViewComponent` to `board.component.ts`'s standalone `imports` array.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/administration/board/component-tree-view.component.ts frontend/src/app/administration/board/component-tree-view.component.spec.ts frontend/src/app/administration/board/board.component.ts frontend/src/app/administration/board/board.component.html
git commit -m "feat(fe): add component tree admin view to board screen"
```

---

## Task 15: Frontend — wire `PermissionGateComponent` into the Platzzuweisung page

**Files:**
- Modify: `frontend/src/app/administration/platzzuweisung/platzzuweisung.component.ts`
- Modify: `frontend/src/app/administration/platzzuweisung/platzzuweisung.component.spec.ts`

**Interfaces:**
- Consumes: `PermissionGateComponent` (Task 11).

- [ ] **Step 1: Write the failing test**

Add to the existing `platzzuweisung.component.spec.ts` (following whatever fake-instantiation or TestBed pattern that file already uses — check it first; if it hand-instantiates like `board.component.spec.ts`, mirror that; the assertions below just check that the template renders the three gate wrappers, so adapt the exact fixture/compile mechanism to match the file's existing style):

```ts
it('wraps the page, semester selector and table each in a permission gate', () => {
  const fixture = TestBed.createComponent(PlatzzuweisungComponent);
  fixture.detectChanges();
  const gates = fixture.nativeElement.querySelectorAll('app-permission-gate');
  const keys = Array.from(gates).map((el: any) => el.getAttribute('componentKey'));
  expect(keys).toEqual(['platzzuweisung', 'platzzuweisung.semester-auswahl', 'platzzuweisung.zuweisungstabelle']);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --include='**/platzzuweisung.component.spec.ts'`
Expected: FAIL — no `app-permission-gate` elements in the current template.

- [ ] **Step 3: Wrap the template**

In `platzzuweisung.component.ts`, add `PermissionGateComponent` to the standalone `imports` array, then wrap the existing template (shown truncated in research at lines 25-80) as follows — the outer gate wraps the whole page body, the two inner gates wrap the semester dropdown and the table respectively:

```ts
template: `
  <app-permission-gate componentKey="platzzuweisung" [parentKey]="null" label="Platzzuweisung">
    <div class="page-container">
      <h2>Platzzuweisung</h2>

      @if (loading) {
        <mat-spinner diameter="40"></mat-spinner>
      } @else {
        <app-permission-gate #semesterGate="permissionGate" componentKey="platzzuweisung.semester-auswahl" parentKey="platzzuweisung" label="Semester-Auswahl">
          <mat-form-field appearance="outline" class="semester-select">
            <mat-label>Semester</mat-label>
            <mat-select [disabled]="semesterGate.readonly" [value]="selectedSemesterId" (selectionChange)="onSemesterChange($event.value)">
              @for (semester of semesters; track semester.id) {
                <mat-option [value]="semester.id">{{ getSemesterLabel(semester) }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </app-permission-gate>

        <app-permission-gate #tableGate="permissionGate" componentKey="platzzuweisung.zuweisungstabelle" parentKey="platzzuweisung" label="Zuweisungstabelle">
          <table mat-table [dataSource]="children" class="mat-elevation-z2 full-width">
            <ng-container matColumnDef="name">
              <th mat-header-cell *matHeaderCellDef>Name</th>
              <td mat-cell *matCellDef="let child">
                {{ child.lastName }}, {{ child.firstName }}
              </td>
            </ng-container>

            <ng-container matColumnDef="alter">
              <th mat-header-cell *matHeaderCellDef>Alter</th>
              <td mat-cell *matCellDef="let child">
                {{ getAge(child.dateOfBirth) ?? '—' }}
              </td>
            </ng-container>

            <ng-container matColumnDef="gruppe">
              <th mat-header-cell *matHeaderCellDef>Gruppe</th>
              <td mat-cell *matCellDef="let child">
                <mat-select
                  [disabled]="tableGate.readonly"
                  [value]="child.groupInstanceId"
                  (selectionChange)="onGroupChange(child, $event.value)"
                  placeholder="—">
                  <mat-option [value]="null">—</mat-option>
                  @for (group of groups; track group.id) {
                    <mat-option [value]="group.id">{{ $any(group.value).label }}</mat-option>
                  }
                </mat-select>
              </td>
            </ng-container>

            <ng-container matColumnDef="eintritt">
              <th mat-header-cell *matHeaderCellDef>Eintritt</th>
              <td mat-cell *matCellDef="let child">
                <mat-form-field appearance="outline" class="date-field">
                  <input matInput [matDatepicker]="entryPicker"
                    [disabled]="tableGate.readonly"
                    [value]="parseDate(child.entryDate)"
                    [min]="semesterMinDate"
                    [max]="semesterMaxDate"
                    (dateChange)="onEntryDateChange(child, formatDate($event.value))">
                  <mat-datepicker-toggle matSuffix [for]="entryPicker" [disabled]="tableGate.readonly"></mat-datepicker-toggle>
                  <mat-datepicker #entryPicker></mat-datepicker>
                </mat-form-field>
              </td>
            </ng-container>
            <!-- remaining column definitions and mat-header-row / mat-row unchanged from the existing template -->
          </table>
        </app-permission-gate>
      }
    </div>
  </app-permission-gate>
`,
```

Note: the pre-existing `[disabled]="isEntryDateDisabled(child)"` binding on the entry-date input (visible in the original source) must be combined with the new `tableGate.readonly`, e.g. `[disabled]="tableGate.readonly || isEntryDateDisabled(child)"` — don't drop the existing per-row disable logic when adding the gate.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx ng test --include='**/platzzuweisung.component.spec.ts'`
Expected: PASS. Re-run the full frontend suite once to catch any collateral breakage from the template restructure: `cd frontend && npx ng test`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/administration/platzzuweisung/platzzuweisung.component.ts frontend/src/app/administration/platzzuweisung/platzzuweisung.component.spec.ts
git commit -m "feat(fe): wrap Platzzuweisung page and its two components in permission gates"
```

---

## Task 16: Manual end-to-end smoke test

**Files:** none — this is a manual verification pass, no code changes.

**Interfaces:** none.

- [ ] **Step 1: Start backend and frontend**

Run backend: `cd backend && mvn quarkus:dev`
Run frontend (separate terminal): `cd frontend && npx ng serve`

- [ ] **Step 2: Verify the seed and the button**

Log in as an admin. Navigate to the Vorstand/Rollen screen (`administration/board`). Confirm the "Rechte vergeben" button and the component tree view (listing `platzzuweisung`, `platzzuweisung.semester-auswahl`, `platzzuweisung.zuweisungstabelle`) are visible.

- [ ] **Step 3: Toggle edit mode and confirm gears appear**

Click "Rechte vergeben", select "Editieren", close the dialog. Navigate to `administration/platzzuweisung`. Confirm normal navigation worked and that three gear icons are visible: one on the page container, one on the semester dropdown, one on the assignment table.

- [ ] **Step 4: Grant a non-admin role EDIT on the table, VIEW-only via the page**

Click the page-level gear. Grant some non-admin role "sichtbar" (leave as default). Click the table-level gear. Grant the same role "editieren". Save both.

- [ ] **Step 5: Verify inheritance and enforcement as that role**

Log out, log in as a person holding that role (non-admin). Navigate to `administration/platzzuweisung`. Confirm: the semester dropdown is visible but disabled (inherited `VIEW` from the page, no override at `semester-auswahl`); the table is fully editable (local `EDIT` override); changing a child's group or entry date succeeds (network tab shows 204, not 403).

- [ ] **Step 6: Verify the floor cannot be lowered**

As the admin, reopen the table-level gear popup for that same role. Confirm the "nicht sichtbar" option is disabled/locked (since the page granted at least `VIEW`, and — because the pilot page default is `VIEW` for everyone with page access — the table cannot demote it lower).

- [ ] **Step 7: Toggle back to Navigieren**

As the admin, reopen "Rechte vergeben", select "Navigieren". Confirm all gear icons disappear across the app while navigation and data continue to work normally.

- [ ] **Step 8: Report results**

If any step fails, note which one and the observed vs. expected behavior before considering this plan complete.

---
