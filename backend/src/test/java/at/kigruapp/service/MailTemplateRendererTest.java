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
    void substitutesTokensMangledBySanitizerComments() {
        // The OWASP sanitizer wedges an empty comment between braces on save.
        String result = renderer.render(
                "<p>Hi {<!-- -->{person.firstName}}{<!-- -->{person.lastName}}</p>",
                Map.of("firstName", "Anna", "lastName", "Muster"));
        assertEquals("<p>Hi AnnaMuster</p>", result);
    }

    @Test
    void htmlEscapesSubstitutedValue() {
        String result = renderer.render("<p>{{person.notes}}</p>", Map.of("notes", "<script>alert(1)</script>"));
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>", result);
    }

    @Test
    void ersetztDutyTokens() {
        String result = renderer.render(
                "<p>Am {{duty.date}} kochst du fuer {{duty.groups}}.</p>",
                Map.of(),
                Map.of("date", "15.09.2026", "groups", "Rot, Blau"));

        assertEquals("<p>Am 15.09.2026 kochst du fuer Rot, Blau.</p>", result);
    }

    @Test
    void mischtPersonUndDutyTokens() {
        String result = renderer.render(
                "<p>Hallo {{person.firstName}}, dein Kochdienst ist am {{duty.date}}.</p>",
                Map.of("firstName", "Anna"),
                Map.of("date", "15.09.2026"));

        assertEquals("<p>Hallo Anna, dein Kochdienst ist am 15.09.2026.</p>", result);
    }

    @Test
    void unbekanntesDutyTokenWirdGeleert() {
        String result = renderer.render("<p>{{duty.unbekannt}}</p>", Map.of(), Map.of());

        assertEquals("<p></p>", result);
    }

    @Test
    void escaptDutyWerte() {
        String result = renderer.render("<p>{{duty.description}}</p>", Map.of(),
                Map.of("description", "<script>x</script>"));

        assertEquals("<p>&lt;script&gt;x&lt;/script&gt;</p>", result);
    }

    @Test
    void alteSignaturVerhaeltSichUnveraendert() {
        String result = renderer.render("<p>Hallo {{person.firstName}}</p>", Map.of("firstName", "Anna"));

        assertEquals("<p>Hallo Anna</p>", result);
    }

    private static class FakeBlockRenderer implements MailBlockRenderer {
        private final String type;
        private final String output;

        FakeBlockRenderer(String type, String output) {
            this.type = type;
            this.output = output;
        }

        @Override
        public boolean supports(String blockType) {
            return type.equals(blockType);
        }

        @Override
        public String render(com.fasterxml.jackson.databind.JsonNode config) {
            return output;
        }
    }

    @Test
    void replacesABlockMarkerWithTheMatchingRendererOutput() {
        MailTemplateRenderer renderer = new MailTemplateRenderer(java.util.List.of(new FakeBlockRenderer("cookingDuty", "<table></table>")));

        String result = renderer.render("<p>Vorher</p>{{block.cookingDuty:eyJncm91cElkIjoiZzEifQ==}}<p>Nachher</p>", java.util.Map.of());

        assertEquals("<p>Vorher</p><table></table><p>Nachher</p>", result);
    }

    @Test
    void blanksABlockMarkerWhenTheMatchingRendererReturnsNull() {
        MailTemplateRenderer renderer = new MailTemplateRenderer(java.util.List.of(new FakeBlockRenderer("cookingDuty", null)));

        String result = renderer.render("<p>{{block.cookingDuty:eyJncm91cElkIjoiZzEifQ==}}</p>", java.util.Map.of());

        assertEquals("<p></p>", result);
    }

    @Test
    void blanksABlockMarkerWhenNoRendererSupportsItsType() {
        MailTemplateRenderer renderer = new MailTemplateRenderer(java.util.List.of(new FakeBlockRenderer("cookingDuty", "<table></table>")));

        String result = renderer.render("<p>{{block.unknownType:eyJ4IjoxfQ==}}</p>", java.util.Map.of());

        assertEquals("<p></p>", result);
    }

    @Test
    void blanksABlockMarkerWhoseDecodedConfigIsNotValidJson() {
        // "bm90IGpzb24=" is valid base64url (matches the marker pattern) but decodes to
        // the plain text "not json" — readTree() throws, exercising the catch path.
        MailTemplateRenderer renderer = new MailTemplateRenderer(java.util.List.of(new FakeBlockRenderer("cookingDuty", "<table></table>")));

        String result = renderer.render("<p>{{block.cookingDuty:bm90IGpzb24=}}</p>", java.util.Map.of());

        assertEquals("<p></p>", result);
    }

    @Test
    void personTokenAndBlockMarkerBothResolveInTheSameBody() {
        MailTemplateRenderer renderer = new MailTemplateRenderer(java.util.List.of(new FakeBlockRenderer("cookingDuty", "<table></table>")));

        String result = renderer.render("<p>Hallo {{person.firstName}}</p>{{block.cookingDuty:eyJncm91cElkIjoiZzEifQ==}}", java.util.Map.of("firstName", "Anna"));

        assertEquals("<p>Hallo Anna</p><table></table>", result);
    }
}
