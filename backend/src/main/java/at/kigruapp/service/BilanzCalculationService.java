package at.kigruapp.service;

import at.kigruapp.dto.BilanzCellDTO;
import at.kigruapp.dto.BilanzMatrixDTO;
import at.kigruapp.entity.Currency;
import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.KostenDefinition;
import at.kigruapp.entity.KostenValue;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.Semester;
import at.kigruapp.entity.BilanzOverride;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class BilanzCalculationService {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoCollection<Document> assignments() {
        return mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    // ---------- public API ----------

    public BilanzMatrixDTO computeMatrix(int year) {
        YearMonth current = YearMonth.now();
        String currentYearMonth = String.format("%04d-%02d", current.getYear(), current.getMonthValue());
        List<Semester> semesters = Semester.listAll();
        List<KostenDefinition> activeDefs = KostenDefinition.findActive();

        List<Family> families = Family.listAll();
        families.sort(Comparator.comparing(f -> f.name == null ? "" : f.name.toLowerCase()));

        List<BilanzMatrixDTO.FamilyRow> rows = new ArrayList<>();
        for (Family family : families) {
            List<Person> children = childrenOf(family.id);
            List<BilanzMatrixDTO.MonthCell> months = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (int m = 1; m <= 12; m++) {
                CellComputation cc = computeCellInternal(children, year, m, semesters, activeDefs, current);
                months.add(new BilanzMatrixDTO.MonthCell(
                        m, cc.amount, cc.currencySymbol, cc.mixedCurrency,
                        cc.future, cc.editable, cc.active, cc.entryMarker, cc.exitMarker));
                if (!cc.future) {
                    total = total.add(cc.amount);
                }
            }
            rows.add(new BilanzMatrixDTO.FamilyRow(family.id.toHexString(), family.name, months, total));
        }
        return new BilanzMatrixDTO(year, currentYearMonth, rows);
    }

    public BilanzCellDTO computeCell(ObjectId familyId, int year, int month) {
        List<Semester> semesters = Semester.listAll();
        List<KostenDefinition> activeDefs = KostenDefinition.findActive();
        List<Person> children = childrenOf(familyId);

        Semester semester = semesterForMonth(year, month, semesters);
        List<BilanzCellDTO.Line> lines = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        Set<ObjectId> currencies = new HashSet<>();

        if (semester != null) {
            for (Person child : children) {
                GroupRef gref = groupAssignment(child.id, semester.id);
                if (gref == null || !activeInMonth(gref, year, month)) {
                    continue;
                }
                for (KostenDefinition def : activeDefs) {
                    BigDecimal def0 = defaultAmount(semester.id, gref.groupId, def.id);
                    BigDecimal eff = effectiveAmount(child.id, year, month, def.id, def0);
                    if (eff == null) {
                        continue;
                    }
                    Currency cur = Currency.findById(def.currencyId);
                    lines.add(new BilanzCellDTO.Line(
                            child.id.toHexString(), childName(child), def.id.toHexString(), def.label,
                            cur != null ? cur.symbol : "",
                            def0 != null ? def0 : BigDecimal.ZERO, eff));
                    sum = sum.add(eff);
                    currencies.add(def.currencyId);
                }
            }
        }
        return new BilanzCellDTO(lines, sum, currencies.size() > 1);
    }

    // ---------- per-cell computation ----------

    private static class CellComputation {
        BigDecimal amount = BigDecimal.ZERO;
        String currencySymbol = "";
        boolean mixedCurrency = false;
        boolean future = false;
        boolean active = false;
        boolean editable = false;
        boolean entryMarker = false;
        boolean exitMarker = false;
    }

    private CellComputation computeCellInternal(
            List<Person> children, int year, int month,
            List<Semester> semesters, List<KostenDefinition> activeDefs, YearMonth current) {
        CellComputation cc = new CellComputation();
        cc.future = YearMonth.of(year, month).isAfter(current);

        Semester semester = semesterForMonth(year, month, semesters);
        Set<ObjectId> currencies = new HashSet<>();
        String firstSymbol = "";

        if (semester != null) {
            for (Person child : children) {
                GroupRef gref = groupAssignment(child.id, semester.id);
                if (gref == null) {
                    continue;
                }
                if (matchesYearMonth(gref.entryDate, year, month)) {
                    cc.entryMarker = true;
                }
                if (matchesYearMonth(gref.exitDate, year, month)) {
                    cc.exitMarker = true;
                }
                if (!activeInMonth(gref, year, month)) {
                    continue;
                }
                cc.active = true;
                for (KostenDefinition def : activeDefs) {
                    BigDecimal def0 = defaultAmount(semester.id, gref.groupId, def.id);
                    BigDecimal eff = effectiveAmount(child.id, year, month, def.id, def0);
                    if (eff == null) {
                        continue;
                    }
                    cc.amount = cc.amount.add(eff);
                    Currency cur = Currency.findById(def.currencyId);
                    if (cur != null) {
                        if (currencies.isEmpty()) {
                            firstSymbol = cur.symbol;
                        }
                        currencies.add(def.currencyId);
                    }
                }
            }
        }

        cc.editable = !cc.future && cc.active;
        cc.mixedCurrency = currencies.size() > 1;
        cc.currencySymbol = firstSymbol;

        // Invariant: an inactive cell carries nothing.
        if (!cc.active) {
            cc.amount = BigDecimal.ZERO;
            cc.mixedCurrency = false;
            cc.entryMarker = false;
            cc.exitMarker = false;
        }
        return cc;
    }

    // ---------- helpers ----------

    private Semester semesterForMonth(int year, int month, List<Semester> semesters) {
        Instant firstOfMonth = LocalDate.of(year, month, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        for (Semester s : semesters) {
            if (s.start == null || s.end == null) {
                continue;
            }
            if (!s.start.isAfter(firstOfMonth) && !s.end.isBefore(firstOfMonth)) {
                return s;
            }
        }
        return null;
    }

    private BigDecimal defaultAmount(ObjectId semesterId, ObjectId groupId, ObjectId defId) {
        KostenValue v = KostenValue.findByKeys(semesterId, groupId, defId);
        return v != null ? v.amount : null;
    }

    private BigDecimal effectiveAmount(ObjectId personId, int year, int month, ObjectId defId, BigDecimal def0) {
        BilanzOverride o = BilanzOverride.findByKeys(personId, year, month, defId);
        if (o != null) {
            return o.amount;
        }
        return def0; // may be null -> caller skips
    }

    private static class GroupRef {
        ObjectId groupId;
        String entryDate;
        String exitDate;
    }

    private GroupRef groupAssignment(ObjectId personId, ObjectId semesterId) {
        Document d = assignments().find(new Document("personId", personId)
                .append("semesterId", semesterId)
                .append("section", "group")).first();
        if (d == null) {
            return null;
        }
        GroupRef g = new GroupRef();
        g.groupId = d.getObjectId("fieldInstanceId");
        g.entryDate = d.getString("entryDate");
        g.exitDate = d.getString("exitDate");
        return g;
    }

    private boolean activeInMonth(GroupRef g, int year, int month) {
        String firstDay = String.format("%04d-%02d-01", year, month);
        String lastDay = LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.lastDayOfMonth()).toString();
        boolean entryOk = g.entryDate == null || g.entryDate.isBlank() || g.entryDate.compareTo(lastDay) <= 0;
        boolean exitOk = g.exitDate == null || g.exitDate.isBlank() || g.exitDate.compareTo(firstDay) >= 0;
        return entryOk && exitOk;
    }

    private boolean matchesYearMonth(String date, int year, int month) {
        if (date == null || date.length() < 7) {
            return false;
        }
        return date.substring(0, 4).equals(String.format("%04d", year))
                && date.substring(5, 7).equals(String.format("%02d", month));
    }

    private List<Person> childrenOf(ObjectId familyId) {
        List<Person> children = new ArrayList<>();
        for (Person p : Person.<Person>findByFamilyId(familyId)) {
            if (isChild(p)) {
                children.add(p);
            }
        }
        return children;
    }

    private boolean isChild(Person person) {
        if (person.basicProperties == null) {
            return false;
        }
        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def != null && "personType".equals(def.fieldName)) {
                Document inst = fieldInstances().find(new Document("_id", ref.fieldInstanceId)).first();
                if (inst != null && "CHILD".equals(inst.get("value"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String childName(Person person) {
        String first = basicValue(person, "firstName");
        String last = basicValue(person, "lastName");
        return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
    }

    private String basicValue(Person person, String fieldName) {
        if (person.basicProperties == null) {
            return null;
        }
        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def != null && fieldName.equals(def.fieldName)) {
                Document inst = fieldInstances().find(new Document("_id", ref.fieldInstanceId)).first();
                if (inst != null && inst.get("value") != null) {
                    return inst.get("value").toString();
                }
            }
        }
        return null;
    }
}
