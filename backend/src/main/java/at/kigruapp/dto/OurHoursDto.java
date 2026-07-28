package at.kigruapp.dto;

import java.util.ArrayList;
import java.util.List;

public class OurHoursDto {
    public String familyId;
    public int familyMonthlyMinutes;
    public int monthsInSemester;
    public int sollMinutes;
    public int istMinutes;
    public List<MonthRow> months = new ArrayList<>();
    public List<Entry> entries = new ArrayList<>();

    public static class MonthRow {
        public String month;        // "YYYY-MM"
        public int sollMinutes;
        public int istMinutes;
    }

    public static class Entry {
        public String id;
        public String personId;
        public String personName;
        public String roleLabel;
        public String date;
        public int minutes;
        public String comment;
    }
}
