package at.kigruapp.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;
import org.bson.types.ObjectId;

import java.io.IOException;

@Singleton
public class ObjectIdModule implements ObjectMapperCustomizer {

    @Override
    public void customize(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        SimpleModule module = new SimpleModule();

        module.addSerializer(ObjectId.class, new StdSerializer<>(ObjectId.class) {
            @Override
            public void serialize(ObjectId value, JsonGenerator gen, SerializerProvider provider)
                    throws IOException {
                gen.writeString(value.toHexString());
            }
        });

        module.addDeserializer(ObjectId.class, new StdDeserializer<>(ObjectId.class) {
            @Override
            public ObjectId deserialize(JsonParser p, DeserializationContext ctx)
                    throws IOException {
                String value = p.getValueAsString();
                if (value == null || value.isEmpty()) {
                    return null;
                }
                return new ObjectId(value);
            }
        });

        mapper.registerModule(module);
    }
}
