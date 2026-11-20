package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.dto.AdminPaymentQueryRequest;
import io.github.chenforegone.encoreticket.model.entity.Payment;
import io.github.chenforegone.encoreticket.model.vo.PaymentDetailVO.NotificationSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 支付数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {
    @Select("SELECT * FROM payment WHERE order_id=#{orderId} ORDER BY create_time DESC,id DESC FOR UPDATE")
    List<Payment> lockByOrder(@Param("orderId") long orderId);

    @org.apache.ibatis.annotations.Update("UPDATE payment SET status=#{after},version=version+1,update_time=#{now} WHERE id=#{id} AND version=#{version} AND status=#{before}")
    int transition(@Param("id") long id, @Param("version") int version,
                   @Param("before") int before, @Param("after") int after,
                   @Param("now") java.time.Instant now);

    @Select("<script>SELECT * FROM payment WHERE 1=1"
            + "<if test='q.paymentStatus != null'> AND status = #{q.paymentStatus.code}</if>"
            + "<if test='q.orderId != null'> AND order_id = #{q.orderId}</if>"
            + "<if test='q.paymentNo != null and q.paymentNo != &quot;&quot;'> AND payment_no = #{q.paymentNo}</if>"
            + "<if test='q.createdFrom != null'> AND create_time &gt;= #{q.createdFrom}</if>"
            + "<if test='q.createdTo != null'> AND create_time &lt; #{q.createdTo}</if>"
            + " ORDER BY create_time DESC, id DESC LIMIT #{offset},#{limit}</script>")
    List<Payment> selectAdminPage(@Param("q") AdminPaymentQueryRequest q,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    @Select("<script>SELECT COUNT(*) FROM payment WHERE 1=1"
            + "<if test='q.paymentStatus != null'> AND status = #{q.paymentStatus.code}</if>"
            + "<if test='q.orderId != null'> AND order_id = #{q.orderId}</if>"
            + "<if test='q.paymentNo != null and q.paymentNo != &quot;&quot;'> AND payment_no = #{q.paymentNo}</if>"
            + "<if test='q.createdFrom != null'> AND create_time &gt;= #{q.createdFrom}</if>"
            + "<if test='q.createdTo != null'> AND create_time &lt; #{q.createdTo}</if>"
            + "</script>")
    long countAdmin(@Param("q") AdminPaymentQueryRequest q);

    @Select("SELECT provider,event_id,event_type,processed_at,create_time AS created_at "
            + "FROM payment_notification WHERE payment_no=#{paymentNo} ORDER BY create_time ASC,id ASC")
    List<NotificationSummary> selectNotificationSummaries(
            @Param("paymentNo") String paymentNo);
}
