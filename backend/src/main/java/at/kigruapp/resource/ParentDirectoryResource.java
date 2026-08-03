package at.kigruapp.resource;

import at.kigruapp.dto.ParentDirectoryAttributeDTO;
import at.kigruapp.dto.ParentDirectoryDTO;
import at.kigruapp.entity.Person;
import at.kigruapp.security.CurrentUserService;
import at.kigruapp.service.ParentDirectoryAttributeService;
import at.kigruapp.service.ParentDirectoryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

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

    @Inject
    ParentDirectoryAttributeService attributeService;

    @GET
    public ParentDirectoryDTO get() {
        Person current = currentUserService.getCurrentPerson();
        if (current == null || current.familyId == null) {
            throw new ForbiddenException();
        }
        return parentDirectoryService.buildForFamily(current.familyId);
    }

    /**
     * Admin-pflichtig durch den Standard des SecurityFilters: nur
     * /api/v1/parent-directory selbst ist fuer Eltern whitelisted.
     */
    @GET
    @Path("/attributes")
    public ParentDirectoryAttributeDTO.Catalog attributes() {
        var visible = attributeService.visibleKeys();
        List<ParentDirectoryAttributeDTO> attributes = attributeService.catalog().stream()
                .map(entry -> new ParentDirectoryAttributeDTO(
                        entry.key(), entry.label(), entry.scope(),
                        visible.contains(entry.key()),
                        ParentDirectoryAttributeService.CHILD_NAME.equals(entry.key())))
                .toList();
        return new ParentDirectoryAttributeDTO.Catalog(attributes);
    }

    @PUT
    @Path("/attributes")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveAttributes(ParentDirectoryAttributeDTO.VisibleAttributesRequest request) {
        try {
            attributeService.save(request == null ? List.of() : request.visibleAttributes());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    Response.status(400).entity(e.getMessage()).type(MediaType.TEXT_PLAIN).build());
        }
        return Response.noContent().build();
    }
}
