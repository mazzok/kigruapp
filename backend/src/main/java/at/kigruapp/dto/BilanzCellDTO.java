package at.kigruapp.dto;

import java.math.BigDecimal;
import java.util.List;

public record BilanzCellDTO(List<Line> lines, BigDecimal sum, boolean mixedCurrency) {
    public record Line(
            String personId,
            String childName,
            String definitionId,
            String label,
            String currencySymbol,
            BigDecimal defaultAmount,
            BigDecimal effectiveAmount) {}
}
