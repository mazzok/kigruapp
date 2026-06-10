package at.kigruapp.dto;

import java.util.List;

public class PersonSectionDTO {
    public String section;
    public List<FieldInstanceDTO> fields;

    public PersonSectionDTO() {}

    public PersonSectionDTO(String section, List<FieldInstanceDTO> fields) {
        this.section = section;
        this.fields = fields;
    }
}
