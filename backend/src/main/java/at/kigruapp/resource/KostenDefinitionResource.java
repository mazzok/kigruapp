package at.kigruapp.resource;

import at.kigruapp.dto.KostenDefinitionDTO;
import at.kigruapp.entity.Currency;
import at.kigruapp.entity.KostenDefinition;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.stream.Collectors;

@Path("/api/v1/kosten-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KostenDefinitionResource {

    public record CreateKostenDefinitionRequest(String label, String currencyId) {}
    public record SetActiveRequest(boolean active) {}

    @GET
    public List<KostenDefinitionDTO> list() {
        return KostenDefinition.<KostenDefinition>listAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @POST
    public Response create(CreateKostenDefinitionRequest request) {
        if (request.label() == null || request.label().isBlank()) {
            throw new BadRequestException("label is required");
        }
        if (request.currencyId() == null || !ObjectId.isValid(request.currencyId())) {
            throw new BadRequestException("currencyId is required");
        }
        Currency currency = Currency.findById(new ObjectId(request.currencyId()));
        if (currency == null) {
            throw new BadRequestException("Unknown currency: " + request.currencyId());
        }

        KostenDefinition definition = new KostenDefinition();
        definition.label = request.label();
        definition.currencyId = currency.id;
        definition.active = true;
        definition.persist();

        return Response.status(201).entity(toDTO(definition)).build();
    }

    @PATCH
    @Path("/{id}/active")
    public Response setActive(@PathParam("id") String id, SetActiveRequest request) {
        if (!ObjectId.isValid(id)) {
            throw new BadRequestException("Invalid id: " + id);
        }
        KostenDefinition definition = KostenDefinition.findById(new ObjectId(id));
        if (definition == null) {
            throw new NotFoundException();
        }
        definition.active = request.active();
        definition.update();
        return Response.ok(toDTO(definition)).build();
    }

    private KostenDefinitionDTO toDTO(KostenDefinition definition) {
        Currency currency = Currency.findById(definition.currencyId);
        return new KostenDefinitionDTO(
                definition.id.toString(),
                definition.label,
                definition.active,
                currency
        );
    }
}
