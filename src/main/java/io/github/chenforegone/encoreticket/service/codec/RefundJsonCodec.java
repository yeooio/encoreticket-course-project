package io.github.chenforegone.encoreticket.service.codec;

import io.github.chenforegone.encoreticket.model.vo.RefundVO;
import tools.jackson.databind.json.JsonMapper;

/**
 * 独立退款响应快照编码，不依赖支付 codec。
 * @author c1342
 */
public final class RefundJsonCodec {
    private final JsonMapper json;
    public RefundJsonCodec(JsonMapper json) { this.json=json; }
    public String write(RefundVO value) { return json.writeValueAsString(value); }
    public RefundVO read(String value) {
        RefundVO result=json.readValue(value,RefundVO.class);
        if(result==null||result.refundId()==null||result.status()==null||result.amount()==null) throw new IllegalStateException("退款快照不完整");
        return result;
    }
}
