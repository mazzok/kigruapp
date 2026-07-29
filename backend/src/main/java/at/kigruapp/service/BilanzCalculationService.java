package at.kigruapp.service;

import at.kigruapp.dto.BilanzCellDTO;
import at.kigruapp.dto.BilanzMatrixDTO;
import at.kigruapp.entity.AliquotConfig;
import at.kigruapp.entity.Currency;
import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.KostenDefinition;
import at.kigruapp.entity.KostenDiscount;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class BilanzCalculationService {

    @Inject
    MongoClient mongoClient;

    @Inject
    AliquotService aliquotService;

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

        List<Family> allFamilies = Family.listAll();
        List<Person> allChildren = new ArrayList<>();
        for (Family family : allFamilies) {
            allChildren.addAll(childrenOf(family.id));
        }
        allChildren.sort(Comparator.comparing(c -> childName(c).toLowerCase()));

        List<BilanzMatrixDTO.ChildRow> rows = new ArrayList<>();
        for (Person child : allChildren) {
            List<Person> single = List.of(child);
            List<BilanzMatrixDTO.MonthCell> months = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (int m = 1; m <= 12; m++) {
                CellComputation cc = computeCellInternal(single, year, m, semesters, activeDefs, current);
                months.add(new BilanzMatrixDTO.MonthCell(
                        m, cc.amount, cc.currencySymbol, cc.mixedCurrency,
                        cc.future, cc.editable, cc.active, cc.entryMarker, cc.exitMarker));
                if (!cc.future) {
                    total = total.add(cc.amount);
                }
            }
            rows.add(new BilanzMatrixDTO.ChildRow(child.id.toHexString(), childName(child), months, total));
        }
        return new BilanzMatrixDTO(year, currentYearMonth, rows);
    }

    public BilanzCellDTO computeCell(ObjectId personId, int year, int month) {
        List<Semester> semesters = Semester.listAll();
        List<KostenDefinition> activeDefs = KostenDefinition.findActive();
        Person child = Person.findById(personId);

        Semester semester = semesterForMonth(year, month, semesters);
        List<BilanzCellDTO.Line> lines = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        Set<ObjectId> currencies = new HashSet<>();

        if (semester != null && child != null) {
            GroupRef gref = groupAssignment(child.id, semester.id);
            if (gref != null) {
                AliquotMode mode = aliquotMode(semester.id);
                KostenDiscount discountCfg = KostenDiscount.findBySemesterId(semester.id);
                BigDecimal frac = aliquotService.monthFraction(
                        mode, gref.entryDate, gref.exitDate, year, month);
                if (frac.signum() != 0) {
                    List<ChildBase> present = presentSiblings(
                            child.familyId, semester, year, month, mode, activeDefs, discountCfg);
                    BigDecimal factor = discountFactor(discountCfg, child.id.toHexString(), present);
                    String fracWeight = frac.stripTrailingZeros().toPlainString();
                    for (KostenDefinition def : activeDefs) {
                        BigDecimal def0 = defaultAmount(semester.id, gref.groupId, def.id);
                        BilanzOverride o = BilanzOverride.findByKeys(child.id, year, month, def.id);
                        boolean elig = eligible(discountCfg, def);
                        BigDecimal eff;
                        int discountPercent;
                        String weight;
                        if (o != null) {
                            eff = o.amount; // override bypasses discount and aliquot
                            // Neither discount factor nor aliquot fraction was applied — report honestly.
                            discountPercent = 0;
                            weight = "1";
                        } else if (def0 == null) {
                            continue;
                        } else {
                            BigDecimal defFactor = elig ? factor : BigDecimal.ONE;
                            eff = def0.multiply(defFactor).multiply(frac)
                                    .setScale(2, RoundingMode.HALF_UP);
                            discountPercent = elig
                                    ? (int) Math.round((1 - factor.doubleValue()) * 100) : 0;
                            weight = fracWeight;
                        }
                        Currency cur = Currency.findById(def.currencyId);
                        lines.add(new BilanzCellDTO.Line(
                                child.id.toHexString(), childName(child), def.id.toHexString(), def.label,
                                cur != null ? cur.symbol : "",
                                def0 != null ? def0 : BigDecimal.ZERO, eff,
                                discountPercent, weight));
                        sum = sum.add(eff);
                        currencies.add(def.currencyId);
                    }
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
            AliquotMode mode = aliquotMode(semester.id);
            KostenDiscount discountCfg = KostenDiscount.findBySemesterId(semester.id);
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
                BigDecimal frac = aliquotService.monthFraction(
                        mode, gref.entryDate, gref.exitDate, year, month);
                if (frac.signum() == 0) {
                    continue;
                }
                cc.active = true;
                List<ChildBase> present = presentSiblings(
                        child.familyId, semester, year, month, mode, activeDefs, discountCfg);
                BigDecimal factor = discountFactor(discountCfg, child.id.toHexString(), present);
                for (KostenDefinition def : activeDefs) {
                    BigDecimal def0 = defaultAmount(semester.id, gref.groupId, def.id);
                    BilanzOverride o = BilanzOverride.findByKeys(child.id, year, month, def.id);
                    BigDecimal eff;
                    if (o != null) {
                        eff = o.amount; // override bypasses discount and aliquot
                    } else if (def0 == null) {
                        continue;
                    } else {
                        boolean elig = eligible(discountCfg, def);
                        BigDecimal defFactor = elig ? factor : BigDecimal.ONE;
                        eff = def0.multiply(defFactor).multiply(frac)
                                .setScale(2, RoundingMode.HALF_UP);
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
        boolean hasFirst = first != null && !first.isBlank();
        boolean hasLast = last != null && !last.isBlank();
        if (hasLast && hasFirst) {
            return last + ", " + first;
        }
        if (hasLast) {
            return last;
        }
        return hasFirst ? first : "";
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

    // ---------- discount + aliquot wiring ----------

    private AliquotMode aliquotMode(ObjectId semesterId) {
        AliquotConfig cfg = AliquotConfig.findBySemesterId(semesterId);
        return AliquotMode.fromString(cfg != null ? cfg.kostenMode : null);
    }

    private boolean eligible(KostenDiscount discountCfg, KostenDefinition def) {
        if (discountCfg == null) {
            return false;
        }
        if (discountCfg.applyToAll) {
            return true;
        }
        return discountCfg.eligibleDefinitionIds != null
                && discountCfg.eligibleDefinitionIds.contains(def.id);
    }

    /** Sum of default amounts over discount-eligible defs for a child's group (ranking base). */
    private BigDecimal discountableBase(ObjectId semesterId, ObjectId groupId,
                                        List<KostenDefinition> defs, KostenDiscount discountCfg) {
        BigDecimal base = BigDecimal.ZERO;
        for (KostenDefinition def : defs) {
            if (!eligible(discountCfg, def)) {
                continue;
            }
            BigDecimal d0 = defaultAmount(semesterId, groupId, def.id);
            if (d0 != null) {
                base = base.add(d0);
            }
        }
        return base;
    }

    /** Present family children this month with their discountable base, for ranking. */
    private List<ChildBase> presentSiblings(ObjectId familyId, Semester semester, int year, int month,
                                            AliquotMode mode, List<KostenDefinition> defs,
                                            KostenDiscount discountCfg) {
        List<ChildBase> out = new ArrayList<>();
        if (familyId == null) {
            return out;
        }
        for (Person c : childrenOf(familyId)) {
            GroupRef g = groupAssignment(c.id, semester.id);
            if (g == null) {
                continue;
            }
            BigDecimal frac = aliquotService.monthFraction(mode, g.entryDate, g.exitDate, year, month);
            if (frac.signum() == 0) {
                continue;
            }
            ChildBase cb = new ChildBase();
            cb.childId = c.id.toHexString();
            cb.base = discountableBase(semester.id, g.groupId, defs, discountCfg);
            out.add(cb);
        }
        return out;
    }

    // ---------- sibling discount (pure) ----------

    public static class ChildBase {
        public String childId;
        public BigDecimal base;
    }

    /** Discount factor (0..1) for targetChild given the family's present children this month. */
    public BigDecimal discountFactor(KostenDiscount cfg, String targetChildId, List<ChildBase> present) {
        if (cfg == null || cfg.tiers == null || cfg.tiers.isEmpty()) {
            return BigDecimal.ONE;
        }
        List<ChildBase> ranked = new ArrayList<>(present);
        Comparator<ChildBase> byBase = Comparator.comparing((ChildBase b) -> b.base);
        boolean leastFirst = "LEAST_EXPENSIVE_FIRST".equals(cfg.order);
        Comparator<ChildBase> cmp = (leastFirst ? byBase : byBase.reversed())
                .thenComparing(b -> b.childId);
        ranked.sort(cmp);

        int ordinal = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).childId.equals(targetChildId)) {
                ordinal = i + 1;
                break;
            }
        }
        if (ordinal < 1) {
            return BigDecimal.ONE;
        }
        int percent = 0;
        int bestFrom = 0;
        for (KostenDiscount.Tier t : cfg.tiers) {
            if (t.fromChild <= ordinal && t.fromChild >= bestFrom) {
                bestFrom = t.fromChild;
                percent = t.percent;
            }
        }
        return BigDecimal.valueOf(100 - percent)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }
}
