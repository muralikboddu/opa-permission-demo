# OPA Permission Demo

A fully runnable demo of a permission model enforced as policy using [Open Policy Agent](https://www.openpolicyagent.org/). It demonstrates two core capabilities:

1. **Permission model as policy** — a single Rego `allow` rule backs both point-of-call access checks ("can this banker open this one trade?") and bulk push-down filtering ("give me the trades this region head is allowed to see").
2. **Partial evaluation with reference data** — OPA resolves static reference data (`data.regions`) at compile time and returns a concrete residual predicate (e.g. `country_code IN [US, CA, MX, ...]`) that is pushed directly into the database `WHERE` clause. The database never sees the policy logic, only the resolved filter.

Both paths use the **same Rego policy**. The blotter list and the single-trade view can never disagree about what a user is allowed to see.

> For a low-level walkthrough of every component — data flow diagrams, line-by-line code explanation, OPA AST internals, and a full end-to-end worked example — see **[CODEBASE.md](./CODEBASE.md)**.

---

## How it works

```
Request
  │
  ▼
RoleService          ← resolves who the caller is (role, desk, region, team roster)
  │
  ▼
OpaClient
  ├── evaluate()     → POST /v1/data/trades/authz    (point-of-call: one trade)
  └── compileRaw()   → POST /v1/compile               (bulk: marks resource as "unknown")
                                                        OPA partially evaluates the policy
                                                        and returns the residual conditions
  │
  ▼
PermissionOrchestrator
  ├── calls PartialEvalTranslator to walk OPA's AST → CompiledPredicate
  └── emits a structured audit log entry (type: openpolicyagent.org/partial_eval)
  │
  ▼
SqlPredicateRenderer / ElasticsearchQueryRenderer
  └── renders CompiledPredicate → SQL WHERE clause or ES Query DSL
```

### The three visibility rules (in `policies/trades/authz.rego`)

| Rule | Who | Condition | Reference data source |
|---|---|---|---|
| `desk_match` | banker | `resource.desk_id == subject.desk_id` | None — simple equality |
| `region_match` | region_head | `resource.country_code in data.regions[subject.region]` | `policies/data.json` — static, loaded into OPA |
| `team_match` | business_manager | `resource.owner_id in subject.team_member_ids` | `TeamDirectoryClient` — simulated HR API, cached, passed as `input.subject` |

The `region_match` rule is the key partial evaluation demo: `data.regions["americas"]` is resolved to a concrete array at compile time, so the residual handed to the database is just `country_code IN (US, CA, MX, ...)` — no policy logic leaks into the query.

---

## Prerequisites

```bash
brew install opa maven
```

Java 21+ is required. Verify:

```bash
opa version    # expect 1.x
mvn -version   # expect 3.x
java -version  # expect 21+
```

No Docker, no Postgres, no Elasticsearch needed — the app uses an H2 in-memory database with 25 seed trades.

---

## Running it

### Terminal 1 — start OPA

```bash
opa run --server --addr 0.0.0.0:8181 \
  --config-file opa-config.yaml \
  ./policies
```

OPA loads the policy (`policies/trades/authz.rego`) and reference data (`policies/data.json`) automatically. You should see:

```
{"level":"info","msg":"Initializing server."}
{"level":"info","msg":"Starting decision logger.","plugin":"decision_logs"}
```

### Terminal 2 — start the Spring Boot app

```bash
cd app && mvn spring-boot:run
```

First run downloads Maven dependencies (~1 min). Wait for:

```
Started PermDemoApplication on port 8080
```

The H2 database is seeded automatically from `app/src/main/resources/data.sql`.

---

## The three demo users

```bash
curl -s localhost:8080/demo/users | jq .
```

| user | role | sees |
|---|---|---|
| `alice` | banker | trades on desk `FX-NY` |
| `bob` | region_head | trades where `country_code` is in Americas (`data.regions.americas`) |
| `carol` | business_manager | trades owned by anyone on `team-42` (roster resolved via `TeamDirectoryClient` + cache) |

---

## Scenario 1 — point-of-call check

OPA evaluates the full policy against a single concrete trade. Returns `allow: true/false` and the name of the rule that fired.

```bash
# alice ALLOWED — trade 1 is on desk FX-NY
curl -s "localhost:8080/trades/1?user=alice" | jq '{allow, reasons}'

# alice DENIED — trade 6 is on desk FX-LN
curl -s "localhost:8080/trades/6?user=alice" | jq '{allow, reasons}'

# bob ALLOWED — trade 6 is in US (americas)
curl -s "localhost:8080/trades/6?user=bob" | jq '{allow, reasons}'

# carol ALLOWED — trade 11 owner is p101 (team-42 member)
curl -s "localhost:8080/trades/11?user=carol" | jq '{allow, reasons}'
```

Trade 21 (`desk=FX-NY, country=US, owner=p101`) is visible to all three users, each via a different rule:

```bash
curl -s "localhost:8080/trades/21?user=alice" | jq '{allow, reasons}'
# → {allow: true, reasons: ["desk_match"]}

curl -s "localhost:8080/trades/21?user=bob"   | jq '{allow, reasons}'
# → {allow: true, reasons: ["region_match"]}

curl -s "localhost:8080/trades/21?user=carol" | jq '{allow, reasons}'
# → {allow: true, reasons: ["team_match"]}
```

---

## Scenario 2 — partial evaluation with reference data (bulk push-down)

OPA partially evaluates the policy with `input.resource` marked as unknown. It resolves `data.regions["americas"]` to a concrete array at compile time and returns the residual as an AST. The app translates that AST into a SQL `WHERE` clause and pushes it into the database.

**Inspect the raw OPA compile output:**

```bash
curl -s "localhost:8080/trades/_debug/compile?user=bob" | jq .result.queries
```

You will see OPA's AST for the residual: `internal.member_2(input.resource.country_code, ["US","CA","MX","BR","AR","CL","CO","PE","CR","PA"])`. The `data.regions` lookup has been fully resolved — the database never sees the policy, only the concrete IN-list.

**See the residual pushed as a SQL WHERE clause:**

```bash
curl -s "localhost:8080/trades?user=bob&from=2024-01-01&to=2024-12-31&size=20" \
  | jq '{appliedFilter, country_codes: [.rows[].country_code]}'
```

The `appliedFilter` field shows the exact SQL that ran, including the OPA-derived `country_code IN (?,?,?,...)` predicate. Every row returned has an Americas country code.

**Carol's partial eval — team roster from `input.subject`:**

```bash
curl -s "localhost:8080/trades/_debug/compile?user=carol" | jq .result.queries
```

Same mechanism: `input.subject.team_member_ids` is known input (not in `unknowns`), so OPA resolves it to a concrete `owner_id IN [p101,...,p110]` residual.

**Alice's partial eval — simple equality:**

```bash
curl -s "localhost:8080/trades/_debug/compile?user=alice" | jq .result.queries
# Residual: desk_id == "FX-NY"

curl -s "localhost:8080/trades?user=alice&from=2024-01-01&to=2024-12-31&size=20" \
  | jq '{appliedFilter, desk_ids: [.rows[].desk_id]}'
```

---

## Scenario 3 — compliance audit logs

Every bulk query emits a structured audit log entry in the same `openpolicyagent.org/` namespace as OPA's own decision logs, so both streams can be shipped to the same compliance sink.

Trigger all three users and watch the app terminal:

```bash
curl -s "localhost:8080/trades?user=alice&from=2024-01-01&to=2024-12-31&size=5" > /dev/null
curl -s "localhost:8080/trades?user=bob&from=2024-01-01&to=2024-12-31&size=5" > /dev/null
curl -s "localhost:8080/trades?user=carol&from=2024-01-01&to=2024-12-31&size=5" > /dev/null
```

Each bulk request produces a log entry like:

```json
{
  "decision_id": "56f3c467-71eb-4404-a633-4a762d963418",
  "timestamp": "2026-11-07T18:19:42.915698Z",
  "type": "openpolicyagent.org/partial_eval",
  "path": "trades/authz",
  "subject": {"username": "bob", "role": "region_head", "region": "americas"},
  "result": {
    "outcome": "conditional",
    "filter": "country_code IN [US, CA, MX, BR, AR, CL, CO, PE, CR, PA]",
    "branch_count": 1
  }
}
```

Point-of-call check decisions are logged by OPA itself (see the OPA terminal). Both log streams share the same `openpolicyagent.org/` type prefix so a single log shipper can handle them.

### Shipping logs to a remote compliance system

Edit `opa-config.yaml` and uncomment the HTTP plugin section:

```yaml
decision_logs:
  console: true
  plugin: http
  service: compliance_backend

services:
  compliance_backend:
    url: https://your-splunk-hec-or-elasticsearch-endpoint
    credentials:
      bearer:
        token: "${COMPLIANCE_TOKEN}"
```

OPA batches decision log entries and POSTs them every 5–10 seconds (tunable via `min_delay_seconds` / `max_delay_seconds`). Works natively with Splunk HEC, Elasticsearch, or any webhook receiver.

---

## Seed data

25 trades in `app/src/main/resources/data.sql`, designed to cover all visibility scenarios:

| Trade IDs | desk | country | owner | Visible to |
|---|---|---|---|---|
| 1–5 | FX-NY | DE, JP, GB, CH, AE | e001–e005 | alice only |
| 6–10 | FX-LN, FX-HK, FX-TK | US, CA, MX, BR, AR | e006–e010 | bob only |
| 11–17 | FX-LN, FX-TK, FX-SG, FX-HK | DE, JP, SG, GB, HK, AU, NZ | p101–p107 | carol only |
| 18–20 | FX-TK, FX-SG, FX-HK | DE, CH, AU | e011–e013 | nobody |
| 21–25 | mixed | mixed | mixed | 2–3 users each |

---

## Project structure

```
├── policies/
│   ├── data.json                        # data.regions: Americas / EMEA / APAC country lists
│   └── trades/
│       ├── authz.rego                   # The permission policy
│       └── authz_test.rego              # OPA unit tests
│
├── opa-config.yaml                      # OPA server config: decision log batching + HTTP plugin
│
└── app/
    └── src/main/java/com/example/permdemo/
        ├── model/
        │   ├── Subject.java             # input.subject: who is making the request
        │   └── ResourceAttributes.java  # input.resource: the trade being accessed
        ├── role/
        │   ├── RoleService.java         # Resolves username → Subject (role + attributes)
        │   └── TeamDirectoryClient.java # Simulated HR API + Caffeine cache
        ├── opa/
        │   ├── OpaClient.java           # evaluate() + compileRaw() — two OPA API calls
        │   ├── PartialEvalTranslator.java  # Walks OPA's Compile API AST → CompiledPredicate
        │   ├── CompiledPredicate.java   # AlwaysAllow | AlwaysDeny | Conditional (OR-of-ANDs)
        │   ├── PermissionOrchestrator.java  # compile + translate + audit log
        │   ├── SqlPredicateRenderer.java    # CompiledPredicate → SQL WHERE clause
        │   └── ElasticsearchQueryRenderer.java  # CompiledPredicate → ES Query DSL
        └── trades/
            ├── TradeController.java     # Four endpoints covering all scenarios
            ├── TradeQueryService.java   # JdbcTemplate push-down query with keyset pagination
            └── ElasticTradeService.java # Elasticsearch push-down query
```

---

## Running the OPA policy tests

```bash
opa test ./policies -v
```

No server needed. Tests cover all three visibility rules and the deny case.

---

## Extending it

- **Add a new visibility rule** — add it to `authz.rego` and it participates in the OR automatically. No changes needed in `PartialEvalTranslator` unless it uses an operator outside `==`, `!=`, `<`, `<=`, `>`, `>=`, `in`.
- **Connect a real HR/identity API** — replace the hardcoded map in `TeamDirectoryClient.fetchTeamMembers()` with an HTTP call. Nothing downstream changes; `RoleService` hands a resolved `Subject` to OPA either way.
- **Add a new pushdown column** — add it to the `ALLOWED_COLUMNS` set in `SqlPredicateRenderer`. That set is a deliberate guardrail: it prevents a policy bug from ever interpolating an arbitrary field name into SQL.
- **Ship logs to Splunk/ES** — uncomment the HTTP plugin section in `opa-config.yaml` and set the URL and token.
