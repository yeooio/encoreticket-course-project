package io.github.chenforegone.encoreticket.common.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 仅接受 JSON 字符串，防止 ID 和金额的数值强制转换。
 *
 * @author c1342
 */
public class StrictStringDeserializer extends ValueDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return context.reportInputMismatch(String.class, "必须提供 JSON 字符串");
        }
        return parser.getString();
    }
}

