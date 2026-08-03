package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Loest Anzeigenamen von field_instances in konstant zwei Abfragen auf:
 * value.label, sonst der skalare Wert, sonst das deutsche Label bzw. der
 * fieldName der zugehoerigen FieldDefinition. Geloeschte Instanzen fehlen im
 * Ergebnis, unaufloesbare Namen stehen als null darin.
 */
@ApplicationScoped
public class FieldInstanceLabelResolver {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public Map<ObjectId, String> resolveLabels(Collection<ObjectId> instanceIds) {
        Map<ObjectId, String> labels = new LinkedHashMap<>();
        if (instanceIds == null || instanceIds.isEmpty()) return labels;

        MongoCollection<Document> instances = mongoClient.getDatabase(databaseName)
                .getCollection("field_instances");

        Map<ObjectId, ObjectId> definitionByInstance = new LinkedHashMap<>();
        for (Document instance : instances.find(Filters.in("_id", new LinkedHashSet<>(instanceIds)))) {
            ObjectId instanceId = instance.getObjectId("_id");
            labels.put(instanceId, labelFromValue(instance.get("value")));
            ObjectId definitionId = instance.getObjectId("definitionId");
            if (definitionId != null) {
                definitionByInstance.put(instanceId, definitionId);
            }
        }

        Set<ObjectId> missing = new LinkedHashSet<>();
        for (Map.Entry<ObjectId, ObjectId> entry : definitionByInstance.entrySet()) {
            if (labels.get(entry.getKey()) == null) {
                missing.add(entry.getValue());
            }
        }
        if (missing.isEmpty()) return labels;

        Map<ObjectId, String> definitionNames = new LinkedHashMap<>();
        for (FieldDefinition def : FieldDefinition.<FieldDefinition>list("_id in ?1", new ArrayList<>(missing))) {
            String label = def.label != null ? trimToNull(def.label.get("de")) : null;
            definitionNames.put(def.id, label != null ? label : trimToNull(def.fieldName));
        }
        for (Map.Entry<ObjectId, ObjectId> entry : definitionByInstance.entrySet()) {
            if (labels.get(entry.getKey()) == null) {
                labels.put(entry.getKey(), definitionNames.get(entry.getValue()));
            }
        }
        return labels;
    }

    private String labelFromValue(Object value) {
        if (value instanceof Document valueDoc) {
            return trimToNull(valueDoc.getString("label"));
        }
        if (value instanceof String stringValue) {
            return trimToNull(stringValue);
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
