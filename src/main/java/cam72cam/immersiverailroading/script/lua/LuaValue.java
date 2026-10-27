package cam72cam.immersiverailroading.script.lua;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Lua value. Tables keep insertion order so pairs() is stable.
 * Integral numbers used as keys collapse to the same entry (1 and 1.0).
 */
public final class LuaValue {
    public enum Kind { NIL, BOOLEAN, NUMBER, STRING, TABLE, FUNCTION }

    public static final LuaValue NIL = new LuaValue(Kind.NIL, null);
    public static final LuaValue TRUE = new LuaValue(Kind.BOOLEAN, Boolean.TRUE);
    public static final LuaValue FALSE = new LuaValue(Kind.BOOLEAN, Boolean.FALSE);

    public final Kind kind;
    final Object value;

    private LuaValue(Kind kind, Object value) {
        this.kind = kind;
        this.value = value;
    }

    public static LuaValue bool(boolean value) {
        return value ? TRUE : FALSE;
    }

    public static LuaValue number(double value) {
        return new LuaValue(Kind.NUMBER, value);
    }

    public static LuaValue string(String value) {
        return new LuaValue(Kind.STRING, value);
    }

    public static LuaValue table() {
        return new LuaValue(Kind.TABLE, new LuaTable());
    }

    public static LuaValue function(LuaRuntime.Closure closure) {
        return new LuaValue(Kind.FUNCTION, closure);
    }

    public static LuaValue function(LuaRuntime.Native nativeFn) {
        return new LuaValue(Kind.FUNCTION, nativeFn);
    }

    public boolean isNil() {
        return kind == Kind.NIL;
    }

    public boolean isTruthy() {
        return kind != Kind.NIL && !(kind == Kind.BOOLEAN && value == Boolean.FALSE);
    }

    public double asNumber(int line) throws LuaException {
        if (kind == Kind.NUMBER) {
            return (Double) value;
        }
        if (kind == Kind.STRING) {
            Double parsed = parseNumber((String) value);
            if (parsed != null) {
                return parsed;
            }
        }
        throw new LuaException("number expected, got " + typeName(), line);
    }

    public String asString() {
        switch (kind) {
            case NIL:
                return "nil";
            case BOOLEAN:
                return Boolean.TRUE.equals(value) ? "true" : "false";
            case NUMBER:
                return formatNumber((Double) value);
            case STRING:
                return (String) value;
            case TABLE:
                return "table: " + Integer.toHexString(System.identityHashCode(value));
            case FUNCTION:
                return "function: " + Integer.toHexString(System.identityHashCode(value));
            default:
                return "nil";
        }
    }

    public String typeName() {
        switch (kind) {
            case NIL:
                return "nil";
            case BOOLEAN:
                return "boolean";
            case NUMBER:
                return "number";
            case STRING:
                return "string";
            case TABLE:
                return "table";
            case FUNCTION:
                return "function";
            default:
                return "nil";
        }
    }

    public LuaTable table(int line) throws LuaException {
        if (kind != Kind.TABLE) {
            throw new LuaException("table expected, got " + typeName(), line);
        }
        return (LuaTable) value;
    }

    public static String formatNumber(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    public static Double parseNumber(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static Object keyOf(LuaValue key) {
        if (key.kind == Kind.NUMBER) {
            double number = (Double) key.value;
            if (!Double.isInfinite(number) && number == Math.rint(number)) {
                return (long) number;
            }
            return number;
        }
        if (key.kind == Kind.STRING) {
            return key.value;
        }
        if (key.kind == Kind.BOOLEAN) {
            return key.value;
        }
        return key;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LuaValue)) {
            return false;
        }
        LuaValue that = (LuaValue) other;
        if (kind != that.kind) {
            return false;
        }
        if (kind == Kind.NUMBER) {
            return ((Double) value).doubleValue() == ((Double) that.value).doubleValue();
        }
        if (kind == Kind.NIL) {
            return true;
        }
        if (kind == Kind.TABLE || kind == Kind.FUNCTION) {
            return value == that.value;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        if (kind == Kind.NUMBER) {
            double number = (Double) value;
            if (number == Math.rint(number) && !Double.isInfinite(number)) {
                return Long.hashCode((long) number);
            }
            return Double.hashCode(number);
        }
        return value == null ? 0 : value.hashCode();
    }

    public static final class LuaTable {
        private final Map<Object, LuaValue> entries = new LinkedHashMap<>();

        public LuaValue get(LuaValue key) {
            if (key == null || key.isNil()) {
                return NIL;
            }
            LuaValue found = entries.get(keyOf(key));
            return found == null ? NIL : found;
        }

        public void set(LuaValue key, LuaValue entry) throws LuaException {
            if (key == null || key.isNil()) {
                throw new LuaException("table index is nil");
            }
            if (key.kind == Kind.NUMBER && Double.isNaN((Double) key.value)) {
                throw new LuaException("table index is NaN");
            }
            Object boxed = keyOf(key);
            if (entry == null || entry.isNil()) {
                entries.remove(boxed);
            } else {
                entries.put(boxed, entry);
            }
        }

        public void set(String key, LuaValue entry) throws LuaException {
            set(LuaValue.string(key), entry);
        }

        public int length() {
            int count = 0;
            while (!get(LuaValue.number(count + 1)).isNil()) {
                count++;
                if (count > 1_000_000) {
                    break;
                }
            }
            return count;
        }

        public List<Map.Entry<Object, LuaValue>> pairs() {
            return new ArrayList<>(entries.entrySet());
        }

        public static LuaValue keyValue(Object key) {
            if (key instanceof Long) {
                return LuaValue.number(((Long) key).doubleValue());
            }
            if (key instanceof Double) {
                return LuaValue.number((Double) key);
            }
            if (key instanceof String) {
                return LuaValue.string((String) key);
            }
            if (key instanceof Boolean) {
                return LuaValue.bool((Boolean) key);
            }
            if (key instanceof LuaValue) {
                return (LuaValue) key;
            }
            return NIL;
        }
    }
}
