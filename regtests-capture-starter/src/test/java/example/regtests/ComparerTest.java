package example.regtests;

import example.regtests.Comparer.Difference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComparerTest {

    private final Comparer comparer = new Comparer(Fixtures.CONFIG);

    @Test
    void timestampsAndUuidsAreNormalisedWithNoConfig() {
        String before = "{\"id\":\"3f2b8c1e-1111-4222-8333-444455556666\",\"at\":\"2026-01-02T03:04:05.678Z\"}";
        String after = "{\"id\":\"9a7d6e5f-aaaa-4bbb-8ccc-ddddeeeeffff\",\"at\":\"2027-11-12T13:14:15Z\"}";

        assertThat(comparer.compareJson("Response", Json.parseBody(before), Json.parseBody(after))).isEmpty();
    }

    @Test
    void numbersCompareByValue() {
        assertThat(comparer.compareJson("Response", Json.parseBody("{\"a\":10.50}"), Json.parseBody("{\"a\":10.5}"))).isEmpty();
    }

    @Test
    void aJsonPathInTheConfigFileIsIgnored() {
        assertThat(comparer.compareJson("Response", Json.parseBody("{\"number\":\"Q-1\"}"), Json.parseBody("{\"number\":\"Q-2\"}"))).isEmpty();
    }

    @Test
    void aRealDifferenceIsReadable() {
        List<Difference> found = comparer.compareJson("Response", Json.parseBody("{\"total\":10.50,\"gone\":1}"), Json.parseBody("{\"total\":12.00}"));

        assertThat(found).containsExactly(
                new Difference("Response", "$.gone", "1", "(absent)"),
                new Difference("Response", "$.total", "10.50", "12.00"));
    }

    @Test
    void parametersCompareByPositionAndSkipTheIgnoredOnes() {
        String sql = "{call save_thing(?, ?, ?)}";

        List<Difference> found = comparer.compareParams(sql, Fixtures.json(1, "run-a", "same"), Fixtures.json(2, "run-b", "same"));

        assertThat(found).containsExactly(new Difference("Database call save_thing", "parameter 1", "1", "2"));
    }
}
