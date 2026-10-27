package cam72cam.immersiverailroading.script.lua;

import cam72cam.immersiverailroading.model.TextureBudget;
import cam72cam.immersiverailroading.registry.PackLoadReport;

import java.util.List;

/**
 * Standalone checks for the script interpreter, texture budget, and pack report.
 * Run with {@code java cam72cam.immersiverailroading.script.lua.LuaSelfTest}.
 */
public final class LuaSelfTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        arithmetic();
        controlFlow();
        functions();
        tables();
        events();
        errors();
        textureBudget();
        packReport();
        if (failures > 0) {
            throw new AssertionError(failures + " check(s) failed");
        }
        System.out.println("LuaSelfTest ok");
    }

    private static void arithmetic() throws Exception {
        LuaRuntime rt = LuaRuntime.compile("function add(a, b) return a + b * 2 end");
        eq(4, rt.call("add", List.of(LuaValue.number(2), LuaValue.number(1))).get(0).asNumber(0), "add");
        LuaRuntime mod = LuaRuntime.compile("function m() return -7 % 3 end");
        eq(2, mod.call("m", List.of()).get(0).asNumber(0), "mod");
        LuaRuntime cat = LuaRuntime.compile("function c() return 'a' .. 1 .. 'b' end");
        eq("a1b", cat.call("c", List.of()).get(0).asString(), "concat");
    }

    private static void controlFlow() throws Exception {
        LuaRuntime rt = LuaRuntime.compile("""
                function classify(n)
                  local result = 'zero'
                  if n > 0 then
                    result = 'pos'
                  elseif n < 0 then
                    result = 'neg'
                  end
                  local sum = 0
                  for i = 1, 4 do
                    sum = sum + i
                  end
                  local i = 0
                  while i < 3 do
                    i = i + 1
                  end
                  return result, sum, i
                end
                """);
        List<LuaValue> out = rt.call("classify", List.of(LuaValue.number(-2)));
        eq("neg", out.get(0).asString(), "sign");
        eq(10, out.get(1).asNumber(0), "for sum");
        eq(3, out.get(2).asNumber(0), "while");
    }

    private static void functions() throws Exception {
        LuaRuntime rt = LuaRuntime.compile("""
                local function fact(n)
                  if n <= 1 then return 1 end
                  return n * fact(n - 1)
                end
                function run()
                  return fact(5)
                end
                """);
        eq(120, rt.call("run", List.of()).get(0).asNumber(0), "fact");
    }

    private static void tables() throws Exception {
        LuaRuntime rt = LuaRuntime.compile("""
                function run()
                  local t = {a = 1, b = 2, 10, 20}
                  local sum = 0
                  for k, v in pairs(t) do
                    if type(v) == 'number' then sum = sum + v end
                  end
                  local listed = 0
                  for i, v in ipairs(t) do
                    listed = listed + v
                  end
                  t.a = nil
                  return sum, listed, t.a == nil
                end
                """);
        List<LuaValue> out = rt.call("run", List.of());
        eq(33, out.get(0).asNumber(0), "pairs sum");
        eq(30, out.get(1).asNumber(0), "ipairs");
        eq(true, out.get(2).isTruthy(), "nil field");
    }

    private static void events() throws Exception {
        LuaRuntime rt = LuaRuntime.compile("""
                local seen = 0
                function on_tick(stock)
                  seen = seen + 1
                  if stock.speed() > 5 then
                    stock.rotate_group('FAN', 0, stock.tick() * 3, 0)
                  end
                  return seen
                end
                """);
        LuaValue stock = LuaValue.table();
        stock.table(0).set("speed", LuaValue.function((runtime, args) -> List.of(LuaValue.number(12))));
        stock.table(0).set("tick", LuaValue.function((runtime, args) -> List.of(LuaValue.number(4))));
        stock.table(0).set("rotate_group", LuaValue.function((runtime, args) -> {
            eq("FAN", args.get(0).asString(), "group");
            eq(12, args.get(2).asNumber(0), "yaw");
            return List.of();
        }));
        eq(1, rt.call("on_tick", List.of(stock)).get(0).asNumber(0), "event");
    }

    private static void errors() {
        try {
            LuaRuntime.compile("function broken(\n  return\n");
            fail("parser should reject unfinished function");
        } catch (LuaException ex) {
            if (ex.line <= 0 || !ex.format("cab.lua").contains("cab.lua")) {
                fail("error should name the line and script: " + ex.format("cab.lua"));
            }
        }
        try {
            LuaRuntime rt = LuaRuntime.compile("function loop() while true do end end", 1000);
            rt.call("loop", List.of());
            fail("runaway loop should stop");
        } catch (LuaException ex) {
            if (!ex.getMessage().contains("exceeded")) {
                fail("unexpected limit message " + ex.getMessage());
            }
        }
    }

    private static void textureBudget() {
        List<Integer> drop = TextureBudget.sheetsToDrop(List.of(4096, 1024, 512), 1024);
        if (!drop.equals(List.of(4096))) {
            fail("budget should drop only the 4096 sheet, got " + drop);
        }
        if (!TextureBudget.sheetsToDrop(List.of(512), 1024).isEmpty()) {
            fail("a sheet already inside the budget must stay");
        }
        List<Integer> onlyFull = TextureBudget.sheetsToDrop(List.of(4096), 1024);
        if (!onlyFull.isEmpty()) {
            fail("the only sheet must stay even when it is larger than the budget");
        }
        List<Integer> lods = TextureBudget.lodSizes(4096, true, 1024, 512);
        if (!lods.contains(1024) || !lods.contains(512)) {
            fail("compact mode should request the near and far sheets");
        }
        if (!TextureBudget.lodSizes(1024, true, 1024, 512).equals(List.of(512))) {
            fail("a budget equal to the device max must not duplicate the full atlas");
        }
    }

    private static void packReport() {
        PackLoadReport report = new PackLoadReport();
        report.loaded("rolling_stock/freight/box.json", "Example Pack");
        report.skipped("secret_loco", "blacklisted");
        report.failed("rolling_stock/locomotives/missing.json", "North Pack", "file not found", null);
        String text = report.format();
        if (!text.contains("FAILED") || !text.contains("file not found") || !text.contains("North Pack") || !text.contains("loaded 1")) {
            fail("pack report missing explicit failure details:\n" + text);
        }
    }

    private static void eq(double expected, double actual, String label) {
        if (Double.compare(expected, actual) != 0) {
            fail(label + " expected " + expected + " but was " + actual);
        }
    }

    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            fail(label + " expected " + expected + " but was " + actual);
        }
    }

    private static void eq(boolean expected, boolean actual, String label) {
        if (expected != actual) {
            fail(label + " expected " + expected + " but was " + actual);
        }
    }

    private static void fail(String message) {
        failures++;
        System.err.println(message);
    }
}
