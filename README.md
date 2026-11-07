# OPA permission-orchestrator demo

A runnable demo of the design discussed: a `RoleService` + `PermissionOrchestrator`
split, backed by OPA/Rego, that supports both point-of-call checks ("can this
banker open this one trade?") and bulk push-down filtering ("show me the
~10,000 of 1,000,000 trades this region head is allowed to see") -- against
both Postgres and Elasticsearch, from the *same* Rego policy.

## What's here

| Piece | Where | What it shows |
|---|---|---|
| The policy | `policies/trades/authz.rego` | One `allow` rule, OR of three visibility rules (desk / region / team) -- backs both the single-record check and the bulk filter |
| Region reference data | `policies/data.json` | `data.regions`: static, loaded into OPA -- see "region head" scenario |
| Team reference data | `app/.../role/TeamDirectoryClient.java` | Simulated external HR API + cache -- see "business manager" scenario |
| 1,000,000-row dataset | `postgres/init.sql` | Seeded via `generate_series`, so push-down actually matters |
| Elasticsearch variant | `elasticsearch/` | The same push-down idea, targeting Elasticsearch Query DSL instead of SQL |
| The app | `app/` (Spring Boot) | `RoleService`, `OpaClient`, `PartialEvalTranslator`, and the four demo endpoints |

## Prerequisites

- Docker and Docker Compose
- That's it -- the Java app builds inside its own container (see `app/Dockerfile`), so you don't need a local JDK or Maven install.

## Running it

```bash
docker compose up --build
```

First run takes a few minutes: Postgres seeds 1,000,000 rows, Elasticsearch
seeds 20,000 documents, and the app image builds with Maven. Wait for
`app` to log that it's started on port 8080.

Try the OPA policy tests directly (needs the `opa` CLI locally, or run it
through the container):

```bash
docker compose exec opa opa test /policies -v
```

## The three demo users

`GET /demo/users` lists them, but in short:

| user | role | sees |
|---|---|---|
| `alice` | banker | trades on desk `FX-NY` |
| `bob` | region_head | trades whose `country_code` is in the Americas (`data.regions.americas`) |
| `carol` | business_manager | trades owned by anyone on `team-42` (roster resolved via the simulated HR API + cache) |

## Scenario 1 -- point-of-call check

```bash
curl "localhost:8080/trades/1?user=alice"
curl "localhost:8080/trades/1?user=bob"
```

Same trade, different users, different `allow`/`reasons` -- and the id `1`
here is whatever the seed happened to put there, so don't be surprised if
`alice` is denied on some ids and allowed on others; that's the point.

## Scenario 2 -- bulk push-down to Postgres (the 1,000,000-row case)

```bash
curl "localhost:8080/trades?user=bob&from=2024-01-01&to=2025-12-31&size=20"
```

The response includes `appliedFilter` -- the actual SQL predicate OPA's
compiled output produced, so you can see exactly what got pushed into the
`WHERE` clause instead of being filtered in the app. `nextCursor` gives you
`afterDate`/`afterId` for the next page (keyset pagination, not
`OFFSET` -- see the pagination-correctness discussion this project came out
of): pass those back as query params to get page 2 with no double-counting
or skipped rows.

```bash
curl "localhost:8080/trades?user=carol&from=2024-01-01&to=2025-12-31&size=20&afterDate=2024-03-01&afterId=4213"
```

## Scenario 3 -- the same policy, pushed down into Elasticsearch instead

```bash
curl "localhost:8080/trades/es?user=bob&from=2024-01-01&to=2025-12-31&size=20"
```

Same `allow` rule, same compiled predicate, rendered as Elasticsearch Query
DSL (`bool`/`should`/`terms`) instead of SQL, run against the 20,000-document
index instead of the 1,000,000-row table.

## Inspecting the raw OPA output

```bash
curl "localhost:8080/trades/_debug/compile?user=carol"
```

Returns OPA's Compile API response verbatim -- useful on its own, and
essential if you hit the caveat below.

## What was and wasn't verified before this reached you

Being direct about this because it matters for a project meant to demonstrate
correctness: this was built in a sandboxed environment whose network egress
was locked down to a handful of package registries. It could reach **npm**
and **PyPI**, but not **Maven Central**, not **Docker Hub**, and not OPA's own
download server -- so it was not possible to actually run `mvn package`,
`docker compose up`, or a real OPA server while building this.

What that means concretely, and what was done about it:

- **The Rego policy** (`authz.rego`) uses standard Rego v1 syntax (`if`,
  `contains`, `in`, no `import future.keywords` needed) and was written
  against OPA's documented language reference. It ships with
  `authz_test.rego` -- run `opa test policies/ -v` yourself as a first step;
  that's real, fast, and doesn't depend on anything in this list.
- **The hardest part to get right without a live OPA server** is
  `PartialEvalTranslator.java` -- the code that walks OPA's Compile API
  response (`result.queries`) and turns it into SQL/Elasticsearch. Its
  understanding of that response's shape (the `ref`/`var`/`array` term
  format, and `internal.member_2` as what `in` compiles down to) comes from
  OPA's own long-stable, documented AST format, not guesswork. To get real
  confidence without a live OPA, I extracted that exact class (byte-for-byte,
  minus the `@Component` annotation) into a standalone harness, compiled it
  with plain `javac` against Jackson jars found on disk, and ran it against
  seven hand-built fixtures matching that documented shape: a plain equality
  clause, an `in`-membership clause, the always-allow case (`[[]]`), the
  always-deny case (`queries` absent), a two-branch OR, and a deliberately
  unsupported operator to confirm it fails loudly rather than mistranslating.
  All 13 assertions passed. That's real evidence the *algorithm* is correct
  against the documented format -- it is not the same as having run it
  against a live OPA binary.
- **If `PartialEvalTranslator` throws `UnsupportedRegoConstructException`
  the first time you run this for real**, that's the seam to look at: call
  `GET /trades/_debug/compile?user=...`, compare the raw JSON to what the
  class expects (its Javadoc walks through the expected shape), and adjust.
  It's designed to fail loudly with the raw JSON attached rather than
  silently produce a wrong WHERE clause.
- **Everything else** (docker-compose wiring, the Postgres seed SQL, the
  Spring Boot wiring, the Elasticsearch mapping/bulk load) was written
  carefully against stable, well-documented APIs and cross-checked by eye,
  but genuinely was not executed end-to-end before delivery. `docker compose
  config` was used to validate the compose file's YAML/schema, which did
  pass.

None of this should stop it from working -- it's the kind of thing that's
normally caught by `docker compose up` and a first curl, which is exactly
what running it locally will do. Consider the first run itself the last
verification step.

## Extending it

- Add a fourth rule to `authz.rego` and it participates in the OR
  automatically -- no changes needed in `PartialEvalTranslator` unless it
  uses an operator outside `==`, `!=`, `<`, `<=`, `>`, `>=`, `in`.
- Swap `TeamDirectoryClient`'s hardcoded map for a real HTTP call to your
  org's HR/identity API -- nothing downstream needs to change, since
  `RoleService` only ever hands a resolved `Subject` to OPA either way.
- The `SqlPredicateRenderer` column allow-list (`ALLOWED_COLUMNS`) is a
  deliberate guardrail: it's where you add new pushdown-able columns, and
  it's what stops a policy bug from ever interpolating an arbitrary field
  name into SQL.
