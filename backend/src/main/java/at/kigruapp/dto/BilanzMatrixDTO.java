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
            boolean exitMarker) {}
}
