package at.kigruapp.resource;

import at.kigruapp.dto.FieldInstanceDTO;
import at.kigruapp.entity.EntityType;
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
import java.util.Map;
import java.util.stream.Collectors;

@Path("/api/v1/field-instances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FieldInstanceResource {

    @Inject
    JsonSchemaValidatorService schemaValidator;

    @GET
    public List<FieldInstanceDTO> list(
            @QueryParam("entityType") EntityType entityType,
            @QueryParam("entityId") String entityId) {
        if (entityType == null || entityId == null) {
            return List.of();
        }
        ObjectId eid = new ObjectId(entityId);
        List<FieldInstance> instances = FieldInstance.findByEntity(entityType, eid);
        Map<ObjectId, FieldInstance> byDefId = instances.stream()
                .collect(Collectors.toMap(i -> i.definitionId, i -> i));

        List<FieldDefinition> allDefs = FieldDefinition.findByEntity(entityType);
        List<FieldInstanceDTO> result = new ArrayList<>();

        for (FieldDefinition def : allDefs) {
            FieldInstance inst = byDefId.get(def.id);
            boolean outdated = def.outdatedAt != null;
            if (outdated && inst == null) {
                continue;
            }
            result.add(toDTO(def, inst, outdated));
        }
        return result;
    }

    @GET
    @Path("/{id}")
    public FieldInstanceDTO get(@PathParam("id") String id) {
        FieldInstance inst = FieldInstance.findById(new ObjectId(id));
        if (inst == null) {
            throw new NotFoundException();
        }
        FieldDefinition def = FieldDefinition.findById(inst.definitionId);
        return toDTO(def, inst, def != null && def.outdatedAt != null);
    }

    @POST
    public Response create(FieldInstance instance) {
        FieldDefinition def = FieldDefinition.findById(instance.definitionId);
        if (def == null) {
            return Response.status(400).entity("Definition not found").build();
        }
        if (def.outdatedAt != null) {
            return Response.status(400).entity("Definition is outdated").build();
        }
        FieldInstance existing = FieldInstance.findByDefinitionAndEntity(instance.definitionId, instance.entityId);
        if (existing != null) {
            return Response.status(409).entity("Instance already exists for this definition and entity").build();
        }
        try {
            schemaValidator.validate(def.jsonSchema, instance.value);
        } catch (JsonSchemaValidatorService.ValidationException e) {
            return Response.status(400).entity(e.getMessage()).build();
        }
        instance.createdAt = Instant.now();
        instance.updatedAt = instance.createdAt;
        instance.persist();
        return Response.status(201).entity(instance).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") String id, FieldInstance update) {
        FieldInstance inst = FieldInstance.findById(new ObjectId(id));
        if (inst == null) {
            throw new NotFoundException();
        }
        FieldDefinition def = FieldDefinition.findById(inst.definitionId);
        if (def != null && def.jsonSchema != null) {
            try {
                schemaValidator.validate(def.jsonSchema, update.value);
            } catch (JsonSchemaValidatorService.ValidationException e) {
                return Response.status(400).entity(e.getMessage()).build();
            }
        }
        inst.value = update.value;
        inst.updatedAt = Instant.now();
        inst.update();
        return Response.ok(inst).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        FieldInstance inst = FieldInstance.findById(new ObjectId(id));
        if (inst == null) {
            throw new NotFoundException();
        }
        inst.delete();
        return Response.noContent().build();
    }

    public record BatchItem(String definitionId, Object value) {}

    @PUT
    @Path("/batch")
    public Response batchUpsert(
            @QueryParam("entityType") EntityType entityType,
            @QueryParam("entityId") String entityId,
            List<BatchItem> items) {
        if (entityType == null || entityId == null) {
            return Response.status(400).entity("entityType and entityId required").build();
        }
        ObjectId eid = new ObjectId(entityId);
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

            FieldInstance inst = FieldInstance.findByDefinitionAndEntity(defId, eid);
            if (inst == null) {
                inst = new FieldInstance();
                inst.definitionId = defId;
                inst.entityType = entityType;
                inst.entityId = eid;
                inst.value = item.value();
                inst.createdAt = now;
                inst.updatedAt = now;
                inst.persist();
            } else {
                inst.value = item.value();
                inst.updatedAt = now;
                inst.update();
            }
            results.add(inst);
        }
        return Response.ok(results).build();
    }

    private FieldInstanceDTO toDTO(FieldDefinition def, FieldInstance inst, boolean outdated) {
        FieldInstanceDTO dto = new FieldInstanceDTO();
        dto.definitionId = def.id.toHexString();
        dto.fieldName = def.fieldName;
        dto.label = def.label;
        dto.description = def.description;
        dto.jsonSchema = def.jsonSchema;
        dto.required = def.required;
        dto.definitionOutdated = outdated;
        if (inst != null) {
            dto.id = inst.id.toHexString();
            dto.value = inst.value;
        }
        return dto;
    }
}
