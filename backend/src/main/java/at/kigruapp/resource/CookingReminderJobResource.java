package at.kigruapp.resource;

import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.panache.common.Sort;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Kochdienst-Erinnerungen: Job und die fest zugeordnete Vorlage werden hier
 * gemeinsam gepflegt. Die 1:1-Bindung liegt bewusst im Backend — die UI kann
 * sie nicht durch einen halb fehlgeschlagenen Doppel-Request zerreissen.
 * Admin-only (nicht im SecurityFilter freigeschaltet).
 */
@Path("/api/v1/cooking-reminder-jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CookingReminderJobResource {

    private static final Pattern SEND_TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    @jakarta.inject.Inject
    at.kigruapp.scheduler.CookingReminderScheduler cookingReminderScheduler;

    public record JobDto(String id, String name, String senderAccountId, String subject,
                         String sendTime, boolean active, String templateId,
                         String templateName, String templateBodyHtml) {}

    public record SaveRequest(String name, String senderAccountId, String subject, String sendTime,
                              boolean active, String templateName, String templateBodyHtml) {}

    @GET
    public List<JobDto> list() {
        List<JobDto> result = new ArrayList<>();
        for (MailJob job : MailJob.<MailJob>listAll(Sort.descending("updatedAt"))) {
            if (job.isCooking()) {
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
        template.kind = MailTemplate.KIND_COOKING;
        template.createdAt = Instant.now();
        template.updatedAt = template.createdAt;
        template.persist();

        MailJob job = new MailJob();
        try {
            job.kind = MailJob.KIND_COOKING;
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
        cookingReminderScheduler.reschedule();
        return Response.status(201).entity(toDto(job, template)).build();
    }

    @PUT
    @Path("/{id}")
    public JobDto update(@PathParam("id") String id, SaveRequest request) {
        MailJob job = findCookingJob(id);
        validate(request);

        MailTemplate template = loadTemplate(job);
        if (template == null) {
            template = new MailTemplate();
            template.kind = MailTemplate.KIND_COOKING;
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
        cookingReminderScheduler.reschedule();
        return toDto(job, template);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        MailJob job = findCookingJob(id);
        MailTemplate template = loadTemplate(job);
        job.delete();
        if (template != null) {
            template.delete();
        }
        cookingReminderScheduler.reschedule();
        return Response.noContent().build();
    }

    private MailJob findCookingJob(String id) {
        if (!ObjectId.isValid(id)) {
            throw new NotFoundException();
        }
        MailJob job = MailJob.findById(new ObjectId(id));
        if (job == null || !job.isCooking()) {
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
        job.sendTime = request.sendTime().trim();
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
        if (request.sendTime() == null || !SEND_TIME_PATTERN.matcher(request.sendTime().trim()).matches()) {
            throw new BadRequestException("Versandzeit muss im Format HH:mm vorliegen");
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
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
    }

    private JobDto toDto(MailJob job, MailTemplate template) {
        return new JobDto(job.id.toHexString(), job.name, job.senderAccountId, job.subject,
                job.sendTime, job.active,
                template == null ? null : template.id.toHexString(),
                template == null ? null : template.name,
                template == null ? null : template.bodyHtml);
    }
}
