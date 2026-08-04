package at.kigruapp.service.landing;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.Person;
import at.kigruapp.service.PersonPropertyResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code {{person.*}}} — dieselbe Allowlist wie bei den Mail-Platzhaltern und
 * im PersonPropertyResolver; alle drei beschreiben denselben Begriff
 * "skalare Personeneigenschaft".
 */
@ApplicationScoped
public class PersonTokenProvider implements LandingTokenProvider {

    private static final Set<String> SCALAR_PERSON_FIELD_ALLOWLIST = Set.of(
            "firstName", "lastName", "email", "phone", "dateOfBirth", "gender", "entryDate", "exitDate", "notes"
    );

    @Inject
    PersonPropertyResolver personPropertyResolver;

    @Override
    public List<LandingPlaceholder> placeholders() {
        return FieldDefinition.findActive().stream()
                .filter(def -> SCALAR_PERSON_FIELD_ALLOWLIST.contains(def.fieldName))
                .map(def -> new LandingPlaceholder(
                        "{{person." + def.fieldName + "}}", labelDe(def), "person"))
                .sorted(Comparator.comparing(LandingPlaceholder::label))
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, String> values(Person person) {
        Map<String, String> properties = personPropertyResolver.resolve(List.of(person))
                .getOrDefault(person.id, Map.of());
        Map<String, String> result = new HashMap<>();
        properties.forEach((fieldName, value) -> {
            if (SCALAR_PERSON_FIELD_ALLOWLIST.contains(fieldName)) {
                result.put("{{person." + fieldName + "}}", value);
            }
        });
        return result;
    }

    private String labelDe(FieldDefinition def) {
        if (def.label == null) {
            return def.fieldName;
        }
        String de = def.label.get("de");
        return de != null ? de : def.fieldName;
    }
}
