package io.github.chenforegone.encoreticket.mapper;

import io.github.chenforegone.encoreticket.model.vo.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * 退款专用持久化访问；事务、归属与状态规则由 Service 决定。
 * @author c1342
 */
@Repository
public class RefundProcessingMapper {
    private final JdbcTemplate jdbc;
    public RefundProcessingMapper(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public int insertRefund(long id,String no,long order,long payment,BigDecimal amount,LocalDateTime now) {
        return jdbc.update("INSERT INTO refund(id,refund_no,order_id,payment_id,amount,status,source,attempt_no,next_run_at,create_time,update_time) VALUES(?,?,?,?,?,0,'USER',1,?,?,?)",id,no,order,payment,amount,now,now,now);
    }
    public int acceptOrder(long id,LocalDateTime now) { return jdbc.update("UPDATE orders SET status=4,version=version+1,update_time=? WHERE id=? AND status=1",now,id); }
    public int attemptNumber(long id) { return jdbc.queryForObject("SELECT attempt_no FROM refund WHERE id=?",Integer.class,id); }
    public int retry(long id,int attempt,LocalDateTime now) { return jdbc.update("UPDATE refund SET status=0,attempt_no=?,next_run_at=?,lease_token=NULL,lease_until=NULL,update_time=? WHERE id=? AND status=2",attempt,now,now,id); }
    public int scheduleCheck(long id,LocalDateTime now) { return jdbc.update("UPDATE refund SET next_run_at=? WHERE id=?",now,id); }
    public io.github.chenforegone.encoreticket.common.response.PageResponse<RefundVO> page(int page,int size,Integer status,String source,Long order,LocalDateTime from,LocalDateTime to) {
        StringBuilder where=new StringBuilder(" WHERE 1=1"); List<Object> args=new ArrayList<>();
        if(status!=null) { where.append(" AND status=?"); args.add(status); }
        if(source!=null) { where.append(" AND source=?"); args.add(source); }
        if(order!=null) { where.append(" AND order_id=?"); args.add(order); }
        if(from!=null) { where.append(" AND create_time>=?"); args.add(from); }
        if(to!=null) { where.append(" AND create_time<?"); args.add(to); }
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM refund"+where,Long.class,args.toArray());
        args.add(size); args.add((page-1)*size);
        var items=jdbc.query("SELECT * FROM refund"+where+" ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->view(rs),args.toArray());
        return new io.github.chenforegone.encoreticket.common.response.PageResponse<>(items,page,size,count);
    }
    public List<RefundDetailVO.Attempt> attempts(long id) {
        return jdbc.query("SELECT attempt_no,status,create_time,update_time FROM refund_attempt WHERE refund_id=? ORDER BY attempt_no",(rs,n)->new RefundDetailVO.Attempt(rs.getInt(1),rs.getString(2),rs.getObject(3,LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject(4,LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);
    }
    public List<RefundDetailVO.Audit> audits(long id) {
        return jdbc.query("SELECT operation,operator_id,create_time FROM refund_audit WHERE refund_id=? ORDER BY create_time,id",(rs,n)->new RefundDetailVO.Audit(rs.getString(1),rs.getString(2),rs.getObject(3,LocalDateTime.class).toInstant(ZoneOffset.UTC)),id);
    }
    public List<Long> pending(LocalDateTime now,int limit) { return jdbc.query("SELECT id FROM refund WHERE status=0 AND (next_run_at IS NULL OR next_run_at<=?) AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_run_at,id LIMIT ?",(rs,n)->rs.getLong(1),now,now,limit); }
    public int deferInvalidClaim(long id,LocalDateTime now,LocalDateTime next) { return jdbc.update("UPDATE refund SET next_run_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND status=0 AND (lease_until IS NULL OR lease_until<=?)",next,id,now); }
    public int claim(long id,String lease,LocalDateTime now,LocalDateTime until) {
        return jdbc.update("UPDATE refund SET lease_token=?,lease_until=?,next_run_at=? WHERE id=? AND status=0 AND (next_run_at IS NULL OR next_run_at<=?) AND (lease_until IS NULL OR lease_until<=?)",lease,until,until,id,now,now);
    }
    public int attemptCount(long id,int attempt) { return jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempt WHERE refund_id=? AND attempt_no=?",Integer.class,id,attempt); }
    public boolean previousAttemptFailed(long id,int attempt) { return jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempt WHERE refund_id=? AND attempt_no=? AND status='FAILED'",Integer.class,id,attempt-1)==1; }
    public int markSubmitted(long id,int attempt,LocalDateTime now) { return jdbc.update("UPDATE refund_attempt SET submitted=TRUE,update_time=? WHERE refund_id=? AND attempt_no=?",now,id,attempt); }
    public int voidTickets(long order,LocalDateTime now) { return jdbc.update("UPDATE ticket t JOIN order_item i ON i.id=t.order_item_id SET t.status=2,t.update_time=? WHERE i.order_id=? AND t.status=0",now,order); }
    public int releaseSeats(long order,LocalDateTime now) { return jdbc.update("UPDATE screening_seat SET status=0,order_id=NULL,update_time=? WHERE order_id=? AND status=2",now,order); }
    public int completeOrder(long order,LocalDateTime now) { return jdbc.update("UPDATE orders SET status=5,version=version+1,update_time=? WHERE id=? AND status=4",now,order); }
    public int succeed(long id,LocalDateTime now) { return jdbc.update("UPDATE refund SET status=1,lease_token=NULL,lease_until=NULL,update_time=? WHERE id=?",now,id); }
    public int fail(long id,LocalDateTime now) { return jdbc.update("UPDATE refund SET status=2,lease_token=NULL,lease_until=NULL,update_time=? WHERE id=? AND status=0",now,id); }
    public int finishAttempt(long id,int attempt,String status,LocalDateTime now) { return jdbc.update("UPDATE refund_attempt SET status=?,update_time=? WHERE refund_id=? AND attempt_no=?",status,now,id,attempt); }
    public int releaseLease(long id,String lease,LocalDateTime next) { return jdbc.update("UPDATE refund SET lease_token=NULL,lease_until=NULL,next_run_at=? WHERE id=? AND lease_token=?",next,id,lease); }
    public boolean lockScreening(long id) { return !jdbc.query("SELECT id FROM screening WHERE id=? FOR UPDATE",(rs,n)->rs.getLong(1),id).isEmpty(); }
    public List<OrderData> order(long id,boolean lock) { return jdbc.query("SELECT o.id,o.user_id,o.screening_id,o.status,o.total_amount,s.start_time FROM orders o LEFT JOIN screening s ON s.id=o.screening_id WHERE o.id=?"+(lock?" FOR UPDATE":""),(rs,n)->new OrderData(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getInt(4),rs.getBigDecimal(5),rs.getObject(6,LocalDateTime.class)),id); }
    public List<PaymentData> successfulPayments(long order) { return jdbc.query("SELECT id,payment_no,amount,currency FROM payment WHERE order_id=? AND status=1",(rs,n)->new PaymentData(rs.getLong(1),rs.getString(2),rs.getBigDecimal(3),rs.getString(4)),order); }
    public BigDecimal itemTotal(long order) { return jdbc.queryForObject("SELECT SUM(unit_price) FROM order_item WHERE order_id=?",BigDecimal.class,order); }
    public List<Long> ticketIds(long order) { return jdbc.query("SELECT t.id FROM ticket t JOIN order_item i ON i.id=t.order_item_id WHERE i.order_id=? ORDER BY t.id",(rs,n)->rs.getLong(1),order); }
    public TicketData lockTicket(long id) { return jdbc.queryForObject("SELECT id,status,order_item_id FROM ticket WHERE id=? FOR UPDATE",(rs,n)->new TicketData(rs.getLong(1),rs.getInt(2),rs.getLong(3)),id); }
    public List<Long> itemIds(long order) { return jdbc.query("SELECT id FROM order_item WHERE order_id=? ORDER BY id",(rs,n)->rs.getLong(1),order); }
    public List<Map<String,Object>> lockSeats(long order) { return jdbc.queryForList("SELECT ss.id,ss.order_id,ss.status,ss.screening_id,o.screening_id AS expected FROM order_item i JOIN orders o ON o.id=i.order_id LEFT JOIN screening_seat ss ON ss.id=i.screening_seat_id WHERE i.order_id=? ORDER BY ss.id FOR UPDATE",order); }
    public long ownedSeatCount(long order) { return jdbc.queryForObject("SELECT COUNT(*) FROM screening_seat WHERE order_id=?",Long.class,order); }
    public List<RefundVO> refunds(long order) { return jdbc.query("SELECT * FROM refund WHERE order_id=? ORDER BY create_time DESC,id DESC",(rs,n)->view(rs),order); }
    public List<RefundVO> refund(long id) { return jdbc.query("SELECT * FROM refund WHERE id=?",(rs,n)->view(rs),id); }
    public void reserveSnapshot(long user,String operation,String key,String digest,LocalDateTime now,LocalDateTime expiry) {
        jdbc.update("DELETE FROM api_idempotency WHERE operator_id=? AND operation=? AND idempotency_key=? AND expires_at<=?",user,operation,key,now);
        jdbc.update("INSERT INTO api_idempotency(operator_id,operation,idempotency_key,request_digest,expires_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE operator_id=operator_id",user,operation,key,digest,expiry);
    }
    public Map<String,Object> lockSnapshot(long user,String operation,String key) { return jdbc.queryForMap("SELECT request_digest,response_json FROM api_idempotency WHERE operator_id=? AND operation=? AND idempotency_key=? FOR UPDATE",user,operation,key); }
    public int saveSnapshot(long user,String operation,String key,String json) { return jdbc.update("UPDATE api_idempotency SET response_json=? WHERE operator_id=? AND operation=? AND idempotency_key=? AND response_json IS NULL",json,user,operation,key); }
    public int createAttempt(long id,int attempt,LocalDateTime now) { return jdbc.update("INSERT INTO refund_attempt(refund_id,attempt_no,status,submitted,create_time,update_time) VALUES(?,?,'PENDING',FALSE,?,?)",id,attempt,now,now); }
    public int audit(long auditId,long refund,String action,Long user,LocalDateTime now) { return jdbc.update("INSERT INTO refund_audit(id,refund_id,operation,operator_id,create_time) VALUES(?,?,?,?,?)",auditId,refund,action,user,now); }
    public int cleanup(String apply,String process,LocalDateTime now) { return jdbc.update("DELETE FROM api_idempotency WHERE operation IN (?,?) AND expires_at<=? ORDER BY expires_at LIMIT 500",apply,process,now); }
    private RefundVO view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RefundVO(rs.getString("id"),rs.getString("refund_no"),rs.getString("order_id"),rs.getString("payment_id"),rs.getBigDecimal("amount").setScale(2).toPlainString(),"CNY",switch(rs.getInt("status")){case 0->"PENDING";case 1->"SUCCESS";case 2->"FAILED";default->throw new IllegalStateException("退款状态异常");},rs.getString("source"),rs.getObject("create_time",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("update_time",LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
    /**
     * 订单持久化摘要。
     * @author c1342
     */
    public record OrderData(long id,long userId,long screeningId,int status,BigDecimal amount,LocalDateTime start) { }
    /**
     * 成功收款持久化摘要。
     * @author c1342
     */
    public record PaymentData(long id,String paymentNo,BigDecimal amount,String currency) { }
    /**
     * 票券持久化摘要。
     * @author c1342
     */
    public record TicketData(long id,int status,long item) { }
}
