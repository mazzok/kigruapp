package at.kigruapp.resource;

import at.kigruapp.entity.EntityType;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.service.JsonSchemaValidatorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.List;

@Path("/api/v1/field-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FieldDefinitionResource {

    @Inject
    JsonSchemaValidatorService schemaValidator;

    @GET
    public List<FieldDefinition> list(
            @QueryParam("entity") EntityType entity,
            @QueryParam("active") @DefaultValue("false") boolean activeOnly) {
        if (entity != null && activeOnly) {
            return FieldDefinition.findActiveByEntity(entity);
        } else if (entity != null) {
            return FieldDefinition.findByEntity(entity);
        }
        return FieldDefinition.listAll();
    }

    @POST
    public Response create(FieldDefinition fieldDef) {
        if (fieldDef.jsonSchema == null || fieldDef.jsonSchema.isEmpty()) {
            return Response.status(400).entity("jsonSchema is required").build();
        }
        try {
            schemaValidator.validateSchema(fieldDef.jsonSchema);
        } catch (Exception e) {
            return Response.status(400).entity("Invalid JSON Schema: " + e.getMessage()).build();
        }
        fieldDef.createdAt = Instant.now();
        fieldDef.outdatedAt = null;
        fieldDef.persist();
        return Response.status(201).entity(fieldDef).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") String id, FieldDefinition update) {
        FieldDefinition fieldDef = FieldDefinition.findById(new ObjectId(id));
        if (fieldDef == null) {
            throw new NotFoundException();
        }
        if (update.jsonSchema != null && !update.jsonSchema.isEmpty()) {
            try {
                schemaValidator.validateSchema(update.jsonSchema);
            } catch (Exception e) {
                return Response.status(400).entity("Invalid JSON Schema: " + e.getMessage()).build();
            }
        }
        fieldDef.entity = update.entity;
        fieldDef.fieldName = update.fieldName;
        fieldDef.label = update.label;
        fieldDef.description = update.description;
        fieldDef.jsonSchema = update.jsonSchema;
        fieldDef.required = update.required;
        fieldDef.update();
        return Response.ok(fieldDef).build();
    }

    @PATCH
    @Path("/{id}/outdate")
    public Response outdate(@PathParam("id") String id) {
        FieldDefinition fieldDef = FieldDefinition.findById(new ObjectId(id));
        if (fieldDef == null) {
            throw new NotFoundException();
        }
        if (fieldDef.outdatedAt != null) {
            return Response.status(409).entity("Already outdated").build();
        }
        fieldDef.outdatedAt = Instant.now();
        fieldDef.update();
        return Response.ok(fieldDef).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        FieldDefinition fieldDef = FieldDefinition.findById(new ObjectId(id));
        if (fieldDef == null) {
            throw new NotFoundException();
        }
        fieldDef.outdatedAt = Instant.now();
        fieldDef.update();
        return Response.noContent().build();
    }
}
