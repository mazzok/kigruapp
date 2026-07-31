package at.kigruapp.resource;

import at.kigruapp.entity.ClosureDefinition;
import at.kigruapp.entity.ClosurePeriod;
import io.quarkus.panache.common.Sort;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;

@Path("/api/v1/closure-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClosureDefinitionResource {

    public record DefinitionRequest(String label, String color, Boolean active) {}

    @GET
    public List<ClosureDefinition> list(@QueryParam("includeInactive") boolean includeInactive) {
        Sort newestFirst = Sort.descending("createdAt");
        if (includeInactive) {
            return ClosureDefinition.listAll(newestFirst);
        }
        return ClosureDefinition.list("active", newestFirst, true);
    }

    @POST
    public Response create(DefinitionRequest request) {
        validate(request);

        ClosureDefinition definition = new ClosureDefinition();
        definition.label = request.label().trim();
        definition.color = request.color().trim();
        definition.active = true;
        definition.createdAt = Instant.now();
        definition.persist();
        return Response.status(201).entity(definition).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") String id, DefinitionRequest request) {
        validate(request);
        ClosureDefinition definition = findOr404(id);

        boolean contentChanged = !definition.label.equals(request.label().trim())
            || !definition.color.equals(request.color().trim());

        // Reines Aktivieren/Deaktivieren bleibt erlaubt — sonst liesse sich eine
        // verknuepfte Definition nie wieder reaktivieren.
        if (contentChanged && !ClosurePeriod.findByDefinition(definition.id).isEmpty()) {
            throw new ClientErrorException(
                "Definition ist mit Zeitraeumen verknuepft und kann nicht geaendert werden. "
                    + "Bitte ueber /revise eine Kopie anlegen.",
                Response.Status.CONFLICT);
        }

        definition.label = request.label().trim();
        definition.color = request.color().trim();
        if (request.active() != null) {
            definition.active = request.active();
        }
        definition.update();
        return Response.ok(definition).build();
    }

    /**
     * Legt eine Kopie mit den neuen Werten an und deaktiviert das Original.
     * Bereits verknuepfte Zeitraeume behalten Label und Farbe von damals.
     */
    @POST
    @Path("/{id}/revise")
    public Response revise(@PathParam("id") String id, DefinitionRequest request) {
        validate(request);
        ClosureDefinition original = findOr404(id);

        ClosureDefinition copy = new ClosureDefinition();
        copy.label = request.label().trim();
        copy.color = request.color().trim();
        copy.active = true;
        copy.createdAt = Instant.now();
        copy.persist();

        original.active = false;
        original.update();

        return Response.status(201).entity(copy).build();
    }

    /** Loescht nicht, sondern deaktiviert — verknuepfte Zeitraeume bleiben gueltig. */
    @DELETE
    @Path("/{id}")
    public Response deactivate(@PathParam("id") String id) {
        ClosureDefinition definition = findOr404(id);
        definition.active = false;
        definition.update();
        return Response.noContent().build();
    }

    private void validate(DefinitionRequest request) {
        if (request == null || request.label() == null || request.label().isBlank()) {
            throw new BadRequestException("label is required");
        }
        if (request.color() == null || request.color().isBlank()) {
            throw new BadRequestException("color is required");
        }
    }

    private ClosureDefinition findOr404(String id) {
        if (!ObjectId.isValid(id)) {
            throw new NotFoundException("Definition nicht gefunden");
        }
        ClosureDefinition definition = ClosureDefinition.findById(new ObjectId(id));
        if (definition == null) {
            throw new NotFoundException("Definition nicht gefunden");
        }
        return definition;
    }
}
