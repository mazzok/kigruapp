package at.kigruapp.resource;

import at.kigruapp.service.HolidayService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Path("/api/v1/holidays")
@Produces(MediaType.APPLICATION_JSON)
public class HolidayResource {

    @Inject
    HolidayService holidayService;

    @GET
    public List<HolidayService.HolidayDto> list(@QueryParam("from") String from,
                                                @QueryParam("to") String to) {
        return holidayService.between(parse(from, "from"), parse(to, "to"));
    }

    private LocalDate parse(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(name + " must be yyyy-MM-dd");
        }
    }
}
