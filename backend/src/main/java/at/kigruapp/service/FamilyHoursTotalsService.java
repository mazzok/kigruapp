package at.kigruapp.service;

import at.kigruapp.entity.AliquotConfig;
import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Soll- und Ist-Minuten einer Familie in einem Semester. Nutzt dieselben
 * HoursBalanceService-Bausteine wie {@code HourEntryResource.our()}; ein Test
 * hält beide Wege auf demselben Ergebnis fest.
 */
@ApplicationScoped
public class FamilyHoursTotalsService {

    public record Totals(int sollMinutes, int istMinutes) {}

    @Inject
    HoursBalanceService hoursBalanceService;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    /** Jüngstes Semester, oder {@code null}, wenn noch keines angelegt ist. */
    public ObjectId latestSemesterId() {
        List<Semester> latest = Semester.listAll(Sort.descending("createdAt"));
        return latest.isEmpty() ? null : latest.get(0).id;
    }

    public Totals totalsFor(Person person, ObjectId semesterId) {
        if (person == null || semesterId == null) {
            return new Totals(0, 0);
        }
        Semester semester = Semester.findById(semesterId);

        List<Person> members = person.familyId == null
                ? List.of(person)
                : Person.findByFamilyId(person.familyId);

        RequiredHours cfg = RequiredHours.findBySemesterId(semesterId);
        AliquotConfig aliquotCfg = AliquotConfig.findBySemesterId(semesterId);
        AliquotMode mode = AliquotMode.fromString(aliquotCfg != null ? aliquotCfg.stundenMode : null);

        Map<String, Integer> sollByMonth = hoursBalanceService.familySollByMonth(
                cfg, mode, semester, placements(members, semesterId));
        int soll = sollByMonth.values().stream().mapToInt(Integer::intValue).sum();

        int ist = 0;
        for (Person member : members) {
            List<HourEntry> entries = HourEntry.<HourEntry>find(
                    "personId = ?1 and semesterId = ?2", member.id, semesterId).list();
            for (HourEntry entry : entries) {
                ist += entry.minutes;
            }
        }
        return new Totals(soll, ist);
    }

    /** Ein Placement pro platziertem Kind der Familie, mit Ein-/Austrittsdatum. */
    private List<HoursBalanceService.ChildPlacement> placements(List<Person> members, ObjectId semesterId) {
        List<HoursBalanceService.ChildPlacement> placements = new ArrayList<>();
        if (members.isEmpty()) {
            return placements;
        }
        List<ObjectId> memberIds = new ArrayList<>();
        for (Person member : members) {
            memberIds.add(member.id);
        }
        Document filter = new Document("semesterId", semesterId)
                .append("section", "group")
                .append("personId", new Document("$in", memberIds));
        MongoCollection<Document> assignments =
                mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
        Set<ObjectId> seen = new HashSet<>();
        for (Document doc : assignments.find(filter)) {
            ObjectId personId = doc.getObjectId("personId");
            if (personId == null || !seen.add(personId)) {
                continue;
            }
            HoursBalanceService.ChildPlacement placement = new HoursBalanceService.ChildPlacement();
            placement.childId = personId.toHexString();
            placement.entryDate = doc.getString("entryDate");
            placement.exitDate = doc.getString("exitDate");
            placements.add(placement);
        }
        return placements;
    }
}
