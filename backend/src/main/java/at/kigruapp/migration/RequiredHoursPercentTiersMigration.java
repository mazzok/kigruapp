package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Staffeln der zu leistenden Stunden werden von absoluten Minuten auf einen
 * Prozent-Rabatt umgestellt, damit sie auch bei gruppenabhängigen Sätzen gelten.
 */
@ApplicationScoped
@Startup
public class RequiredHoursPercentTiersMigration {

    public static final String MIGRATION_ID = "required-hours-percent-tiers-v1";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        migrate();
    }

    public void migrate() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> migrations = db.getCollection("migrations");
        if (migrations.find(new Document("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        MongoCollection<Document> collection = db.getCollection("requiredHours");
        for (Document doc : collection.find()) {
            int defaultMinutes = doc.getInteger("defaultMinutesPerMonth", 0);
            List<Document> tiers = doc.getList("tiers", Document.class);
            List<Document> converted = new ArrayList<>();
            if (tiers != null) {
                for (Document tier : tiers) {
                    converted.add(new Document("fromChild", tier.getInteger("fromChild", 0))
                            .append("percent", percentFor(defaultMinutes, tier)));
                }
            }
            collection.updateOne(new Document("_id", doc.getObjectId("_id")),
                    new Document("$set", new Document("tiers", converted)
                            .append("allGroups", true)
                            .append("order", "MOST_EXPENSIVE_FIRST")
                            .append("groupRates", List.of())));
        }

        migrations.insertOne(new Document("_id", MIGRATION_ID)
                .append("executedAt", Date.from(Instant.now())));
    }

    /** Rabatt in Prozent: 100 − 100 × minutes / default, auf 0..100 geklemmt. */
    private int percentFor(int defaultMinutes, Document tier) {
        if (tier.get("percent") != null) {
            return tier.getInteger("percent", 0);   // bereits umgerechnet
        }
        int minutes = tier.getInteger("minutesPerMonth", 0);
        if (defaultMinutes <= 0) {
            return 0;
        }
        int percent = BigDecimal.valueOf(100L * minutes)
                .divide(BigDecimal.valueOf(defaultMinutes), 0, RoundingMode.HALF_UP)
                .intValue();
        int discount = 100 - percent;
        return Math.max(0, Math.min(100, discount));
    }
}
