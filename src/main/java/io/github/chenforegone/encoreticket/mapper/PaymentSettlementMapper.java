package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.OrderItem;
import io.github.chenforegone.encoreticket.model.entity.Payment;
import io.github.chenforegone.encoreticket.model.entity.PaymentAudit;
import io.github.chenforegone.encoreticket.model.entity.PaymentNotification;
import io.github.chenforegone.encoreticket.model.entity.Refund;
import io.github.chenforegone.encoreticket.model.entity.ScreeningSeat;
import io.github.chenforegone.encoreticket.model.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 支付结算跨表写入及关键行加锁的数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface PaymentSettlementMapper extends BaseMapper<Payment> {
    @org.apache.ibatis.annotations.Select("SELECT o.create_time,o.expires_at,s.sale_end_time FROM orders o JOIN screening s ON s.id=o.screening_id WHERE o.id=#{orderId}")
    DeadlineRow selectDeadline(@Param("orderId") long orderId);

    @org.apache.ibatis.annotations.Select("SELECT attempt_id FROM payment WHERE id=#{paymentId}")
    String selectAttempt(@Param("paymentId") long paymentId);

    @org.apache.ibatis.annotations.Update("UPDATE payment SET attempt_id=#{attempt} WHERE id=#{paymentId}")
    int updateAttempt(@Param("paymentId") long paymentId, @Param("attempt") String attempt);

    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM payment_audit WHERE payment_id=#{paymentId} AND operation='ATTEMPT_STARTED' AND remark=#{attempt}")
    int countKnownAttempt(@Param("paymentId") long paymentId, @Param("attempt") String attempt);

    @org.apache.ibatis.annotations.Update("UPDATE orders SET status=#{after},version=version+1,update_time=#{now} WHERE id=#{id} AND version=#{version} AND status=#{before}")
    int updateOrderState(@Param("id") long id, @Param("version") int version,
                         @Param("before") int before, @Param("after") int after,
                         @Param("now") Instant now);

    @org.apache.ibatis.annotations.Select("SELECT id FROM screening_seat WHERE order_id=#{orderId} ORDER BY id")
    List<Long> selectSeatIds(@Param("orderId") long orderId);

    @org.apache.ibatis.annotations.Select("SELECT id FROM screening_seat WHERE id=#{seatId} FOR UPDATE")
    Long lockSeat(@Param("seatId") long seatId);

    @org.apache.ibatis.annotations.Select("SELECT screening_seat_id FROM order_item WHERE order_id=#{orderId} ORDER BY screening_seat_id")
    List<Long> selectItemSeatIds(@Param("orderId") long orderId);

    @org.apache.ibatis.annotations.Update("UPDATE screening_seat SET status=#{status},order_id=#{newOrderId},update_time=#{now} WHERE id=#{seatId} AND order_id=#{orderId} AND status=1")
    int updateSeatState(@Param("seatId") long seatId, @Param("orderId") long orderId,
                        @Param("status") int status, @Param("newOrderId") Long newOrderId,
                        @Param("now") Instant now);

    @org.apache.ibatis.annotations.Select("SELECT id FROM order_item WHERE order_id=#{orderId} ORDER BY id")
    List<Long> selectOrderItemIds(@Param("orderId") long orderId);

    int insertTicket(@Param("ticket") Ticket ticket);

    @org.apache.ibatis.annotations.Insert("INSERT INTO payment_notification(id,provider,event_id,payment_no,event_type,payload_hash,processed_at,create_time,attempt_id) VALUES(#{id},#{provider},#{eventId},#{paymentNo},#{eventType},#{payloadHash},#{processedAt},#{createTime},#{attemptId}) ON DUPLICATE KEY UPDATE id=id")
    int insertNotificationIfAbsent(@Param("id") long id, @Param("provider") String provider,
                                   @Param("eventId") String eventId, @Param("paymentNo") String paymentNo,
                                   @Param("eventType") String eventType, @Param("payloadHash") byte[] payloadHash,
                                   @Param("processedAt") Instant processedAt, @Param("createTime") Instant createTime,
                                   @Param("attemptId") String attemptId);

    @org.apache.ibatis.annotations.Select("SELECT payload_hash FROM payment_notification WHERE provider=#{provider} AND event_id=#{eventId} FOR UPDATE")
    @org.apache.ibatis.annotations.ConstructorArgs(
            @org.apache.ibatis.annotations.Arg(column = "payload_hash", javaType = byte[].class))
    NotificationHashRow selectNotificationHashForUpdate(@Param("provider") String provider, @Param("eventId") String eventId);

    @org.apache.ibatis.annotations.Select("SELECT payload_hash FROM payment_notification WHERE provider=#{provider} AND event_id=#{eventId}")
    @org.apache.ibatis.annotations.ConstructorArgs(
            @org.apache.ibatis.annotations.Arg(column = "payload_hash", javaType = byte[].class))
    NotificationHashRow selectNotificationHash(@Param("provider") String provider, @Param("eventId") String eventId);

    /**
     * 通知摘要的单行投影，避免数组返回类型被识别为多结果集合。
     *
     * @author c1342
     */
    record NotificationHashRow(byte[] payloadHash) { }

    @org.apache.ibatis.annotations.Insert("INSERT INTO refund_attempt(refund_id,attempt_no,status,submitted,create_time,update_time) VALUES(#{refundId},1,'PENDING',FALSE,#{now},#{now})")
    int insertRefundAttempt(@Param("refundId") long refundId, @Param("now") Instant now);

    @org.apache.ibatis.annotations.Insert("INSERT INTO refund_audit(id,refund_id,operation,operator_id,create_time) VALUES(#{id},#{refundId},'LATE_PAYMENT_ACCEPTED',NULL,#{now})")
    int insertRefundAudit(@Param("id") long id, @Param("refundId") long refundId, @Param("now") Instant now);

    record DeadlineRow(java.time.LocalDateTime createTime, java.time.LocalDateTime expiresAt,
                       java.time.LocalDateTime saleEndTime) { }
}
