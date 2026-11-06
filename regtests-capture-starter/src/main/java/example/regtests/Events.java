package example.regtests;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes one JSON line per real step to a local file, which the demo console tails. A file rather
 * than a socket, so that replay makes no network call of any kind, not even to report on itself.
 * Unset means every call here does nothing.
 */
final class Events {

    private static final Logger log = LoggerFactory.getLogger(Events.class);
    private static final String SOURCE_ROOT = "regtests-capture-starter/src/main/java/";

    private final Path file;

    Events(String path) {
        this.file = path == null || path.isBlank() ? null : Path.of(path);
    }

    void emit(String pointer, Class<?> where, String title, String detail) {
        emit(pointer, where, title, detail, null);
    }

    void emit(String pointer, Class<?> where, String title, String detail, Object data) {
        if (file == null) {
            return;
        }
        ObjectNode line = Json.MAPPER.createObjectNode();
        line.put("ts", System.currentTimeMillis());
        line.put("source", "library");
        line.put("reg", pointer);
        line.put("file", sourceFile(where));
        line.put("title", title);
        line.put("detail", detail);
        line.put("thread", Thread.currentThread().getName());
        if (data != null) {
            line.set("data", Json.MAPPER.valueToTree(data));
        }
        write(Json.compact(line));
    }

    private synchronized void write(String line) {
        try {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not write an event to {}: {}", file, e.toString());
        }
    }

    private static String sourceFile(Class<?> where) {
        String name = where.getName();
        int nested = name.indexOf('$');
        String outer = nested < 0 ? name : name.substring(0, nested);
        return SOURCE_ROOT + outer.replace('.', '/') + ".java";
    }
}
