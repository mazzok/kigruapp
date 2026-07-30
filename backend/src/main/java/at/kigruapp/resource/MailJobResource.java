package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.MailJob;
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
 * Admin-only mail job endpoint. Not whitelisted in SecurityFilter, so the
 * default-deny rule makes every method admin-only.
 */
@Path("/api/v1/mail-jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MailJobResource {

    private final CronParser cronParser =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    /** fieldNames a selection of the given kind may legitimately point at. */
    private static final Map<RecipientKind, Set<String>> ALLOWED_FIELD_NAMES = Map.of(
            RecipientKind.GROUP, Set.of("group"),
            RecipientKind.TEAM, Set.of("parent-team", "board"),
            RecipientKind.ROLE, Set.of("parent-team-role", "board-role"));

    @Inject
    MailJobScheduler mailJobScheduler;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @GET
    public List<MailJob> list() {
        return MailJob.listAll(Sort.descending("updatedAt"));
    }

    @GET
    @Path("/{id}")
    public MailJob get(@PathParam("id") String id) {
        MailJob job = MailJob.findById(new ObjectId(id));
        if (job == null) {
            throw new NotFoundException();
        }
        return job;
    }

    @POST
    public Response create(MailJob request) {
        validate(request);
        MailJob job = new MailJob();
        applyFields(job, request);
        job.active = false;
        job.createdAt = Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();
        return Response.status(201).entity(job).build();
    }

    @PUT
    @Path("/{id}")
    public MailJob update(@PathParam("id") String id, MailJob request) {
        MailJob job = MailJob.findById(new ObjectId(id));
        if (job == null) {
            throw new NotFoundException();
        }
        validate(request);
        boolean cronChanged = !job.cron.equals(request.cron);
        applyFields(job, request);
        job.updatedAt = Instant.now();
        job.update();
        if (job.active && cronChanged) {
            mailJobScheduler.schedule(job);
        }
        return job;
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        MailJob job = MailJob.findById(new ObjectId(id));
        if (job == null) {
            throw new NotFoundException();
        }
        if (job.active) {
            mailJobScheduler.unschedule(job.id);
        }
        job.delete();
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/activate")
    @Consumes(MediaType.WILDCARD)
    public MailJob activate(@PathParam("id") String id) {
        MailJob job = MailJob.findById(new ObjectId(id));
        if (job == null) {
            throw new NotFoundException();
        }
        try {
            cronParser.parse(job.cron).validate();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid cron expression: " + e.getMessage());
        }
        mailJobScheduler.schedule(job);
        job.active = true;
        job.updatedAt = Instant.now();
        job.update();
        return job;
    }

    @POST
    @Path("/{id}/deactivate")
    @Consumes(MediaType.WILDCARD)
    public MailJob deactivate(@PathParam("id") String id) {
        MailJob job = MailJob.findById(new ObjectId(id));
        if (job == null) {
            throw new NotFoundException();
        }
        mailJobScheduler.unschedule(job.id);
        job.active = false;
        job.updatedAt = Instant.now();
        job.update();
        return job;
    }

    private void applyFields(MailJob job, MailJob request) {
        job.name = request.name;
        job.templateId = request.templateId;
        job.subject = request.subject;
        job.senderAccountId = request.senderAccountId;
        job.cron = request.cron;
        job.allParents = request.allParents;
        job.recipientSelections = request.recipientSelections == null
                ? new ArrayList<>()
                : request.recipientSelections;
    }

    private void validate(MailJob request) {
        if (request.name == null || request.name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (request.templateId == null) {
            throw new BadRequestException("templateId is required");
        }
        if (request.subject == null || request.subject.isBlank()) {
            throw new BadRequestException("subject is required");
        }
        if (request.cron == null || request.cron.isBlank()) {
            throw new BadRequestException("cron is required");
        }
        try {
            cronParser.parse(request.cron).validate();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid cron expression: " + e.getMessage());
        }
        validateSenderAccountId(request.senderAccountId);
        validateRecipientSelections(request);
    }

    private void validateSenderAccountId(String senderAccountId) {
        at.kigruapp.entity.MailAccount account = null;
        if (senderAccountId != null) {
            try {
                account = at.kigruapp.entity.MailAccount.findById(new ObjectId(senderAccountId));
            } catch (IllegalArgumentException ignored) {
                // malformed id -> treated as unknown below
            }
        }
        if (account == null) {
            throw new BadRequestException("senderAccountId does not reference a known mail account");
        }
        if (!account.enabled) {
            throw new BadRequestException("senderAccountId references a disabled mail account");
        }
    }

    /**
     * Each selection must point at an existing field instance whose definition is
     * not outdated and matches the selection's kind. Skipped entirely when the job
     * addresses all parents, because the selections are ignored at run time then.
     */
    private void validateRecipientSelections(MailJob request) {
        if (request.allParents || request.recipientSelections == null) {
            return;
        }
        for (RecipientSelection sel : request.recipientSelections) {
            if (sel == null || sel.kind == null || sel.fieldInstanceId == null) {
                throw new BadRequestException("recipientSelections entry is missing kind or fieldInstanceId");
            }
            if (!matchesKind(sel)) {
                throw new BadRequestException("recipientSelections contains an unknown or outdated "
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
}
