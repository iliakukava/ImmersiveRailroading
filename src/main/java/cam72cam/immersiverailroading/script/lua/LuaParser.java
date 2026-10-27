package cam72cam.immersiverailroading.script.lua;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lua 5.1 lexer and parser for the model-script subset.
 * The grammar covers the statements and expressions modelers need; bitwise operators and goto are rejected with a line number.
 */
public final class LuaParser {
    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private int cursor;

    private LuaParser(String source) {
        this.source = source == null ? "" : source;
    }

    public static Block parse(String source) throws LuaException {
        LuaParser parser = new LuaParser(source);
        parser.lex();
        Block block = parser.block(false);
        parser.expect(TokenType.EOF, "end of script");
        return block;
    }

    public static final class Block {
        public final List<Stmt> statements = new ArrayList<>();
    }

    public abstract static class Stmt {
        public final int line;

        protected Stmt(int line) {
            this.line = line;
        }
    }

    public abstract static class Expr {
        public final int line;

        protected Expr(int line) {
            this.line = line;
        }
    }

    public static final class Assign extends Stmt {
        public final List<Expr> targets;
        public final List<Expr> values;

        Assign(int line, List<Expr> targets, List<Expr> values) {
            super(line);
            this.targets = targets;
            this.values = values;
        }
    }

    public static final class LocalAssign extends Stmt {
        public final List<String> names;
        public final List<Expr> values;

        LocalAssign(int line, List<String> names, List<Expr> values) {
            super(line);
            this.names = names;
            this.values = values;
        }
    }

    public static final class LocalFunction extends Stmt {
        public final String name;
        public final FuncExpr function;

        LocalFunction(int line, String name, FuncExpr function) {
            super(line);
            this.name = name;
            this.function = function;
        }
    }

    public static final class CallStmt extends Stmt {
        public final Call call;

        CallStmt(Call call) {
            super(call.line);
            this.call = call;
        }
    }

    public static final class IfStmt extends Stmt {
        public final List<Expr> conditions = new ArrayList<>();
        public final List<Block> bodies = new ArrayList<>();
        public Block elseBody;

        IfStmt(int line) {
            super(line);
        }
    }

    public static final class WhileStmt extends Stmt {
        public final Expr condition;
        public final Block body;

        WhileStmt(int line, Expr condition, Block body) {
            super(line);
            this.condition = condition;
            this.body = body;
        }
    }

    public static final class RepeatStmt extends Stmt {
        public final Block body;
        public final Expr condition;

        RepeatStmt(int line, Block body, Expr condition) {
            super(line);
            this.body = body;
            this.condition = condition;
        }
    }

    public static final class NumericFor extends Stmt {
        public final String name;
        public final Expr start;
        public final Expr stop;
        public final Expr step;
        public final Block body;

        NumericFor(int line, String name, Expr start, Expr stop, Expr step, Block body) {
            super(line);
            this.name = name;
            this.start = start;
            this.stop = stop;
            this.step = step;
            this.body = body;
        }
    }

    public static final class GenericFor extends Stmt {
        public final List<String> names;
        public final List<Expr> iterators;
        public final Block body;

        GenericFor(int line, List<String> names, List<Expr> iterators, Block body) {
            super(line);
            this.names = names;
            this.iterators = iterators;
            this.body = body;
        }
    }

    public static final class ReturnStmt extends Stmt {
        public final List<Expr> values;

        ReturnStmt(int line, List<Expr> values) {
            super(line);
            this.values = values;
        }
    }

    public static final class BreakStmt extends Stmt {
        BreakStmt(int line) {
            super(line);
        }
    }

    public static final class DoStmt extends Stmt {
        public final Block body;

        DoStmt(int line, Block body) {
            super(line);
            this.body = body;
        }
    }

    public static final class Literal extends Expr {
        public final LuaValue value;

        Literal(int line, LuaValue value) {
            super(line);
            this.value = value;
        }
    }

    public static final class Name extends Expr {
        public final String name;

        Name(int line, String name) {
            super(line);
            this.name = name;
        }
    }

    public static final class Unary extends Expr {
        public final String op;
        public final Expr expr;

        Unary(int line, String op, Expr expr) {
            super(line);
            this.op = op;
            this.expr = expr;
        }
    }

    public static final class Binary extends Expr {
        public final String op;
        public final Expr left;
        public final Expr right;

        Binary(int line, String op, Expr left, Expr right) {
            super(line);
            this.op = op;
            this.left = left;
            this.right = right;
        }
    }

    public static final class TableExpr extends Expr {
        public final List<Field> fields = new ArrayList<>();

        TableExpr(int line) {
            super(line);
        }
    }

    public static final class Field {
        public final Expr key;
        public final Expr value;

        Field(Expr key, Expr value) {
            this.key = key;
            this.value = value;
        }
    }

    public static final class Index extends Expr {
        public final Expr table;
        public final Expr key;

        Index(int line, Expr table, Expr key) {
            super(line);
            this.table = table;
            this.key = key;
        }
    }

    public static final class Call extends Expr {
        public final Expr callee;
        public final List<Expr> args;
        public final String method;

        Call(int line, Expr callee, List<Expr> args, String method) {
            super(line);
            this.callee = callee;
            this.args = args;
            this.method = method;
        }
    }

    public static final class FuncExpr extends Expr {
        public final List<String> params;
        public final Block body;

        FuncExpr(int line, List<String> params, Block body) {
            super(line);
            this.params = params;
            this.body = body;
        }
    }

    private enum TokenType {
        EOF, NAME, NUMBER, STRING,
        PLUS, MINUS, STAR, SLASH, PERCENT, CARET, HASH, DOTDOT,
        EQ, NE, LT, GT, LE, GE, ASSIGN,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
        COMMA, SEMI, COLON, DOT,
        AND, OR, NOT,
        IF, THEN, ELSEIF, ELSE, END, WHILE, DO, FOR, IN, FUNCTION, LOCAL, RETURN, BREAK, REPEAT, UNTIL,
        TRUE, FALSE, NIL
    }

    private static final class Token {
        final TokenType type;
        final String text;
        final double number;
        final int line;

        Token(TokenType type, String text, double number, int line) {
            this.type = type;
            this.text = text;
            this.number = number;
            this.line = line;
        }
    }

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("and", TokenType.AND);
        KEYWORDS.put("or", TokenType.OR);
        KEYWORDS.put("not", TokenType.NOT);
        KEYWORDS.put("if", TokenType.IF);
        KEYWORDS.put("then", TokenType.THEN);
        KEYWORDS.put("elseif", TokenType.ELSEIF);
        KEYWORDS.put("else", TokenType.ELSE);
        KEYWORDS.put("end", TokenType.END);
        KEYWORDS.put("while", TokenType.WHILE);
        KEYWORDS.put("do", TokenType.DO);
        KEYWORDS.put("for", TokenType.FOR);
        KEYWORDS.put("in", TokenType.IN);
        KEYWORDS.put("function", TokenType.FUNCTION);
        KEYWORDS.put("local", TokenType.LOCAL);
        KEYWORDS.put("return", TokenType.RETURN);
        KEYWORDS.put("break", TokenType.BREAK);
        KEYWORDS.put("repeat", TokenType.REPEAT);
        KEYWORDS.put("until", TokenType.UNTIL);
        KEYWORDS.put("true", TokenType.TRUE);
        KEYWORDS.put("false", TokenType.FALSE);
        KEYWORDS.put("nil", TokenType.NIL);
    }

    private void lex() throws LuaException {
        int i = 0;
        int line = 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < source.length() && source.charAt(i + 1) == '-') {
                i += 2;
                if (i < source.length() && source.charAt(i) == '[') {
                    int level = longBracketLevel(i);
                    if (level >= 0) {
                        int next = skipLongBracket(i, level, line);
                        line += countLines(source.substring(i, Math.min(next, source.length())));
                        i = next;
                        continue;
                    }
                }
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '[') {
                int level = longBracketLevel(i);
                if (level >= 0) {
                    int startLine = line;
                    int close = findLongClose(i, level);
                    if (close < 0) {
                        throw new LuaException("unfinished long string", startLine);
                    }
                    int contentStart = i + 1 + level + 1;
                    if (contentStart < source.length() && source.charAt(contentStart) == '\n') {
                        contentStart++;
                    }
                    String text = source.substring(contentStart, close);
                    tokens.add(new Token(TokenType.STRING, text, 0, startLine));
                    line = startLine + countLines(source.substring(i, close + 1 + level + 1));
                    i = close + 1 + level + 1;
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                int startLine = line;
                int start = i + 1;
                StringBuilder builder = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < source.length()) {
                    char n = source.charAt(i);
                    if (n == '\n') {
                        break;
                    }
                    if (n == c) {
                        closed = true;
                        i++;
                        break;
                    }
                    if (n == '\\') {
                        i++;
                        if (i >= source.length()) {
                            break;
                        }
                        char esc = source.charAt(i);
                        switch (esc) {
                            case 'n':
                                builder.append('\n');
                                break;
                            case 'r':
                                builder.append('\r');
                                break;
                            case 't':
                                builder.append('\t');
                                break;
                            case '\\':
                            case '"':
                            case '\'':
                                builder.append(esc);
                                break;
                            case '\n':
                                builder.append('\n');
                                line++;
                                break;
                            default:
                                if (esc >= '0' && esc <= '9') {
                                    int value = esc - '0';
                                    int digits = 1;
                                    while (digits < 3 && i + 1 < source.length()) {
                                        char d = source.charAt(i + 1);
                                        if (d < '0' || d > '9') {
                                            break;
                                        }
                                        value = value * 10 + (d - '0');
                                        i++;
                                        digits++;
                                    }
                                    builder.append((char) value);
                                } else {
                                    builder.append(esc);
                                }
                                break;
                        }
                        i++;
                        continue;
                    }
                    builder.append(n);
                    i++;
                }
                if (!closed) {
                    throw new LuaException("unfinished string", startLine);
                }
                tokens.add(new Token(TokenType.STRING, builder.toString(), 0, startLine));
                continue;
            }
            if (isDigit(c) || (c == '.' && i + 1 < source.length() && isDigit(source.charAt(i + 1)))) {
                int start = i;
                int startLine = line;
                while (i < source.length() && (isDigit(source.charAt(i)) || source.charAt(i) == '.')) {
                    i++;
                }
                if (i < source.length() && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
                    i++;
                    if (i < source.length() && (source.charAt(i) == '+' || source.charAt(i) == '-')) {
                        i++;
                    }
                    while (i < source.length() && isDigit(source.charAt(i))) {
                        i++;
                    }
                }
                String text = source.substring(start, i);
                Double number = LuaValue.parseNumber(text);
                if (number == null) {
                    throw new LuaException("malformed number", startLine);
                }
                tokens.add(new Token(TokenType.NUMBER, text, number, startLine));
                continue;
            }
            if (isNameStart(c)) {
                int start = i;
                int startLine = line;
                i++;
                while (i < source.length() && isNamePart(source.charAt(i))) {
                    i++;
                }
                String text = source.substring(start, i);
                TokenType keyword = KEYWORDS.get(text);
                tokens.add(new Token(keyword == null ? TokenType.NAME : keyword, text, 0, startLine));
                continue;
            }
            int startLine = line;
            TokenType type = single(c, i);
            if (type == null) {
                throw new LuaException("unexpected character '" + c + "'", startLine);
            }
            if (type == TokenType.EQ || type == TokenType.NE || type == TokenType.LE || type == TokenType.GE || type == TokenType.DOTDOT) {
                i += 2;
            } else {
                i++;
            }
            tokens.add(new Token(type, null, 0, startLine));
        }
        tokens.add(new Token(TokenType.EOF, null, 0, line));
    }

    private TokenType single(char c, int i) {
        switch (c) {
            case '+':
                return TokenType.PLUS;
            case '-':
                return TokenType.MINUS;
            case '*':
                return TokenType.STAR;
            case '/':
                return TokenType.SLASH;
            case '%':
                return TokenType.PERCENT;
            case '^':
                return TokenType.CARET;
            case '#':
                return TokenType.HASH;
            case '(':
                return TokenType.LPAREN;
            case ')':
                return TokenType.RPAREN;
            case '{':
                return TokenType.LBRACE;
            case '}':
                return TokenType.RBRACE;
            case '[':
                return TokenType.LBRACKET;
            case ']':
                return TokenType.RBRACKET;
            case ',':
                return TokenType.COMMA;
            case ';':
                return TokenType.SEMI;
            case ':':
                return TokenType.COLON;
            case '=':
                if (peekChar(i + 1) == '=') {
                    return TokenType.EQ;
                }
                return TokenType.ASSIGN;
            case '~':
                if (peekChar(i + 1) == '=') {
                    return TokenType.NE;
                }
                return null;
            case '<':
                if (peekChar(i + 1) == '=') {
                    return TokenType.LE;
                }
                return TokenType.LT;
            case '>':
                if (peekChar(i + 1) == '=') {
                    return TokenType.GE;
                }
                return TokenType.GT;
            case '.':
                if (peekChar(i + 1) == '.') {
                    return TokenType.DOTDOT;
                }
                return TokenType.DOT;
            default:
                return null;
        }
    }

    private char peekChar(int index) {
        return index >= 0 && index < source.length() ? source.charAt(index) : '\0';
    }

    private int longBracketLevel(int index) {
        if (index >= source.length() || source.charAt(index) != '[') {
            return -1;
        }
        int i = index + 1;
        int level = 0;
        while (i < source.length() && source.charAt(i) == '=') {
            level++;
            i++;
        }
        if (i < source.length() && source.charAt(i) == '[') {
            return level;
        }
        return -1;
    }

    private int skipLongBracket(int index, int level, int line) throws LuaException {
        int close = findLongClose(index, level);
        if (close < 0) {
            throw new LuaException("unfinished long comment", line);
        }
        return close + 1 + level + 1;
    }

    private int findLongClose(int openBracket, int level) {
        int content = openBracket + 1 + level + 1;
        String closer = "]" + "=".repeat(level) + "]";
        int found = source.indexOf(closer, content);
        return found;
    }

    private static int countLines(String text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private Block block(boolean stopAtUntil) throws LuaException {
        Block block = new Block();
        while (!isBlockEnd(stopAtUntil)) {
            if (check(TokenType.SEMI)) {
                advance();
                continue;
            }
            if (check(TokenType.RETURN)) {
                block.statements.add(returnStmt());
                break;
            }
            block.statements.add(statement());
        }
        return block;
    }

    private boolean isBlockEnd(boolean stopAtUntil) {
        TokenType type = peek().type;
        return type == TokenType.EOF || type == TokenType.END || type == TokenType.ELSE || type == TokenType.ELSEIF
                || (stopAtUntil && type == TokenType.UNTIL);
    }

    private Stmt statement() throws LuaException {
        Token token = peek();
        switch (token.type) {
            case IF:
                return ifStmt();
            case WHILE:
                return whileStmt();
            case REPEAT:
                return repeatStmt();
            case FOR:
                return forStmt();
            case DO: {
                int line = advance().line;
                Block body = block(false);
                expect(TokenType.END, "'end'");
                return new DoStmt(line, body);
            }
            case BREAK:
                return new BreakStmt(advance().line);
            case LOCAL:
                return localStmt();
            case FUNCTION:
                return functionStmt();
            default:
                break;
        }
        Expr prefix = suffix(orExpr());
        if (prefix instanceof Call && !check(TokenType.ASSIGN) && !check(TokenType.COMMA)) {
            return new CallStmt((Call) prefix);
        }
        List<Expr> targets = new ArrayList<>();
        targets.add(prefix);
        while (check(TokenType.COMMA)) {
            advance();
            targets.add(assignable(suffix(orExpr())));
        }
        expect(TokenType.ASSIGN, "'='");
        List<Expr> values = exprList();
        for (Expr target : targets) {
            assignable(target);
        }
        return new Assign(prefix.line, targets, values);
    }

    private Expr assignable(Expr expr) throws LuaException {
        if (expr instanceof Name || expr instanceof Index) {
            return expr;
        }
        throw new LuaException("cannot assign to this expression", expr.line);
    }

    private Stmt ifStmt() throws LuaException {
        IfStmt stmt = new IfStmt(advance().line);
        do {
            stmt.conditions.add(expression());
            expect(TokenType.THEN, "'then'");
            stmt.bodies.add(block(false));
        } while (check(TokenType.ELSEIF) && advance() != null);
        if (check(TokenType.ELSE)) {
            advance();
            stmt.elseBody = block(false);
        }
        expect(TokenType.END, "'end'");
        return stmt;
    }

    private Stmt whileStmt() throws LuaException {
        int line = advance().line;
        Expr condition = expression();
        expect(TokenType.DO, "'do'");
        Block body = block(false);
        expect(TokenType.END, "'end'");
        return new WhileStmt(line, condition, body);
    }

    private Stmt repeatStmt() throws LuaException {
        int line = advance().line;
        Block body = block(true);
        expect(TokenType.UNTIL, "'until'");
        return new RepeatStmt(line, body, expression());
    }

    private Stmt forStmt() throws LuaException {
        int line = advance().line;
        String first = expectName();
        if (check(TokenType.ASSIGN)) {
            advance();
            Expr start = expression();
            expect(TokenType.COMMA, "','");
            Expr stop = expression();
            Expr step = null;
            if (check(TokenType.COMMA)) {
                advance();
                step = expression();
            }
            expect(TokenType.DO, "'do'");
            Block body = block(false);
            expect(TokenType.END, "'end'");
            return new NumericFor(line, first, start, stop, step, body);
        }
        List<String> names = new ArrayList<>();
        names.add(first);
        while (check(TokenType.COMMA)) {
            advance();
            names.add(expectName());
        }
        expect(TokenType.IN, "'in'");
        List<Expr> iterators = exprList();
        expect(TokenType.DO, "'do'");
        Block body = block(false);
        expect(TokenType.END, "'end'");
        return new GenericFor(line, names, iterators, body);
    }

    private Stmt localStmt() throws LuaException {
        int line = advance().line;
        if (check(TokenType.FUNCTION)) {
            advance();
            String name = expectName();
            return new LocalFunction(line, name, functionBody(line));
        }
        List<String> names = new ArrayList<>();
        names.add(expectName());
        while (check(TokenType.COMMA)) {
            advance();
            names.add(expectName());
        }
        List<Expr> values = new ArrayList<>();
        if (check(TokenType.ASSIGN)) {
            advance();
            values = exprList();
        }
        return new LocalAssign(line, names, values);
    }

    private Stmt functionStmt() throws LuaException {
        int line = advance().line;
        Expr name = new Name(peek().line, expectName());
        String method = null;
        while (check(TokenType.DOT)) {
            advance();
            String field = expectName();
            name = new Index(name.line, name, new Literal(name.line, LuaValue.string(field)));
        }
        if (check(TokenType.COLON)) {
            advance();
            method = expectName();
        }
        FuncExpr function = functionBody(line);
        if (method != null) {
            function.params.add(0, "self");
            name = new Index(name.line, name, new Literal(name.line, LuaValue.string(method)));
        }
        List<Expr> targets = new ArrayList<>();
        targets.add(name);
        List<Expr> values = new ArrayList<>();
        values.add(function);
        return new Assign(line, targets, values);
    }

    private FuncExpr functionBody(int line) throws LuaException {
        expect(TokenType.LPAREN, "'('");
        List<String> params = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            params.add(expectName());
            while (check(TokenType.COMMA)) {
                advance();
                params.add(expectName());
            }
        }
        expect(TokenType.RPAREN, "')'");
        Block body = block(false);
        expect(TokenType.END, "'end'");
        return new FuncExpr(line, params, body);
    }

    private ReturnStmt returnStmt() throws LuaException {
        int line = advance().line;
        if (isBlockEnd(true) || check(TokenType.SEMI) || check(TokenType.EOF)) {
            return new ReturnStmt(line, new ArrayList<>());
        }
        return new ReturnStmt(line, exprList());
    }

    private List<Expr> exprList() throws LuaException {
        List<Expr> values = new ArrayList<>();
        values.add(expression());
        while (check(TokenType.COMMA)) {
            advance();
            values.add(expression());
        }
        return values;
    }

    private Expr expression() throws LuaException {
        return orExpr();
    }

    private Expr orExpr() throws LuaException {
        Expr left = andExpr();
        while (check(TokenType.OR)) {
            int line = advance().line;
            left = new Binary(line, "or", left, andExpr());
        }
        return left;
    }

    private Expr andExpr() throws LuaException {
        Expr left = relExpr();
        while (check(TokenType.AND)) {
            int line = advance().line;
            left = new Binary(line, "and", left, relExpr());
        }
        return left;
    }

    private Expr relExpr() throws LuaException {
        Expr left = concatExpr();
        if (check(TokenType.EQ) || check(TokenType.NE) || check(TokenType.LT) || check(TokenType.GT) || check(TokenType.LE) || check(TokenType.GE)) {
            Token op = advance();
            return new Binary(op.line, op.text == null ? symbol(op.type) : op.text, left, concatExpr());
        }
        return left;
    }

    private Expr concatExpr() throws LuaException {
        Expr left = addExpr();
        if (check(TokenType.DOTDOT)) {
            int line = advance().line;
            return new Binary(line, "..", left, concatExpr());
        }
        return left;
    }

    private Expr addExpr() throws LuaException {
        Expr left = mulExpr();
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            Token op = advance();
            left = new Binary(op.line, op.type == TokenType.PLUS ? "+" : "-", left, mulExpr());
        }
        return left;
    }

    private Expr mulExpr() throws LuaException {
        Expr left = unaryExpr();
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            Token op = advance();
            String symbol = op.type == TokenType.STAR ? "*" : op.type == TokenType.SLASH ? "/" : "%";
            left = new Binary(op.line, symbol, left, unaryExpr());
        }
        return left;
    }

    private Expr unaryExpr() throws LuaException {
        if (check(TokenType.NOT) || check(TokenType.HASH) || check(TokenType.MINUS)) {
            Token op = advance();
            String symbol = op.type == TokenType.NOT ? "not" : op.type == TokenType.HASH ? "#" : "-";
            return new Unary(op.line, symbol, unaryExpr());
        }
        return powExpr();
    }

    private Expr powExpr() throws LuaException {
        Expr left = suffix(primary());
        if (check(TokenType.CARET)) {
            int line = advance().line;
            return new Binary(line, "^", left, unaryExpr());
        }
        return left;
    }

    private Expr suffix(Expr expr) throws LuaException {
        while (true) {
            if (check(TokenType.LPAREN) || check(TokenType.LBRACE) || check(TokenType.STRING)) {
                expr = call(expr, null);
            } else if (check(TokenType.COLON)) {
                advance();
                String method = expectName();
                expr = call(expr, method);
            } else if (check(TokenType.DOT)) {
                int line = advance().line;
                expr = new Index(line, expr, new Literal(line, LuaValue.string(expectName())));
            } else if (check(TokenType.LBRACKET)) {
                int line = advance().line;
                Expr key = expression();
                expect(TokenType.RBRACKET, "']'");
                expr = new Index(line, expr, key);
            } else {
                return expr;
            }
        }
    }

    private Call call(Expr callee, String method) throws LuaException {
        List<Expr> args = new ArrayList<>();
        if (method != null) {
            if (!check(TokenType.LPAREN) && !check(TokenType.LBRACE) && !check(TokenType.STRING)) {
                throw new LuaException("expected arguments after ':'", peek().line);
            }
        }
        if (check(TokenType.LPAREN)) {
            advance();
            if (!check(TokenType.RPAREN)) {
                args = exprList();
            }
            expect(TokenType.RPAREN, "')'");
        } else if (check(TokenType.LBRACE)) {
            args.add(tableExpr());
        } else if (check(TokenType.STRING)) {
            Token string = advance();
            args.add(new Literal(string.line, LuaValue.string(string.text)));
        }
        return new Call(callee.line, callee, args, method);
    }

    private Expr primary() throws LuaException {
        Token token = peek();
        switch (token.type) {
            case NUMBER:
                advance();
                return new Literal(token.line, LuaValue.number(token.number));
            case STRING:
                advance();
                return new Literal(token.line, LuaValue.string(token.text));
            case TRUE:
                advance();
                return new Literal(token.line, LuaValue.TRUE);
            case FALSE:
                advance();
                return new Literal(token.line, LuaValue.FALSE);
            case NIL:
                advance();
                return new Literal(token.line, LuaValue.NIL);
            case NAME:
                advance();
                return new Name(token.line, token.text);
            case LPAREN: {
                advance();
                Expr inner = expression();
                expect(TokenType.RPAREN, "')'");
                return inner;
            }
            case LBRACE:
                return tableExpr();
            case FUNCTION: {
                int line = advance().line;
                return functionBody(line);
            }
            default:
                throw new LuaException("unexpected " + describe(token), token.line);
        }
    }

    private Expr tableExpr() throws LuaException {
        Token open = expect(TokenType.LBRACE, "'{'");
        TableExpr table = new TableExpr(open.line);
        int array = 1;
        while (!check(TokenType.RBRACE)) {
            if (check(TokenType.LBRACKET)) {
                advance();
                Expr key = expression();
                expect(TokenType.RBRACKET, "']'");
                expect(TokenType.ASSIGN, "'='");
                table.fields.add(new Field(key, expression()));
            } else if (check(TokenType.NAME) && lookahead(1).type == TokenType.ASSIGN) {
                Token name = advance();
                advance();
                table.fields.add(new Field(new Literal(name.line, LuaValue.string(name.text)), expression()));
            } else {
                table.fields.add(new Field(new Literal(open.line, LuaValue.number(array)), expression()));
                array++;
            }
            if (check(TokenType.COMMA) || check(TokenType.SEMI)) {
                advance();
                continue;
            }
            break;
        }
        expect(TokenType.RBRACE, "'}'");
        return table;
    }

    private String symbol(TokenType type) {
        switch (type) {
            case EQ:
                return "==";
            case NE:
                return "~=";
            case LT:
                return "<";
            case GT:
                return ">";
            case LE:
                return "<=";
            case GE:
                return ">=";
            default:
                return type.name().toLowerCase(Locale.ROOT);
        }
    }

    private String describe(Token token) {
        if (token.type == TokenType.NAME) {
            return "name '" + token.text + "'";
        }
        if (token.type == TokenType.EOF) {
            return "end of script";
        }
        if (token.text != null && token.type != TokenType.STRING && token.type != TokenType.NUMBER) {
            return "'" + token.text + "'";
        }
        return token.type.name().toLowerCase(Locale.ROOT);
    }

    private Token peek() {
        return tokens.get(cursor);
    }

    private Token lookahead(int offset) {
        int index = cursor + offset;
        if (index >= tokens.size()) {
            return tokens.get(tokens.size() - 1);
        }
        return tokens.get(index);
    }

    private boolean check(TokenType type) {
        return peek().type == type;
    }

    private Token advance() {
        Token token = peek();
        if (cursor + 1 < tokens.size()) {
            cursor++;
        }
        return token;
    }

    private Token expect(TokenType type, String what) throws LuaException {
        if (!check(type)) {
            throw new LuaException("expected " + what + " but found " + describe(peek()), peek().line);
        }
        return advance();
    }

    private String expectName() throws LuaException {
        Token token = expect(TokenType.NAME, "a name");
        return token.text;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isNameStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isNamePart(char c) {
        return isNameStart(c) || isDigit(c);
    }
}
