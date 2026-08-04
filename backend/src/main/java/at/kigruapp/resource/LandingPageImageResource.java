package at.kigruapp.resource;

import at.kigruapp.entity.LandingPageImage;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Bilder für die Startseite. Getrennt von {@link LandingPageResource}, weil der
 * Sanitizer dort {@code data:}-URIs verwirft (siehe dessen Klassenkommentar) —
 * hochgeladene Bilder landen daher hier und werden per http(s)-URL referenziert.
 */
@Path("/api/v1/landing-page/images")
public class LandingPageImageResource {

    /** Deckt sich mit der Prüfung im Frontend (Auswahl-Dialog); hier zusätzlich serverseitig erzwungen. */
    static final int MAX_BYTES = 5 * 1024 * 1024;

    public record UploadResponse(String id, String url) {}

    @POST
    @Consumes({"image/png", "image/jpeg", "image/gif", "image/webp"})
    @Produces(MediaType.APPLICATION_JSON)
    public UploadResponse upload(byte[] data, @HeaderParam("Content-Type") String contentType) {
        if (data == null || data.length == 0) {
            throw new BadRequestException("Bilddaten fehlen");
        }
        if (data.length > MAX_BYTES) {
            throw new BadRequestException("Bild ist zu groß (max. 5 MB)");
        }

        LandingPageImage image = new LandingPageImage();
        image.contentType = contentType;
        image.data = data;
        image.createdAt = Instant.now();
        image.persist();

        String id = image.id.toString();
        return new UploadResponse(id, "/api/v1/landing-page/images/" + id);
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        if (!ObjectId.isValid(id)) {
            throw new NotFoundException();
        }
        LandingPageImage image = LandingPageImage.findById(new ObjectId(id));
        if (image == null) {
            throw new NotFoundException();
        }
        return Response.ok(image.data).type(image.contentType).build();
    }
}
