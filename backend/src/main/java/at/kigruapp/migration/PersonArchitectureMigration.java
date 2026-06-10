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
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Startup
public class PersonArchitectureMigration {

    private static final String MIGRATION_ID = "parents-children-to-persons-v1";

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

        MongoCollection<Document> defs = db.getCollection("field_definitions");
        MongoCollection<Document> instances = db.getCollection("field_instances");
        MongoCollection<Document> persons = db.getCollection("persons");
        String now = Instant.now().toString();

        // Migrate parents
        MongoCollection<Document> parents = db.getCollection("parents");
        for (Document parent : parents.find()) {
            List<Document> basicProps = new ArrayList<>();

            addFieldRef(basicProps, defs, instances, now, "personType", "PARENT");
            addFieldRef(basicProps, defs, instances, now, "firstName", parent.getString("firstName"));
            addFieldRef(basicProps, defs, instances, now, "lastName", parent.getString("lastName"));
            addFieldRef(basicProps, defs, instances, now, "email", parent.getString("email"));
            addFieldRef(basicProps, defs, instances, now, "phone", parent.getString("phone"));

            Object addressRaw = parent.get("address");
            if (addressRaw instanceof Document addr) {
                Document addrValue = new Document()
                        .append("street", addr.getString("street"))
                        .append("zip", addr.getString("zip"))
                        .append("city", addr.getString("city"));
                addFieldRef(basicProps, defs, instances, now, "address", addrValue);
            }

            List<Document> migratedRefs = migrateExistingInstances(
                    instances, defs, parent.getObjectId("_id"), "PARENT");
            basicProps.addAll(migratedRefs);

            Document person = new Document()
                    .append("familyId", parent.getObjectId("familyId"))
                    .append("keycloakUserId", parent.getString("keycloakUserId"))
                    .append("basicProperties", basicProps)
                    .append("roles", new ArrayList<>())
                    .append("schedules", new ArrayList<>())
                    .append("duties", new ArrayList<>())
                    .append("finance", new ArrayList<>())
                    .append("customProperties", new ArrayList<>())
                    .append("createdAt", now)
                    .append("updatedAt", now);
            persons.insertOne(person);
        }

        // Migrate children
        MongoCollection<Document> children = db.getCollection("children");
        for (Document child : children.find()) {
            List<Document> basicProps = new ArrayList<>();

            addFieldRef(basicProps, defs, instances, now, "personType", "CHILD");
            addFieldRef(basicProps, defs, instances, now, "firstName", child.getString("firstName"));
            addFieldRef(basicProps, defs, instances, now, "lastName", child.getString("lastName"));
            addFieldRef(basicProps, defs, instances, now, "dateOfBirth",
                    child.get("dateOfBirth") != null ? child.get("dateOfBirth").toString() : null);
            addFieldRef(basicProps, defs, instances, now, "gender", child.getString("gender"));
            addFieldRef(basicProps, defs, instances, now, "entryDate",
                    child.get("entryDate") != null ? child.get("entryDate").toString() : null);
            addFieldRef(basicProps, defs, instances, now, "exitDate",
                    child.get("exitDate") != null ? child.get("exitDate").toString() : null);
            addFieldRef(basicProps, defs, instances, now, "notes", child.getString("notes"));

            List<Document> migratedRefs = migrateExistingInstances(
                    instances, defs, child.getObjectId("_id"), "CHILD");
            basicProps.addAll(migratedRefs);

            Document person = new Document()
                    .append("familyId", child.getObjectId("familyId"))
                    .append("basicProperties", basicProps)
                    .append("roles", new ArrayList<>())
                    .append("schedules", new ArrayList<>())
                    .append("duties", new ArrayList<>())
                    .append("finance", new ArrayList<>())
                    .append("customProperties", new ArrayList<>())
                    .append("createdAt", now)
                    .append("updatedAt", now);
            persons.insertOne(person);
        }

        instances.updateMany(
                new Document("entityType", new Document("$exists", true)),
                new Document("$unset", new Document("entityType", "").append("entityId", ""))
        );

        defs.updateMany(
                new Document("entity", new Document("$exists", true)),
                new Document("$unset", new Document("entity", ""))
        );
        defs.updateOne(new Document("fieldName", "firstName"), new Document("$set", new Document("keycloakMapping", "firstName")));
        defs.updateOne(new Document("fieldName", "lastName"), new Document("$set", new Document("keycloakMapping", "lastName")));
        defs.updateOne(new Document("fieldName", "email"), new Document("$set", new Document("keycloakMapping", "email")));

        persons.createIndex(new Document("familyId", 1));

        migrations.insertOne(new Document("_id", MIGRATION_ID)
                .append("executedAt", now));
    }

    private void addFieldRef(List<Document> refs, MongoCollection<Document> defs,
                             MongoCollection<Document> instances, String now,
                             String fieldName, Object value) {
        if (value == null) return;
        Document defDoc = defs.find(new Document("fieldName", fieldName)).first();
        if (defDoc == null) return;

        ObjectId instId = new ObjectId();
        instances.insertOne(new Document("_id", instId)
                .append("definitionId", defDoc.getObjectId("_id"))
                .append("value", value)
                .append("createdAt", now)
                .append("updatedAt", now));

        refs.add(new Document()
                .append("definitionId", defDoc.getObjectId("_id"))
                .append("fieldInstanceId", instId));
    }

    private List<Document> migrateExistingInstances(MongoCollection<Document> instances,
                                                     MongoCollection<Document> defs,
                                                     ObjectId entityId, String entityType) {
        List<Document> refs = new ArrayList<>();
        for (Document inst : instances.find(new Document("entityType", entityType).append("entityId", entityId))) {
            ObjectId defId = inst.getObjectId("definitionId");
            refs.add(new Document()
                    .append("definitionId", defId)
                    .append("fieldInstanceId", inst.getObjectId("_id")));
        }
        return refs;
    }
}
