package at.kigruapp.service.landing;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CookingTokenProviderTest {

    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Inject
    CookingTokenProvider provider;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private FieldDefinition persistCookingDutyDefinition() {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = "cookingDuty";
        def.label = Map.of("de", "Kochdienst", "en", "Cooking duty");
        def.createdAt = java.time.Instant.now();
        def.persist();
        return def;
    }

    /** Person mit beliebig vielen Kochdienst-Terminen (Datum im Format yyyy-MM-dd). */
    private Person persistPersonWithDuties(ObjectId familyId, FieldDefinition def, String... dates) {
        Person person = new Person();
        person.familyId = familyId;
        person.schedules = new ArrayList<>();
        for (String date : dates) {
            ObjectId instanceId = new ObjectId();
            fieldInstances().insertOne(new Document("_id", instanceId)
                    .append("definitionId", def.id)
                    .append("value", new Document("date", date)));
            person.schedules.add(new FieldRef(def.id, instanceId));
        }
        person.persist();
        return person;
    }

    @Test
    void placeholderIsGroupedAsKochdienst() {
        assertEquals(1, provider.placeholders().size());
        assertEquals("{{kochdienst.naechsterTermin}}", provider.placeholders().get(0).token());
        assertEquals("kochdienst", provider.placeholders().get(0).group());
    }

    @Test
    void naechsterKuenftigerTerminWirdDeutschFormatiert() {
        FieldDefinition def = persistCookingDutyDefinition();
        String soon = LocalDate.now().plusDays(3).toString();
        Person person = persistPersonWithDuties(new ObjectId(), def, soon);

        assertEquals(LocalDate.parse(soon).format(GERMAN_DATE),
                provider.values(person).get("{{kochdienst.naechsterTermin}}"));
    }

    @Test
    void vergangeneTermineWerdenIgnoriert() {
        FieldDefinition def = persistCookingDutyDefinition();
        Person person = persistPersonWithDuties(new ObjectId(), def,
                LocalDate.now().minusDays(5).toString());

        assertEquals("", provider.values(person).get("{{kochdienst.naechsterTermin}}"));
    }

    @Test
    void vonMehrerenKuenftigenTerminenGewinntDerFruehste() {
        FieldDefinition def = persistCookingDutyDefinition();
        String later = LocalDate.now().plusDays(20).toString();
        String earlier = LocalDate.now().plusDays(4).toString();
        Person person = persistPersonWithDuties(new ObjectId(), def, later, earlier);

        assertEquals(LocalDate.parse(earlier).format(GERMAN_DATE),
                provider.values(person).get("{{kochdienst.naechsterTermin}}"));
    }

    @Test
    void terminEinesFamilienmitgliedsZaehltEbenfalls() {
        FieldDefinition def = persistCookingDutyDefinition();
        ObjectId familyId = new ObjectId();
        Person me = persistPersonWithDuties(familyId, def);
        String date = LocalDate.now().plusDays(2).toString();
        persistPersonWithDuties(familyId, def, date);

        assertEquals(LocalDate.parse(date).format(GERMAN_DATE),
                provider.values(me).get("{{kochdienst.naechsterTermin}}"));
    }

    @Test
    void ohneKochdienstDefinitionLiefertLeerenWertStattFehler() {
        Person person = new Person();
        person.familyId = new ObjectId();
        person.persist();

        assertTrue(provider.values(person).get("{{kochdienst.naechsterTermin}}").isEmpty());
    }
}
