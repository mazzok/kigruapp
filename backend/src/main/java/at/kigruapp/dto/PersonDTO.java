package at.kigruapp.dto;

import java.util.List;

public class PersonDTO {
    public String id;
    public String familyId;
    public String keycloakUserId;
    public List<FieldInstanceDTO> basicProperties;
    public List<FieldInstanceDTO> roles;
    public List<FieldInstanceDTO> schedules;
    public List<FieldInstanceDTO> duties;
    public List<FieldInstanceDTO> finance;
    public List<FieldInstanceDTO> customProperties;
    public List<FieldInstanceDTO> assignedDuty;
    public List<FieldInstanceDTO> assignedRole;
    public String createdAt;
    public String updatedAt;
}
