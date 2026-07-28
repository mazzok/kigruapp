package at.kigruapp.dto;

import java.util.ArrayList;
import java.util.List;

public class FamilyHoursSummaryDto {
    public String familyId;
    public String familyName;
    public int childCount;
    public int familyMonthlyMinutes;
    public int monthsInSemester;
    public int sollMinutes;
    public int istMinutes;
    public List<HourSummaryDto> members = new ArrayList<>();
}
