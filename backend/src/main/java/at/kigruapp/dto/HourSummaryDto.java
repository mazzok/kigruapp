package at.kigruapp.dto;

import java.util.List;

/** Admin-Übersicht: Summe und Einzeleinträge eines Elternteils in einem Semester. */
public class HourSummaryDto {
    public String personId;
    public String name;
    public int totalMinutes;
    public List<HourEntryDto> entries;
}
