package at.kigruapp.service;

import java.util.List;

/**
 * Einzige Quelle der Kochdienst-Platzhalter. Der Scheduler befuellt genau diese
 * Felder, der Vorlagen-Editor bietet genau diese Chips an.
 */
public final class CookingDutyTokens {

    public static final String GROUP = "KOCHDIENST";
    public static final String GROUP_LABEL = "Kochdienst";

    public record Token(String fieldName, String label) {}

    /** Reihenfolge = Reihenfolge der Chips in der Maske. */
    public static final List<Token> TOKENS = List.of(
            new Token("date", "Datum"),
            new Token("groups", "Gruppen"),
            new Token("description", "Was wird gekocht"),
            new Token("daysBefore", "Tage vorher"),
            new Token("personName", "Wer kocht"));

    public static List<String> fieldNames() {
        return TOKENS.stream().map(Token::fieldName).toList();
    }

    private CookingDutyTokens() {}
}
