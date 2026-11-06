package example.quotes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The promise to teams, as a build check: a service needs no code for this to work. If anything
 * under src/main mentions the capture library, the build fails here.
 */
class NoCaptureCodeTest {

    @Test
    void theServiceHasNoCaptureCode() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            List<Path> offenders = files.filter(Files::isRegularFile).filter(NoCaptureCodeTest::mentionsLibrary).toList();
            assertThat(offenders).as("files under src/main that mention the capture library").isEmpty();
        }
    }

    private static boolean mentionsLibrary(Path file) {
        try {
            return Files.readString(file).toLowerCase(Locale.ROOT).contains("regtests");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
