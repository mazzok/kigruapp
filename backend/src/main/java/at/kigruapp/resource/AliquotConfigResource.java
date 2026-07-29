package at.kigruapp.resource;

import at.kigruapp.dto.AliquotConfigDto;
import at.kigruapp.entity.AliquotConfig;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

import java.util.Set;

@Path("/api/v1/aliquot-config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AliquotConfigResource {

    private static final Set<String> ALLOWED = Set.of("NONE", "WHOLE_MONTH", "PER_DAY");

    @GET
    public AliquotConfigDto get(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        AliquotConfig cfg = AliquotConfig.findBySemesterId(semesterId);
        return toDto(semesterId, cfg);
    }

    @PUT
    public AliquotConfigDto put(@QueryParam("semesterId") String semesterIdParam, AliquotConfigDto in) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        if (in == null || in.mode == null || !ALLOWED.contains(in.mode)) {
            throw new BadRequestException("mode muss NONE, WHOLE_MONTH oder PER_DAY sein");
        }
        AliquotConfig cfg = AliquotConfig.findBySemesterId(semesterId);
        if (cfg == null) {
            cfg = new AliquotConfig();
            cfg.semesterId = semesterId;
        }
        cfg.mode = in.mode;
        cfg.persistOrUpdate();
        return toDto(semesterId, cfg);
    }

    private ObjectId requireSemesterId(String semesterIdParam) {
        if (semesterIdParam == null || semesterIdParam.isBlank() || !ObjectId.isValid(semesterIdParam)) {
            throw new BadRequestException("semesterId erforderlich");
        }
        return new ObjectId(semesterIdParam);
    }

    private AliquotConfigDto toDto(ObjectId semesterId, AliquotConfig cfg) {
        AliquotConfigDto dto = new AliquotConfigDto();
        dto.semesterId = semesterId.toHexString();
        dto.mode = cfg != null ? cfg.mode : "NONE";
        return dto;
    }
}
