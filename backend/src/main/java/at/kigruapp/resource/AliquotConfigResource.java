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
        return toDto(semesterId, AliquotConfig.findBySemesterId(semesterId));
    }

    @PUT
    public AliquotConfigDto put(@QueryParam("semesterId") String semesterIdParam, AliquotConfigDto in) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        if (in == null || !ALLOWED.contains(in.stundenMode) || !ALLOWED.contains(in.kostenMode)) {
            throw new BadRequestException("stundenMode/kostenMode müssen NONE, WHOLE_MONTH oder PER_DAY sein");
        }
        AliquotConfig cfg = AliquotConfig.findBySemesterId(semesterId);
        if (cfg == null) {
            cfg = new AliquotConfig();
            cfg.semesterId = semesterId;
        }
        cfg.stundenMode = in.stundenMode;
        cfg.kostenMode = in.kostenMode;
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
        dto.stundenMode = cfg != null ? cfg.stundenMode : "NONE";
        dto.kostenMode = cfg != null ? cfg.kostenMode : "NONE";
        return dto;
    }
}
