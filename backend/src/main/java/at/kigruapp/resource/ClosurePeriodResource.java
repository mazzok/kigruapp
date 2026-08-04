package at.kigruapp.resource;

import at.kigruapp.entity.ClosureDefinition;
import at.kigruapp.entity.ClosurePeriod;
import at.kigruapp.service.ClosurePeriodNormalizer;
import at.kigruapp.service.ClosurePeriodNormalizer.DateSpan;
import at.kigruapp.service.HolidayService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@Path("/api/v1/closure-periods")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClosurePeriodResource {

    @Inject
    HolidayService holidayService;

    public record PeriodDto(String id, LocalDate from, LocalDate to, String definitionId) {}

    public record ApplyRequest(List<String> days, String definitionId, String mode) {}

    @GET
    public List<PeriodDto> list(@QueryParam("from") String from, @QueryParam("to") String to) {
        LocalDate start = parseDate(from, "from");
        LocalDate end = parseDate(to, "to");
        if (end.isBefore(start)) {
            throw new BadRequestException("to must not be before from");
        }
        return ClosurePeriod.findOverlapping(start, end).stream()
            .sorted(Comparator.comparing((ClosurePeriod p) -> p.from))
            .map(ClosurePeriodResource::toDto)
            .toList();
    }

    /**
     * Nimmt die rohe Tagesauswahl entgegen und ersetzt saemtliche Zeitraeume der
     * betroffenen Definition durch das normalisierte Ergebnis. Dadurch ist der
     * Aufruf wiederholbar, ohne Duplikate zu erzeugen.
     */
    @POST
    @Path("/apply")
    public List<PeriodDto> apply(ApplyRequest request) {
        if (request == null || request.days() == null || request.days().isEmpty()) {
            throw new BadRequestException("days must not be empty");
        }
        boolean assigning = "assign".equals(request.mode());
        if (!assigning && !"remove".equals(request.mode())) {
            throw new BadRequestException("mode must be 'assign' or 'remove'");
        }

        ClosureDefinition definition = findDefinitionOr404(request.definitionId());
        if (assigning && !definition.active) {
            throw new ClientErrorException(
                "Definition ist deaktiviert und kann nicht mehr zugewiesen werden.",
                Response.Status.CONFLICT);
        }

        List<LocalDate> days = request.days().stream()
            .map(value -> parseDate(value, "days"))
            .sorted()
            .toList();

        List<ClosurePeriod> existing = ClosurePeriod.findByDefinition(definition.id);
        Predicate<LocalDate> selectable = selectablePredicate(days, existing);

        for (LocalDate day : days) {
            if (!selectable.test(day)) {
                throw new BadRequestException(
                    "Tag " + day + " ist ein Wochenende oder Feiertag und kann nicht zugeordnet werden.");
            }
        }

        List<DateSpan> before = existing.stream()
            .map(p -> new DateSpan(p.from, p.to))
            .sorted(Comparator.comparing(DateSpan::from))
            .toList();

        List<DateSpan> after = assigning
            ? ClosurePeriodNormalizer.assign(before, days, selectable)
            : ClosurePeriodNormalizer.remove(before, days, selectable);

        ClosurePeriod.delete("definitionId", definition.id);
        List<PeriodDto> result = new ArrayList<>();
        for (DateSpan span : after) {
            ClosurePeriod period = new ClosurePeriod();
            period.from = span.from();
            period.to = span.to();
            period.definitionId = definition.id;
            period.persist();
            result.add(toDto(period));
        }
        return result;
    }

    /**
     * Ein Tag ist auswaehlbar, wenn er ein Werktag und kein Feiertag ist. Die
     * Feiertage werden einmal fuer das gesamte betroffene Fenster geladen, damit
     * der Normalizer sie ohne weitere Abfragen auswerten kann.
     */
    private Predicate<LocalDate> selectablePredicate(List<LocalDate> days, List<ClosurePeriod> existing) {
        LocalDate min = days.get(0);
        LocalDate max = days.get(days.size() - 1);
        for (ClosurePeriod period : existing) {
            if (period.from.isBefore(min)) {
                min = period.from;
            }
            if (period.to.isAfter(max)) {
                max = period.to;
            }
        }
        Set<LocalDate> holidays = holidayService.datesBetween(min, max);
        return day -> ClosurePeriodNormalizer.isWeekday(day) && !holidays.contains(day);
    }

    private ClosureDefinition findDefinitionOr404(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new NotFoundException("Definition nicht gefunden");
        }
        ClosureDefinition definition = ClosureDefinition.findById(new ObjectId(id));
        if (definition == null) {
            throw new NotFoundException("Definition nicht gefunden");
        }
        return definition;
    }

    private static PeriodDto toDto(ClosurePeriod period) {
        return new PeriodDto(period.id.toString(), period.from, period.to,
            period.definitionId.toString());
    }

    private static LocalDate parseDate(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(name + " must be yyyy-MM-dd");
        }
    }
}
