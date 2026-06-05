package at.kigruapp.resource;

import at.kigruapp.entity.Parent;
import at.kigruapp.security.KeycloakUserService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.util.List;

@Path("/api/v1/parents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ParentResource {

    @Inject
    KeycloakUserService keycloakUserService;

    @GET
    public List<Parent> list() {
        return Parent.listAll();
    }

    @GET
    @Path("/{id}")
    public Parent get(@PathParam("id") String id) {
        Parent parent = Parent.findById(new ObjectId(id));
        if (parent == null) {
            throw new NotFoundException();
        }
        return parent;
    }

    @POST
    public Response create(Parent parent) {
        if (parent.email != null && !parent.email.isBlank()) {
            try {
                String keycloakId = keycloakUserService.createUser(
                    parent.email, parent.firstName, parent.lastName
                );
                parent.keycloakUserId = keycloakId;
            } catch (Exception e) {
                System.err.println("Keycloak user creation failed: " + e.getMessage());
            }
        }
        parent.persist();
        return Response.status(201).entity(parent).build();
    }

    @PUT
    @Path("/{id}")
    public Parent update(@PathParam("id") String id, Parent update) {
        Parent parent = Parent.findById(new ObjectId(id));
        if (parent == null) {
            throw new NotFoundException();
        }
        parent.firstName = update.firstName;
        parent.lastName = update.lastName;
        parent.email = update.email;
        parent.phone = update.phone;
        parent.address = update.address;
        parent.permissions = update.permissions;
        parent.customFields = update.customFields;
        parent.update();
        return parent;
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        Parent parent = Parent.findById(new ObjectId(id));
        if (parent == null) {
            throw new NotFoundException();
        }
        parent.delete();
        return Response.noContent().build();
    }
}
