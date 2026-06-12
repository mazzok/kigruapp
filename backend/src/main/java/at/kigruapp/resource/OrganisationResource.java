package at.kigruapp.resource;

import at.kigruapp.dto.DutyEntryDTO;
import at.kigruapp.dto.OrganisationDTO;
import at.kigruapp.entity.DutyEntry;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.Organisation;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Path("/api/v1/organisation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrganisationResource {

    @GET
    public List<OrganisationDTO> list() {
        return Organisation.listAll().stream()
                .map(o -> toDTO((Organisation) o))
                .collect(Collectors.toList());
    }

    @GET
    @Path("/{tag}")
    public OrganisationDTO getByTag(@PathParam("tag") String tag) {
        Organisation org = Organisation.findByTag(tag);
        if (org == null) {
            throw new NotFoundException();
        }
        return toDTO(org);
    }

    @PUT
    @Path("/id/{id}")
    public Response update(@PathParam("id") String id, Organisation update) {
        Organisation org = Organisation.findById(new ObjectId(id));
        if (org == null) {
            throw new NotFoundException();
        }
        org.definitionIds = update.definitionIds;
        org.entries = update.entries;
        org.update();
        return Response.ok(toDTO(org)).build();
    }

    private OrganisationDTO toDTO(Organisation org) {
        OrganisationDTO dto = new OrganisationDTO();
        dto.id = org.id.toString();
        dto.tag = org.tag;
        dto.definitions = resolveDefinitions(org.definitionIds);
        if (org.entries != null) {
            dto.entries = org.entries.stream().map(this::toEntryDTO).collect(Collectors.toList());
        } else {
            dto.entries = new ArrayList<>();
        }
        return dto;
    }

    private DutyEntryDTO toEntryDTO(DutyEntry entry) {
        DutyEntryDTO dto = new DutyEntryDTO();
        dto.name = entry.name;
        dto.definitions = resolveDefinitions(entry.definitionIds);
        return dto;
    }

    private List<FieldDefinition> resolveDefinitions(List<ObjectId> defIds) {
        if (defIds == null || defIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<FieldDefinition> result = new ArrayList<>();
        for (ObjectId defId : defIds) {
            FieldDefinition def = FieldDefinition.findById(defId);
            if (def != null) {
                result.add(def);
            }
        }
        return result;
    }
}
