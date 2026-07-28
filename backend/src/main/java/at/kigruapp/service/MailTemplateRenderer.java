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

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{person\\.([a-zA-Z0-9_]+)}}");

    /**
     * The OWASP HTML sanitizer applied on save neutralizes template-injection
     * sequences by wedging an empty comment between braces, e.g. {@code {<!-- -->{}.
     * Stripping empty comments restores {@code {{...}} so tokens match — and it
     * repairs bodies already stored in the mangled form without a migration.
     */
    private static final Pattern EMPTY_COMMENT = Pattern.compile("<!--\\s*-->");

    public String render(String bodyHtml, Map<String, String> properties) {
        if (bodyHtml == null) {
            return null;
        }
        String normalized = EMPTY_COMMENT.matcher(bodyHtml).replaceAll("");
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String fieldName = matcher.group(1);
            String value = properties != null ? properties.get(fieldName) : null;
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
