package io.github.chenforegone.encoreticket.service.codec;

import io.github.chenforegone.encoreticket.model.vo.PaymentVO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用应用的 Jackson 3 配置保存支付响应快照。
 * @author c1342
 */
@Component
public final class IdempotencyJsonCodec {
    private final JsonMapper json;
    public IdempotencyJsonCodec(JsonMapper json) { this.json = json; }
    public String write(PaymentVO payment) { return json.writeValueAsString(payment); }
    public PaymentVO read(String value) { return json.readValue(value, PaymentVO.class); }
}
