package at.kigruapp.dto;

import java.util.Map;

public class FieldInstanceDTO {
    public String id;
    public String definitionId;
    public String fieldName;
    public Map<String, String> label;
    public String description;
    public Map<String, Object> jsonSchema;
    public boolean required;
    public String keycloakMapping;
    public Object value;
    public boolean definitionOutdated;
}
