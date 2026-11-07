package com.example.permdemo.trades;

import org.springframework.data.jpa.repository.JpaRepository;

/** Only used for the point-of-call path (fetch one row by id). The bulk
 *  push-down path deliberately bypasses JPA and talks to the database
 *  directly via TradeQueryService/JdbcTemplate, because the WHERE clause
 *  it needs is built dynamically from OPA's compiled predicate. */
public interface TradeRepository extends JpaRepository<Trade, Long> {
}
