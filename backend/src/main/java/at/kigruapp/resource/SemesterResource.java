package at.kigruapp.resource;

import at.kigruapp.entity.Semester;
import io.quarkus.panache.common.Sort;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;

@Path("/api/v1/semesters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SemesterResource {

    public record CreateSemesterRequest(Instant start, Instant end) {}

    @GET
    public List<Semester> list() {
        return Semester.listAll(Sort.descending("createdAt"));
    }

    @POST
    public Response create(CreateSemesterRequest request) {
        if (request.start() == null || request.end() == null) {
            throw new BadRequestException("start and end are required");
        }
        if (!request.start().isBefore(request.end())) {
            throw new BadRequestException("start must be before end");
        }
        for (Semester existing : Semester.<Semester>listAll()) {
            boolean overlaps = !request.end().isBefore(existing.start) && !request.start().isAfter(existing.end);
            if (overlaps) {
                throw new BadRequestException("Zeitraum ueberlappt mit bestehendem Semester " + existing.id);
            }
        }

        Semester prev = latestSemesterOrNull();

        Semester semester = new Semester();
        semester.start = request.start();
        semester.end = request.end();
        semester.createdAt = Instant.now();
        semester.persist();
        if (prev != null) {
            copyConfig(prev.id, semester.id);
        }
        return Response.status(201).entity(semester).build();
    }

    private Semester latestSemesterOrNull() {
        List<Semester> all = Semester.listAll(Sort.descending("createdAt"));
        return all.isEmpty() ? null : all.get(0);
    }

    private void copyConfig(org.bson.types.ObjectId from, org.bson.types.ObjectId to) {
        at.kigruapp.entity.RequiredHours rh = at.kigruapp.entity.RequiredHours.findBySemesterId(from);
        if (rh != null) {
            at.kigruapp.entity.RequiredHours c = new at.kigruapp.entity.RequiredHours();
            c.semesterId = to;
            c.defaultMinutesPerMonth = rh.defaultMinutesPerMonth;
            c.allGroups = rh.allGroups;
            c.order = rh.order;
            c.groupRates = new java.util.ArrayList<>(rh.groupRates == null ? java.util.List.of() : rh.groupRates);
            c.tiers = new java.util.ArrayList<>(rh.tiers == null ? java.util.List.of() : rh.tiers);
            c.persist();
        }
        at.kigruapp.entity.KostenDiscount kd = at.kigruapp.entity.KostenDiscount.findBySemesterId(from);
        if (kd != null) {
            at.kigruapp.entity.KostenDiscount c = new at.kigruapp.entity.KostenDiscount();
            c.semesterId = to; c.applyToAll = kd.applyToAll; c.order = kd.order;
            c.tiers = new java.util.ArrayList<>(kd.tiers == null ? java.util.List.of() : kd.tiers);
            c.eligibleDefinitionIds = new java.util.ArrayList<>(kd.eligibleDefinitionIds == null ? java.util.List.of() : kd.eligibleDefinitionIds);
            c.persist();
        }
        at.kigruapp.entity.AliquotConfig ac = at.kigruapp.entity.AliquotConfig.findBySemesterId(from);
        if (ac != null) {
            at.kigruapp.entity.AliquotConfig c = new at.kigruapp.entity.AliquotConfig();
            c.semesterId = to; c.stundenMode = ac.stundenMode; c.kostenMode = ac.kostenMode;
            c.persist();
        }
        for (at.kigruapp.entity.KostenValue v : at.kigruapp.entity.KostenValue.<at.kigruapp.entity.KostenValue>list("semesterId", from)) {
            at.kigruapp.entity.KostenValue c = new at.kigruapp.entity.KostenValue();
            c.semesterId = to; c.groupId = v.groupId; c.definitionId = v.definitionId; c.amount = v.amount;
            c.persist();
        }
    }
}
