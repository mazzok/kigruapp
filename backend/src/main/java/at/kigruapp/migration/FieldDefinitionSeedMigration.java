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
import java.util.Date;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Startup
public class FieldDefinitionSeedMigration {

    private static final String MIGRATION_ID = "seed-basic-property-definitions-v3";

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
        Date now = Date.from(Instant.now());

        // Fix v1 bug: createdAt was stored as String instead of BSON Date
        for (Document doc : defs.find()) {
            Object createdAt = doc.get("createdAt");
            if (createdAt instanceof String) {
                defs.updateOne(
                        new Document("_id", doc.getObjectId("_id")),
                        new Document("$set", new Document("createdAt", Date.from(Instant.parse((String) createdAt))))
                );
            }
        }

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

        // Cooking duty definition
        seedDef(defs, now, "cookingDuty",
                Map.of("de", "Kochdienst", "en", "Cooking Duty"),
                new Document("type", "object")
                        .append("properties", new Document()
                                .append("date", new Document("type", "string").append("format", "date"))
                                .append("groups", new Document("type", "array").append("items", new Document("type", "string")))
                                .append("description", new Document("type", "string"))
                                .append("foodProperties", new Document("type", "object")))
                        .append("required", List.of("date", "groups")),
                false, null);

        // Food property definitions
        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Glutenfrei", "en", "Gluten-free"),
                new Document("type", "boolean"), false,
                new Document("icon", "grain"));

        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Weizenfrei", "en", "Wheat-free"),
                new Document("type", "boolean"), false,
                new Document("icon", "do_not_disturb"));

        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Vegetarisch", "en", "Vegetarian"),
                new Document("type", "boolean"), false,
                new Document("icon", "eco"));

        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Vegan", "en", "Vegan"),
                new Document("type", "boolean"), false,
                new Document("icon", "spa"));

        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Ohne Milchprodukte", "en", "Dairy-free"),
                new Document("type", "boolean"), false,
                new Document("icon", "water_drop"));

        seedDefWithProperties(defs, now, "food-property",
                Map.of("de", "Histaminvertraeglich", "en", "Histamine-friendly"),
                new Document("type", "boolean"), false,
                new Document("icon", "health_and_safety"));

        migrations.insertOne(new Document("_id", MIGRATION_ID)
                .append("executedAt", now));
    }

    private void seedDef(MongoCollection<Document> defs, Date now,
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

    private void seedDefWithProperties(MongoCollection<Document> defs, Date now,
                                        String fieldName, Map<String, String> label,
                                        Document jsonSchema, boolean required,
                                        Document properties) {
        String labelDe = label.get("de");
        if (defs.find(new Document("fieldName", fieldName).append("label.de", labelDe)).first() != null) {
            return;
        }
        Document doc = new Document()
                .append("fieldName", fieldName)
                .append("label", new Document(label))
                .append("jsonSchema", jsonSchema)
                .append("required", required)
                .append("properties", properties)
                .append("createdAt", now);
        defs.insertOne(doc);
    }
}
