package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RequiredHoursPercentTiersMigrationTest {

    @Inject
    MongoClient mongoClient;

    @Inject
    RequiredHoursPercentTiersMigration migration;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private Document runOn(Document requiredHours) {
        var db = mongoClient.getDatabase(databaseName);
        db.getCollection("migrations").deleteOne(new Document("_id", RequiredHoursPercentTiersMigration.MIGRATION_ID));
        db.getCollection("requiredHours").deleteMany(new Document());
        ObjectId id = new ObjectId();
        db.getCollection("requiredHours").insertOne(requiredHours.append("_id", id));

        migration.migrate();

        return db.getCollection("requiredHours").find(new Document("_id", id)).first();
    }

    @Test
    void convertsAbsoluteTiersToPercent() {
        Document after = runOn(new Document("semesterId", new ObjectId())
                .append("defaultMinutesPerMonth", 480)
                .append("tiers", List.of(
                        new Document("fromChild", 2).append("minutesPerMonth", 360),
                        new Document("fromChild", 3).append("minutesPerMonth", 0))));

        List<Document> tiers = after.getList("tiers", Document.class);
        assertEquals(25, tiers.get(0).getInteger("percent"));   // 360 von 480 -> 25 % Rabatt
        assertEquals(100, tiers.get(1).getInteger("percent"));  // 0 von 480 -> 100 % Rabatt
        assertNull(tiers.get(0).get("minutesPerMonth"));
    }

    @Test
    void setsNewFieldsToDefaults() {
        Document after = runOn(new Document("semesterId", new ObjectId())
                .append("defaultMinutesPerMonth", 480)
                .append("tiers", List.of()));

        assertTrue(after.getBoolean("allGroups"));
        assertEquals("MOST_EXPENSIVE_FIRST", after.getString("order"));
        assertTrue(after.getList("groupRates", Document.class).isEmpty());
    }

    @Test
    void zeroDefaultYieldsZeroPercent() {
        Document after = runOn(new Document("semesterId", new ObjectId())
                .append("defaultMinutesPerMonth", 0)
                .append("tiers", List.of(new Document("fromChild", 2).append("minutesPerMonth", 120))));

        assertEquals(0, after.getList("tiers", Document.class).get(0).getInteger("percent"));
    }

    @Test
    void tierAboveDefaultIsClampedToZeroPercent() {
        Document after = runOn(new Document("semesterId", new ObjectId())
                .append("defaultMinutesPerMonth", 480)
                .append("tiers", List.of(new Document("fromChild", 2).append("minutesPerMonth", 600))));

        assertEquals(0, after.getList("tiers", Document.class).get(0).getInteger("percent"));
    }

    @Test
    void runsOnlyOnce() {
        var db = mongoClient.getDatabase(databaseName);
        Document after = runOn(new Document("semesterId", new ObjectId())
                .append("defaultMinutesPerMonth", 480)
                .append("tiers", List.of(new Document("fromChild", 2).append("minutesPerMonth", 360))));
        assertEquals(25, after.getList("tiers", Document.class).get(0).getInteger("percent"));

        // Zweiter Lauf darf die bereits umgerechneten Werte nicht erneut anfassen.
        migration.migrate();
        Document again = db.getCollection("requiredHours").find(new Document("_id", after.getObjectId("_id"))).first();
        assertEquals(25, again.getList("tiers", Document.class).get(0).getInteger("percent"));
    }
}
