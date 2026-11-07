package com.example.permdemo.trades;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Maps to the 1,000,000-row table seeded by postgres/init.sql. */
@Entity
@Table(name = "trades")
public class Trade {

    @Id
    private Long id;

    @Column(name = "trade_date")
    private LocalDate tradeDate;

    @Column(name = "desk_id")
    private String deskId;

    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "owner_id")
    private String ownerId;

    private String symbol;

    private BigDecimal notional;

    protected Trade() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public String getDeskId() {
        return deskId;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getNotional() {
        return notional;
    }
}
