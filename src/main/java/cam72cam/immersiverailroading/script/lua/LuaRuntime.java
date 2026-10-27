package cam72cam.immersiverailroading.script.lua;

import cam72cam.immersiverailroading.script.lua.LuaParser.Binary;
import cam72cam.immersiverailroading.script.lua.LuaParser.Block;
import cam72cam.immersiverailroading.script.lua.LuaParser.BreakStmt;
import cam72cam.immersiverailroading.script.lua.LuaParser.Call;
import cam72cam.immersiverailroading.script.lua.LuaParser.CallStmt;
import cam72cam.immersiverailroading.script.lua.LuaParser.DoStmt;
import cam72cam.immersiverailroading.script.lua.LuaParser.Expr;
import cam72cam.immersiverailroading.script.lua.LuaParser.FuncExpr;
import cam72cam.immersiverailroading.script.lua.LuaParser.GenericFor;
import cam72cam.immersiverailroading.script.lua.LuaParser.IfStmt;
import cam72cam.immersiverailroading.script.lua.LuaParser.Index;
import cam72cam.immersiverailroading.script.lua.LuaParser.Literal;
import cam72cam.immersiverailroading.script.lua.LuaParser.LocalAssign;
import cam72cam.immersiverailroading.script.lua.LuaParser.LocalFunction;
import cam72cam.immersiverailroading.script.lua.LuaParser.Name;
import cam72cam.immersiverailroading.script.lua.LuaParser.NumericFor;
import cam72cam.immersiverailroading.script.lua.LuaParser.RepeatStmt;
import cam72cam.immersiverailroading.script.lua.LuaParser.ReturnStmt;
import cam72cam.immersiverailroading.script.lua.LuaParser.Stmt;
import cam72cam.immersiverailroading.script.lua.LuaParser.TableExpr;
import cam72cam.immersiverailroading.script.lua.LuaParser.Unary;
import cam72cam.immersiverailroading.script.lua.LuaParser.WhileStmt;
import cam72cam.immersiverailroading.script.lua.LuaValue.LuaTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tree-walking interpreter for {@link LuaParser} chunks.
 * Each event call is capped so a runaway loop cannot stall the game thread.
 */
public final class LuaRuntime {
    public static final int DEFAULT_STEP_LIMIT = 250_000;
    private static final int MAX_STRING = 1_000_000;

    private final Block chunk;
    private final LuaTable globals = new LuaTable();
    private final int stepLimit;
    private Scope scope = new Scope(null);
    private int steps;
    private Consumer<String> printer = System.out::println;

    private LuaRuntime(Block chunk, int stepLimit) {
        this.chunk = chunk;
        this.stepLimit = stepLimit;
        installStandardLibrary();
    }

    public static LuaRuntime compile(String source) throws LuaException {
        return compile(source, DEFAULT_STEP_LIMIT);
    }

    public static LuaRuntime compile(String source, int stepLimit) throws LuaException {
        LuaRuntime runtime = new LuaRuntime(LuaParser.parse(source), stepLimit);
        runtime.exec(runtime.chunk);
        return runtime;
    }

    public LuaTable globals() {
        return globals;
    }

    public void setPrinter(Consumer<String> printer) {
        this.printer = printer == null ? message -> { } : printer;
    }

    public LuaValue get(String name) {
        return globals.get(LuaValue.string(name));
    }

    public void set(String name, LuaValue value) throws LuaException {
        globals.set(name, value);
    }

    public List<LuaValue> call(String name, List<LuaValue> args) throws LuaException {
        LuaValue function = get(name);
        if (function.isNil()) {
            return Collections.singletonList(LuaValue.NIL);
        }
        return call(function, args, 0);
    }

    public List<LuaValue> call(LuaValue function, List<LuaValue> args, int line) throws LuaException {
        steps = 0;
        return invoke(function, args == null ? Collections.emptyList() : args, line);
    }

    private void installStandardLibrary() {
        try {
            set("print", LuaValue.function((rt, args) -> {
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) {
                        builder.append('\t');
                    }
                    builder.append(args.get(i).asString());
                }
                rt.printer.accept(builder.toString());
                return Collections.emptyList();
            }));
            set("type", LuaValue.function((rt, args) -> Collections.singletonList(LuaValue.string(arg(args, 0).typeName()))));
            set("tostring", LuaValue.function((rt, args) -> Collections.singletonList(LuaValue.string(arg(args, 0).asString()))));
            set("tonumber", LuaValue.function((rt, args) -> {
                LuaValue value = arg(args, 0);
                if (value.kind == LuaValue.Kind.NUMBER) {
                    return Collections.singletonList(value);
                }
                Double parsed = LuaValue.parseNumber(value.asString());
                return Collections.singletonList(parsed == null ? LuaValue.NIL : LuaValue.number(parsed));
            }));
            set("assert", LuaValue.function((rt, args) -> {
                if (!arg(args, 0).isTruthy()) {
                    String message = args.size() > 1 ? args.get(1).asString() : "assertion failed";
                    throw new LuaException(message);
                }
                return args;
            }));
            set("error", LuaValue.function((rt, args) -> {
                throw new LuaException(arg(args, 0).asString());
            }));
            set("pcall", LuaValue.function((rt, args) -> {
                if (args.isEmpty()) {
                    return List.of(LuaValue.FALSE, LuaValue.string("value expected"));
                }
                try {
                    List<LuaValue> result = rt.invoke(args.get(0), args.subList(1, args.size()), 0);
                    List<LuaValue> wrapped = new ArrayList<>();
                    wrapped.add(LuaValue.TRUE);
                    wrapped.addAll(result);
                    return wrapped;
                } catch (LuaException ex) {
                    return List.of(LuaValue.FALSE, LuaValue.string(ex.getMessage()));
                }
            }));
            LuaValue next = LuaValue.function((rt, args) -> {
                LuaTable table = arg(args, 0).table(0);
                Object previous = args.size() > 1 && !args.get(1).isNil() ? LuaValue.keyOf(args.get(1)) : null;
                boolean take = previous == null;
                for (Map.Entry<Object, LuaValue> entry : table.pairs()) {
                    if (take) {
                        return List.of(LuaTable.keyValue(entry.getKey()), entry.getValue());
                    }
                    if (entry.getKey().equals(previous)) {
                        take = true;
                    }
                }
                return List.of(LuaValue.NIL);
            });
            set("next", next);
            set("pairs", LuaValue.function((rt, args) -> List.of(next, arg(args, 0), LuaValue.NIL)));
            set("ipairs", LuaValue.function((rt, args) -> {
                LuaValue table = arg(args, 0);
                LuaValue iterator = LuaValue.function((inner, innerArgs) -> {
                    LuaTable source = innerArgs.get(0).table(0);
                    int index = (int) innerArgs.get(1).asNumber(0) + 1;
                    LuaValue value = source.get(LuaValue.number(index));
                    if (value.isNil()) {
                        return List.of(LuaValue.NIL);
                    }
                    return List.of(LuaValue.number(index), value);
                });
                return List.of(iterator, table, LuaValue.number(0));
            }));

            LuaValue math = LuaValue.table();
            LuaTable mathTable = math.table(0);
            mathTable.set("abs", nativeNumber(Math::abs));
            mathTable.set("floor", nativeNumber(Math::floor));
            mathTable.set("ceil", nativeNumber(Math::ceil));
            mathTable.set("sqrt", nativeNumber(Math::sqrt));
            mathTable.set("sin", nativeNumber(Math::sin));
            mathTable.set("cos", nativeNumber(Math::cos));
            mathTable.set("rad", nativeNumber(Math::toRadians));
            mathTable.set("deg", nativeNumber(Math::toDegrees));
            mathTable.set("min", LuaValue.function((rt, args) -> Collections.singletonList(LuaValue.number(reduce(args, Math::min)))));
            mathTable.set("max", LuaValue.function((rt, args) -> Collections.singletonList(LuaValue.number(reduce(args, Math::max)))));
            mathTable.set("pi", LuaValue.number(Math.PI));
            mathTable.set("huge", LuaValue.number(Double.POSITIVE_INFINITY));
            set("math", math);

            LuaValue stringLib = LuaValue.table();
            LuaTable strings = stringLib.table(0);
            strings.set("len", LuaValue.function((rt, args) -> Collections.singletonList(LuaValue.number(arg(args, 0).asString().length()))));
            strings.set("sub", LuaValue.function((rt, args) -> {
                String text = arg(args, 0).asString();
                int start = (int) arg(args, 1).asNumber(0);
                int end = args.size() > 2 ? (int) args.get(2).asNumber(0) : text.length();
                start = luaIndex(start, text.length());
                end = luaIndex(end, text.length());
                if (start > end || start > text.length()) {
                    return Collections.singletonList(LuaValue.string(""));
                }
                return Collections.singletonList(LuaValue.string(text.substring(start - 1, Math.min(end, text.length()))));
            }));
            strings.set("format", LuaValue.function((rt, args) -> Collections.singletonList(LuaValue.string(format(args)))));
            strings.set("lower", LuaValue.function((rt, args) -> Collections.singletonList(LuaValue.string(arg(args, 0).asString().toLowerCase(Locale.ROOT)))));
            strings.set("upper", LuaValue.function((rt, args) -> Collections.singletonList(LuaValue.string(arg(args, 0).asString().toUpperCase(Locale.ROOT)))));
            set("string", stringLib);

            LuaValue tableLib = LuaValue.table();
            LuaTable tables = tableLib.table(0);
            tables.set("insert", LuaValue.function((rt, args) -> {
                LuaTable table = arg(args, 0).table(0);
                if (args.size() < 2) {
                    return Collections.emptyList();
                }
                if (args.size() == 2) {
                    table.set(LuaValue.number(table.length() + 1), args.get(1));
                } else {
                    int index = (int) args.get(1).asNumber(0);
                    int length = table.length();
                    for (int i = length; i >= index; i--) {
                        table.set(LuaValue.number(i + 1), table.get(LuaValue.number(i)));
                    }
                    table.set(LuaValue.number(index), args.get(2));
                }
                return Collections.emptyList();
            }));
            tables.set("remove", LuaValue.function((rt, args) -> {
                LuaTable table = arg(args, 0).table(0);
                int length = table.length();
                int index = args.size() > 1 ? (int) args.get(1).asNumber(0) : length;
                LuaValue removed = table.get(LuaValue.number(index));
                for (int i = index; i < length; i++) {
                    table.set(LuaValue.number(i), table.get(LuaValue.number(i + 1)));
                }
                table.set(LuaValue.number(length), LuaValue.NIL);
                return Collections.singletonList(removed);
            }));
            set("table", tableLib);
        } catch (LuaException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static LuaValue nativeNumber(java.util.function.DoubleUnaryOperator op) {
        return LuaValue.function((rt, args) -> Collections.singletonList(LuaValue.number(op.applyAsDouble(arg(args, 0).asNumber(0)))));
    }

    private static double reduce(List<LuaValue> args, java.util.function.DoubleBinaryOperator op) throws LuaException {
        if (args.isEmpty()) {
            throw new LuaException("value expected");
        }
        double value = args.get(0).asNumber(0);
        for (int i = 1; i < args.size(); i++) {
            value = op.applyAsDouble(value, args.get(i).asNumber(0));
        }
        return value;
    }

    private static int luaIndex(int index, int length) {
        if (index < 0) {
            index = length + index + 1;
        }
        return Math.max(index, 1);
    }

    private static String format(List<LuaValue> args) throws LuaException {
        if (args.isEmpty()) {
            return "";
        }
        String pattern = args.get(0).asString();
        StringBuilder out = new StringBuilder();
        int arg = 1;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c != '%' || i + 1 >= pattern.length()) {
                out.append(c);
                continue;
            }
            char spec = pattern.charAt(++i);
            if (spec == '%') {
                out.append('%');
                continue;
            }
            if (arg >= args.size()) {
                throw new LuaException("bad argument #" + arg + " to 'format'");
            }
            LuaValue value = args.get(arg++);
            switch (spec) {
                case 's':
                    out.append(value.asString());
                    break;
                case 'd':
                case 'i':
                    out.append((long) value.asNumber(0));
                    break;
                case 'f':
                    out.append(value.asNumber(0));
                    break;
                default:
                    throw new LuaException("invalid format % " + spec);
            }
        }
        return limit(out.toString(), 0);
    }

    private void exec(Block block) throws LuaException {
        for (Stmt statement : block.statements) {
            step(statement.line);
            exec(statement);
        }
    }

    private void exec(Stmt statement) throws LuaException {
        if (statement instanceof CallStmt) {
            eval(((CallStmt) statement).call);
        } else if (statement instanceof cam72cam.immersiverailroading.script.lua.LuaParser.Assign) {
            cam72cam.immersiverailroading.script.lua.LuaParser.Assign assign =
                    (cam72cam.immersiverailroading.script.lua.LuaParser.Assign) statement;
            List<LuaValue> values = evalList(assign.values);
            for (int i = 0; i < assign.targets.size(); i++) {
                assignTo(assign.targets.get(i), valueAt(values, i));
            }
        } else if (statement instanceof LocalAssign) {
            LocalAssign local = (LocalAssign) statement;
            List<LuaValue> values = evalList(local.values);
            for (int i = 0; i < local.names.size(); i++) {
                Cell cell = new Cell(valueAt(values, i));
                scope.locals.put(local.names.get(i), cell);
            }
        } else if (statement instanceof LocalFunction) {
            LocalFunction local = (LocalFunction) statement;
            Cell cell = new Cell(LuaValue.NIL);
            scope.locals.put(local.name, cell);
            cell.value = closure(local.function);
        } else if (statement instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) statement;
            boolean taken = false;
            for (int i = 0; i < ifStmt.conditions.size(); i++) {
                if (first(ifStmt.conditions.get(i)).isTruthy()) {
                    exec(ifStmt.bodies.get(i));
                    taken = true;
                    break;
                }
            }
            if (!taken && ifStmt.elseBody != null) {
                exec(ifStmt.elseBody);
            }
        } else if (statement instanceof WhileStmt) {
            WhileStmt loop = (WhileStmt) statement;
            while (first(loop.condition).isTruthy()) {
                step(loop.line);
                if (!runLoop(loop.body)) {
                    break;
                }
            }
        } else if (statement instanceof RepeatStmt) {
            RepeatStmt loop = (RepeatStmt) statement;
            do {
                step(loop.line);
                if (!runLoop(loop.body)) {
                    break;
                }
            } while (!first(loop.condition).isTruthy());
        } else if (statement instanceof NumericFor) {
            NumericFor loop = (NumericFor) statement;
            double start = first(loop.start).asNumber(loop.line);
            double stop = first(loop.stop).asNumber(loop.line);
            double step = loop.step == null ? 1 : first(loop.step).asNumber(loop.line);
            if (step == 0) {
                throw new LuaException("'for' step is zero", loop.line);
            }
            for (double i = start; step > 0 ? i <= stop : i >= stop; i += step) {
                step(loop.line);
                Scope inner = push();
                try {
                    inner.locals.put(loop.name, new Cell(LuaValue.number(i)));
                    if (!runLoop(loop.body)) {
                        break;
                    }
                } finally {
                    pop();
                }
            }
        } else if (statement instanceof GenericFor) {
            GenericFor loop = (GenericFor) statement;
            List<LuaValue> iterator = evalList(loop.iterators);
            LuaValue function = valueAt(iterator, 0);
            LuaValue state = valueAt(iterator, 1);
            LuaValue control = valueAt(iterator, 2);
            while (true) {
                step(loop.line);
                List<LuaValue> produced = invoke(function, List.of(state, control), loop.line);
                if (valueAt(produced, 0).isNil()) {
                    break;
                }
                control = produced.get(0);
                Scope inner = push();
                try {
                    for (int i = 0; i < loop.names.size(); i++) {
                        inner.locals.put(loop.names.get(i), new Cell(valueAt(produced, i)));
                    }
                    if (!runLoop(loop.body)) {
                        break;
                    }
                } finally {
                    pop();
                }
            }
        } else if (statement instanceof DoStmt) {
            Scope inner = push();
            try {
                exec(((DoStmt) statement).body);
            } finally {
                pop();
            }
            if (inner == null) {
                return;
            }
        } else if (statement instanceof ReturnStmt) {
            throw new LuaReturn(evalList(((ReturnStmt) statement).values));
        } else if (statement instanceof BreakStmt) {
            throw new LuaBreak();
        }
    }

    private boolean runLoop(Block body) throws LuaException {
        try {
            exec(body);
            return true;
        } catch (LuaBreak ex) {
            return false;
        }
    }

    private List<LuaValue> eval(Expr expr) throws LuaException {
        step(expr.line);
        if (expr instanceof Literal) {
            return Collections.singletonList(((Literal) expr).value);
        }
        if (expr instanceof Name) {
            return Collections.singletonList(lookup(((Name) expr).name));
        }
        if (expr instanceof Unary) {
            Unary unary = (Unary) expr;
            LuaValue value = first(unary.expr);
            switch (unary.op) {
                case "not":
                    return Collections.singletonList(LuaValue.bool(!value.isTruthy()));
                case "-":
                    return Collections.singletonList(LuaValue.number(-value.asNumber(unary.line)));
                case "#":
                    if (value.kind == LuaValue.Kind.STRING) {
                        return Collections.singletonList(LuaValue.number(value.asString().length()));
                    }
                    return Collections.singletonList(LuaValue.number(value.table(unary.line).length()));
                default:
                    throw new LuaException("unknown operator " + unary.op, unary.line);
            }
        }
        if (expr instanceof Binary) {
            Binary binary = (Binary) expr;
            if ("and".equals(binary.op)) {
                LuaValue left = first(binary.left);
                return Collections.singletonList(left.isTruthy() ? first(binary.right) : left);
            }
            if ("or".equals(binary.op)) {
                LuaValue left = first(binary.left);
                return Collections.singletonList(left.isTruthy() ? left : first(binary.right));
            }
            if ("..".equals(binary.op)) {
                return Collections.singletonList(LuaValue.string(limit(first(binary.left).asString() + first(binary.right).asString(), binary.line)));
            }
            LuaValue left = first(binary.left);
            LuaValue right = first(binary.right);
            switch (binary.op) {
                case "+":
                    return Collections.singletonList(LuaValue.number(left.asNumber(binary.line) + right.asNumber(binary.line)));
                case "-":
                    return Collections.singletonList(LuaValue.number(left.asNumber(binary.line) - right.asNumber(binary.line)));
                case "*":
                    return Collections.singletonList(LuaValue.number(left.asNumber(binary.line) * right.asNumber(binary.line)));
                case "/":
                    return Collections.singletonList(LuaValue.number(left.asNumber(binary.line) / right.asNumber(binary.line)));
                case "%":
                    double divisor = right.asNumber(binary.line);
                    double result = left.asNumber(binary.line) % divisor;
                    if (result != 0 && Math.signum(result) != Math.signum(divisor)) {
                        result += divisor;
                    }
                    return Collections.singletonList(LuaValue.number(result));
                case "^":
                    return Collections.singletonList(LuaValue.number(Math.pow(left.asNumber(binary.line), right.asNumber(binary.line))));
                case "==":
                    return Collections.singletonList(LuaValue.bool(left.equals(right)));
                case "~=":
                    return Collections.singletonList(LuaValue.bool(!left.equals(right)));
                case "<":
                    return Collections.singletonList(LuaValue.bool(compare(left, right, binary.line) < 0));
                case ">":
                    return Collections.singletonList(LuaValue.bool(compare(left, right, binary.line) > 0));
                case "<=":
                    return Collections.singletonList(LuaValue.bool(compare(left, right, binary.line) <= 0));
                case ">=":
                    return Collections.singletonList(LuaValue.bool(compare(left, right, binary.line) >= 0));
                default:
                    throw new LuaException("unknown operator " + binary.op, binary.line);
            }
        }
        if (expr instanceof TableExpr) {
            LuaValue table = LuaValue.table();
            LuaTable data = table.table(expr.line);
            for (LuaParser.Field field : ((TableExpr) expr).fields) {
                List<LuaValue> values = eval(field.value);
                data.set(first(field.key), valueAt(values, 0));
            }
            return Collections.singletonList(table);
        }
        if (expr instanceof Index) {
            Index index = (Index) expr;
            return Collections.singletonList(first(index.table).table(index.line).get(first(index.key)));
        }
        if (expr instanceof Call) {
            return callEval((Call) expr);
        }
        if (expr instanceof FuncExpr) {
            return Collections.singletonList(closure((FuncExpr) expr));
        }
        throw new LuaException("unsupported expression", expr.line);
    }

    private List<LuaValue> callEval(Call call) throws LuaException {
        List<LuaValue> args = new ArrayList<>();
        LuaValue callee;
        if (call.method != null) {
            LuaValue self = first(call.callee);
            callee = self.table(call.line).get(LuaValue.string(call.method));
            args.add(self);
        } else {
            callee = first(call.callee);
        }
        for (int i = 0; i < call.args.size(); i++) {
            List<LuaValue> piece = eval(call.args.get(i));
            if (i == call.args.size() - 1) {
                args.addAll(piece);
            } else {
                args.add(valueAt(piece, 0));
            }
        }
        return invoke(callee, args, call.line);
    }

    private List<LuaValue> invoke(LuaValue function, List<LuaValue> args, int line) throws LuaException {
        if (function.kind != LuaValue.Kind.FUNCTION) {
            throw new LuaException("attempt to call a " + function.typeName() + " value", line);
        }
        Object raw = function.value;
        if (raw instanceof Native) {
            try {
                List<LuaValue> result = ((Native) raw).call(this, args);
                return result == null || result.isEmpty() ? Collections.singletonList(LuaValue.NIL) : result;
            } catch (LuaException ex) {
                if (ex.line == 0 && line > 0) {
                    throw new LuaException(ex.getMessage(), line, ex);
                }
                throw ex;
            }
        }
        Closure closure = (Closure) raw;
        Scope inner = new Scope(closure.env);
        Scope previous = scope;
        scope = inner;
        try {
            for (int i = 0; i < closure.params.size(); i++) {
                inner.locals.put(closure.params.get(i), new Cell(i < args.size() ? args.get(i) : LuaValue.NIL));
            }
            exec(closure.body);
            return Collections.singletonList(LuaValue.NIL);
        } catch (LuaReturn returned) {
            return returned.values.isEmpty() ? Collections.singletonList(LuaValue.NIL) : returned.values;
        } finally {
            scope = previous;
        }
    }

    private LuaValue closure(FuncExpr function) {
        return LuaValue.function(new Closure(function.params, function.body, scope));
    }

    private void assignTo(Expr target, LuaValue value) throws LuaException {
        if (target instanceof Name) {
            String name = ((Name) target).name;
            Cell local = findLocal(name);
            if (local != null) {
                local.value = value;
            } else {
                globals.set(name, value);
            }
            return;
        }
        if (target instanceof Index) {
            Index index = (Index) target;
            first(index.table).table(index.line).set(first(index.key), value);
            return;
        }
        throw new LuaException("cannot assign to this expression", target.line);
    }

    private LuaValue lookup(String name) {
        Cell local = findLocal(name);
        if (local != null) {
            return local.value;
        }
        return globals.get(LuaValue.string(name));
    }

    private Cell findLocal(String name) {
        Scope current = scope;
        while (current != null) {
            Cell cell = current.locals.get(name);
            if (cell != null) {
                return cell;
            }
            current = current.parent;
        }
        return null;
    }

    private List<LuaValue> evalList(List<Expr> expressions) throws LuaException {
        if (expressions == null || expressions.isEmpty()) {
            return Collections.emptyList();
        }
        List<LuaValue> values = new ArrayList<>();
        for (int i = 0; i < expressions.size(); i++) {
            List<LuaValue> piece = eval(expressions.get(i));
            if (i == expressions.size() - 1) {
                values.addAll(piece);
            } else {
                values.add(valueAt(piece, 0));
            }
        }
        return values;
    }

    private LuaValue first(Expr expr) throws LuaException {
        return valueAt(eval(expr), 0);
    }

    private static LuaValue valueAt(List<LuaValue> values, int index) {
        if (values == null || index >= values.size()) {
            return LuaValue.NIL;
        }
        LuaValue value = values.get(index);
        return value == null ? LuaValue.NIL : value;
    }

    private static LuaValue arg(List<LuaValue> args, int index) {
        return valueAt(args, index);
    }

    private static int compare(LuaValue left, LuaValue right, int line) throws LuaException {
        if (left.kind == LuaValue.Kind.NUMBER && right.kind == LuaValue.Kind.NUMBER) {
            return Double.compare(left.asNumber(line), right.asNumber(line));
        }
        if (left.kind == LuaValue.Kind.STRING && right.kind == LuaValue.Kind.STRING) {
            return left.asString().compareTo(right.asString());
        }
        throw new LuaException("attempt to compare " + left.typeName() + " with " + right.typeName(), line);
    }

    private static String limit(String text, int line) throws LuaException {
        if (text.length() > MAX_STRING) {
            throw new LuaException("string exceeds " + MAX_STRING + " characters", line);
        }
        return text;
    }

    private void step(int line) throws LuaException {
        steps++;
        if (steps > stepLimit) {
            throw new LuaException("script exceeded " + stepLimit + " steps", line);
        }
    }

    private Scope push() {
        scope = new Scope(scope);
        return scope;
    }

    private void pop() {
        if (scope.parent != null) {
            scope = scope.parent;
        }
    }

    @FunctionalInterface
    public interface Native {
        List<LuaValue> call(LuaRuntime runtime, List<LuaValue> args) throws LuaException;
    }

    public static final class Closure {
        final List<String> params;
        final Block body;
        final Scope env;

        Closure(List<String> params, Block body, Scope env) {
            this.params = params;
            this.body = body;
            this.env = env;
        }
    }

    private static final class Scope {
        final Scope parent;
        final Map<String, Cell> locals = new HashMap<>();

        Scope(Scope parent) {
            this.parent = parent;
        }
    }

    private static final class Cell {
        LuaValue value;

        Cell(LuaValue value) {
            this.value = value == null ? LuaValue.NIL : value;
        }
    }

    private static final class LuaReturn extends RuntimeException {
        final List<LuaValue> values;

        LuaReturn(List<LuaValue> values) {
            this.values = values;
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class LuaBreak extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
