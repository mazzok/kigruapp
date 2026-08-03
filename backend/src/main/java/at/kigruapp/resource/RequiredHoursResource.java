package at.kigruapp.resource;

import at.kigruapp.dto.RequiredHoursDto;
import at.kigruapp.entity.RequiredHours;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

@Path("/api/v1/required-hours")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RequiredHoursResource {

    @Inject
    at.kigruapp.service.GroupCatalogService groupCatalog;

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
        cfg.allGroups = in.allGroups;
        cfg.order = RequiredHours.LEAST_EXPENSIVE_FIRST.equals(in.order)
                ? RequiredHours.LEAST_EXPENSIVE_FIRST : RequiredHours.MOST_EXPENSIVE_FIRST;
        // Beim Umschalten auf "für alle Gruppen" bleiben die Gruppenwerte erhalten,
        // damit ein versehentlicher Klick die Eingaben nicht vernichtet.
        if (!in.allGroups) {
            cfg.groupRates = new ArrayList<>();
            for (RequiredHoursDto.GroupRateDto g : in.groupRates) {
                RequiredHours.GroupRate rate = new RequiredHours.GroupRate();
                rate.groupInstanceId = new ObjectId(g.groupInstanceId);
                rate.minutesPerMonth = g.minutesPerMonth;
                cfg.groupRates.add(rate);
            }
        }
        cfg.tiers = new ArrayList<>();
        for (RequiredHoursDto.TierDto t : in.tiers) {
            RequiredHours.Tier tier = new RequiredHours.Tier();
            tier.fromChild = t.fromChild;
            tier.percent = t.percent;
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
            if (t.percent < 0 || t.percent > 100) {
                throw new BadRequestException("percent muss zwischen 0 und 100 liegen");
            }
            prevFrom = t.fromChild;
        }
        if (!in.allGroups) {
            List<RequiredHoursDto.GroupRateDto> rates =
                    in.groupRates == null ? List.of() : in.groupRates;
            java.util.Map<String, Integer> byGroup = new java.util.HashMap<>();
            for (RequiredHoursDto.GroupRateDto g : rates) {
                if (g.groupInstanceId == null || !ObjectId.isValid(g.groupInstanceId)) {
                    throw new BadRequestException("groupInstanceId ungültig");
                }
                if (g.minutesPerMonth <= 0) {
                    throw new BadRequestException("Stunden je Gruppe müssen größer als 0 sein");
                }
                byGroup.put(g.groupInstanceId, g.minutesPerMonth);
            }
            for (at.kigruapp.service.GroupCatalogService.GroupInfo group : groupCatalog.listGroups()) {
                if (!byGroup.containsKey(group.id().toHexString())) {
                    throw new BadRequestException("Stunden fehlen für Gruppe " + group.label());
                }
            }
        }
    }

    private RequiredHoursDto toDto(ObjectId semesterId, RequiredHours cfg) {
        RequiredHoursDto dto = new RequiredHoursDto();
        dto.semesterId = semesterId.toHexString();
        dto.tiers = new ArrayList<>();
        dto.groupRates = new ArrayList<>();
        if (cfg != null) {
            dto.defaultMinutesPerMonth = cfg.defaultMinutesPerMonth;
            dto.allGroups = cfg.allGroups;
            dto.order = cfg.order == null ? RequiredHours.MOST_EXPENSIVE_FIRST : cfg.order;
            if (cfg.groupRates != null) {
                for (RequiredHours.GroupRate g : cfg.groupRates) {
                    RequiredHoursDto.GroupRateDto gd = new RequiredHoursDto.GroupRateDto();
                    gd.groupInstanceId = g.groupInstanceId == null ? null : g.groupInstanceId.toHexString();
                    gd.minutesPerMonth = g.minutesPerMonth;
                    dto.groupRates.add(gd);
                }
            }
            if (cfg.tiers != null) {
                for (RequiredHours.Tier t : cfg.tiers) {
                    RequiredHoursDto.TierDto td = new RequiredHoursDto.TierDto();
                    td.fromChild = t.fromChild;
                    td.percent = t.percent;
                    dto.tiers.add(td);
                }
            }
        }
        return dto;
    }
}
