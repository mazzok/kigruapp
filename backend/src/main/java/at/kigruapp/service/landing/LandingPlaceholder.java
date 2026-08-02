package at.kigruapp.service.landing;

/**
 * Eine im Editor einfügbare Kachel.
 *
 * @param token vollständiger Token inklusive Klammern, z.B. {@code {{person.firstName}}}
 * @param label deutsche Beschriftung der Kachel
 * @param group Familie des Tokens ("person", "stunden", "kochdienst") — das Frontend gruppiert danach
 */
public record LandingPlaceholder(String token, String label, String group) {}
