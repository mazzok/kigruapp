package at.kigruapp.dto;

import java.util.List;
import java.util.Map;

/**
 * Antwort von GET /api/v1/parent-directory: alle Gruppen der eigenen Kinder im
 * laufenden Semester, je Gruppe die dort vertretenen Familien. Enthält bewusst
 * keine Personen-IDs — der Client soll damit nichts nachladen können.
 *
 * Welche Werte enthalten sind, entscheidet die globale Attribut-Auswahl:
 * abgewählte Attribute fehlen in {@code columns} und in {@code values}, statt
 * leer geliefert zu werden.
 */
public record ParentDirectoryDTO(String semesterId, List<ColumnEntry> columns, List<GroupEntry> groups) {

    public record ColumnEntry(String key, String label, String scope) {}

    public record GroupEntry(String groupInstanceId, String groupName, List<FamilyEntry> families) {}

    public record FamilyEntry(
            String familyId,
            boolean isOwnFamily,
            List<ChildEntry> children,
            List<ParentEntry> parents,
            String address) {}

    public record ChildEntry(String name, String entryDate, String exitDate) {}

    public record ParentEntry(Map<String, String> values) {}
}
