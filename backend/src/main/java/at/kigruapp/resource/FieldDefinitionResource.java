package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.util.List;

@Path("/api/v1/field-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FieldDefinitionResource {

    @GET
    public List<FieldDefinition> list() {
        return FieldDefinition.listAll();
    }

    @POST
    public Response create(FieldDefinition fieldDef) {
        fieldDef.persist();
        return Response.status(201).entity(fieldDef).build();
    }

    @PUT
    @Path("/{id}")
    public FieldDefinition update(@PathParam("id") String id, FieldDefinition update) {
        FieldDefinition fieldDef = FieldDefinition.findById(new ObjectId(id));
        if (fieldDef == null) {
            throw new NotFoundException();
        }
        fieldDef.entity = update.entity;
        fieldDef.fieldName = update.fieldName;
        fieldDef.label = update.label;
        fieldDef.type = update.type;
        fieldDef.options = update.options;
        fieldDef.required = update.required;
        fieldDef.update();
        return fieldDef;
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        FieldDefinition fieldDef = FieldDefinition.findById(new ObjectId(id));
        if (fieldDef == null) {
            throw new NotFoundException();
        }
        fieldDef.delete();
        return Response.noContent().build();
    }
}
