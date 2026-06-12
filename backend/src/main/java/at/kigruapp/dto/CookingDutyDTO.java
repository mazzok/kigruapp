package at.kigruapp.dto;

import java.util.List;
import java.util.Map;

public class CookingDutyDTO {
    public String id;
    public String personId;
    public String familyId;
    public String personName;
    public String date;
    public List<String> groups;
    public String description;
    public Map<String, Boolean> foodProperties;
}
