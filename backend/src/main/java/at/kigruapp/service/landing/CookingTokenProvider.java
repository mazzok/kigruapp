package at.kigruapp.service.landing;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * {@code {{kochdienst.naechsterTermin}}} — der nächste künftige Kochdienst der
 * eigenen Familie. Die Termine hängen als FieldRef in {@code Person.schedules};
 * der FieldInstance-Wert ist ein Dokument mit {@code date} (yyyy-MM-dd).
 */
@ApplicationScoped
public class CookingTokenProvider implements LandingTokenProvider {

    private static final String NEXT_DUTY = "{{kochdienst.naechsterTermin}}";
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Override
    public List<LandingPlaceholder> placeholders() {
        return List.of(new LandingPlaceholder(NEXT_DUTY, "Nächster Kochdienst", "kochdienst"));
    }

    @Override
    public Map<String, String> values(Person person) {
        return Map.of(NEXT_DUTY, nextDutyDate(person));
    }

    private String nextDutyDate(Person person) {
        FieldDefinition cookingDutyDef = FieldDefinition.find("fieldName", "cookingDuty").firstResult();
        if (cookingDutyDef == null) {
            return "";
        }
        List<Person> members = person.familyId == null
                ? List.of(person)
                : Person.findByFamilyId(person.familyId);

        MongoCollection<Document> instances =
                mongoClient.getDatabase(databaseName).getCollection("field_instances");
        String today = LocalDate.now().toString();
        String earliest = null;

        for (Person member : members) {
            if (member.schedules == null) {
                continue;
            }
            for (FieldRef ref : member.schedules) {
                if (!cookingDutyDef.id.equals(ref.definitionId)) {
                    continue;
                }
                Document doc = instances.find(new Document("_id", ref.fieldInstanceId)).first();
                if (doc == null) {
                    continue;
                }
                FieldInstance inst = FieldInstance.fromDocument(doc);
                if (!(inst.value instanceof Document valueDoc)) {
                    continue;
                }
                String date = valueDoc.getString("date");
                // ISO-Datumsstrings sind lexikografisch vergleichbar.
                if (date == null || date.compareTo(today) < 0) {
                    continue;
                }
                if (earliest == null || date.compareTo(earliest) < 0) {
                    earliest = date;
                }
            }
        }
        return earliest == null ? "" : LocalDate.parse(earliest).format(GERMAN_DATE);
    }
}
