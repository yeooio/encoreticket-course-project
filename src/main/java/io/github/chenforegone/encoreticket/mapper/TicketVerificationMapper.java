package io.github.chenforegone.encoreticket.mapper;

import io.github.chenforegone.encoreticket.model.dto.TicketVerificationQueryRequest;
import io.github.chenforegone.encoreticket.model.query.TicketVerificationProjection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 核销专属参数化 SQL；业务锁由 Service 显式排序。
 *
 * @author c1342
 */
@Repository
public class TicketVerificationMapper {
    private final JdbcTemplate jdbc;
    public TicketVerificationMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 加锁后的场次状态。
     * @author c1342
     */
    public record Screening(long id, int status, LocalDateTime admission, LocalDateTime start, LocalDateTime end) { }

    /**
     * 幂等成功快照；占位与业务一同提交。
     * @author c1342
     */
    public record Idempotency(String digest, String response, Instant expiresAt) { }

    private static final String JOIN = """
            FROM ticket t
            LEFT JOIN order_item oi ON oi.id=t.order_item_id
            LEFT JOIN orders o ON o.id=oi.order_id
            LEFT JOIN screening s ON s.id=o.screening_id
            LEFT JOIN screening_seat ss ON ss.id=oi.screening_seat_id
            LEFT JOIN seat st ON st.id=ss.seat_id
            LEFT JOIN hall h ON h.id=s.hall_id
            LEFT JOIN movie m ON m.id=s.movie_id
            LEFT JOIN ticket_verification v ON v.ticket_id=t.id
            LEFT JOIN sys_user vu ON vu.id=v.operator_id
            """;
    private static final String AUDIT_JOIN = JOIN.replace("FROM ticket t", "FROM ticket_verification v LEFT JOIN ticket t ON t.id=v.ticket_id")
            .replace("LEFT JOIN ticket_verification v ON v.ticket_id=t.id", "");
    private static final String SELECT = """
            SELECT t.id ticket_id,t.ticket_code,t.status ticket_status,oi.id item_id,
            o.id order_id,o.screening_id order_screening_id,o.status order_status,o.order_no,
            ss.id screening_seat_id,ss.screening_id seat_screening_id,ss.order_id seat_order_id,ss.status sale_status,
            st.id seat_id,st.hall_id seat_hall_id,st.row_number,st.seat_number,
            s.id screening_id,m.id movie_id,h.id hall_id,m.title movie_title,h.hall_name,
            v.id verification_id,vu.id operator_id,v.device_code,v.verified_at,s.start_time,s.end_time
            """ + JOIN;
    private static final RowMapper<TicketVerificationProjection> PROJECTION = (r, n) -> new TicketVerificationProjection(
            number(r,"ticket_id"),r.getString("ticket_code"),integer(r,"ticket_status"),number(r,"item_id"),
            number(r,"order_id"),number(r,"order_screening_id"),integer(r,"order_status"),r.getString("order_no"),
            number(r,"screening_seat_id"),number(r,"seat_screening_id"),number(r,"seat_order_id"),integer(r,"sale_status"),
            number(r,"seat_id"),number(r,"seat_hall_id"),integer(r,"row_number"),integer(r,"seat_number"),
            number(r,"screening_id"),number(r,"movie_id"),number(r,"hall_id"),r.getString("movie_title"),r.getString("hall_name"),
            number(r,"verification_id"),number(r,"operator_id"),r.getString("device_code"),instant(r,"verified_at"),
            r.getObject("start_time",LocalDateTime.class),r.getObject("end_time",LocalDateTime.class));

    public boolean operatorExists(long operator) {
        return !jdbc.queryForList("SELECT id FROM sys_user WHERE id=?", Long.class, operator).isEmpty();
    }

    public TicketVerificationProjection byCode(String code) {
        return first(jdbc.query(SELECT + " WHERE t.ticket_code=? AND BINARY t.ticket_code=BINARY ?", PROJECTION,code,code));
    }

    public TicketVerificationProjection byId(long id) {
        return first(jdbc.query(SELECT + " WHERE t.id=?",PROJECTION,id));
    }

    public Screening lockScreening(long id) {
        return first(jdbc.query("SELECT id,status,admission_open_time,start_time,end_time FROM screening WHERE id=? FOR UPDATE",
                (r,n) -> new Screening(r.getLong(1),r.getInt(2),r.getObject(3,LocalDateTime.class),
                        r.getObject(4,LocalDateTime.class),r.getObject(5,LocalDateTime.class)),id));
    }

    public Integer lockOrder(long id) {
        return first(jdbc.query("SELECT status FROM orders WHERE id=? FOR UPDATE",(r,n) -> r.getInt(1),id));
    }

    public Integer lockTicket(long id) {
        return first(jdbc.query("SELECT status FROM ticket WHERE id=? FOR UPDATE",(r,n) -> r.getInt(1),id));
    }

    public int use(long id, Instant now) {
        return jdbc.update("UPDATE ticket SET status=1,update_time=? WHERE id=? AND status=0",utc(now),id);
    }

    public void audit(long id,long ticket,long operator,Instant now) {
        jdbc.update("INSERT INTO ticket_verification(id,ticket_id,operator_id,device_code,verified_at,create_time) VALUES(?,?,?,'DEFAULT',?,?)",
                id,ticket,operator,utc(now),utc(now));
    }

    public Idempotency find(long operator,String operation,String key,boolean lock) {
        return first(jdbc.query("SELECT request_digest,response_json,expires_at FROM api_idempotency WHERE operator_id=? AND operation=? AND idempotency_key=?" + (lock ? " FOR UPDATE" : ""),
                (r,n) -> new Idempotency(r.getString(1),r.getString(2),instant(r,"expires_at")),operator,operation,key));
    }

    public void reserve(long operator,String operation,String key,String digest,Instant now) {
        jdbc.update("INSERT INTO api_idempotency(operator_id,operation,idempotency_key,request_digest,response_json,expires_at) VALUES(?,?,?,?,NULL,?)",
                operator,operation,key,digest,utc(now.plusSeconds(86400)));
    }

    public int save(long operator,String operation,String key,String response,Instant now) {
        return jdbc.update("UPDATE api_idempotency SET response_json=?,expires_at=? WHERE operator_id=? AND operation=? AND idempotency_key=?",
                response,utc(now.plusSeconds(86400)),operator,operation,key);
    }

    public void delete(long operator,String operation,String key) {
        jdbc.update("DELETE FROM api_idempotency WHERE operator_id=? AND operation=? AND idempotency_key=?",operator,operation,key);
    }

    public int cleanup(String operation,Instant now,int limit) {
        return jdbc.update("DELETE FROM api_idempotency WHERE operation=? AND expires_at<=? ORDER BY expires_at LIMIT ?",operation,utc(now),limit);
    }

    public long count(TicketVerificationQueryRequest q) {
        List<Object> args = new ArrayList<>();
        String where = filters(q,args);
        return jdbc.queryForObject("SELECT COUNT(*) " + AUDIT_JOIN + where,Long.class,args.toArray());
    }

    public List<TicketVerificationProjection> list(TicketVerificationQueryRequest q) {
        List<Object> args = new ArrayList<>();
        String where = filters(q,args);
        args.add(q.size()); args.add(((long)q.pageNumber()-1)*q.size());
        return jdbc.query(SELECT.replace(JOIN,AUDIT_JOIN) + where + " ORDER BY v.verified_at DESC,v.id DESC LIMIT ? OFFSET ?",PROJECTION,args.toArray());
    }

    private String filters(TicketVerificationQueryRequest q,List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE v.id IS NOT NULL");
        add(sql,args,"o.screening_id=?",q.getScreeningId()==null?null:TicketVerificationQueryRequest.id(q.getScreeningId()));
        add(sql,args,"v.operator_id=?",q.getOperatorId()==null?null:TicketVerificationQueryRequest.id(q.getOperatorId()));
        add(sql,args,"BINARY t.ticket_code=BINARY ?",q.getTicketCode());
        add(sql,args,"BINARY v.device_code=BINARY ?",q.getDeviceCode());
        add(sql,args,"v.verified_at>=?",q.from()==null?null:utc(q.from()));
        add(sql,args,"v.verified_at<?",q.to()==null?null:utc(q.to()));
        return sql.toString();
    }

    private static void add(StringBuilder sql,List<Object> args,String condition,Object value) {
        if (value!=null) { sql.append(" AND ").append(condition); args.add(value); }
    }
    private static Long number(ResultSet r,String column) throws SQLException { return r.getObject(column,Long.class); }
    private static Integer integer(ResultSet r,String column) throws SQLException { return r.getObject(column,Integer.class); }
    private static Instant instant(ResultSet r,String column) throws SQLException {
        LocalDateTime value=r.getObject(column,LocalDateTime.class);
        return value==null?null:value.toInstant(ZoneOffset.UTC);
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    private static <T> T first(List<T> rows) { return rows.isEmpty()?null:rows.getFirst(); }
}
