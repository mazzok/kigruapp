package at.kigruapp.resource;

import at.kigruapp.dto.KostenValueDTO;
import at.kigruapp.entity.Currency;
import at.kigruapp.entity.KostenDefinition;
import at.kigruapp.entity.KostenValue;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Path("/api/v1/kosten-values")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KostenValueResource {

    public record UpsertKostenValueRequest(String semesterId, String groupId, String definitionId, BigDecimal amount) {}

    @GET
    public List<KostenValueDTO> list(
            @QueryParam("semesterId") String semesterIdParam,
            @QueryParam("groupId") String groupIdParam) {
        if (semesterIdParam == null || !ObjectId.isValid(semesterIdParam)) {
            throw new BadRequestException("semesterId is required");
        }
        if (groupIdParam == null || !ObjectId.isValid(groupIdParam)) {
            throw new BadRequestException("groupId is required");
        }
        ObjectId semesterId = new ObjectId(semesterIdParam);
        ObjectId groupId = new ObjectId(groupIdParam);

        return KostenDefinition.findActive().stream()
                .map(definition -> {
                    KostenValue value = KostenValue.findByKeys(semesterId, groupId, definition.id);
                    Currency currency = Currency.findById(definition.currencyId);
                    BigDecimal amount = value != null ? value.amount : null;
                    return new KostenValueDTO(definition.id.toString(), definition.label, currency, amount);
                })
                .collect(Collectors.toList());
    }

    @PUT
    public Response upsert(UpsertKostenValueRequest request) {
        if (request.semesterId() == null || !ObjectId.isValid(request.semesterId())) {
            throw new BadRequestException("semesterId is required");
        }
        if (request.groupId() == null || !ObjectId.isValid(request.groupId())) {
            throw new BadRequestException("groupId is required");
        }
        if (request.definitionId() == null || !ObjectId.isValid(request.definitionId())) {
            throw new BadRequestException("definitionId is required");
        }
        ObjectId semesterId = new ObjectId(request.semesterId());
        ObjectId groupId = new ObjectId(request.groupId());
        ObjectId definitionId = new ObjectId(request.definitionId());

        KostenDefinition definition = KostenDefinition.findById(definitionId);
        if (definition == null || !definition.active) {
            throw new BadRequestException("Unknown or inactive definition: " + request.definitionId());
        }

        KostenValue existing = KostenValue.findByKeys(semesterId, groupId, definitionId);

        if (request.amount() == null) {
            if (existing != null) {
                existing.delete();
            }
            return Response.noContent().build();
        }

        if (existing != null) {
            existing.amount = request.amount();
            existing.update();
        } else {
            KostenValue value = new KostenValue();
            value.semesterId = semesterId;
            value.groupId = groupId;
            value.definitionId = definitionId;
            value.amount = request.amount();
            value.persist();
        }
        return Response.noContent().build();
    }
}
