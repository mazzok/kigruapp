package at.kigruapp.dto;

import java.util.ArrayList;
import java.util.List;

public class RequiredHoursDto {
    public String semesterId;
    public int defaultMinutesPerMonth;
    public boolean allGroups = true;
    public String order = "MOST_EXPENSIVE_FIRST";
    public List<GroupRateDto> groupRates = new ArrayList<>();
    public List<TierDto> tiers = new ArrayList<>();

    public static class GroupRateDto {
        public String groupInstanceId;
        public int minutesPerMonth;
    }

    public static class TierDto {
        public int fromChild;
        public int percent;
    }
}
