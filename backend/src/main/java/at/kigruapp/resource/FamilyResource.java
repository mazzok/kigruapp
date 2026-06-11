package at.kigruapp.resource;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.Person;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.List;

@Path("/api/v1/families")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FamilyResource {

    @GET
    public List<Family> list() {
        return Family.listAll();
    }

    @GET
    @Path("/{id}")
    public Family get(@PathParam("id") String id) {
        Family family = Family.findById(new ObjectId(id));
        if (family == null) {
            throw new NotFoundException();
        }
        return family;
    }

    @POST
    public Response create(Family family) {
        family.createdAt = Instant.now();
        family.persist();
        return Response.status(201).entity(family).build();
    }

    @PUT
    @Path("/{id}")
    public Family update(@PathParam("id") String id, Family update) {
        Family family = Family.findById(new ObjectId(id));
        if (family == null) {
            throw new NotFoundException();
        }
        family.name = update.name;
        family.address = update.address;
        family.update();
        return family;
    }

    @GET
    @Path("/{id}/persons")
    public List<Person> getPersons(@PathParam("id") String id) {
        Family family = Family.findById(new ObjectId(id));
        if (family == null) {
            throw new NotFoundException();
        }
        return Person.findByFamilyId(family.id);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        Family family = Family.findById(new ObjectId(id));
        if (family == null) {
            throw new NotFoundException();
        }
        family.delete();
        return Response.noContent().build();
    }
}
