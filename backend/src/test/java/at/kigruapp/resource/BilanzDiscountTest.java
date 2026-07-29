package at.kigruapp.resource;

import at.kigruapp.entity.AliquotConfig;
import at.kigruapp.entity.BilanzOverride;
import at.kigruapp.entity.Currency;
import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.KostenDefinition;
import at.kigruapp.entity.KostenDiscount;
import at.kigruapp.entity.KostenValue;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Wiring-Tests für Geschwister-Rabatt + Aliquot in der Bilanz-Berechnung (Task 6b).
 */
@QuarkusTest
public class BilanzDiscountTest {

    @Inject MongoClient mongoClient;
    @ConfigProperty(name = "quarkus.mongodb.database") String databaseName;

    private com.mongodb.client.MongoCollection<Document> coll(String name) {
        return mongoClient.getDatabase(databaseName).getCollection(name);
    }

    private void fullCleanup() {
        BilanzOverride.deleteAll();
        KostenValue.deleteAll();
        KostenDefinition.deleteAll();
        KostenDiscount.deleteAll();
        AliquotConfig.deleteAll();
        Currency.deleteAll();
        Semester.deleteAll();
        Person.deleteAll();
        Family.deleteAll();
        coll("semester_assignments").deleteMany(new Document());
        coll("field_instances").deleteMany(new Document());
        coll("field_definitions").deleteMany(new Document());
    }

    private Instant utc(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private ObjectId createSemester(int year) {
        Semester s = new Semester();
        s.start = utc(year, 1, 1);
        s.end = utc(year, 12, 31);
        s.createdAt = Instant.now();
        s.persist();
        return s.id;
    }

    private ObjectId createCurrency(String code, String symbol) {
        Currency c = new Currency();
        c.code = code; c.symbol = symbol;
        c.persist();
        return c.id;
    }

    private ObjectId createDefinition(ObjectId currencyId, String label) {
        KostenDefinition d = new KostenDefinition();
        d.label = label; d.currencyId = currencyId; d.active = true;
        d.persist();
        return d.id;
    }

    private void setDefault(ObjectId semesterId, ObjectId groupId, ObjectId defId, String amount) {
        KostenValue v = new KostenValue();
        v.semesterId = semesterId; v.groupId = groupId; v.definitionId = defId;
        v.amount = new BigDecimal(amount);
        v.persist();
    }

    private ObjectId createFamily(String name) {
        Family f = new Family();
        f.name = name; f.createdAt = Instant.now();
        f.persist();
        return f.id;
    }

    private ObjectId createChild(ObjectId familyId, String firstName, ObjectId semesterId,
                                 ObjectId groupId, String entryDate, String exitDate) {
        ObjectId typeDefId = new ObjectId();
        coll("field_definitions").insertOne(new Document("_id", typeDefId).append("fieldName", "personType"));
        ObjectId typeInstId = new ObjectId();
        coll("field_instances").insertOne(new Document("_id", typeInstId)
                .append("definitionId", typeDefId).append("value", "CHILD"));
        ObjectId nameDefId = new ObjectId();
        coll("field_definitions").insertOne(new Document("_id", nameDefId).append("fieldName", "firstName"));
        ObjectId nameInstId = new ObjectId();
        coll("field_instances").insertOne(new Document("_id", nameInstId)
                .append("definitionId", nameDefId).append("value", firstName));

        Person p = new Person();
        p.familyId = familyId;
        p.basicProperties = List.of(new FieldRef(typeDefId, typeInstId), new FieldRef(nameDefId, nameInstId));
        p.persist();

        Document a = new Document("_id", new ObjectId())
                .append("personId", p.id)
                .append("semesterId", semesterId)
                .append("section", "group")
                .append("definitionId", new ObjectId())
                .append("fieldInstanceId", groupId);
        if (entryDate != null) a.append("entryDate", entryDate);
        if (exitDate != null) a.append("exitDate", exitDate);
        coll("semester_assignments").insertOne(a);
        return p.id;
    }

    private void createDiscount(ObjectId semesterId, boolean applyToAll, String order,
                                int fromChild, int percent, List<ObjectId> eligibleDefinitionIds) {
        KostenDiscount d = new KostenDiscount();
        d.semesterId = semesterId;
        d.applyToAll = applyToAll;
        d.order = order;
        KostenDiscount.Tier t = new KostenDiscount.Tier();
        t.fromChild = fromChild;
        t.percent = percent;
        d.tiers.add(t);
        if (eligibleDefinitionIds != null) {
            d.eligibleDefinitionIds = new java.util.ArrayList<>(eligibleDefinitionIds);
        }
        d.persist();
    }

    private void createAliquot(ObjectId semesterId, String mode) {
        AliquotConfig c = new AliquotConfig();
        c.semesterId = semesterId;
        c.kostenMode = mode;
        c.persist();
    }

    /**
     * Zwei Geschwister in derselben Familie, ein rabattfähiger (eligibleDefinitionIds) Posten mit
     * unterschiedlicher Basis pro Kind + ein nicht-rabattfähiger Posten (Essen). Rang 1 (teuerstes
     * Kind) bleibt voll, Rang 2 erhält 50 %. Der Essen-Posten bleibt für beide voll.
     */
    @Test
    void eligibleDiscountAppliesToSecondRankedChildOnly() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId eur = createCurrency("EUR", "€");
        ObjectId beitrag = createDefinition(eur, "Elternbeitrag");   // rabattfähig
        ObjectId essen = createDefinition(eur, "Essen");            // nicht rabattfähig
        ObjectId groupA = new ObjectId();
        ObjectId groupB = new ObjectId();
        setDefault(semesterId, groupA, beitrag, "300.00");
        setDefault(semesterId, groupA, essen, "100.00");
        setDefault(semesterId, groupB, beitrag, "200.00");
        setDefault(semesterId, groupB, essen, "100.00");
        // applyToAll=false => nur eligibleDefinitionIds-Posten rabattiert; Tier ab 2. Kind 50 %.
        createDiscount(semesterId, false, "MOST_EXPENSIVE_FIRST", 2, 50, List.of(beitrag));

        ObjectId familyId = createFamily("Meier");
        ObjectId anna = createChild(familyId, "Anna", semesterId, groupA, null, null); // Basis 300 -> Rang 1
        ObjectId ben = createChild(familyId, "Ben", semesterId, groupB, null, null);   // Basis 200 -> Rang 2

        // Rang 1 (Anna): Elternbeitrag voll 300, discountPercent 0; Essen voll 100.
        given()
            .queryParam("personId", anna.toString())
            .queryParam("year", 2020).queryParam("month", 3)
            .when().get("/api/v1/bilanzen/cell")
            .then().statusCode(200)
            .body("lines.find { it.label == 'Elternbeitrag' }.effectiveAmount", is(300.00f))
            .body("lines.find { it.label == 'Elternbeitrag' }.discountPercent", is(0))
            .body("lines.find { it.label == 'Essen' }.effectiveAmount", is(100.00f))
            .body("lines.find { it.label == 'Essen' }.discountPercent", is(0));

        // Rang 2 (Ben): Elternbeitrag 50 % von 200 = 100, discountPercent 50; Essen bleibt 100.
        given()
            .queryParam("personId", ben.toString())
            .queryParam("year", 2020).queryParam("month", 3)
            .when().get("/api/v1/bilanzen/cell")
            .then().statusCode(200)
            .body("lines.find { it.label == 'Elternbeitrag' }.effectiveAmount", is(100.00f))
            .body("lines.find { it.label == 'Elternbeitrag' }.discountPercent", is(50))
            .body("lines.find { it.label == 'Essen' }.effectiveAmount", is(100.00f))
            .body("lines.find { it.label == 'Essen' }.discountPercent", is(0));
    }

    /**
     * PER_DAY-Aliquot: ein Kind tritt am 16. April ein (15 von 30 Tagen -> Faktor 0.5).
     * Der Monatsbetrag wird pro-rata auf 50 % reduziert; weight = "0.5".
     */
    @Test
    void perDayAliquotProratesMidMonthEntrant() {
        fullCleanup();
        ObjectId semesterId = createSemester(2020);
        ObjectId eur = createCurrency("EUR", "€");
        ObjectId beitrag = createDefinition(eur, "Elternbeitrag");
        ObjectId groupId = new ObjectId();
        setDefault(semesterId, groupId, beitrag, "100.00");
        createAliquot(semesterId, "PER_DAY");

        ObjectId familyId = createFamily("Meier");
        ObjectId anna = createChild(familyId, "Anna", semesterId, groupId, "2020-04-16", null);

        given()
            .queryParam("personId", anna.toString())
            .queryParam("year", 2020).queryParam("month", 4)
            .when().get("/api/v1/bilanzen/cell")
            .then().statusCode(200)
            .body("lines.size()", is(1))
            .body("lines[0].effectiveAmount", is(50.00f))
            .body("lines[0].weight", is("0.5"));
    }
}
