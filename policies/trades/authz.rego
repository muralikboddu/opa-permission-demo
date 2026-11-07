package trades.authz

# ---------------------------------------------------------------------------
# This single `allow` rule backs BOTH code paths discussed in the design:
#   1. Point-of-call check: POST /v1/data/trades/authz/allow with a concrete
#      input (one trade). Returns true/false for that one record.
#   2. Bulk push-down: POST /v1/compile with input.resource marked as an
#      "unknown". OPA partially evaluates this same rule and returns the
#      leftover conditions, which the app translates into a SQL WHERE clause
#      (Postgres) or an Elasticsearch Query DSL fragment.
#
# Because it's the same rule in both cases, the blotter list and the single
# trade view can never disagree about what a given user is allowed to see.
#
# `allow` is a disjunction (OR) of three independent visibility rules -- the
# "best case" visibility discussed: a trade is visible if ANY of them match.
# ---------------------------------------------------------------------------

default allow := false

# --- Rule 1: desk-based visibility -----------------------------------------
# A banker sees trades booked on their own desk. `input.resource.desk_id` is
# a plain column on the trades table, so this collapses to a trivial
# equality predicate under partial evaluation.
allow if {
	input.subject.role == "banker"
	input.resource.desk_id == input.subject.desk_id
}

# --- Rule 2: region-head visibility -----------------------------------------
# A region head sees every trade whose country is in their region. The
# region -> country-code mapping is NOT stored on the trade at all; it's
# reference data loaded into OPA's `data` document (see policies/data.json).
# `input.subject.region` is known input, so `data.regions[input.subject.region]`
# fully resolves at compile time into a concrete array, and the residual left
# for the (still unknown) resource is a plain `country_code in [...]` check --
# directly expressible as a SQL IN-list or an ES "terms" query.
allow if {
	input.subject.role == "region_head"
	input.resource.country_code in data.regions[input.subject.region]
}

# --- Rule 3: business-manager / team visibility -----------------------------
# A business manager sees trades booked by anyone on their team. Team
# membership is NOT static reference data here -- it's resolved by the app
# (simulating a call to an external HR/org API, cached) and passed in as part
# of `input.subject` on each request. Because `input.subject` is not in the
# `unknowns` list, it's just as "known" to the compiler as `data` would be,
# so this resolves the same way as rule 2: a concrete IN-list residual.
allow if {
	input.subject.role == "business_manager"
	input.resource.owner_id in input.subject.team_member_ids
}

# ---------------------------------------------------------------------------
# `reasons` is purely for the demo UI / point-of-call response -- it is NOT
# used by the bulk/push-down path, so it has no bearing on what the compiler
# needs to translate. It just tells you WHICH rule fired, for a human looking
# at a single "can I open this trade" response.
# ---------------------------------------------------------------------------
reasons contains "desk_match" if {
	input.subject.role == "banker"
	input.resource.desk_id == input.subject.desk_id
}

reasons contains "region_match" if {
	input.subject.role == "region_head"
	input.resource.country_code in data.regions[input.subject.region]
}

reasons contains "team_match" if {
	input.subject.role == "business_manager"
	input.resource.owner_id in input.subject.team_member_ids
}
