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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CookingDutyReminderFieldsTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    FieldDefinition cookingDutyDef;

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());

        cookingDutyDef = new FieldDefinition();
        cookingDutyDef.fieldName = "cookingDuty";
        cookingDutyDef.createdAt = Instant.now();
        cookingDutyDef.persist();
    }

    private void persistDuty(Document value) {
        ObjectId instanceId = new ObjectId();
        fieldInstances().insertOne(new Document("_id", instanceId)
                .append("definitionId", cookingDutyDef.id)
                .append("value", value));

        Person person = new Person();
        person.familyId = new ObjectId();
        person.basicProperties = new ArrayList<>();
        person.schedules = new ArrayList<>();
        FieldRef ref = new FieldRef();
        ref.definitionId = cookingDutyDef.id;
        ref.fieldInstanceId = instanceId;
        person.schedules.add(ref);
        person.persist();
    }

    @Test
    void reminderFieldsAreReturned() {
        persistDuty(new Document("date", "2026-09-15")
                .append("groups", List.of("g1"))
                .append("reminderEnabled", true)
                .append("reminderDaysBefore", 5));

        given()
                .when().get("/api/v1/cooking-duties?month=2026-09")
                .then().statusCode(200)
                .body("[0].reminderEnabled", is(true))
                .body("[0].reminderDaysBefore", is(5));
    }

    @Test
    void dutyWithoutReminderReportsDisabled() {
        persistDuty(new Document("date", "2026-09-16").append("groups", List.of("g1")));

        given()
                .when().get("/api/v1/cooking-duties?month=2026-09")
                .then().statusCode(200)
                .body("[0].reminderEnabled", is(false))
                .body("[0].reminderDaysBefore", nullValue());
    }
}
