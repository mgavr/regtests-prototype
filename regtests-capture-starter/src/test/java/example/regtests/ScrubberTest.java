package example.regtests;

import com.fasterxml.jackson.databind.JsonNode;
import example.regtests.Model.SqlColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScrubberTest {

    private final Scrubber scrubber = new Scrubber("test-salt", Fixtures.CONFIG);

    @Test
    void theSameValueGetsTheSameMaskInTrafficAndInRows() {
        JsonNode traffic = scrubber.json(Json.parseBody("{\"fullName\":\"Sample Value\",\"amount\":5}"));
        JsonNode row = scrubber.row(List.of(new SqlColumn("FULL_NAME", "java.lang.String", 12)), Fixtures.json("Sample Value"));

        assertThat(traffic.get("fullName").asText()).startsWith(Scrubber.PREFIX).isEqualTo(row.get(0).asText());
        assertThat(traffic.get("amount").asInt()).isEqualTo(5);
    }

    @Test
    void maskingIsDeterministicAndIdempotent() {
        String masked = scrubber.mask("Sample Value");

        assertThat(scrubber.mask("Sample Value")).isEqualTo(masked);
        assertThat(scrubber.mask(masked)).isEqualTo(masked);
    }

    @Test
    void fieldsFromTheConfigFileAddToTheCommonOnes() {
        JsonNode masked = scrubber.json(Json.parseBody("{\"nickname\":\"abc\",\"phone\":\"555\",\"city\":\"xyz\"}"));

        assertThat(masked.get("nickname").asText()).startsWith(Scrubber.PREFIX);
        assertThat(masked.get("phone").asText()).startsWith(Scrubber.PREFIX);
        assertThat(masked.get("city").asText()).isEqualTo("xyz");
    }

    @Test
    void parametersAreMaskedByPositionForTheNamedStatementOnly() {
        JsonNode masked = scrubber.params("{call get_thing(?, ?)}", Fixtures.json("keep", "hide"));
        JsonNode other = scrubber.params("{call other(?, ?)}", Fixtures.json("keep", "hide"));

        assertThat(masked.get(0).asText()).isEqualTo("keep");
        assertThat(masked.get(1).asText()).startsWith(Scrubber.PREFIX);
        assertThat(other.get(1).asText()).isEqualTo("hide");
    }
}
