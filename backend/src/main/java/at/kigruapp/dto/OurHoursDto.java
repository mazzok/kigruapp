package at.kigruapp.dto;

import java.util.ArrayList;
import java.util.List;

public class OurHoursDto {
    public String familyId;
    /** Monatswert bei voller Anwesenheit aller Kinder; 0, wenn es keinen solchen Monat gibt. */
    public int familyMonthlyMinutes;
    public int monthsInSemester;
    public int sollMinutes;
    public int istMinutes;
    public boolean allGroups = true;
    public List<ChildSoll> children = new ArrayList<>();
    public List<MonthRow> months = new ArrayList<>();
    public List<Entry> entries = new ArrayList<>();

    public static class ChildSoll {
        public String childId;
        public String name;
        public String groupLabel;
        public String groupColor;
        public int baseMinutesPerMonth;
        public String entryDate;
        public String exitDate;
        public int sollMinutes;
    }

    public static class MonthRow {
        public String month;        // "YYYY-MM"
        public int sollMinutes;
        public int istMinutes;
        public List<ChildShare> children = new ArrayList<>();
    }

    public static class ChildShare {
        public String childId;
        public int minutes;
        public int fractionPercent;
        public int discountPercent;
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
