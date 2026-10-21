package dev.porter.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.rule.Rule;
import dev.porter.rewrite.engine.NameTable;
import dev.porter.rewrite.engine.Rewriter;
import dev.porter.rewrite.engine.RuleTestRunner;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Acceptance: every rule under rules/ passes its own before/after tests. */
class RuleTestsTest {
    @Test
    void rulesExistAndAreUnique() {
        List<Rule> rules = TestSupport.allPublicRules();
        assertTrue(rules.size() > 100, "expected the seed rule set, got " + rules.size());
        assertEquals(rules.size(), rules.stream().map(Rule::id).distinct().count());
    }

    @TestFactory
    Stream<DynamicTest> everyRulePassesItsTests() {
        RuleTestRunner runner = new RuleTestRunner(NameTable.empty(), TestSupport.facts(), Rewriter.Options.defaults());
        return TestSupport.allPublicRules().stream().map(r -> DynamicTest.dynamicTest(r.id(), () -> {
            List<RuleTestRunner.Failure> f = runner.run(r);
            assertTrue(f.isEmpty(), () -> String.join("\n", f.stream().map(RuleTestRunner.Failure::message).toList()));
        }));
    }
}
