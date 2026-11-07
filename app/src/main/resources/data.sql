-- Sample trades for local H2 demo.
-- Coverage:
--   alice  (banker, desk FX-NY)         → rows where desk_id = 'FX-NY'
--   bob    (region_head, americas)       → rows where country_code IN ('US','CA','MX','BR','AR','CL','CO','PE','CR','PA')
--   carol  (business_manager, team-42)  → rows where owner_id IN ('p101'..'p110')

-- desk=FX-NY, various countries, non-team owners → alice only
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (1,  '2024-01-15', 'FX-NY', 'DE', 'e001', 'EURUSD', 1000000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (2,  '2024-01-20', 'FX-NY', 'JP', 'e002', 'USDJPY', 2000000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (3,  '2024-02-05', 'FX-NY', 'GB', 'e003', 'GBPUSD',  500000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (4,  '2024-02-15', 'FX-NY', 'CH', 'e004', 'USDCHF',  750000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (5,  '2024-03-01', 'FX-NY', 'AE', 'e005', 'USDAED', 1500000.00);

-- americas country codes, non-FX-NY desks, non-team owners → bob only
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (6,  '2024-01-10', 'FX-LN', 'US', 'e006', 'EURUSD',  800000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (7,  '2024-02-01', 'FX-HK', 'CA', 'e007', 'USDCAD',  600000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (8,  '2024-02-20', 'FX-LN', 'MX', 'e008', 'USDMXN', 1200000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (9,  '2024-03-10', 'FX-TK', 'BR', 'e009', 'USDBRL',  900000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (10, '2024-03-25', 'FX-LN', 'AR', 'e010', 'USDARS',  400000.00);

-- team-42 owners (p101-p110), non-FX-NY desks, non-americas countries → carol only
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (11, '2024-01-08', 'FX-LN', 'DE', 'p101', 'EURGBP', 1100000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (12, '2024-01-25', 'FX-TK', 'JP', 'p102', 'USDJPY', 1800000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (13, '2024-02-12', 'FX-SG', 'SG', 'p103', 'USDSGD',  650000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (14, '2024-02-28', 'FX-LN', 'GB', 'p104', 'GBPEUR',  950000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (15, '2024-03-15', 'FX-HK', 'HK', 'p105', 'USDHKD', 2100000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (16, '2024-04-02', 'FX-TK', 'AU', 'p106', 'AUDUSD',  780000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (17, '2024-04-18', 'FX-SG', 'NZ', 'p107', 'NZDUSD',  430000.00);

-- nobody can see (not FX-NY, not americas, not team-42)
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (18, '2024-01-18', 'FX-TK', 'DE', 'e011', 'EURJPY',  700000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (19, '2024-02-08', 'FX-SG', 'CH', 'e012', 'USDCHF',  850000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (20, '2024-03-20', 'FX-HK', 'AU', 'e013', 'AUDUSD', 1300000.00);

-- multi-match rows: visible to more than one user
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (21, '2024-01-30', 'FX-NY', 'US', 'p101', 'EURUSD',  500000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (22, '2024-02-22', 'FX-NY', 'CA', 'p108', 'USDCAD',  880000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (23, '2024-03-05', 'FX-NY', 'DE', 'p106', 'EURGBP', 1050000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (24, '2024-04-12', 'FX-SG', 'US', 'p109', 'USDUSD',  750000.00);
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional) VALUES (25, '2024-05-01', 'FX-LN', 'CO', 'e014', 'USDCOP',  450000.00);
