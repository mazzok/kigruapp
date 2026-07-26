package at.kigruapp.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MailTemplateRendererTest {

    private final MailTemplateRenderer renderer = new MailTemplateRenderer();

    @Test
    void substitutesKnownToken() {
        String result = renderer.render("<p>Hallo {{person.firstName}}</p>", Map.of("firstName", "Peter"));
        assertEquals("<p>Hallo Peter</p>", result);
    }

    @Test
    void blanksUnresolvedToken() {
        String result = renderer.render("<p>Hallo {{person.firstName}}</p>", Map.of());
        assertEquals("<p>Hallo </p>", result);
    }

    @Test
    void substitutesRepeatedAndMultipleTokens() {
        String result = renderer.render(
                "<p>{{person.firstName}} {{person.lastName}}, again: {{person.firstName}}</p>",
                Map.of("firstName", "Peter", "lastName", "Muster"));
        assertEquals("<p>Peter Muster, again: Peter</p>", result);
    }

    @Test
    void htmlEscapesSubstitutedValue() {
        String result = renderer.render("<p>{{person.notes}}</p>", Map.of("notes", "<script>alert(1)</script>"));
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>", result);
    }
}
