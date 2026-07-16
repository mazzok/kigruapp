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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

@ApplicationScoped
@Startup
public class SemesterBootstrapMigration {

    private static final String MIGRATION_ID = "semester-bootstrap-v1";

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

        MongoCollection<Document> semesters = db.getCollection("semesters");
        MongoCollection<Document> persons = db.getCollection("persons");
        MongoCollection<Document> assignments = db.getCollection("semester_assignments");

        LocalDate today = LocalDate.now();
        int startYear = today.getMonthValue() >= 9 ? today.getYear() : today.getYear() - 1;
        Date start = Date.from(LocalDate.of(startYear, 9, 1).atStartOfDay(ZoneOffset.UTC).toInstant());
        Date end = Date.from(LocalDate.of(startYear + 1, 8, 31).atStartOfDay(ZoneOffset.UTC).toInstant());
        Date now = Date.from(Instant.now());

        ObjectId semesterId = new ObjectId();
        semesters.insertOne(new Document("_id", semesterId)
                .append("start", start)
                .append("end", end)
                .append("createdAt", now));

        for (Document person : persons.find()) {
            ObjectId personId = person.getObjectId("_id");
            backfillSection(assignments, person, personId, semesterId, "organisationalUnit", "group");
            backfillSection(assignments, person, personId, semesterId, "assignedDuty", "team");
            backfillSection(assignments, person, personId, semesterId, "assignedRole", "role");
        }

        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", now));
    }

    @SuppressWarnings("unchecked")
    private void backfillSection(MongoCollection<Document> assignments, Document person, ObjectId personId,
                                   ObjectId semesterId, String personField, String section) {
        List<Document> refs = (List<Document>) person.get(personField);
        if (refs == null) return;
        for (Document ref : refs) {
            ObjectId defId = ref.getObjectId("definitionId");
            ObjectId instId = ref.getObjectId("fieldInstanceId");
            if (defId == null || instId == null) continue;
            assignments.insertOne(new Document("_id", new ObjectId())
                    .append("personId", personId)
                    .append("semesterId", semesterId)
                    .append("section", section)
                    .append("definitionId", defId)
                    .append("fieldInstanceId", instId));
        }
    }
}
