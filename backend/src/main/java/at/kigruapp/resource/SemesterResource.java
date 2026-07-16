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

        Semester semester = new Semester();
        semester.start = request.start();
        semester.end = request.end();
        semester.createdAt = Instant.now();
        semester.persist();
        return Response.status(201).entity(semester).build();
    }
}
