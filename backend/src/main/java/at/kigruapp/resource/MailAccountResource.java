package at.kigruapp.resource;

import at.kigruapp.dto.MailAccountDto;
import at.kigruapp.dto.MailAccountUpdateDto;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.service.EncryptionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Admin-only mail account CRUD. Not whitelisted in SecurityFilter, so the
 * default-deny rule makes every method admin-only.
 */
@Path("/api/v1/mail-accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MailAccountResource {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Inject
    EncryptionService encryptionService;

    @GET
    public List<MailAccountDto> list() {
        return MailAccount.<MailAccount>listAll().stream().map(MailAccountResource::toDto).toList();
    }

    @GET
    @Path("/{id}")
    public MailAccountDto get(@PathParam("id") String id) {
        MailAccount account = MailAccount.findById(new ObjectId(id));
        if (account == null) {
            throw new NotFoundException();
        }
        return toDto(account);
    }

    @POST
    public Response create(MailAccountUpdateDto in) {
        MailAccount account = new MailAccount();
        validate(in, account);
        apply(account, in);
        account.persist();
        return Response.status(201).entity(toDto(account)).build();
    }

    @PUT
    @Path("/{id}")
    public MailAccountDto update(@PathParam("id") String id, MailAccountUpdateDto in) {
        MailAccount account = MailAccount.findById(new ObjectId(id));
        if (account == null) {
            throw new NotFoundException();
        }
        validate(in, account);
        apply(account, in);
        account.update();
        return toDto(account);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        MailAccount account = MailAccount.findById(new ObjectId(id));
        if (account == null) {
            throw new NotFoundException();
        }
        if (MailJob.count("senderAccountId", id) > 0) {
            throw new WebApplicationException(
                    "Konto wird von einem Job verwendet und kann nicht gelöscht werden", 409);
        }
        account.delete();
        return Response.noContent().build();
    }

    /** {@code existing} carries an already-stored password so username-without-password is allowed on update. */
    private void validate(MailAccountUpdateDto in, MailAccount existing) {
        if (in.name == null || in.name.isBlank()) {
            throw new BadRequestException("name must not be empty");
        }
        if (in.host == null || in.host.isBlank()) {
            throw new BadRequestException("host must not be empty");
        }
        if (in.port < 1 || in.port > 65535) {
            throw new BadRequestException("port must be between 1 and 65535");
        }
        if (in.encryption == null) {
            throw new BadRequestException("encryption must be set");
        }
        if (in.fromAddress == null || !EMAIL.matcher(in.fromAddress).matches()) {
            throw new BadRequestException("fromAddress must be a valid email address");
        }
        boolean providesPassword = in.password != null && !in.password.isBlank();
        boolean hasStoredPassword = existing.encryptedPassword != null && !existing.encryptedPassword.isBlank()
                && !Boolean.TRUE.equals(in.clearPassword);
        if (in.username != null && !in.username.isBlank() && !providesPassword && !hasStoredPassword) {
            throw new BadRequestException("password is required when a username is set");
        }
    }

    private void apply(MailAccount account, MailAccountUpdateDto in) {
        account.name = in.name;
        account.host = in.host;
        account.port = in.port;
        account.encryption = in.encryption;
        account.username = in.username;
        account.fromAddress = in.fromAddress;
        account.fromName = in.fromName;
        account.enabled = in.enabled;
        if (in.password != null && !in.password.isBlank()) {
            account.encryptedPassword = encryptionService.encrypt(in.password);
        } else if (Boolean.TRUE.equals(in.clearPassword)) {
            account.encryptedPassword = null;
        }
    }

    static MailAccountDto toDto(MailAccount a) {
        MailAccountDto dto = new MailAccountDto();
        dto.id = a.id.toHexString();
        dto.name = a.name;
        dto.host = a.host;
        dto.port = a.port;
        dto.encryption = a.encryption;
        dto.username = a.username;
        dto.fromAddress = a.fromAddress;
        dto.fromName = a.fromName;
        dto.enabled = a.enabled;
        dto.passwordSet = a.encryptedPassword != null && !a.encryptedPassword.isBlank();
        return dto;
    }
}
