package at.kigruapp.service.landing;

import at.kigruapp.entity.Person;

import java.util.List;
import java.util.Map;

/**
 * Liefert eine Familie von Startseiten-Platzhaltern. Eine neue Familie ist eine
 * weitere {@code @ApplicationScoped}-Implementierung — die Resource iteriert
 * nur über alle Beans und muss dafür nicht angefasst werden.
 */
public interface LandingTokenProvider {

    /** Kacheln, die der Editor anbietet. */
    List<LandingPlaceholder> placeholders();

    /**
     * Werte für den angegebenen Nutzer, Schlüssel sind vollständige Tokens.
     * Fehlen die zugrundeliegenden Daten (kein Semester, kein Dienst), gibt die
     * Implementierung einen leeren Wert zurück statt zu werfen — die Startseite
     * darf an einem fehlenden Datensatz nicht scheitern.
     */
    Map<String, String> values(Person person);
}
