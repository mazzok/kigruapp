package at.kigruapp.dto;

import at.kigruapp.entity.Currency;

public record KostenDefinitionDTO(String id, String label, boolean active, Currency currency) {}
