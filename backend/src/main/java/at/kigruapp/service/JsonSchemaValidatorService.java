package at.kigruapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class JsonSchemaValidatorService {

    @Inject
    ObjectMapper objectMapper;

    public void validateSchema(Map<String, Object> schemaMap) {
        JsonNode schemaNode = objectMapper.valueToTree(schemaMap);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        factory.getSchema(schemaNode);
    }

    public void validate(Map<String, Object> schemaMap, Object value) {
        JsonNode schemaNode = objectMapper.valueToTree(schemaMap);
        JsonNode valueNode = objectMapper.valueToTree(value);

        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema schema = factory.getSchema(schemaNode);
        Set<ValidationMessage> errors = schema.validate(valueNode);

        if (!errors.isEmpty()) {
            String message = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));
            throw new ValidationException("JSON Schema validation failed: " + message);
        }
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }
}
