package at.kigruapp.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {{person.&lt;fieldName&gt;}} tokens in a template body with a
 * recipient's resolved property values. Pure — no I/O.
 */
@ApplicationScoped
public class MailTemplateRenderer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{(person|duty)\\.([a-zA-Z0-9_]+)}}");

    /**
     * The OWASP HTML sanitizer applied on save neutralizes template-injection
     * sequences by wedging an empty comment between braces, e.g. {@code {<!-- -->{}.
     * Stripping empty comments restores {@code {{...}} so tokens match — and it
     * repairs bodies already stored in the mangled form without a migration.
     */
    private static final Pattern EMPTY_COMMENT = Pattern.compile("<!--\\s*-->");

    public String render(String bodyHtml, Map<String, String> properties) {
        return render(bodyHtml, properties, Map.of());
    }

    /**
     * Wie {@link #render(String, Map)}, löst zusätzlich {@code {{duty.<feld>}}}
     * aus der zweiten Map auf. Beide Namespaces teilen sich Escaping und die
     * Leerkommentar-Reparatur.
     */
    public String render(String bodyHtml, Map<String, String> personProperties,
                         Map<String, String> dutyProperties) {
        if (bodyHtml == null) {
            return null;
        }
        String normalized = EMPTY_COMMENT.matcher(bodyHtml).replaceAll("");
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String namespace = matcher.group(1);
            String fieldName = matcher.group(2);
            Map<String, String> source = "duty".equals(namespace) ? dutyProperties : personProperties;
            String value = source != null ? source.get(fieldName) : null;
            String replacement = value != null ? escapeHtml(value) : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
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
