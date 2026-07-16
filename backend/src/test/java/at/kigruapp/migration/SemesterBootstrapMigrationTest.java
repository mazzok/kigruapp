package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class SemesterBootstrapMigrationTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    SemesterBootstrapMigration migration;

    @Test
    void backfillsLegacyPersonAssignmentsIntoSemesterAssignments() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> migrations = db.getCollection("migrations");
        MongoCollection<Document> persons = db.getCollection("persons");
        MongoCollection<Document> semesters = db.getCollection("semesters");
        MongoCollection<Document> assignments = db.getCollection("semester_assignments");

        migrations.deleteOne(new Document("_id", "semester-bootstrap-v1"));
        semesters.deleteMany(new Document());
        assignments.deleteMany(new Document());

        ObjectId personId = new ObjectId();
        ObjectId groupDefId = new ObjectId();
        ObjectId groupInstId = new ObjectId();
        persons.insertOne(new Document("_id", personId)
                .append("organisationalUnit", List.of(
                        new Document("definitionId", groupDefId).append("fieldInstanceId", groupInstId))));

        migration.onStart(null);

        Document semester = semesters.find().first();
        assertNotNull(semester);

        Document assignment = assignments.find(new Document("personId", personId)).first();
        assertNotNull(assignment);
        assertEquals("group", assignment.getString("section"));
        assertEquals(groupDefId, assignment.getObjectId("definitionId"));
        assertEquals(groupInstId, assignment.getObjectId("fieldInstanceId"));
        assertEquals(semester.getObjectId("_id"), assignment.getObjectId("semesterId"));

        persons.deleteOne(new Document("_id", personId));
    }
}
