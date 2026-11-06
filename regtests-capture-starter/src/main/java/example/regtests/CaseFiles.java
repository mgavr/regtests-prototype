package example.regtests;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import example.regtests.Model.HttpCall;
import example.regtests.Model.Inbound;
import example.regtests.Model.JdbcCall;
import example.regtests.Model.RecordedCase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Pattern;

/** Reads and writes case folders under the recordings directory. */
final class CaseFiles {

    static final String INBOUND = "inbound.json";
    static final String OUTBOUND_HTTP = "outbound-http.json";
    static final String SQL = "sql.json";

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private final Path root;

    CaseFiles(String root) {
        this.root = Path.of(root);
    }

    /** A case name becomes a folder, so it comes from a header and is checked before use. */
    static boolean isSafeName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    boolean exists(String caseName) {
        return isSafeName(caseName) && Files.isRegularFile(root.resolve(caseName).resolve(INBOUND));
    }

    // REG-13: the case is saved, one folder with three files. Replay writes down what the service did through this same code, so a run has the same format. inbound.json goes last, so a folder that has it is complete.
    Path save(String caseName, JsonNode inbound, JsonNode http, JsonNode jdbc) {
        Path folder = root.resolve(caseName);
        try {
            Files.createDirectories(folder);
            write(folder.resolve(OUTBOUND_HTTP), http);
            write(folder.resolve(SQL), jdbc);
            write(folder.resolve(INBOUND), inbound);
            return folder;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save case " + caseName, e);
        }
    }

    RecordedCase load(String caseName) {
        Path folder = root.resolve(caseName);
        try {
            Inbound inbound = Json.MAPPER.readValue(folder.resolve(INBOUND).toFile(), Inbound.class);
            List<HttpCall> http = Json.MAPPER.readValue(folder.resolve(OUTBOUND_HTTP).toFile(), new TypeReference<>() {
            });
            List<JdbcCall> jdbc = Json.MAPPER.readValue(folder.resolve(SQL).toFile(), new TypeReference<>() {
            });
            return new RecordedCase(inbound, http, jdbc);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load case " + caseName, e);
        }
    }

    private static void write(Path file, JsonNode content) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, Json.MAPPER.writeValueAsString(content) + "\n");
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE); // A reader never sees half a file.
    }
}
