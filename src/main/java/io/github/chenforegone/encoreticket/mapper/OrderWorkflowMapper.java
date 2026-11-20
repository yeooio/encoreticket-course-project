package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.chenforegone.encoreticket.model.vo.OrderDetailVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * 订单跨表持久化；写操作由服务持有事务。
 * @author c1342
 */
@Repository
public class OrderWorkflowMapper {
    private final JdbcTemplate jdbc;
    public OrderWorkflowMapper(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public int cleanup(Instant now) {
        return jdbc.update("DELETE FROM api_idempotency WHERE operation='order-create:v1' AND expires_at<=? ORDER BY expires_at LIMIT 500",utc(now));
    }
    public void lockUser(long user) {
        if(jdbc.query("SELECT id FROM sys_user WHERE id=? FOR UPDATE",(r,n)->r.getLong(1),user).isEmpty())
            throw new io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException();
    }
    public void lockPurchase(long screening) {
        var halls=jdbc.query("SELECT hall_id FROM screening WHERE id=?",(r,n)->r.getLong(1),screening);
        if(halls.isEmpty()) throw new io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException();
        if(jdbc.query("SELECT id FROM hall WHERE id=? FOR UPDATE",(r,n)->r.getLong(1),halls.getFirst()).isEmpty())
            throw new IllegalStateException("场次影厅关联缺失");
        var current=jdbc.queryForList("SELECT hall_id FROM screening WHERE id=? FOR UPDATE",screening);
        if(current.isEmpty()||((Number)current.getFirst().get("hall_id")).longValue()!=halls.getFirst()) throw conflict();
    }
    public void requirePurchasable(long screening,List<Long> ids,Instant now) {
        var local=LocalDateTime.ofInstant(now,ZoneId.of("Asia/Shanghai"));
        if(jdbc.queryForObject("SELECT COUNT(*) FROM screening s JOIN movie m ON m.id=s.movie_id JOIN hall h ON h.id=s.hall_id WHERE s.id=? AND s.status=1 AND m.status=0 AND h.status=0 AND s.sale_start_time<=? AND s.sale_end_time>?",Long.class,screening,local,local)!=1) throw conflict();
        for(long id:ids) {
            var ownership=jdbc.query("SELECT screening_id FROM screening_seat WHERE id=?",(r,n)->r.getLong(1),id);
            if(ownership.isEmpty()) throw new io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException();
            if(ownership.getFirst()!=screening) throw new io.github.chenforegone.encoreticket.common.exception.BusinessRuleException(io.github.chenforegone.encoreticket.common.error.ErrorCode.BUSINESS_RULE_VIOLATION);
            var rows=jdbc.queryForList("SELECT ss.screening_id,ss.status,s.status AS physical_status,s.hall_id,sc.hall_id AS expected_hall FROM screening_seat ss JOIN seat s ON s.id=ss.seat_id JOIN screening sc ON sc.id=ss.screening_id WHERE ss.id=? FOR UPDATE",id);
            if(rows.isEmpty()) throw new io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException();
            var row=rows.getFirst();
            if(((Number)row.get("screening_id")).longValue()!=screening) throw new io.github.chenforegone.encoreticket.common.exception.BusinessRuleException(io.github.chenforegone.encoreticket.common.error.ErrorCode.BUSINESS_RULE_VIOLATION);
            if(((Number)row.get("status")).intValue()!=0||((Number)row.get("physical_status")).intValue()!=0||!row.get("hall_id").equals(row.get("expected_hall"))) throw conflict();
        }
    }
    private static io.github.chenforegone.encoreticket.common.exception.ConflictException conflict() {
        return new io.github.chenforegone.encoreticket.common.exception.ConflictException(io.github.chenforegone.encoreticket.common.error.ErrorCode.RESOURCE_CONFLICT);
    }
    public List<Long> pendingOrders(long user,long screening) {
        return jdbc.query("SELECT id FROM orders WHERE user_id=? AND screening_id=? AND status=0 ORDER BY id FOR UPDATE",(r,n)->r.getLong(1),user,screening);
    }
    public boolean pendingPayment(long order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id=? AND status=0",Long.class,order)>0;
    }
    public void requireNoLiveOrder(long user,long screening) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND screening_id=? AND status=0",Long.class,user,screening)>0)
            throw new io.github.chenforegone.encoreticket.common.exception.ConflictException(io.github.chenforegone.encoreticket.common.error.ErrorCode.RESOURCE_CONFLICT);
    }
    public long insert(long user, long screening, List<Long> seats, Instant now) {
        BigDecimal price=jdbc.queryForObject("SELECT price FROM screening WHERE id=?",BigDecimal.class,screening);
        long id=IdWorker.getId();
        jdbc.update("INSERT INTO orders(id,order_no,user_id,screening_id,total_amount,expires_at,status,version,create_time,update_time) VALUES(?,?,?,?,?,?,0,0,?,?)",
                id,"O"+id,user,screening,price.multiply(BigDecimal.valueOf(seats.size())),utc(now.plusSeconds(900)),utc(now),utc(now));
        for(long seat:seats) {
            jdbc.update("INSERT INTO order_item(id,order_id,screening_seat_id,unit_price,create_time,update_time) VALUES(?,?,?,?,?,?)",IdWorker.getId(),id,seat,price,utc(now),utc(now));
            if(jdbc.update("UPDATE screening_seat SET status=1,order_id=?,update_time=? WHERE id=? AND screening_id=? AND status=0",id,utc(now),seat,screening)!=1) throw conflict();
        }
        return id;
    }
    public io.github.chenforegone.encoreticket.common.response.PageResponse<io.github.chenforegone.encoreticket.model.vo.OrderSummaryVO> page(long user,int page,int size,String status,Long screening,String no,Instant now) {
        StringBuilder where=new StringBuilder(" WHERE o.user_id=?");
        List<Object> args=new ArrayList<>(); args.add(user);
        if(screening!=null) { where.append(" AND o.screening_id=?"); args.add(screening); }
        if(no!=null) { where.append(" AND o.order_no=?"); args.add(no); }
        if(status!=null) {
            String effective="LEAST(o.expires_at,DATE_ADD(o.create_time,INTERVAL 15 MINUTE),(SELECT DATE_SUB(s.sale_end_time,INTERVAL 8 HOUR) FROM screening s WHERE s.id=o.screening_id))";
            if(status.equals("PENDING_PAYMENT")) { where.append(" AND o.status=0 AND "+effective+">?"); args.add(utc(now)); }
            else if(status.equals("EXPIRED")) { where.append(" AND (o.status=3 OR (o.status=0 AND "+effective+"<=?))"); args.add(utc(now)); }
            else { where.append(" AND o.status=?"); args.add(io.github.chenforegone.encoreticket.model.enums.OrderStatus.valueOf(status).getCode()); }
        }
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM orders o"+where,Long.class,args.toArray());
        args.add(size); args.add((long)(page-1)*size);
        var ids=jdbc.query("SELECT o.id FROM orders o"+where+" ORDER BY o.create_time DESC,o.id DESC LIMIT ? OFFSET ?",(r,n)->r.getLong(1),args.toArray());
        return new io.github.chenforegone.encoreticket.common.response.PageResponse<>(ids.stream().map(id->io.github.chenforegone.encoreticket.model.vo.OrderSummaryVO.from(detail(id,user,now))).toList(),page,size,total);
    }
    public int lockOwned(long id,long user) {
        var rows=jdbc.queryForList("SELECT user_id,status FROM orders WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()||((Number)rows.getFirst().get("user_id")).longValue()!=user)
            throw new io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException();
        return ((Number)rows.getFirst().get("status")).intValue();
    }
    public Instant deadline(long id) {
        return jdbc.queryForObject("SELECT LEAST(o.expires_at,DATE_ADD(o.create_time,INTERVAL 15 MINUTE),DATE_SUB(s.sale_end_time,INTERVAL 8 HOUR)) FROM orders o JOIN screening s ON s.id=o.screening_id WHERE o.id=?",LocalDateTime.class,id).toInstant(ZoneOffset.UTC);
    }
    public void expire(long id,Instant now) {
        if(jdbc.update("UPDATE orders SET status=3,version=version+1,update_time=? WHERE id=? AND status=0",utc(now),id)!=1) throw conflict();
        release(id,now);
    }
    public void release(long id,Instant now) {
        jdbc.query("SELECT id FROM screening_seat WHERE order_id=? ORDER BY id FOR UPDATE",(r,n)->r.getLong(1),id);
        jdbc.update("UPDATE screening_seat SET status=0,order_id=NULL,update_time=? WHERE order_id=? AND status=1",utc(now),id);
    }
    public void cancel(long id,Instant now) {
        if(jdbc.update("UPDATE orders SET status=2,version=version+1,update_time=? WHERE id=? AND status=0",utc(now),id)!=1) throw conflict();
        release(id,now);
    }
    public void validateOwned(long user) {
        Long broken=jdbc.queryForObject("SELECT COUNT(*) FROM orders o LEFT JOIN screening s ON s.id=o.screening_id LEFT JOIN movie m ON m.id=s.movie_id LEFT JOIN hall h ON h.id=s.hall_id WHERE o.user_id=? AND (s.id IS NULL OR m.id IS NULL OR h.id IS NULL OR o.status NOT IN (0,1,2,3,4,5))",Long.class,user);
        if(broken!=0) throw new IllegalStateException("订单必要关联或状态异常");
    }
    public String displayStatus(int state,long id,Instant now) {
        var status=io.github.chenforegone.encoreticket.model.enums.OrderStatus.fromValue(state);
        return state==0&&!now.isBefore(deadline(id))?"EXPIRED":status.getApiCode();
    }
    public OrderDetailVO detail(long id,long user,Instant now) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id=? AND user_id=?",Long.class,id,user)!=1)
            throw new io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM orders o JOIN screening s ON s.id=o.screening_id JOIN movie m ON m.id=s.movie_id JOIN hall h ON h.id=s.hall_id WHERE o.id=?",Long.class,id)!=1)
            throw new IllegalStateException("订单必要关联缺失");
        var detailItems=items(id);
        BigDecimal total=jdbc.queryForObject("SELECT total_amount FROM orders WHERE id=?",BigDecimal.class,id);
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE order_id=?",Long.class,id);
        if(detailItems.isEmpty()||detailItems.size()!=count||detailItems.stream().map(i->new BigDecimal(i.unitPrice())).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(total)!=0)
            throw new IllegalStateException("订单明细或金额异常");
        return jdbc.query("SELECT o.*,s.movie_id,s.hall_id,s.start_time,s.end_time,s.sale_end_time,m.title,h.hall_name FROM orders o JOIN screening s ON s.id=o.screening_id JOIN movie m ON m.id=s.movie_id JOIN hall h ON h.id=s.hall_id WHERE o.id=? AND o.user_id=?",
                (r,n)->new OrderDetailVO(r.getString("id"),r.getString("order_no"),r.getString("screening_id"),r.getString("movie_id"),r.getString("title"),r.getString("hall_id"),r.getString("hall_name"),
                        r.getObject("start_time",LocalDateTime.class).atOffset(ZoneOffset.ofHours(8)),r.getObject("end_time",LocalDateTime.class).atOffset(ZoneOffset.ofHours(8)),displayStatus(r.getInt("status"),id,now),
                        detailItems.size(),r.getBigDecimal("total_amount").setScale(2).toPlainString(),"CNY",deadline(id),
                        r.getObject("create_time",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getObject("update_time",LocalDateTime.class).toInstant(ZoneOffset.UTC),detailItems),id,user).getFirst();
    }
    private List<OrderDetailVO.Item> items(long id) {
        return jdbc.query("SELECT i.id,i.screening_seat_id,ss.seat_id,s.row_number,s.seat_number,i.unit_price FROM order_item i JOIN screening_seat ss ON ss.id=i.screening_seat_id JOIN seat s ON s.id=ss.seat_id WHERE i.order_id=? ORDER BY i.screening_seat_id",
                (r,n)->new OrderDetailVO.Item(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getBigDecimal(6).setScale(2).toPlainString()),id);
    }
    public static LocalDateTime utc(Instant now) { return LocalDateTime.ofInstant(now,ZoneOffset.UTC); }
}
