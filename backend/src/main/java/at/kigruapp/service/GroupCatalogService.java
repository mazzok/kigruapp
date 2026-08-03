package at.kigruapp.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Gruppen der Organisation als flache Liste: field_instances der aktiven
 * "group"-Definition, mit Anzeigename und Farbe aus dem value-Dokument.
 */
@ApplicationScoped
public class GroupCatalogService {

    public record GroupInfo(ObjectId id, String label, String color) {}

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public List<GroupInfo> listGroups() {
        var db = mongoClient.getDatabase(databaseName);

        List<ObjectId> definitionIds = new ArrayList<>();
        for (Document def : db.getCollection("field_definitions")
                .find(Filters.and(Filters.eq("fieldName", "group"),
                        Filters.eq("outdatedAt", null)))) {
            definitionIds.add(def.getObjectId("_id"));
        }
        if (definitionIds.isEmpty()) {
            return List.of();
        }

        List<GroupInfo> groups = new ArrayList<>();
        for (Document instance : db.getCollection("field_instances")
                .find(Filters.in("definitionId", definitionIds))) {
            Object value = instance.get("value");
            if (!(value instanceof Document valueDoc)) {
                continue;
            }
            String label = valueDoc.getString("label");
            String color = valueDoc.getString("color");
            if (label == null || label.isBlank()) {
                continue;
            }
            ObjectId id = instance.getObjectId("_id");
            groups.add(new GroupInfo(id, label, color));
        }
        groups.sort(Comparator.comparing(GroupInfo::label));
        return groups;
    }

    public Map<ObjectId, GroupInfo> byId() {
        Map<ObjectId, GroupInfo> map = new LinkedHashMap<>();
        for (GroupInfo info : listGroups()) {
            map.put(info.id(), info);
        }
        return map;
    }
}
