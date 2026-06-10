package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Startup
public class FieldDefinitionSeedMigration {

    private static final String MIGRATION_ID = "seed-basic-property-definitions-v1";

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
        String now = Instant.now().toString();

        seedDef(defs, now, "personType",
                Map.of("de", "Personentyp", "en", "Person Type"),
                new Document("type", "string").append("enum", List.of("PARENT", "CHILD")),
                true, null);

        seedDef(defs, now, "firstName",
                Map.of("de", "Vorname", "en", "First Name"),
                new Document("type", "string"),
                true, "firstName");

        seedDef(defs, now, "lastName",
                Map.of("de", "Nachname", "en", "Last Name"),
                new Document("type", "string"),
                true, "lastName");

        seedDef(defs, now, "email",
                Map.of("de", "E-Mail", "en", "Email"),
                new Document("type", "string").append("format", "email"),
                false, "email");

        seedDef(defs, now, "phone",
                Map.of("de", "Telefon", "en", "Phone"),
                new Document("type", "string"),
                false, null);

        seedDef(defs, now, "dateOfBirth",
                Map.of("de", "Geburtsdatum", "en", "Date of Birth"),
                new Document("type", "string").append("format", "date"),
                false, null);

        seedDef(defs, now, "gender",
                Map.of("de", "Geschlecht", "en", "Gender"),
                new Document("type", "string").append("enum", List.of("male", "female", "diverse")),
                false, null);

        seedDef(defs, now, "entryDate",
                Map.of("de", "Eintrittsdatum", "en", "Entry Date"),
                new Document("type", "string").append("format", "date"),
                false, null);

        seedDef(defs, now, "exitDate",
                Map.of("de", "Austrittsdatum", "en", "Exit Date"),
                new Document("type", "string").append("format", "date"),
                false, null);

        seedDef(defs, now, "notes",
                Map.of("de", "Notizen", "en", "Notes"),
                new Document("type", "string"),
                false, null);

        seedDef(defs, now, "address",
                Map.of("de", "Adresse", "en", "Address"),
                new Document("type", "object")
                        .append("properties", new Document()
                                .append("street", new Document("type", "string"))
                                .append("zip", new Document("type", "string"))
                                .append("city", new Document("type", "string")))
                        .append("required", List.of("street", "zip", "city")),
                false, null);

        migrations.insertOne(new Document("_id", MIGRATION_ID)
                .append("executedAt", now));
    }

    private void seedDef(MongoCollection<Document> defs, String now,
                         String fieldName, Map<String, String> label,
                         Document jsonSchema, boolean required, String keycloakMapping) {
        if (defs.find(new Document("fieldName", fieldName)).first() != null) {
            return;
        }
        Document doc = new Document()
                .append("fieldName", fieldName)
                .append("label", new Document(label))
                .append("jsonSchema", jsonSchema)
                .append("required", required)
                .append("createdAt", now);
        if (keycloakMapping != null) {
            doc.append("keycloakMapping", keycloakMapping);
        }
        defs.insertOne(doc);
    }
}
