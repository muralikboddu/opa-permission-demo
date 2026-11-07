package com.example.permdemo.trades;

import com.example.permdemo.opa.SqlPredicateRenderer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the push-down query directly against Postgres with JdbcTemplate --
 * base date-range filter, plus (when the subject isn't unconditionally
 * allow/deny) OPA's compiled predicate AND'd in, plus keyset pagination
 * (WHERE (trade_date, id) > (?, ?) ORDER BY trade_date, id LIMIT ?) so that
 * every page returned is a full page of already-authorized rows -- see the
 * pagination-correctness discussion this project is demonstrating.
 *
 * This is deliberately plain JDBC rather than JPA Criteria/Specification:
 * the WHERE fragment is opaque text handed to us by SqlPredicateRenderer,
 * so there's nothing a higher-level query DSL would add here.
 */
@Service
public class TradeQueryService {

    private final JdbcTemplate jdbc;

    public TradeQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TradeRow(long id, LocalDate tradeDate, String deskId, String countryCode,
                            String ownerId, String symbol, BigDecimal notional) {}

    public record Page(List<TradeRow> rows, LocalDate nextAfterDate, Long nextAfterId, String appliedSql) {}

    /** predicate == null means "no extra condition" -- i.e. AlwaysAllow was resolved upstream. */
    public Page queryAllowed(LocalDate from, LocalDate to, SqlPredicateRenderer.Rendered predicate,
                              int size, LocalDate afterDate, Long afterId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, trade_date, desk_id, country_code, owner_id, symbol, notional " +
                "FROM trades WHERE trade_date BETWEEN ? AND ?");
        List<Object> params = new ArrayList<>();
        params.add(from);
        params.add(to);

        if (predicate != null) {
            sql.append(" AND ").append(predicate.sql());
            params.addAll(predicate.params());
        }
        if (afterDate != null && afterId != null) {
            sql.append(" AND (trade_date, id) > (?, ?)");
            params.add(afterDate);
            params.add(afterId);
        }
        sql.append(" ORDER BY trade_date, id LIMIT ?");
        params.add(size);

        List<TradeRow> rows = jdbc.query(sql.toString(), (rs, rowNum) -> new TradeRow(
                rs.getLong("id"),
                rs.getObject("trade_date", LocalDate.class),
                rs.getString("desk_id"),
                rs.getString("country_code"),
                rs.getString("owner_id"),
                rs.getString("symbol"),
                rs.getBigDecimal("notional")), params.toArray());

        if (rows.isEmpty()) {
            return new Page(rows, null, null, sql.toString());
        }
        TradeRow last = rows.get(rows.size() - 1);
        return new Page(rows, last.tradeDate(), last.id(), sql.toString());
    }
}
