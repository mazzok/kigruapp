package at.kigruapp.resource;

import at.kigruapp.entity.Child;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.util.List;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChildResource {

    @GET
    @Path("/children")
    public List<Child> list() {
        return Child.listAll();
    }

    @GET
    @Path("/children/{id}")
    public Child get(@PathParam("id") String id) {
        Child child = Child.findById(new ObjectId(id));
        if (child == null) {
            throw new NotFoundException();
        }
        return child;
    }

    @POST
    @Path("/children")
    public Response create(Child child) {
        child.persist();
        return Response.status(201).entity(child).build();
    }

    @PUT
    @Path("/children/{id}")
    public Child update(@PathParam("id") String id, Child update) {
        Child child = Child.findById(new ObjectId(id));
        if (child == null) {
            throw new NotFoundException();
        }
        child.firstName = update.firstName;
        child.lastName = update.lastName;
        child.dateOfBirth = update.dateOfBirth;
        child.gender = update.gender;
        child.entryDate = update.entryDate;
        child.exitDate = update.exitDate;
        child.notes = update.notes;
        child.customFields = update.customFields;
        child.update();
        return child;
    }

    @DELETE
    @Path("/children/{id}")
    public Response delete(@PathParam("id") String id) {
        Child child = Child.findById(new ObjectId(id));
        if (child == null) {
            throw new NotFoundException();
        }
        child.delete();
        return Response.noContent().build();
    }
}
