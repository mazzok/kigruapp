package at.kigruapp.resource;

import at.kigruapp.dto.CookingDutyDTO;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import at.kigruapp.service.CookingDutyQueryService;

import java.util.*;
import java.util.function.Predicate;

@Path("/api/v1/cooking-duties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CookingDutyResource {

    @Inject
    CookingDutyQueryService queryService;

    @GET
    public List<CookingDutyDTO> list(
            @QueryParam("month") String month,
            @QueryParam("groups") String groupsParam) {

        Set<String> groupFilter = new HashSet<>();
        if (groupsParam != null && !groupsParam.isBlank()) {
            groupFilter.addAll(Arrays.asList(groupsParam.split(",")));
        }

        Predicate<String> dateFilter = date -> month == null || month.isBlank() || date.startsWith(month);

        return queryService.query(dateFilter, groupFilter);
    }
}
