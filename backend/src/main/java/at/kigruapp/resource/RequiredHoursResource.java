package at.kigruapp.resource;

import at.kigruapp.dto.RequiredHoursDto;
import at.kigruapp.entity.RequiredHours;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

@Path("/api/v1/required-hours")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RequiredHoursResource {

    @GET
    public RequiredHoursDto get(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        RequiredHours cfg = RequiredHours.findBySemesterId(semesterId);
        return toDto(semesterId, cfg);
    }

    @PUT
    public RequiredHoursDto put(@QueryParam("semesterId") String semesterIdParam, RequiredHoursDto in) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        validate(in);

        RequiredHours cfg = RequiredHours.findBySemesterId(semesterId);
        if (cfg == null) {
            cfg = new RequiredHours();
            cfg.semesterId = semesterId;
        }
        cfg.defaultMinutesPerMonth = in.defaultMinutesPerMonth;
        cfg.tiers = new ArrayList<>();
        for (RequiredHoursDto.TierDto t : in.tiers) {
            RequiredHours.Tier tier = new RequiredHours.Tier();
            tier.fromChild = t.fromChild;
            tier.minutesPerMonth = t.minutesPerMonth;
            cfg.tiers.add(tier);
        }
        cfg.persistOrUpdate();
        return toDto(semesterId, cfg);
    }

    private ObjectId requireSemesterId(String semesterIdParam) {
        if (semesterIdParam == null || semesterIdParam.isBlank() || !ObjectId.isValid(semesterIdParam)) {
            throw new BadRequestException("semesterId erforderlich");
        }
        return new ObjectId(semesterIdParam);
    }

    private void validate(RequiredHoursDto in) {
        if (in == null || in.defaultMinutesPerMonth <= 0) {
            throw new BadRequestException("defaultMinutesPerMonth muss größer als 0 sein");
        }
        List<RequiredHoursDto.TierDto> tiers = in.tiers == null ? List.of() : in.tiers;
        int prevFrom = 1; // erster gültiger Tier-Wert ist 2 -> strikt größer als 1
        for (RequiredHoursDto.TierDto t : tiers) {
            if (t.fromChild < 2) {
                throw new BadRequestException("fromChild muss mindestens 2 sein");
            }
            if (t.fromChild <= prevFrom) {
                throw new BadRequestException("fromChild muss eindeutig und aufsteigend sein");
            }
            if (t.minutesPerMonth < 0) {
                throw new BadRequestException("minutesPerMonth darf nicht negativ sein");
            }
            prevFrom = t.fromChild;
        }
    }

    private RequiredHoursDto toDto(ObjectId semesterId, RequiredHours cfg) {
        RequiredHoursDto dto = new RequiredHoursDto();
        dto.semesterId = semesterId.toHexString();
        dto.tiers = new ArrayList<>();
        if (cfg != null) {
            dto.defaultMinutesPerMonth = cfg.defaultMinutesPerMonth;
            if (cfg.tiers != null) {
                for (RequiredHours.Tier t : cfg.tiers) {
                    RequiredHoursDto.TierDto td = new RequiredHoursDto.TierDto();
                    td.fromChild = t.fromChild;
                    td.minutesPerMonth = t.minutesPerMonth;
                    dto.tiers.add(td);
                }
            }
        }
        return dto;
    }
}
