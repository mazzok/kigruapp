package at.kigruapp.service;

import at.kigruapp.dto.CookingDutyDTO;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class CookingDutyMailBlockRenderer implements MailBlockRenderer {

    private static final String TIMEZONE = "Europe/Vienna";

    @Inject
    CookingDutyQueryService queryService;

    @Inject
    GroupCatalogService groupCatalogService;

    @Override
    public boolean supports(String blockType) {
        return "cookingDuty".equals(blockType);
    }

    @Override
    public String render(JsonNode config) {
        String groupId = config.path("groupId").asText(null);
        String periodUnit = config.path("periodUnit").asText("week");
        int periodAmount = config.path("periodAmount").asInt(1);

        if (groupId == null) {
            return "<p>Gruppe nicht mehr vorhanden.</p>";
        }

        ObjectId groupObjectId;
        try {
            groupObjectId = new ObjectId(groupId);
        } catch (IllegalArgumentException e) {
            return "<p>Gruppe nicht mehr vorhanden.</p>";
        }
        if (!groupCatalogService.byId().containsKey(groupObjectId)) {
            return "<p>Gruppe nicht mehr vorhanden.</p>";
        }

        LocalDate today = LocalDate.now(ZoneId.of(TIMEZONE));
        LocalDate end = "month".equals(periodUnit) ? today.plusMonths(periodAmount) : today.plusWeeks(periodAmount);
        String todayStr = today.toString();
        String endStr = end.toString();

        List<CookingDutyDTO> entries = queryService.query(
                date -> date.compareTo(todayStr) >= 0 && date.compareTo(endStr) < 0,
                Set.of(groupId));

        if (entries.isEmpty()) {
            return "<p>Keine Kochdienst-Einträge im gewählten Zeitraum.</p>";
        }

        StringBuilder html = new StringBuilder();
        html.append("<table style=\"border-collapse:collapse;width:100%\">");
        html.append("<tr>")
                .append("<th style=\"border:1px solid #ccc;padding:4px;text-align:left\">Datum</th>")
                .append("<th style=\"border:1px solid #ccc;padding:4px;text-align:left\">Person</th>")
                .append("<th style=\"border:1px solid #ccc;padding:4px;text-align:left\">Beschreibung</th>")
                .append("</tr>");
        for (CookingDutyDTO entry : entries) {
            html.append("<tr>")
                    .append("<td style=\"border:1px solid #ccc;padding:4px\">").append(escapeHtml(entry.date)).append("</td>")
                    .append("<td style=\"border:1px solid #ccc;padding:4px\">").append(escapeHtml(entry.personName)).append("</td>")
                    .append("<td style=\"border:1px solid #ccc;padding:4px\">").append(escapeHtml(entry.description != null ? entry.description : "")).append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");
        return html.toString();
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
