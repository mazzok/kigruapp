package at.kigruapp.dto;

import java.util.List;

/** Katalog der waehlbaren Attribute der Eltern-Uebersicht (Admin-Ansicht). */
public record ParentDirectoryAttributeDTO(
        String key, String label, String scope, boolean selected, boolean locked) {

    public record Catalog(List<ParentDirectoryAttributeDTO> attributes) {}

    public record VisibleAttributesRequest(List<String> visibleAttributes) {}
}
