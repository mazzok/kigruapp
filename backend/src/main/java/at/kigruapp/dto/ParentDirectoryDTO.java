package at.kigruapp.dto;

import java.util.List;

/**
 * Antwort von GET /api/v1/parent-directory: alle Gruppen der eigenen Kinder im
 * laufenden Semester, je Gruppe die dort vertretenen Familien. Enthält bewusst
 * keine Personen-IDs — der Client soll damit nichts nachladen können.
 */
public record ParentDirectoryDTO(String semesterId, List<GroupEntry> groups) {

    public record GroupEntry(String groupInstanceId, String groupName, List<FamilyEntry> families) {}

    public record FamilyEntry(
            String familyId,
            boolean isOwnFamily,
            List<String> children,
            List<ParentEntry> parents,
            String address) {}

    public record ParentEntry(String firstName, String lastName, String email, String phone) {}
}
