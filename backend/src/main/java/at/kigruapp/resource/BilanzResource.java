package at.kigruapp.resource;

import at.kigruapp.entity.BilanzOverride;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.math.BigDecimal;

@Path("/api/v1/bilanzen")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BilanzResource {

    public record UpsertOverrideRequest(
            String personId, int year, int month, String definitionId, BigDecimal amount) {}

    @PUT
    @Path("/overrides")
    public Response upsertOverride(UpsertOverrideRequest request) {
        if (request.personId() == null || !ObjectId.isValid(request.personId())) {
            throw new BadRequestException("personId is required");
        }
        if (request.definitionId() == null || !ObjectId.isValid(request.definitionId())) {
            throw new BadRequestException("definitionId is required");
        }
        if (request.month() < 1 || request.month() > 12) {
            throw new BadRequestException("month must be 1..12");
        }
        if (request.amount() == null) {
            throw new BadRequestException("amount is required");
        }
        ObjectId personId = new ObjectId(request.personId());
        ObjectId definitionId = new ObjectId(request.definitionId());

        BilanzOverride existing = BilanzOverride.findByKeys(
                personId, request.year(), request.month(), definitionId);
        if (existing != null) {
            existing.amount = request.amount();
            existing.update();
        } else {
            BilanzOverride o = new BilanzOverride();
            o.personId = personId;
            o.year = request.year();
            o.month = request.month();
            o.definitionId = definitionId;
            o.amount = request.amount();
            o.persist();
        }
        return Response.noContent().build();
    }
}
