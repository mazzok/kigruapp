package at.kigruapp.resource;

import at.kigruapp.entity.LandingPage;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.time.Instant;

/**
 * Inhalt der Startseite. GET ist für alle angemeldeten Nutzer freigeschaltet
 * (siehe SecurityFilter), PUT bleibt durch das Default-Deny admin-only.
 */
@Path("/api/v1/landing-page")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LandingPageResource {

    /**
     * Gegenüber der Mail-Policy bewusst gelockert: die Startseite wird im
     * Browser gerendert, nicht in einem Mail-Client, daher sind Überschriften,
     * Tabellen und Bilder erlaubt. {@code script} und {@code iframe} fehlen in
     * der Elementliste und werden dadurch entfernt.
     *
     * {@code allowUrlProtocols("http", "https")} greift auch für
     * {@code img/src} und wirft damit {@code data:}-URIs weg — sonst könnte ein
     * eingebettetes Bild das Dokument um Hunderte Kilobyte aufblähen.
     */
    static final PolicyFactory WEB_HTML_POLICY = new HtmlPolicyBuilder()
            .allowElements(
                    "p", "br", "hr", "b", "strong", "i", "em", "u", "s",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "ol", "ul", "li", "blockquote", "pre", "code",
                    "a", "span", "div", "img",
                    "table", "thead", "tbody", "tr", "th", "td")
            .allowAttributes("href", "target").onElements("a")
            .allowAttributes("src", "alt", "width", "height").onElements("img")
            .allowAttributes("colspan", "rowspan").onElements("td", "th")
            .allowAttributes("style", "class").globally()
            .allowUrlProtocols("http", "https")
            .toFactory();

    /**
     * Sanitisieren, dann die Leerkommentare entfernen, die der Sanitizer
     * zwischen die Klammern schiebt (z.&nbsp;B. {@code {<!-- -->{}). Ohne diesen
     * Schritt zerfallen die gespeicherten {@code {{…}}}-Tokens und weder die
     * Ersetzung noch die Token-zu-Pill-Umwandlung im Editor findet sie wieder.
     */
    static String sanitizeBody(String bodyHtml) {
        return WEB_HTML_POLICY.sanitize(bodyHtml).replaceAll("<!--\\s*-->", "");
    }

    public record LandingPageDto(String bodyHtml, Instant updatedAt) {}

    @GET
    public LandingPageDto get() {
        LandingPage page = LandingPage.findSingleton();
        if (page == null) {
            return new LandingPageDto("", null);
        }
        return new LandingPageDto(page.bodyHtml == null ? "" : page.bodyHtml, page.updatedAt);
    }

    @PUT
    public LandingPageDto save(LandingPageDto request) {
        if (request == null || request.bodyHtml() == null) {
            throw new BadRequestException("bodyHtml is required");
        }
        LandingPage page = LandingPage.findSingleton();
        boolean isNew = page == null;
        if (isNew) {
            page = new LandingPage();
        }
        page.bodyHtml = sanitizeBody(request.bodyHtml());
        page.updatedAt = Instant.now();
        if (isNew) {
            page.persist();
        } else {
            page.update();
        }
        return new LandingPageDto(page.bodyHtml, page.updatedAt);
    }
}
