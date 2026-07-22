package at.kigruapp.resource;

import at.kigruapp.dto.MailSettingsDto;
import at.kigruapp.dto.MailSettingsUpdateDto;
import at.kigruapp.entity.MailEncryption;
import at.kigruapp.entity.MailSettings;
import at.kigruapp.service.EncryptionService;
import at.kigruapp.service.MailException;
import at.kigruapp.service.MailService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.regex.Pattern;

/**
 * Admin-only mail settings endpoint. Not whitelisted in SecurityFilter, so the
 * default-deny rule makes every method admin-only.
 */
@Path("/api/v1/mail-settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MailSettingsResource {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final String TEST_SUBJECT = "kigruapp Testmail";
    private static final String TEST_BODY =
            "Dies ist eine Testmail von kigruapp. Der Mailversand ist korrekt konfiguriert.";

    @Inject
    EncryptionService encryptionService;

    @Inject
    MailService mailService;

    @GET
    public MailSettingsDto get() {
        return toDto(MailSettings.findSingleton());
    }

    @PUT
    public MailSettingsDto put(MailSettingsUpdateDto in) {
        validate(in);
        MailSettings s = MailSettings.findSingleton();
        if (s == null) {
            s = new MailSettings();
        }
        s.host = in.host;
        s.port = in.port;
        s.encryption = in.encryption;
        s.username = in.username;
        s.fromAddress = in.fromAddress;
        s.fromName = in.fromName;
        s.enabled = in.enabled;
        if (in.password != null && !in.password.isBlank()) {
            s.encryptedPassword = encryptionService.encrypt(in.password);
        } else if (Boolean.TRUE.equals(in.clearPassword)) {
            s.encryptedPassword = null;
        }
        s.persistSingleton();
        return toDto(s);
    }

    /**
     * Triggers a test mail. Always returns HTTP 200 with a normalized result; the
     * {@code category} and {@code message} never carry raw SMTP/server text
     * (normalization happens in {@link MailService}/{@link MailException}).
     */
    @POST
    @Path("/test")
    public TestResult sendTest(TestRequest req) {
        String recipient = req == null ? null : req.recipient;
        try {
            mailService.send(recipient, TEST_SUBJECT, TEST_BODY);
            return new TestResult(true, "OK", "Testmail wurde versendet");
        } catch (MailException e) {
            return new TestResult(false, e.category.name(), e.getMessage());
        }
    }

    public static class TestRequest {
        public String recipient;
    }

    public static class TestResult {
        public boolean success;
        public String category;
        public String message;

        public TestResult() {
        }

        public TestResult(boolean success, String category, String message) {
            this.success = success;
            this.category = category;
            this.message = message;
        }
    }

    private void validate(MailSettingsUpdateDto in) {
        if (in.host == null || in.host.isBlank()) {
            throw new BadRequestException("host must not be empty");
        }
        if (in.port < 1 || in.port > 65535) {
            throw new BadRequestException("port must be between 1 and 65535");
        }
        if (in.fromAddress == null || !EMAIL.matcher(in.fromAddress).matches()) {
            throw new BadRequestException("fromAddress must be a valid email address");
        }
    }

    static MailSettingsDto toDto(MailSettings s) {
        MailSettingsDto dto = new MailSettingsDto();
        if (s == null) {
            dto.encryption = MailEncryption.NONE;
            dto.enabled = false;
            dto.passwordSet = false;
            return dto;
        }
        dto.host = s.host;
        dto.port = s.port;
        dto.encryption = s.encryption;
        dto.username = s.username;
        dto.fromAddress = s.fromAddress;
        dto.fromName = s.fromName;
        dto.enabled = s.enabled;
        dto.passwordSet = s.encryptedPassword != null && !s.encryptedPassword.isBlank();
        return dto;
    }
}
