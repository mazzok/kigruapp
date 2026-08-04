package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private FieldDefinition persistDefinition(String fieldName, String labelDe) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.label = Map.of("de", labelDe, "en", labelDe);
        def.createdAt = java.time.Instant.now();
        def.persist();
        return def;
    }

    /**
     * FieldInstance ist kein Panache-Entity — die Instanz wird direkt in die
     * Collection geschrieben, wie in PersonPropertyResolverTest.
     */
    private ObjectId persistFieldInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        fieldInstances().insertOne(
                new Document("_id", id).append("definitionId", definitionId).append("value", value));
        return id;
    }

    /** Legt eine Person mit einem befüllten Basisfeld an. */
    private Person persistPersonWithField(FieldDefinition def, String value) {
        ObjectId instanceId = persistFieldInstance(def.id, value);

        Person person = new Person();
        person.familyId = new ObjectId();
        person.basicProperties = new ArrayList<>();
        person.basicProperties.add(new FieldRef(def.id, instanceId));
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
