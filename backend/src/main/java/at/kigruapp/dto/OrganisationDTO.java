package at.kigruapp.dto;

import at.kigruapp.entity.FieldDefinition;
import java.util.List;

public class OrganisationDTO {
    public String id;
    public String tag;
    public List<FieldDefinition> definitions;
    public List<DutyEntryDTO> entries;
}
