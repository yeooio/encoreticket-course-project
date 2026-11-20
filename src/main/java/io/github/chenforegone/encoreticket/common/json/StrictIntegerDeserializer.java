package io.github.chenforegone.encoreticket.common.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * 仅接受 JSON 整数，避免小数截断或字符串隐式转换；取值范围由 DTO 校验。
 *
 * @author c1342
 */
public class StrictIntegerDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return context.reportInputMismatch(Integer.class, "必须提供 JSON 整数");
        }
        return parser.getIntValue();
    }
}
