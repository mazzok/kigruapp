package at.kigruapp.dto;

import java.math.BigDecimal;
import java.util.List;

public record BilanzMatrixDTO(int year, String currentYearMonth, List<ChildRow> children) {
    public record ChildRow(String personId, String name, List<MonthCell> months, BigDecimal total) {}

    public record MonthCell(
            int month,
            BigDecimal amount,
            String currencySymbol,
            boolean mixedCurrency,
            boolean future,
            boolean editable,
            boolean active,
            boolean entryMarker,
            boolean exitMarker,
            String reason,        // "FUTURE" | "NO_PLACE" | null
            String aliquotMode,   // "NONE" | "WHOLE_MONTH" | "PER_DAY" | null
            String entryDate,     // ISO date if entry falls in this month, else null
            String exitDate,      // ISO date if exit falls in this month, else null
            List<LineBreakdown> lines) {}

    public record LineBreakdown(
            String label,
            String currencySymbol,
            BigDecimal baseAmount,
            int discountPercent,
            int discountOrdinal,
            int presentDays,
            int daysInMonth,
            boolean fullMonth,
            boolean overridden,
            BigDecimal effectiveAmount) {}
}
