-- Seeds a 1,000,000-row trades table so the demo can show OPA's Compile API
-- push-down actually mattering at scale: the app never fetches all 1M rows,
-- it turns the policy into a WHERE clause and lets Postgres's indexes find
-- the ~tens-of-thousands of allowed rows directly.
--
-- generate_series + random() seeds 1M rows in a few seconds on a laptop.

CREATE TABLE trades (
    id           BIGINT PRIMARY KEY,
    trade_date   DATE NOT NULL,
    desk_id      TEXT NOT NULL,
    country_code TEXT NOT NULL,
    owner_id     TEXT NOT NULL,
    symbol       TEXT NOT NULL,
    notional     NUMERIC(18, 2) NOT NULL
);

-- owner_id pool is p001..p200. Carol's team (team-42, see RoleService) is
-- p101..p110 -- a real subset of this pool, so the business-manager
-- scenario returns a genuine, non-trivial slice of the table.
INSERT INTO trades (id, trade_date, desk_id, country_code, owner_id, symbol, notional)
SELECT
    i,
    (DATE '2024-01-01' + (floor(random() * 730))::int)                         AS trade_date,
    (ARRAY['FX-NY', 'FX-LDN', 'FX-SP', 'EQ-NY', 'EQ-LDN', 'EQ-SG'])
        [1 + floor(random() * 6)::int]                                        AS desk_id,
    (ARRAY['US', 'CA', 'MX', 'BR', 'AR', 'CL', 'CO', 'PE', 'CR', 'PA',   -- americas
           'GB', 'DE', 'FR', 'CH', 'AE', 'ZA', 'NL', 'ES', 'IT', 'SA',   -- emea
           'SG', 'JP', 'HK', 'AU', 'IN', 'CN', 'KR', 'MY', 'TH', 'NZ',   -- apac
           'XX', 'YY'])                                                  -- unmapped, on purpose
        [1 + floor(random() * 32)::int]                                       AS country_code,
    'p' || lpad((1 + floor(random() * 200))::int::text, 3, '0')               AS owner_id,
    (ARRAY['EURUSD', 'GBPUSD', 'USDJPY', 'AAPL', 'MSFT', 'GOLD'])
        [1 + floor(random() * 6)::int]                                        AS symbol,
    round((random() * 10000000)::numeric, 2)                                  AS notional
FROM generate_series(1, 1000000) AS s(i);

-- These are what make the push-down predicate cheap: every clause OPA's
-- Compile API can produce (desk_id =, country_code IN (...), owner_id IN
-- (...)) is paired with trade_date so the planner can use an index seek
-- instead of a sequential scan over 1M rows.
CREATE INDEX idx_trades_date ON trades (trade_date);
CREATE INDEX idx_trades_date_desk ON trades (trade_date, desk_id);
CREATE INDEX idx_trades_date_country ON trades (trade_date, country_code);
CREATE INDEX idx_trades_date_owner ON trades (trade_date, owner_id);
-- Supports ORDER BY trade_date, id LIMIT :size keyset pagination directly.
CREATE INDEX idx_trades_date_id ON trades (trade_date, id);

ANALYZE trades;
