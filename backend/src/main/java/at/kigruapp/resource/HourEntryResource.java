package at.kigruapp.resource;

import at.kigruapp.dto.FamilyHoursSummaryDto;
import at.kigruapp.dto.HourEntryDto;
import at.kigruapp.dto.HourEntrySaveDto;
import at.kigruapp.dto.HourSummaryDto;
import at.kigruapp.dto.RoleOptionDto;
import at.kigruapp.entity.Family;
import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import at.kigruapp.security.CurrentUserService;
import at.kigruapp.service.HoursBalanceService;
import at.kigruapp.service.PersonPropertyResolver;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stundenerfassung. /me, /role-options, POST, PUT, DELETE sind für alle
 * angemeldeten Eltern zugänglich (im SecurityFilter whitelisted); PUT/DELETE
 * erzwingen Eigentümer-oder-Admin hier im Resource. GET / und /summary sind
 * nicht whitelisted und damit admin-only.
 */
@Path("/api/v1/hour-entries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HourEntryResource {

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final String COOKING_LABEL = "Kochen";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    CurrentUserService currentUserService;

    @Inject
    PersonPropertyResolver personPropertyResolver;

    @Inject
    HoursBalanceService hoursBalanceService;

    private MongoCollection<Document> semesterAssignments() {
        return mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private ObjectId resolveSemesterId(String semesterIdParam) {
        if (semesterIdParam != null && !semesterIdParam.isBlank()) {
            return new ObjectId(semesterIdParam);
        }
        List<Semester> latest = Semester.listAll(Sort.descending("createdAt"));
        return latest.isEmpty() ? null : latest.get(0).id;
    }

    private ObjectId requireSemesterId(String semesterIdParam) {
        ObjectId semesterId = resolveSemesterId(semesterIdParam);
        if (semesterId == null) {
            throw new BadRequestException("Kein Semester vorhanden");
        }
        return semesterId;
    }

    private Person requireCurrentPerson() {
        Person p = currentUserService.getCurrentPerson();
        if (p == null) {
            throw new ForbiddenException();
        }
        return p;
    }

    @GET
    @Path("/role-options")
    public List<RoleOptionDto> roleOptions(@QueryParam("semesterId") String semesterIdParam) {
        Person me = requireCurrentPerson();
        ObjectId semesterId = resolveSemesterId(semesterIdParam);
        if (semesterId == null) {
            return List.of(cookingOption());
        }
        return resolveRoleOptions(me.id, semesterId);
    }

    @GET
    @Path("/summary")
    public List<HourSummaryDto> summary(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        List<HourEntry> entries = HourEntry.<HourEntry>find(
                "semesterId", Sort.descending("date", "createdAt"), semesterId).list();

        Map<ObjectId, HourSummaryDto> byPerson = new LinkedHashMap<>();
        for (HourEntry e : entries) {
            HourSummaryDto summary = byPerson.computeIfAbsent(e.personId, pid -> {
                HourSummaryDto dto = new HourSummaryDto();
                dto.personId = pid == null ? null : pid.toHexString();
                dto.name = "";
                dto.totalMinutes = 0;
                dto.entries = new ArrayList<>();
                return dto;
            });
            summary.totalMinutes += e.minutes;
            summary.entries.add(toDto(e));
        }

        // Namen für alle beteiligten Personen in beschränkter Query-Anzahl auflösen (G-008).
        List<Person> persons = new ArrayList<>();
        for (ObjectId pid : byPerson.keySet()) {
            if (pid == null) continue;
            Person p = Person.findById(pid);
            if (p != null) persons.add(p);
        }
        Map<ObjectId, Map<String, String>> propsByPerson = personPropertyResolver.resolve(persons);
        for (HourSummaryDto summary : byPerson.values()) {
            if (summary.personId == null) continue;
            ObjectId pid = new ObjectId(summary.personId);
            Map<String, String> props = propsByPerson.getOrDefault(pid, Map.of());
            String first = props.getOrDefault("firstName", "");
            String last = props.getOrDefault("lastName", "");
            String name = (first + " " + last).trim();
            summary.name = name.isEmpty() ? pid.toHexString() : name;
        }
        return new ArrayList<>(byPerson.values());
    }

    @GET
    @Path("/family-summary")
    public List<FamilyHoursSummaryDto> familySummary(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        Semester semester = Semester.findById(semesterId);
        int months = hoursBalanceService.monthsInSemester(semester);
        RequiredHours cfg = RequiredHours.findBySemesterId(semesterId);

        List<FamilyHoursSummaryDto> result = new ArrayList<>();
        for (Family family : Family.<Family>listAll()) {
            List<Person> members = Person.findByFamilyId(family.id);

            int childCount = hoursBalanceService.countPlacedChildren(family.id, semesterId);
            int familyMonthly = hoursBalanceService.familyMonthlyMinutes(cfg, childCount);
            int soll = familyMonthly * months;

            // Ist + Personen-Einträge über alle Familienmitglieder sammeln.
            Map<ObjectId, HourSummaryDto> byPerson = new LinkedHashMap<>();
            int ist = 0;
            for (Person member : members) {
                List<HourEntry> entries = HourEntry.<HourEntry>find(
                        "personId = ?1 and semesterId = ?2",
                        Sort.descending("date", "createdAt"), member.id, semesterId).list();
                if (entries.isEmpty()) continue;
                HourSummaryDto dto = new HourSummaryDto();
                dto.personId = member.id.toHexString();
                dto.name = "";
                dto.totalMinutes = 0;
                dto.entries = new ArrayList<>();
                for (HourEntry e : entries) {
                    dto.totalMinutes += e.minutes;
                    dto.entries.add(toDto(e));
                    ist += e.minutes;
                }
                byPerson.put(member.id, dto);
            }

            if (childCount == 0 && ist == 0) {
                continue; // Familie ohne Soll und ohne Ist ausblenden.
            }

            // Namen der beteiligten Mitglieder auflösen (Person-Objekte bereits geladen).
            List<Person> named = new ArrayList<>();
            for (Person member : members) {
                if (byPerson.containsKey(member.id)) named.add(member);
            }
            Map<ObjectId, Map<String, String>> props = personPropertyResolver.resolve(named);
            for (HourSummaryDto dto : byPerson.values()) {
                Map<String, String> pr = props.getOrDefault(new ObjectId(dto.personId), Map.of());
                String name = (pr.getOrDefault("firstName", "") + " " + pr.getOrDefault("lastName", "")).trim();
                dto.name = name.isEmpty() ? dto.personId : name;
            }

            FamilyHoursSummaryDto fam = new FamilyHoursSummaryDto();
            fam.familyId = family.id.toHexString();
            fam.familyName = family.name == null ? "" : family.name;
            fam.childCount = childCount;
            fam.familyMonthlyMinutes = familyMonthly;
            fam.monthsInSemester = months;
            fam.sollMinutes = soll;
            fam.istMinutes = ist;
            fam.members = new ArrayList<>(byPerson.values());
            result.add(fam);
        }
        return result;
    }

    @GET
    @Path("/me")
    public List<HourEntryDto> listMine() {
        Person me = requireCurrentPerson();
        return HourEntry.<HourEntry>find("personId", Sort.descending("date", "createdAt"), me.id)
                .list().stream().map(HourEntryResource::toDto).toList();
    }

    @POST
    public Response create(HourEntrySaveDto in) {
        Person me = requireCurrentPerson();
        ObjectId semesterId = requireSemesterId(null);
        validatePayload(in);

        RoleOptionDto role = resolveRole(me.id, semesterId, in.roleFieldInstanceId);

        HourEntry entry = new HourEntry();
        entry.personId = me.id;
        entry.semesterId = semesterId;
        entry.roleFieldInstanceId = role.fieldInstanceId == null ? null : new ObjectId(role.fieldInstanceId);
        entry.roleDefinitionId = role.definitionId == null ? null : new ObjectId(role.definitionId);
        entry.roleLabel = role.label;
        entry.date = in.date;
        entry.minutes = in.minutes;
        entry.comment = in.comment == null ? "" : in.comment;
        entry.createdAt = Instant.now();
        entry.updatedAt = entry.createdAt;
        entry.persist();
        return Response.status(201).entity(toDto(entry)).build();
    }

    @PUT
    @Path("/{id}")
    public HourEntryDto update(@PathParam("id") String id, HourEntrySaveDto in) {
        Person me = requireCurrentPerson();
        HourEntry entry = HourEntry.findById(new ObjectId(id));
        if (entry == null) {
            throw new NotFoundException();
        }
        requireOwnerOrAdmin(entry, me);
        validatePayload(in);

        boolean roleUnchanged = java.util.Objects.equals(
                entry.roleFieldInstanceId == null ? null : entry.roleFieldInstanceId.toHexString(),
                in.roleFieldInstanceId == null || in.roleFieldInstanceId.isBlank() ? null : in.roleFieldInstanceId);
        if (!roleUnchanged) {
            RoleOptionDto role = resolveRole(entry.personId, entry.semesterId, in.roleFieldInstanceId);
            entry.roleFieldInstanceId = role.fieldInstanceId == null ? null : new ObjectId(role.fieldInstanceId);
            entry.roleDefinitionId = role.definitionId == null ? null : new ObjectId(role.definitionId);
            entry.roleLabel = role.label;
        }
        entry.date = in.date;
        entry.minutes = in.minutes;
        entry.comment = in.comment == null ? "" : in.comment;
        entry.updatedAt = Instant.now();
        entry.update();
        return toDto(entry);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        Person me = requireCurrentPerson();
        HourEntry entry = HourEntry.findById(new ObjectId(id));
        if (entry == null) {
            throw new NotFoundException();
        }
        requireOwnerOrAdmin(entry, me);
        entry.delete();
        return Response.noContent().build();
    }

    private void requireOwnerOrAdmin(HourEntry entry, Person me) {
        boolean owner = entry.personId != null && entry.personId.equals(me.id);
        if (!owner && !currentUserService.isAdmin()) {
            throw new ForbiddenException();
        }
    }

    private void validatePayload(HourEntrySaveDto in) {
        if (in.date == null || !ISO_DATE.matcher(in.date).matches()) {
            throw new BadRequestException("date muss im Format YYYY-MM-DD vorliegen");
        }
        if (in.minutes <= 0) {
            throw new BadRequestException("minutes muss größer als 0 sein");
        }
    }

    /** Leitet Label/Definition aus der gewählten Rolle des Semesters ab; null = Kochen. */
    private RoleOptionDto resolveRole(ObjectId personId, ObjectId semesterId, String roleFieldInstanceId) {
        if (roleFieldInstanceId == null || roleFieldInstanceId.isBlank()) {
            return cookingOption();
        }
        for (RoleOptionDto opt : resolveRoleOptions(personId, semesterId)) {
            if (roleFieldInstanceId.equals(opt.fieldInstanceId)) {
                return opt;
            }
        }
        throw new BadRequestException("Rolle ist der Person im aktiven Semester nicht zugewiesen");
    }

    private RoleOptionDto cookingOption() {
        RoleOptionDto dto = new RoleOptionDto();
        dto.fieldInstanceId = null;
        dto.definitionId = null;
        dto.label = COOKING_LABEL;
        return dto;
    }

    /** Zugewiesene Rollen (section="role") des Semesters + fixe "Kochen"-Option. */
    List<RoleOptionDto> resolveRoleOptions(ObjectId personId, ObjectId semesterId) {
        Document filter = new Document("personId", personId)
                .append("semesterId", semesterId)
                .append("section", "role");

        // 1. Zuweisungen einsammeln (Reihenfolge des Cursors beibehalten) + fieldInstanceIds sammeln.
        List<Document> assignments = new ArrayList<>();
        List<ObjectId> instanceIds = new ArrayList<>();
        for (Document assignment : semesterAssignments().find(filter)) {
            assignments.add(assignment);
            ObjectId instId = assignment.getObjectId("fieldInstanceId");
            if (instId != null) {
                instanceIds.add(instId);
            }
        }

        // 2. Eine Batch-Query für alle field_instances -> Map id -> label.
        Map<ObjectId, String> labelById = new HashMap<>();
        if (!instanceIds.isEmpty()) {
            for (Document inst : fieldInstances().find(Filters.in("_id", instanceIds))) {
                labelById.put(inst.getObjectId("_id"), labelFromValue(inst.get("value")));
            }
        }

        // 3. DTOs aus der Map bauen, "Kochen" zuletzt.
        List<RoleOptionDto> options = new ArrayList<>();
        for (Document assignment : assignments) {
            ObjectId instId = assignment.getObjectId("fieldInstanceId");
            ObjectId defId = assignment.getObjectId("definitionId");
            RoleOptionDto opt = new RoleOptionDto();
            opt.fieldInstanceId = instId == null ? null : instId.toHexString();
            opt.definitionId = defId == null ? null : defId.toHexString();
            opt.label = instId == null ? "" : labelById.getOrDefault(instId, "");
            options.add(opt);
        }
        options.add(cookingOption());
        return options;
    }

    /** Leitet ein Label aus field_instances.value ab: value.label bzw. value.toString(), sonst leer. */
    private String labelFromValue(Object value) {
        if (value instanceof Document valueDoc) {
            String label = valueDoc.getString("label");
            return label != null ? label : "";
        }
        return value == null ? "" : value.toString();
    }

    static HourEntryDto toDto(HourEntry e) {
        HourEntryDto dto = new HourEntryDto();
        dto.id = e.id.toHexString();
        dto.personId = e.personId == null ? null : e.personId.toHexString();
        dto.semesterId = e.semesterId == null ? null : e.semesterId.toHexString();
        dto.roleFieldInstanceId = e.roleFieldInstanceId == null ? null : e.roleFieldInstanceId.toHexString();
        dto.roleLabel = e.roleLabel;
        dto.date = e.date;
        dto.minutes = e.minutes;
        dto.comment = e.comment;
        return dto;
    }
}
