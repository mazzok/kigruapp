package at.kigruapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.All;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {{person.&lt;fieldName&gt;}} tokens and {{block.&lt;type&gt;:&lt;config&gt;}}
 * markers in a template body. Token substitution is pure (no I/O); block
 * rendering delegates to whichever registered MailBlockRenderer supports the
 * marker's type, which may do I/O (e.g. a Mongo lookup).
 */
@ApplicationScoped
public class MailTemplateRenderer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{(person|duty)\\.([a-zA-Z0-9_]+)}}");
    private static final Pattern BLOCK_PATTERN = Pattern.compile("\\{\\{block\\.([a-zA-Z0-9_]+):([A-Za-z0-9_\\-=]+)}}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * The OWASP HTML sanitizer applied on save neutralizes template-injection
     * sequences by wedging an empty comment between braces, e.g. {@code {<!-- -->{}.
     * Stripping empty comments restores {@code {{...}} so tokens match — and it
     * repairs bodies already stored in the mangled form without a migration.
     */
    private static final Pattern EMPTY_COMMENT = Pattern.compile("<!--\\s*-->");

    @Inject
    @All
    List<MailBlockRenderer> blockRenderers = List.of();

    public MailTemplateRenderer() {
    }

    /** Test-only: bypasses CDI so unit tests can supply renderers directly. */
    MailTemplateRenderer(List<MailBlockRenderer> blockRenderers) {
        this.blockRenderers = blockRenderers;
    }

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
        String withPlaceholders = renderPlaceholders(normalized, personProperties, dutyProperties);
        return renderBlocks(withPlaceholders);
    }

    private String renderPlaceholders(String bodyHtml, Map<String, String> personProperties,
                                      Map<String, String> dutyProperties) {
        Matcher matcher = TOKEN_PATTERN.matcher(bodyHtml);
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

    private String renderBlocks(String bodyHtml) {
        Matcher matcher = BLOCK_PATTERN.matcher(bodyHtml);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = renderBlock(matcher.group(1), matcher.group(2));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String renderBlock(String blockType, String encodedConfig) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encodedConfig);
            JsonNode config = OBJECT_MAPPER.readTree(decoded);
            for (MailBlockRenderer renderer : blockRenderers) {
                if (renderer.supports(blockType)) {
                    String rendered = renderer.render(config);
                    return rendered != null ? rendered : "";
                }
            }
            return "";
        } catch (Exception e) {
            Log.warnf(e, "Failed to render mail block of type '%s'", blockType);
            return "";
        }
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
