package at.kigruapp.resource;

import at.kigruapp.dto.FieldInstanceDTO;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.service.JsonSchemaValidatorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Path("/api/v1/field-instances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FieldInstanceResource {

    @Inject
    JsonSchemaValidatorService schemaValidator;

    @GET
    @Path("/{id}")
    public FieldInstanceDTO get(@PathParam("id") String id) {
        FieldInstance inst = FieldInstance.findById(new ObjectId(id));
        if (inst == null) {
            throw new NotFoundException();
        }
        FieldDefinition def = FieldDefinition.findById(inst.definitionId);
        return toDTO(def, inst);
    }

    public record BatchItem(String definitionId, Object value) {}

    @PUT
    @Path("/batch")
    public Response batchUpsert(List<BatchItem> items) {
        Instant now = Instant.now();
        List<FieldInstance> results = new ArrayList<>();

        for (BatchItem item : items) {
            ObjectId defId = new ObjectId(item.definitionId());
            FieldDefinition def = FieldDefinition.findById(defId);
            if (def == null) {
                return Response.status(400).entity("Definition not found: " + item.definitionId()).build();
            }
            if (def.outdatedAt != null) {
                return Response.status(400).entity("Definition is outdated: " + item.definitionId()).build();
            }
            if (item.value() != null) {
                try {
                    schemaValidator.validate(def.jsonSchema, item.value());
                } catch (JsonSchemaValidatorService.ValidationException e) {
                    return Response.status(400).entity(def.fieldName + ": " + e.getMessage()).build();
                }
            }

            FieldInstance inst = new FieldInstance();
            inst.definitionId = defId;
            inst.value = item.value();
            inst.createdAt = now;
            inst.updatedAt = now;
            inst.persist();
            results.add(inst);
        }
        return Response.ok(results).build();
    }

    private FieldInstanceDTO toDTO(FieldDefinition def, FieldInstance inst) {
        FieldInstanceDTO dto = new FieldInstanceDTO();
        dto.definitionId = def.id.toHexString();
        dto.fieldName = def.fieldName;
        dto.label = def.label;
        dto.description = def.description;
        dto.jsonSchema = def.jsonSchema;
        dto.required = def.required;
        dto.keycloakMapping = def.keycloakMapping;
        dto.definitionOutdated = def.outdatedAt != null;
        if (inst != null) {
            dto.id = inst.id.toHexString();
            dto.value = inst.value;
        }
        return dto;
    }
}
