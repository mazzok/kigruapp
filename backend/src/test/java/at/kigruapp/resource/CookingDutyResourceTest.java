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

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CookingDutyResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    FieldDefinition cookingDutyDef;

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());

        cookingDutyDef = new FieldDefinition();
        cookingDutyDef.fieldName = "cookingDuty";
        cookingDutyDef.createdAt = java.time.Instant.now();
        cookingDutyDef.persist();
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private ObjectId persistCookingDutyInstance(String date, List<String> groups, String description) {
        ObjectId id = new ObjectId();
        Document value = new Document("date", date).append("groups", groups).append("description", description);
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", cookingDutyDef.id).append("value", value));
        return id;
    }

    private void persistPersonWithDuty(ObjectId dutyInstanceId) {
        Person p = new Person();
        p.familyId = new ObjectId();
        p.schedules = List.of(new FieldRef(cookingDutyDef.id, dutyInstanceId));
        p.persist();
    }

    @Test
    void filtersByMonth() {
        persistPersonWithDuty(persistCookingDutyInstance("2026-09-10", List.of("g1"), "September"));
        persistPersonWithDuty(persistCookingDutyInstance("2026-10-10", List.of("g1"), "Oktober"));

        given().when().get("/api/v1/cooking-duties?month=2026-09")
            .then().statusCode(200)
            .body("size()", is(1))
            .body("[0].description", is("September"));
    }

    @Test
    void filtersByGroups() {
        persistPersonWithDuty(persistCookingDutyInstance("2026-09-10", List.of("g1"), "Gruppe1"));
        persistPersonWithDuty(persistCookingDutyInstance("2026-09-11", List.of("g2"), "Gruppe2"));

        given().when().get("/api/v1/cooking-duties?groups=g1")
            .then().statusCode(200)
            .body("size()", is(1))
            .body("[0].description", is("Gruppe1"));
    }

    @Test
    void returnsEmptyListWhenNothingMatches() {
        given().when().get("/api/v1/cooking-duties?month=2099-01")
            .then().statusCode(200)
            .body("size()", is(0));
    }
}
