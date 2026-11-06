package example.regtests;

import com.fasterxml.jackson.databind.node.ArrayNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.SortedMap;

/** What the record and replay JDBC proxies have in common. */
final class Jdbc {

    private Jdbc() {
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(Jdbc.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    static boolean isObjectMethod(Method method) {
        return method.getDeclaringClass() == Object.class;
    }

    static Object objectMethod(Object proxy, Method method, Object[] args, String label) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> label;
        };
    }

    /** Notes setLong(1, x), setString(2, y) and the like. Returns true if this call was one. */
    static boolean noteParam(String name, Object[] args, SortedMap<Integer, Object> params) {
        if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer position) {
            params.put(position, name.equals("setNull") ? null : args[1]);
            return true;
        }
        return false;
    }

    /** IN values by position, with null in the gaps that OUT parameters leave. */
    static ArrayNode paramsAsJson(SortedMap<Integer, Object> params) {
        ArrayNode json = Json.MAPPER.createArrayNode();
        if (!params.isEmpty()) {
            for (int position = 1; position <= params.lastKey(); position++) {
                json.add(Json.toNode(params.get(position)));
            }
        }
        return json;
    }

    /** The SQL of an execute call: its own argument on a plain Statement, otherwise the prepared text. */
    static String sqlOf(Object[] args, String preparedSql) {
        return args != null && args.length > 0 && args[0] instanceof String text ? text : preparedSql;
    }
}
