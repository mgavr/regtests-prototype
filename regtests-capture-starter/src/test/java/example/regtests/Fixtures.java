package example.regtests;

import com.fasterxml.jackson.databind.node.ArrayNode;
import example.regtests.Model.HttpCall;
import example.regtests.Model.Inbound;
import example.regtests.Model.InboundRequest;
import example.regtests.Model.InboundResponse;
import example.regtests.Model.JdbcCall;
import example.regtests.Model.RecordedCase;
import example.regtests.ServiceConfig.Personal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class Fixtures {

    static final ServiceConfig CONFIG = new ServiceConfig(
            new Personal(List.of("nickname"), Map.of("get_thing", List.of(2))));

    private Fixtures() {
    }

    static Regtests regtests(RegtestsMode mode, Path recordings) {
        Regtests regtests = new Regtests(mode, new Events(null), new Scrubber("test-salt", CONFIG),
                new CaseFiles(recordings.toString()), new CaseFiles(recordings.resolve("actual").toString()),
                Duration.ofSeconds(2));
        OutboundHttp.activate(regtests);
        return regtests;
    }

    static ReplaySession replay(Regtests regtests, List<HttpCall> http, List<JdbcCall> jdbc) {
        Inbound inbound = new Inbound("a-case", "id", "now", new InboundRequest("POST", "/x", null, null, null),
                new InboundResponse(200, "application/json", Json.parseBody("{\"total\":10.50}")), null);
        return new ReplaySession("run-1", new RecordedCase(inbound, http, jdbc), regtests.scrubber());
    }

    static ArrayNode json(Object... values) {
        ArrayNode array = Json.MAPPER.createArrayNode();
        for (Object value : values) {
            array.add(Json.toNode(value));
        }
        return array;
    }
}
