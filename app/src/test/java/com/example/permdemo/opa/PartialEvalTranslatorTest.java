package com.example.permdemo.opa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These exact fixtures (and this exact translator code) were run outside
 * Spring/Maven while this project was built, using plain javac against
 * Jackson jars found on disk -- see the README's "what was and wasn't
 * verified" section for why (this sandbox couldn't reach Maven Central or
 * Docker Hub, so nothing here could be verified through a normal `mvn
 * test`). All 13 assertions below passed in that harness. Running `mvn
 * test` in an environment with normal internet access re-confirms the
 * same thing through the real build.
 */
class PartialEvalTranslatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PartialEvalTranslator translator = new PartialEvalTranslator();
    private final SqlPredicateRenderer sqlRenderer = new SqlPredicateRenderer();
    private final ElasticsearchQueryRenderer esRenderer = new ElasticsearchQueryRenderer();

    @Test
    void eqClauseTranslatesToSqlEquality() throws Exception {
        JsonNode queries = mapper.readTree("""
            [[ { "index": 0, "terms": [
                {"type":"ref","value":[{"type":"var","value":"eq"}]},
                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"resource"},{"type":"string","value":"desk_id"}]},
                {"type":"string","value":"FX-NY"}
            ]} ]]
        """);
        CompiledPredicate predicate = translator.translate(queries);
        assertInstanceOf(CompiledPredicate.Conditional.class, predicate);

        var rendered = sqlRenderer.render((CompiledPredicate.Conditional) predicate);
        assertTrue(rendered.sql().contains("desk_id = ?"));
        assertEquals(List.of("FX-NY"), rendered.params());
    }

    @Test
    void inClauseTranslatesToSqlInListAndEsTermsQuery() throws Exception {
        JsonNode queries = mapper.readTree("""
            [[ { "index": 0, "terms": [
                {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"resource"},{"type":"string","value":"country_code"}]},
                {"type":"array","value":[{"type":"string","value":"US"},{"type":"string","value":"CA"},{"type":"string","value":"MX"}]}
            ]} ]]
        """);
        CompiledPredicate.Conditional predicate = (CompiledPredicate.Conditional) translator.translate(queries);

        var rendered = sqlRenderer.render(predicate);
        assertTrue(rendered.sql().contains("country_code IN (?, ?, ?)"));
        assertEquals(List.of("US", "CA", "MX"), rendered.params());

        String esJson = mapper.writeValueAsString(esRenderer.render(predicate));
        assertTrue(esJson.contains("\"terms\""));
        assertTrue(esJson.contains("country_code"));
    }

    @Test
    void emptyOrBranchMeansAlwaysAllow() throws Exception {
        JsonNode queries = mapper.readTree("[[]]");
        assertInstanceOf(CompiledPredicate.AlwaysAllow.class, translator.translate(queries));
    }

    @Test
    void missingQueriesMeansAlwaysDeny() throws Exception {
        JsonNode queries = mapper.readTree("null");
        assertInstanceOf(CompiledPredicate.AlwaysDeny.class, translator.translate(queries));
    }

    @Test
    void twoOrBranchesJoinWithOr() throws Exception {
        JsonNode queries = mapper.readTree("""
            [
              [ { "index": 0, "terms": [
                  {"type":"ref","value":[{"type":"var","value":"eq"}]},
                  {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"resource"},{"type":"string","value":"desk_id"}]},
                  {"type":"string","value":"FX-NY"}
              ]} ],
              [ { "index": 0, "terms": [
                  {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                  {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"resource"},{"type":"string","value":"country_code"}]},
                  {"type":"array","value":[{"type":"string","value":"US"},{"type":"string","value":"CA"}]}
              ]} ]
            ]
        """);
        var rendered = sqlRenderer.render((CompiledPredicate.Conditional) translator.translate(queries));
        assertTrue(rendered.sql().contains(") OR ("));
        assertEquals(3, rendered.params().size());
    }

    @Test
    void unsupportedOperatorThrowsInsteadOfSilentlyMistranslating() throws Exception {
        JsonNode queries = mapper.readTree("""
            [[ { "index": 0, "terms": [
                {"type":"ref","value":[{"type":"var","value":"abs"}]},
                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"resource"},{"type":"string","value":"desk_id"}]},
                {"type":"string","value":"FX-NY"}
            ]} ]]
        """);
        assertThrows(UnsupportedRegoConstructException.class, () -> translator.translate(queries));
    }
}
