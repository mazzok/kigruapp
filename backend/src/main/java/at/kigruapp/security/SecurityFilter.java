package at.kigruapp.security;

import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@Priority(Priorities.AUTHORIZATION)
public class SecurityFilter implements ContainerRequestFilter {

    @Inject
    CurrentUserService currentUserService;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String method = ctx.getMethod();

        // Setup endpoints handle their own auth
        if (path.startsWith("/api/v1/setup")) {
            return;
        }

        Person person = currentUserService.getCurrentPerson();
        if (person == null) {
            abort(ctx);
            return;
        }

        if (!isAllowed(path, method, person)) {
            abort(ctx);
        }
    }

    private boolean isAllowed(String path, String method, Person person) {
        boolean isAdmin = currentUserService.isAdmin();

        if (isAdmin) return true;

        if (path.equals("/api/v1/cooking-duties") && "GET".equals(method)) return true;

        if (path.matches("/api/v1/cooking-duties/[^/]+") && isWriteMethod(method)) {
            return checkCookingDutyFamily(path, person);
        }

        if ((path.equals("/api/v1/organisation/groups") || path.equals("/api/v1/organisation/duty-settings"))
                && "GET".equals(method)) return true;

        if (path.startsWith("/api/v1/field-definitions") && "GET".equals(method)) return true;

        if (path.startsWith("/api/v1/field-instances") && "GET".equals(method)) return true;

        if (path.equals("/api/v1/field-instances") && "POST".equals(method)) return true;

        if (path.matches("/api/v1/field-instances/[^/]+") && isWriteMethod(method)) {
            return checkFieldInstanceFamily(path, person);
        }

        if (path.equals("/api/v1/persons/me") && "GET".equals(method)) return true;

        // Default: admin-only (safe default — deny non-admins for anything not explicitly whitelisted above)
        return false;
    }

    private boolean isWriteMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private boolean checkCookingDutyFamily(String path, Person person) {
        String id = path.substring("/api/v1/cooking-duties/".length());
        if (!ObjectId.isValid(id)) return false;

        Document duty = mongoClient.getDatabase(databaseName)
            .getCollection("cookingDuties")
            .find(Filters.eq("_id", new ObjectId(id)))
            .first();

        if (duty == null) return false;
        ObjectId dutyFamilyId = duty.getObjectId("familyId");
        return person.familyId != null && person.familyId.equals(dutyFamilyId);
    }

    private boolean checkFieldInstanceFamily(String path, Person person) {
        String id = path.substring("/api/v1/field-instances/".length());
        if (!ObjectId.isValid(id)) return false;

        Document instance = mongoClient.getDatabase(databaseName)
            .getCollection("field_instances")
            .find(Filters.eq("_id", new ObjectId(id)))
            .first();

        if (instance == null) return false;
        ObjectId ownerPersonId = instance.getObjectId("personId");
        if (ownerPersonId == null) return false;

        Person owner = Person.findById(ownerPersonId);
        return owner != null && person.familyId != null && person.familyId.equals(owner.familyId);
    }

    private void abort(ContainerRequestContext ctx) {
        ctx.abortWith(Response.status(Response.Status.FORBIDDEN).build());
    }
}
