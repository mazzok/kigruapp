package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import at.kigruapp.entity.RecipientKind;
import at.kigruapp.entity.RecipientSelection;
import at.kigruapp.scheduler.MailJobScheduler;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kochdienst-Uebersichtsjobs: Job und die fest zugeordnete Vorlage werden hier
 * gemeinsam gepflegt, analog zu CookingReminderJobResource, aber mit echtem
 * Cron und konfigurierbaren Empfaengern statt sendTime. Admin-only (nicht im
 * SecurityFilter freigeschaltet).
 */
@Path("/api/v1/cooking-overview-jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CookingOverviewJobResource {

    /** fieldNames a selection of the given kind may legitimately point at. Mirrors MailJobResource. */
    private static final Map<RecipientKind, Set<String>> ALLOWED_FIELD_NAMES = Map.of(
            RecipientKind.GROUP, Set.of("group"),
            RecipientKind.TEAM, Set.of("parent-team", "board"),
            RecipientKind.ROLE, Set.of("parent-team-role", "board-role"));

    private final CronParser cronParser =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    @Inject
    MailJobScheduler mailJobScheduler;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public record JobDto(String id, String name, String senderAccountId, String subject,
                         String cron, boolean allParents, List<RecipientSelection> recipientSelections,
                         boolean active, String templateId, String templateName, String templateBodyHtml) {}

    public record SaveRequest(String name, String senderAccountId, String subject, String cron,
                              boolean allParents, List<RecipientSelection> recipientSelections,
                              boolean active, String templateName, String templateBodyHtml) {}

    @GET
    public List<JobDto> list() {
        List<JobDto> result = new ArrayList<>();
        for (MailJob job : MailJob.<MailJob>listAll(Sort.descending("updatedAt"))) {
            if (job.isCookingOverview()) {
                result.add(toDto(job, loadTemplate(job)));
            }
        }
        return result;
    }

    @POST
    public Response create(SaveRequest request) {
        validate(request);
        MailTemplate template = new MailTemplate();
        template.name = request.templateName().trim();
        template.bodyHtml = MailTemplateResource.sanitizeBody(request.templateBodyHtml());
        template.kind = MailTemplate.KIND_COOKING_OVERVIEW;
        template.createdAt = Instant.now();
        template.updatedAt = template.createdAt;
        template.persist();

        MailJob job = new MailJob();
        try {
            job.kind = MailJob.KIND_COOKING_OVERVIEW;
            job.templateId = template.id;
            applyFields(job, request);
            job.createdAt = Instant.now();
            job.updatedAt = job.createdAt;
            job.persist();
        } catch (RuntimeException e) {
            // Ohne Rollback bliebe eine Vorlage ohne Job zurueck, die nirgends auftaucht.
            template.delete();
            throw e;
        }
        if (job.active) {
            mailJobScheduler.schedule(job);
        }
        return Response.status(201).entity(toDto(job, template)).build();
    }

    @PUT
    @Path("/{id}")
    public JobDto update(@PathParam("id") String id, SaveRequest request) {
        MailJob job = findOverviewJob(id);
        validate(request);

        MailTemplate template = loadTemplate(job);
        if (template == null) {
            template = new MailTemplate();
            template.kind = MailTemplate.KIND_COOKING_OVERVIEW;
            template.createdAt = Instant.now();
        }
        template.name = request.templateName().trim();
        template.bodyHtml = MailTemplateResource.sanitizeBody(request.templateBodyHtml());
        template.updatedAt = Instant.now();
        template.persistOrUpdate();

        job.templateId = template.id;
        applyFields(job, request);
        job.updatedAt = Instant.now();
        job.update();
        if (job.active) {
            mailJobScheduler.schedule(job);
        } else {
            mailJobScheduler.unschedule(job.id);
        }
        return toDto(job, template);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        MailJob job = findOverviewJob(id);
        MailTemplate template = loadTemplate(job);
        if (job.active) {
            mailJobScheduler.unschedule(job.id);
        }
        job.delete();
        if (template != null) {
            template.delete();
        }
        return Response.noContent().build();
    }

    private MailJob findOverviewJob(String id) {
        if (!ObjectId.isValid(id)) {
            throw new NotFoundException();
        }
        MailJob job = MailJob.findById(new ObjectId(id));
        if (job == null || !job.isCookingOverview()) {
            throw new NotFoundException();
        }
        return job;
    }

    private MailTemplate loadTemplate(MailJob job) {
        return job.templateId == null ? null : MailTemplate.findById(job.templateId);
    }

    private void applyFields(MailJob job, SaveRequest request) {
        job.name = request.name().trim();
        job.senderAccountId = request.senderAccountId();
        job.subject = request.subject().trim();
        job.cron = request.cron().trim();
        job.allParents = request.allParents();
        job.recipientSelections = request.recipientSelections() == null
                ? new ArrayList<>()
                : request.recipientSelections();
        job.active = request.active();
    }

    private void validate(SaveRequest request) {
        if (request == null) {
            throw new BadRequestException("Anfrage ist leer");
        }
        requireText(request.name(), "Name ist erforderlich");
        requireText(request.subject(), "Betreff ist erforderlich");
        requireText(request.templateName(), "Name der Vorlage ist erforderlich");
        requireText(request.templateBodyHtml(), "Inhalt der Vorlage ist erforderlich");
        requireText(request.cron(), "Zeitplan ist erforderlich");
        try {
            cronParser.parse(request.cron()).validate();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Zeitplan ist ungueltig: " + e.getMessage());
        }
        MailAccount account = request.senderAccountId() != null && ObjectId.isValid(request.senderAccountId())
                ? MailAccount.<MailAccount>findById(new ObjectId(request.senderAccountId()))
                : null;
        if (account == null) {
            throw new BadRequestException("Mailkonto existiert nicht");
        }
        if (request.active() && !account.enabled) {
            throw new BadRequestException("Ein aktiver Job braucht ein freigeschaltetes Mailkonto");
        }
        validateRecipientSelections(request);
    }

    private void validateRecipientSelections(SaveRequest request) {
        if (request.allParents() || request.recipientSelections() == null) {
            return;
        }
        for (RecipientSelection sel : request.recipientSelections()) {
            if (sel == null || sel.kind == null || sel.fieldInstanceId == null) {
                throw new BadRequestException("recipientSelections enthaelt einen Eintrag ohne kind oder fieldInstanceId");
            }
            if (!matchesKind(sel)) {
                throw new BadRequestException("recipientSelections enthaelt eine unbekannte oder veraltete "
                        + sel.kind + ": " + sel.fieldInstanceId);
            }
        }
    }

    private boolean matchesKind(RecipientSelection sel) {
        Document inst = mongoClient.getDatabase(databaseName)
                .getCollection("field_instances")
                .find(Filters.eq("_id", sel.fieldInstanceId))
                .first();
        if (inst == null) {
            return false;
        }
        ObjectId definitionId = inst.getObjectId("definitionId");
        if (definitionId == null) {
            return false;
        }
        FieldDefinition def = FieldDefinition.findById(definitionId);
        return def != null && def.outdatedAt == null
                && ALLOWED_FIELD_NAMES.get(sel.kind).contains(def.fieldName);
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
    }

    private JobDto toDto(MailJob job, MailTemplate template) {
        return new JobDto(job.id.toHexString(), job.name, job.senderAccountId, job.subject,
                job.cron, job.allParents, job.recipientSelections, job.active,
                template == null ? null : template.id.toHexString(),
                template == null ? null : template.name,
                template == null ? null : template.bodyHtml);
    }
}
