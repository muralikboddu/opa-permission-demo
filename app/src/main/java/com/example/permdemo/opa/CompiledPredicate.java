package com.example.permdemo.opa;

import java.util.List;

/**
 * The result of translating OPA's partial-evaluation output for
 * `data.trades.authz.allow` into something a query layer can use.
 *
 * This mirrors the DNF (OR-of-ANDs) shape described in the design
 * discussion: `queries` from the Compile API is an array (OR'd) of arrays
 * of conditions (AND'd). We collapse the two edge cases explicitly so
 * callers never have to special-case "empty WHERE clause":
 *
 *   - AlwaysAllow: one of the OR-branches had zero conditions left after
 *     partial evaluation (e.g. a role that sees everything unconditionally).
 *     Skip building a predicate at all and return the whole page.
 *   - AlwaysDeny: OPA returned no queries at all -- nothing satisfies the
 *     policy for this subject. Skip the query entirely; return an empty page.
 *   - Conditional: the normal case. A real OR-of-AND predicate to push down.
 */
public sealed interface CompiledPredicate {

    record AlwaysAllow() implements CompiledPredicate {}

    record AlwaysDeny() implements CompiledPredicate {}

    record Conditional(List<List<Condition>> orOfAndConditions) implements CompiledPredicate {}

    record Condition(String field, Op op, List<String> values) {
        public String value() {
            return values.get(0);
        }
    }

    enum Op { EQ, NEQ, LT, LTE, GT, GTE, IN, NOT_IN }
}
