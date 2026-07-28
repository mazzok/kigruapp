package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.panache.common.Sort;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin-only mail template endpoint. Not whitelisted in SecurityFilter, so the
 * default-deny rule makes every method admin-only.
 */
@Path("/api/v1/mail-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MailTemplateResource {

    private static final Set<String> SCALAR_PERSON_FIELD_ALLOWLIST = Set.of(
            "firstName", "lastName", "email", "phone", "dateOfBirth", "gender", "entryDate", "exitDate", "notes"
    );

    /**
     * Defensive sanitize pass for template bodies (G-003): keeps common inline
     * formatting tags plus the style attribute (needed for Quill's inline-styled
     * output to survive), strips everything else (scripts, event handlers, ...).
     */
    private static final PolicyFactory HTML_POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "b", "strong", "i", "em", "u", "ol", "ul", "li", "a", "span", "div")
            .allowAttributes("href").onElements("a")
            .allowAttributes("style").globally()
            .allowStandardUrlProtocols()
            .toFactory();

    /**
     * Sanitize the body, then strip the empty comments the sanitizer wedges
     * between braces (e.g. {@code {<!-- -->{}) to neutralize template syntax.
     * Without this, stored {@code {{person.x}}} tokens are broken and neither the
     * renderer nor the editor's token→pill conversion can match them.
     */
    private static String sanitizeBody(String bodyHtml) {
        return HTML_POLICY.sanitize(bodyHtml).replaceAll("<!--\\s*-->", "");
    }

    public record PlaceholderTile(String token, String fieldName, Map<String, String> label) {}

    @GET
    @Path("/placeholders")
    public List<PlaceholderTile> placeholders() {
        return FieldDefinition.findActive().stream()
                .filter(def -> SCALAR_PERSON_FIELD_ALLOWLIST.contains(def.fieldName))
                .map(def -> new PlaceholderTile("{{person." + def.fieldName + "}}", def.fieldName, def.label))
                .sorted(Comparator.comparing(t -> labelSortKey(t.label())))
                .collect(Collectors.toList());
    }

    private String labelSortKey(Map<String, String> label) {
        if (label == null) return "";
        String v = label.get("de");
        return v != null ? v : String.valueOf(label.values().stream().findFirst().orElse(""));
    }

    @GET
    public List<MailTemplate> list() {
        return MailTemplate.listAll(Sort.descending("updatedAt"));
    }

    @GET
    @Path("/{id}")
    public MailTemplate get(@PathParam("id") String id) {
        MailTemplate template = MailTemplate.findById(new ObjectId(id));
        if (template == null) {
            throw new NotFoundException();
        }
        return template;
    }

    @POST
    public Response create(MailTemplate request) {
        validate(request);
        MailTemplate template = new MailTemplate();
        template.name = request.name;
        template.bodyHtml = sanitizeBody(request.bodyHtml);
        template.createdAt = Instant.now();
        template.updatedAt = template.createdAt;
        template.persist();
        return Response.status(201).entity(template).build();
    }

    @PUT
    @Path("/{id}")
    public MailTemplate update(@PathParam("id") String id, MailTemplate request) {
        MailTemplate template = MailTemplate.findById(new ObjectId(id));
        if (template == null) {
            throw new NotFoundException();
        }
        validate(request);
        template.name = request.name;
        template.bodyHtml = sanitizeBody(request.bodyHtml);
        template.updatedAt = Instant.now();
        template.update();
        return template;
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        ObjectId templateId = new ObjectId(id);
        MailTemplate template = MailTemplate.findById(templateId);
        if (template == null) {
            throw new NotFoundException();
        }
        List<MailJob> referencingJobs = MailJob.list("templateId", templateId);
        if (!referencingJobs.isEmpty()) {
            String jobIds = referencingJobs.stream().map(j -> j.id.toHexString()).collect(Collectors.joining(", "));
            throw new WebApplicationException("template is referenced by job(s): " + jobIds, 409);
        }
        template.delete();
        return Response.noContent().build();
    }

    private void validate(MailTemplate request) {
        if (request.name == null || request.name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (request.bodyHtml == null || request.bodyHtml.isBlank()) {
            throw new BadRequestException("bodyHtml is required");
        }
    }
}
