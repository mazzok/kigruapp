package at.kigruapp.dto;

import java.util.ArrayList;
import java.util.List;

public class KostenDiscountDto {
    public String semesterId;
    public boolean applyToAll;
    public String order;
    public List<TierDto> tiers = new ArrayList<>();
    public java.util.List<String> eligibleDefinitionIds = new java.util.ArrayList<>();

    public static class TierDto {
        public int fromChild;
        public int percent;
    }
}
