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

    // Admin-Pflicht selbst wird in SecurityFilterTest geprueft (getParentDirectoryAttributes_nonAdmin_forbidden
    // etc.): %test.quarkus.oidc.enabled=false schaltet SecurityFilter fuer diesen QuarkusTest komplett ab,
    // ein HTTP-Test koennte die Ablehnung hier also nicht beobachten.
}
