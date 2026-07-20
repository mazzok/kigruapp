package at.kigruapp.dto;

import at.kigruapp.entity.Currency;

import java.math.BigDecimal;

public record KostenValueDTO(String definitionId, String label, Currency currency, BigDecimal amount) {}
