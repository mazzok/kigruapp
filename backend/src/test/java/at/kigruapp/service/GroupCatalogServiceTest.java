package at.kigruapp.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class GroupCatalogServiceTest {

    @Inject
    GroupCatalogService service;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private ObjectId definitionId;

    @BeforeEach
    void seed() {
        var db = mongoClient.getDatabase(databaseName);
        db.getCollection("field_instances").deleteMany(new Document());
        db.getCollection("field_definitions").deleteMany(new Document("fieldName", "group"));

        definitionId = new ObjectId();
        db.getCollection("field_definitions").insertOne(new Document("_id", definitionId)
                .append("fieldName", "group")
                .append("label", new Document("de", "Gruppen"))
                .append("outdatedAt", null));

        db.getCollection("field_instances").insertOne(new Document("_id", new ObjectId())
                .append("definitionId", definitionId)
                .append("value", new Document("label", "Käfergruppe").append("color", "#43a047")));
        db.getCollection("field_instances").insertOne(new Document("_id", new ObjectId())
                .append("definitionId", definitionId)
                .append("value", new Document("label", "Bärengruppe").append("color", "#fb8c00")));
    }

    @Test
    void listsGroupsSortedByLabel() {
        List<GroupCatalogService.GroupInfo> groups = service.listGroups();
        assertEquals(2, groups.size());
        assertEquals("Bärengruppe", groups.get(0).label());
        assertEquals("#fb8c00", groups.get(0).color());
        assertEquals("Käfergruppe", groups.get(1).label());
    }

    @Test
    void byIdContainsEveryGroup() {
        Map<ObjectId, GroupCatalogService.GroupInfo> byId = service.byId();
        assertEquals(2, byId.size());
        for (GroupCatalogService.GroupInfo info : service.listGroups()) {
            assertEquals(info.label(), byId.get(info.id()).label());
        }
    }

    @Test
    void ignoresInstancesOfOutdatedDefinitions() {
        var db = mongoClient.getDatabase(databaseName);
        ObjectId outdated = new ObjectId();
        db.getCollection("field_definitions").insertOne(new Document("_id", outdated)
                .append("fieldName", "group")
                .append("outdatedAt", new java.util.Date()));
        db.getCollection("field_instances").insertOne(new Document("_id", new ObjectId())
                .append("definitionId", outdated)
                .append("value", new Document("label", "Alte Gruppe").append("color", "#000000")));

        assertEquals(2, service.listGroups().size());
    }
}
