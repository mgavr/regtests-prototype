package example.regtests;

/** Two small helpers for SQL text. This is not a SQL parser. */
final class SqlText {

    private SqlText() {
    }

    static String normalize(String sql) {
        return sql.trim().replaceAll("\\s+", " ");
    }

    /** "{call some_procedure(?, ?)}" becomes "some_procedure". Other statements come back as they are, up to a bracket. */
    static String shortName(String sql) {
        String text = sql.replaceFirst("(?i)^\\{?\\s*call\\s+", "");
        int bracket = text.indexOf('(');
        return (bracket > 0 ? text.substring(0, bracket) : text).trim();
    }
}
