package at.kigruapp.resource;

import at.kigruapp.dto.KostenDiscountDto;
import at.kigruapp.entity.KostenDiscount;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Path("/api/v1/kosten-discount")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KostenDiscountResource {

    private static final Set<String> ORDERS = Set.of("MOST_EXPENSIVE_FIRST", "LEAST_EXPENSIVE_FIRST");

    @GET
    public KostenDiscountDto get(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        return toDto(semesterId, KostenDiscount.findBySemesterId(semesterId));
    }

    @PUT
    public KostenDiscountDto put(@QueryParam("semesterId") String semesterIdParam, KostenDiscountDto in) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        validate(in);

        KostenDiscount cfg = KostenDiscount.findBySemesterId(semesterId);
        if (cfg == null) {
            cfg = new KostenDiscount();
            cfg.semesterId = semesterId;
        }
        cfg.applyToAll = in.applyToAll;
        cfg.order = in.order;
        cfg.tiers = new ArrayList<>();
        for (KostenDiscountDto.TierDto t : in.tiers) {
            KostenDiscount.Tier tier = new KostenDiscount.Tier();
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

    private void validate(KostenDiscountDto in) {
        if (in == null || in.order == null || !ORDERS.contains(in.order)) {
            throw new BadRequestException("order muss MOST_EXPENSIVE_FIRST oder LEAST_EXPENSIVE_FIRST sein");
        }
        List<KostenDiscountDto.TierDto> tiers = in.tiers == null ? List.of() : in.tiers;
        int prevFrom = 1;
        for (KostenDiscountDto.TierDto t : tiers) {
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
    }

    private KostenDiscountDto toDto(ObjectId semesterId, KostenDiscount cfg) {
        KostenDiscountDto dto = new KostenDiscountDto();
        dto.semesterId = semesterId.toHexString();
        dto.tiers = new ArrayList<>();
        if (cfg == null) {
            dto.applyToAll = false;
            dto.order = "MOST_EXPENSIVE_FIRST";
            return dto;
        }
        dto.applyToAll = cfg.applyToAll;
        dto.order = cfg.order != null ? cfg.order : "MOST_EXPENSIVE_FIRST";
        if (cfg.tiers != null) {
            for (KostenDiscount.Tier t : cfg.tiers) {
                KostenDiscountDto.TierDto td = new KostenDiscountDto.TierDto();
                td.fromChild = t.fromChild;
                td.percent = t.percent;
                dto.tiers.add(td);
            }
        }
        return dto;
    }
}
