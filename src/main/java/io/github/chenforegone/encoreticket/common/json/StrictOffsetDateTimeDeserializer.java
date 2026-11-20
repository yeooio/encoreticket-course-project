package io.github.chenforegone.encoreticket.common.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import java.time.DateTimeException;
import java.time.OffsetDateTime;

/**
 * 业务时间只接受带偏移的 RFC 3339 字符串，不接受数字时间戳。
 *
 * @author c1342
 */
public class StrictOffsetDateTimeDeserializer extends ValueDeserializer<OffsetDateTime> {
    @Override
    public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return context.reportInputMismatch(OffsetDateTime.class, "必须提供带偏移的时间字符串");
        }
        try {
            return OffsetDateTime.parse(parser.getString());
        } catch (DateTimeException exception) {
            return context.reportInputMismatch(OffsetDateTime.class, "必须提供带偏移的时间字符串");
        }
    }
}

