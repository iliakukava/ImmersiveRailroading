package cam72cam.immersiverailroading.script.lua;

/**
 * A parse or runtime failure in a model script, with a 1-based source line when one is known.
 */
public class LuaException extends Exception {
    public final int line;

    public LuaException(String message) {
        this(message, 0);
    }

    public LuaException(String message, int line) {
        super(message);
        this.line = line;
    }

    public LuaException(String message, int line, Throwable cause) {
        super(message, cause);
        this.line = line;
    }

    public String format(String scriptName) {
        String where = scriptName == null || scriptName.isEmpty() ? "script" : scriptName;
        if (line > 0) {
            return where + ":" + line + ": " + getMessage();
        }
        return where + ": " + getMessage();
    }
}
