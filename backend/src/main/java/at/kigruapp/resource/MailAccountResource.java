package at.kigruapp.resource;

import at.kigruapp.entity.MailSettings;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Admin-only sender-account provider. Not whitelisted in SecurityFilter, so
 * the default-deny rule makes every method admin-only.
 *
 * Today there is exactly one selectable account (the MailSettings singleton).
 * This endpoint exists so the job configuration UI/validation doesn't hard-code
 * "one" — it grows to a real list when multiple accounts are supported later.
 */
@Path("/api/v1/mail-accounts")
@Produces(MediaType.APPLICATION_JSON)
public class MailAccountResource {

    public record MailAccountDto(String id, String fromAddress, String fromName, boolean enabled) {}

    @GET
    public List<MailAccountDto> list() {
        MailSettings settings = MailSettings.findSingleton();
        if (settings == null) {
            return List.of();
        }
        return List.of(new MailAccountDto(
                MailSettings.SINGLETON_ID.toHexString(),
                settings.fromAddress,
                settings.fromName,
                settings.enabled
        ));
    }
}
