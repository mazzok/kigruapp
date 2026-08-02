package at.kigruapp.resource;

import at.kigruapp.dto.ParentDirectoryDTO;
import at.kigruapp.entity.Person;
import at.kigruapp.security.CurrentUserService;
import at.kigruapp.service.ParentDirectoryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Kontrollierte Offenlegung der Elternkontakte innerhalb der eigenen Gruppen.
 * Der Endpoint nimmt bewusst keine Parameter entgegen: welche Gruppen sichtbar
 * sind, ergibt sich ausschließlich aus den Kindern der aufrufenden Familie.
 */
@Path("/api/v1/parent-directory")
@Produces(MediaType.APPLICATION_JSON)
public class ParentDirectoryResource {

    @Inject
    CurrentUserService currentUserService;

    @Inject
    ParentDirectoryService parentDirectoryService;

    @GET
    public ParentDirectoryDTO get() {
        Person current = currentUserService.getCurrentPerson();
        if (current == null || current.familyId == null) {
            throw new ForbiddenException();
        }
        return parentDirectoryService.buildForFamily(current.familyId);
    }
}
