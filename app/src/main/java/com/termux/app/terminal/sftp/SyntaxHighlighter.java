package com.termux.app.terminal.sftp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A small syntax highlighter for the editor. It splits text into tokens, like comments, strings
 * and keywords, with simple rules per language instead of full grammars: good enough to read
 * code, and fast enough to run while editing. Has no Android dependencies.
 */
public final class SyntaxHighlighter {

    public static final int COMMENT = 0;
    public static final int STRING = 1;
    public static final int NUMBER = 2;
    /** Control flow and declaration keywords. */
    public static final int KEYWORD = 3;
    /** Constants and builtin types, like `true`, `null` and `int`. */
    public static final int LITERAL = 4;
    public static final int FUNCTION = 5;
    /** Keys of config files and JSON, and attribute names of markup. */
    public static final int KEY = 6;
    public static final int TAG = 7;
    public static final int VARIABLE = 8;
    /** Preprocessor lines, annotations, section headers and markdown headings. */
    public static final int META = 9;
    public static final int ADDED = 10;
    public static final int REMOVED = 11;

    /** Receives the tokens of a text. */
    public interface TokenSink {
        void token(int start, int end, int type);
    }

    private static final int CODE = 0;
    private static final int MARKUP = 1;
    private static final int MARKDOWN = 2;
    private static final int DIFF = 3;

    /** How to highlight a language. */
    public static final class Language {
        public final String name;
        final int kind;
        final Set<String> extensions = new HashSet<>();
        final Set<String> fileNames = new HashSet<>();
        final Set<String> interpreters = new HashSet<>();
        final Set<String> keywords = new HashSet<>();
        final Set<String> literals = new HashSet<>();
        String[] lineComments = {};
        String blockCommentStart;
        String blockCommentEnd;
        String quotes = "\"'";
        /** Quotes whose strings can span lines. */
        String multiLineQuotes = "";
        boolean tripleQuotes;
        boolean caseInsensitive;
        /** `#` starts a comment only at the start of a word, like in shell. */
        boolean hashCommentAtWordStart;
        /** `$name` and `${...}` are variables. */
        boolean dollarVariables;
        /** `@name` is an annotation or decorator. */
        boolean atMeta;
        /** Lines starting with `#` are preprocessor directives. */
        boolean hashMeta;
        /** A string followed by `:` is a key, like in JSON. */
        boolean stringKeys;
        /** A word followed by `:` is a key, like CSS properties. */
        boolean colonKeys;
        /** Characters that separate a key at the start of a line from its value, like `=` of INI. */
        String lineKeySeparators;
        /** `[section]` lines are headers. */
        boolean sectionHeaders;
        /** Characters, besides letters, digits and `_`, that can be in words. */
        String wordChars = "";

        Language(String name, int kind) {
            this.name = name;
            this.kind = kind;
        }

        Language ext(String... values) { extensions.addAll(Arrays.asList(values)); return this; }
        Language files(String... values) { fileNames.addAll(Arrays.asList(values)); return this; }
        Language interpreters(String... values) { interpreters.addAll(Arrays.asList(values)); return this; }
        Language keywords(String values) { keywords.addAll(Arrays.asList(values.split(" "))); return this; }
        Language literals(String values) { literals.addAll(Arrays.asList(values.split(" "))); return this; }
        Language lineComments(String... values) { lineComments = values; return this; }
        Language blockComment(String start, String end) { blockCommentStart = start; blockCommentEnd = end; return this; }
        Language quotes(String values) { quotes = values; return this; }
        Language multiLineQuotes(String values) { multiLineQuotes = values; return this; }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final String C_KEYWORDS = "if else for while do switch case default break continue return goto sizeof typedef struct union enum static extern const volatile register inline auto";
    private static final String C_TYPES = "void char short int long float double signed unsigned bool size_t ssize_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t true false NULL nullptr";

    private static final List<Language> LANGUAGES = new ArrayList<>();

    static {
        Language shell = new Language("Shell", CODE).ext("sh", "bash", "zsh", "ksh", "bats", "command")
            .files(".bashrc", ".bash_profile", ".bash_logout", ".profile", ".zshrc", ".zprofile", ".zshenv", ".kshrc", "bash.bashrc", "PKGBUILD", "build.sh")
            .interpreters("sh", "bash", "zsh", "ksh", "dash", "ash")
            .keywords("if then else elif fi for while until do done case esac in function select time return break continue local export readonly declare typeset unset shift exit source alias eval exec trap set")
            .literals("true false")
            .lineComments("#").quotes("\"'`").multiLineQuotes("\"'`");
        shell.hashCommentAtWordStart = true;
        shell.dollarVariables = true;
        shell.wordChars = "-";
        LANGUAGES.add(shell);

        Language python = new Language("Python", CODE).ext("py", "pyw", "pyi").files("SConstruct", "SConscript").interpreters("python", "python2", "python3")
            .keywords("and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case")
            .literals("True False None self cls int float str bool list dict set tuple bytes object type len print range")
            .lineComments("#");
        python.tripleQuotes = true;
        python.atMeta = true;
        LANGUAGES.add(python);

        Language js = new Language("JavaScript", CODE).ext("js", "mjs", "cjs", "jsx", "ts", "tsx", "mts", "cts").interpreters("node", "deno", "bun")
            .keywords("break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new of return super switch throw try typeof var void while with yield async await static get set from as interface type enum implements declare namespace readonly public private protected abstract")
            .literals("true false null undefined this NaN Infinity string number boolean any unknown never object")
            .lineComments("//").blockComment("/*", "*/").quotes("\"'`").multiLineQuotes("`");
        js.atMeta = true;
        js.wordChars = "$";
        LANGUAGES.add(js);

        Language json = new Language("JSON", CODE).ext("json", "jsonc", "json5", "geojson", "webmanifest").files(".babelrc", ".eslintrc", ".prettierrc")
            .literals("true false null").lineComments("//").blockComment("/*", "*/");
        json.stringKeys = true;
        LANGUAGES.add(json);

        Language java = new Language("Java", CODE).ext("java", "groovy", "gradle")
            .keywords("abstract assert break case catch class continue default do else enum extends final finally for if implements import instanceof interface native new package private protected public return static strictfp super switch synchronized throw throws transient try volatile while var record yield sealed permits def")
            .literals("true false null this boolean byte char short int long float double void String Object")
            .lineComments("//").blockComment("/*", "*/");
        java.atMeta = true;
        java.tripleQuotes = true;
        LANGUAGES.add(java);

        Language kotlin = new Language("Kotlin", CODE).ext("kt", "kts")
            .keywords("as break class continue do else for fun if in interface is object package return super throw try typealias val var when while by catch constructor finally get import init set where abstract annotation companion const data enum external final inline inner internal lateinit open operator out override private protected public sealed suspend vararg")
            .literals("true false null this it Int Long Short Byte Float Double Boolean Char String Unit Any Nothing")
            .lineComments("//").blockComment("/*", "*/");
        kotlin.atMeta = true;
        kotlin.tripleQuotes = true;
        LANGUAGES.add(kotlin);

        Language c = new Language("C/C++", CODE).ext("c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx", "ino", "m", "mm")
            .keywords(C_KEYWORDS + " class namespace template typename public private protected virtual override final using new delete try catch throw operator friend explicit constexpr noexcept static_cast dynamic_cast reinterpret_cast const_cast decltype this")
            .literals(C_TYPES + " std string vector")
            .lineComments("//").blockComment("/*", "*/");
        c.hashMeta = true;
        LANGUAGES.add(c);

        Language csharp = new Language("C#", CODE).ext("cs", "csx")
            .keywords("abstract as base break case catch checked class const continue default delegate do else enum event explicit extern finally fixed for foreach goto if implicit in interface internal is lock namespace new operator out override params private protected public readonly ref return sealed sizeof stackalloc static struct switch throw try typeof unchecked unsafe using virtual volatile while var async await get set record")
            .literals("true false null this bool byte char decimal double float int long object sbyte short string uint ulong ushort void")
            .lineComments("//").blockComment("/*", "*/");
        csharp.hashMeta = true;
        LANGUAGES.add(csharp);

        Language go = new Language("Go", CODE).ext("go")
            .keywords("break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var")
            .literals("true false nil iota bool byte rune int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 uintptr float32 float64 complex64 complex128 string error any")
            .lineComments("//").blockComment("/*", "*/").quotes("\"'`").multiLineQuotes("`");
        LANGUAGES.add(go);

        Language rust = new Language("Rust", CODE).ext("rs")
            .keywords("as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub ref return static struct super trait type unsafe use where while")
            .literals("true false self Self None Some Ok Err i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box")
            .lineComments("//").blockComment("/*", "*/").quotes("\"").multiLineQuotes("\"");
        LANGUAGES.add(rust);

        Language swift = new Language("Swift", CODE).ext("swift")
            .keywords("associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as catch is super throw throws try async await")
            .literals("true false nil self Self Int Double Float String Bool Character Any")
            .lineComments("//").blockComment("/*", "*/");
        swift.atMeta = true;
        swift.tripleQuotes = true;
        LANGUAGES.add(swift);

        Language ruby = new Language("Ruby", CODE).ext("rb", "rake", "gemspec", "ru").files("Gemfile", "Rakefile", "Vagrantfile", "Podfile").interpreters("ruby")
            .keywords("alias and begin break case class def defined? do else elsif end ensure for if in module next not or redo rescue retry return super then undef unless until when while yield require require_relative include extend attr_accessor attr_reader attr_writer private protected public lambda proc")
            .literals("true false nil self")
            .lineComments("#");
        ruby.wordChars = "?!";
        LANGUAGES.add(ruby);

        Language lua = new Language("Lua", CODE).ext("lua").interpreters("lua", "luajit")
            .keywords("and break do else elseif end for function goto if in local not or repeat return then until while")
            .literals("true false nil self")
            .lineComments("--").blockComment("--[[", "]]");
        LANGUAGES.add(lua);

        Language php = new Language("PHP", CODE).ext("php", "phtml").interpreters("php")
            .keywords("abstract and as break case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield")
            .literals("true false null TRUE FALSE NULL self parent array int float string bool")
            .lineComments("//", "#").blockComment("/*", "*/").multiLineQuotes("\"'");
        php.dollarVariables = true;
        LANGUAGES.add(php);

        Language perl = new Language("Perl", CODE).ext("pl", "pm", "t").interpreters("perl")
            .keywords("my our local sub if elsif else unless while until for foreach do last next redo return use require package no and or not eq ne lt gt le ge cmp")
            .literals("undef")
            .lineComments("#").multiLineQuotes("\"'");
        perl.dollarVariables = true;
        LANGUAGES.add(perl);

        Language sql = new Language("SQL", CODE).ext("sql", "ddl", "dml")
            .keywords("select from where and or not insert into values update set delete create table drop alter add column index view primary key foreign references join inner left right outer full cross on as group by order having limit offset union all distinct case when then else end is null like in between exists default constraint unique check begin commit rollback transaction grant revoke with returning if replace trigger function procedure database schema asc desc")
            .literals("true false int integer bigint smallint varchar char text boolean date timestamp numeric decimal real float double serial blob")
            .lineComments("--", "#").blockComment("/*", "*/");
        sql.caseInsensitive = true;
        LANGUAGES.add(sql);

        Language css = new Language("CSS", CODE).ext("css", "scss", "sass", "less")
            .literals("important inherit initial unset none auto")
            .lineComments("//").blockComment("/*", "*/");
        css.atMeta = true;
        css.colonKeys = true;
        css.wordChars = "-";
        LANGUAGES.add(css);

        LANGUAGES.add(new Language("HTML/XML", MARKUP).ext("html", "htm", "xhtml", "xml", "svg", "plist", "xsl", "xslt", "xsd", "vue", "svelte", "kml", "gpx", "rss", "atom", "iml"));

        Language yaml = new Language("YAML", CODE).ext("yml", "yaml").files(".clang-format", ".clang-tidy")
            .literals("true false null yes no on off True False Null Yes No On Off TRUE FALSE NULL ~")
            .lineComments("#");
        yaml.hashCommentAtWordStart = true;
        yaml.lineKeySeparators = ":";
        LANGUAGES.add(yaml);

        Language toml = new Language("TOML/INI", CODE).ext("toml", "ini", "cfg", "conf", "properties", "env", "desktop", "service", "timer", "socket", "mount", "editorconfig", "gitconfig", "npmrc", "prop", "lock")
            .files(".env", ".gitconfig", ".editorconfig", ".npmrc", ".gitmodules", "termux.properties", "colors.properties", "config", "sshd_config", "ssh_config")
            .literals("true false yes no on off")
            .lineComments("#", ";");
        toml.hashCommentAtWordStart = true;
        toml.lineKeySeparators = "=:";
        toml.sectionHeaders = true;
        LANGUAGES.add(toml);

        Language makefile = new Language("Makefile", CODE).ext("mk", "mak").files("Makefile", "makefile", "GNUmakefile")
            .keywords("ifeq ifneq ifdef ifndef else endif include define endef export override unexport vpath")
            .lineComments("#").quotes("\"'");
        makefile.dollarVariables = true;
        makefile.lineKeySeparators = ":=";
        makefile.wordChars = "-.";
        LANGUAGES.add(makefile);

        Language dockerfile = new Language("Dockerfile", CODE).ext("dockerfile", "containerfile").files("Dockerfile", "Containerfile")
            .keywords("from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as")
            .lineComments("#").multiLineQuotes("\"'");
        dockerfile.caseInsensitive = true;
        dockerfile.dollarVariables = true;
        dockerfile.hashCommentAtWordStart = true;
        LANGUAGES.add(dockerfile);

        LANGUAGES.add(new Language("Markdown", MARKDOWN).ext("md", "markdown", "mdown", "mkd"));
        LANGUAGES.add(new Language("Diff", DIFF).ext("diff", "patch", "rej"));
    }

    private SyntaxHighlighter() {}

    /** The languages that can be chosen, by name. */
    public static List<Language> getLanguages() {
        List<Language> languages = new ArrayList<>(LANGUAGES);
        Collections.sort(languages, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return languages;
    }

    /** The language of a file from its name or `#!` line, or {@code null} for plain text. */
    public static Language detect(String fileName, String firstLine) {
        String name = fileName == null ? "" : fileName;
        for (Language language : LANGUAGES)
            if (language.fileNames.contains(name)) return language;
        String extension = SftpText.getExtension(name);
        if (name.startsWith(".") && name.indexOf('.', 1) < 0) extension = "";
        if (!extension.isEmpty()) {
            for (Language language : LANGUAGES)
                if (language.extensions.contains(extension)) return language;
        }
        if (firstLine != null && firstLine.startsWith("#!")) {
            String interpreter = getInterpreter(firstLine);
            for (Language language : LANGUAGES)
                if (language.interpreters.contains(interpreter)) return language;
        }
        if (name.toLowerCase(Locale.ROOT).startsWith("dockerfile")) return find("Dockerfile");
        if (name.endsWith("rc") && name.startsWith(".") && name.contains("sh")) return find("Shell");
        return null;
    }

    /** The interpreter of a `#!` line, like `python3` of `#!/usr/bin/env python3`. */
    static String getInterpreter(String line) {
        String[] parts = line.substring(2).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return "";
        String command = parts[0].substring(parts[0].lastIndexOf('/') + 1);
        if (command.equals("env")) {
            command = "";
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].startsWith("-") && !parts[i].contains("=")) {
                    command = parts[i];
                    break;
                }
            }
        }
        // python3.11 -> python3
        return command.replaceAll("^(python[0-9]?)\\..*$", "$1");
    }

    public static Language find(String name) {
        for (Language language : LANGUAGES)
            if (language.name.equals(name)) return language;
        return null;
    }

    /**
     * Find the tokens of {@code text} that overlap {@code [from, to)} and give them to {@code sink}.
     * The text is scanned from {@code scanStart}, which should be a line start before
     * {@code from}, like 0, so that comments and strings that start before {@code from} are known.
     */
    public static void highlight(CharSequence text, int scanStart, int from, int to, Language language, TokenSink sink) {
        to = Math.min(to, text.length());
        if (language == null || from >= to) return;
        Scanner scanner = new Scanner(text, from, to, language, sink);
        switch (language.kind) {
            case MARKUP: scanner.scanMarkup(scanStart); break;
            case MARKDOWN: scanner.scanMarkdown(scanStart); break;
            case DIFF: scanner.scanDiff(scanStart); break;
            default: scanner.scanCode(scanStart); break;
        }
    }

    private static final class Scanner {
        final CharSequence text;
        final int from;
        final int to;
        final Language language;
        final TokenSink sink;

        Scanner(CharSequence text, int from, int to, Language language, TokenSink sink) {
            this.text = text;
            this.from = from;
            this.to = to;
            this.language = language;
            this.sink = sink;
        }

        void emit(int start, int end, int type) {
            if (end > from && start < to && end > start) sink.token(Math.max(start, from), Math.min(end, to), type);
        }

        boolean startsWith(int i, String s) {
            if (s == null || i + s.length() > text.length()) return false;
            for (int j = 0; j < s.length(); j++)
                if (text.charAt(i + j) != s.charAt(j)) return false;
            return true;
        }

        int indexOf(String s, int start) {
            int length = text.length();
            for (int i = start; i <= length - s.length(); i++)
                if (startsWith(i, s)) return i;
            return -1;
        }

        int lineEnd(int i) {
            int length = text.length();
            while (i < length && text.charAt(i) != '\n') i++;
            return i;
        }

        boolean isWordChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || language.wordChars.indexOf(c) >= 0;
        }

        boolean isWordStart(char c) {
            return Character.isLetter(c) || c == '_' || (c == '$' && language.wordChars.indexOf('$') >= 0);
        }

        int skipSpaces(int i, int end) {
            while (i < end && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
            return i;
        }

        void scanCode(int start) {
            int i = start;
            boolean lineStart = true;
            while (i < to) {
                char c = text.charAt(i);
                if (c == '\n') {
                    lineStart = true;
                    i++;
                    continue;
                }
                if (lineStart) {
                    lineStart = false;
                    int next = scanLineStart(i);
                    if (next != i) {
                        i = next;
                        continue;
                    }
                }
                if (c == ' ' || c == '\t' || c == '\r') {
                    i++;
                    continue;
                }

                if (language.blockCommentStart != null && startsWith(i, language.blockCommentStart)) {
                    int end = indexOf(language.blockCommentEnd, i + language.blockCommentStart.length());
                    end = end < 0 ? text.length() : end + language.blockCommentEnd.length();
                    emit(i, end, COMMENT);
                    i = end;
                    continue;
                }
                if (isLineComment(i)) {
                    int end = lineEnd(i);
                    emit(i, end, COMMENT);
                    i = end;
                    continue;
                }
                if (language.quotes.indexOf(c) >= 0) {
                    int end = scanString(i);
                    int type = STRING;
                    if (language.stringKeys) {
                        int after = skipSpaces(end, text.length());
                        if (after < text.length() && text.charAt(after) == ':') type = KEY;
                    }
                    emit(i, end, type);
                    i = end;
                    continue;
                }
                if (language.dollarVariables && c == '$' && i + 1 < text.length()) {
                    int end = scanVariable(i);
                    if (end > i + 1) {
                        emit(i, end, VARIABLE);
                        i = end;
                        continue;
                    }
                }
                if (language.atMeta && c == '@' && i + 1 < text.length() && isWordStart(text.charAt(i + 1))) {
                    int end = i + 1;
                    while (end < text.length() && (isWordChar(text.charAt(end)) || text.charAt(end) == '.')) end++;
                    emit(i, end, META);
                    i = end;
                    continue;
                }
                if (Character.isDigit(c) || (c == '.' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1)))) {
                    int end = scanNumber(i);
                    emit(i, end, NUMBER);
                    i = end;
                    continue;
                }
                if (c == '#' && language.colonKeys) {
                    // A CSS color or id selector.
                    int end = i + 1;
                    while (end < text.length() && isWordChar(text.charAt(end))) end++;
                    emit(i, end, NUMBER);
                    i = end;
                    continue;
                }
                if (isWordStart(c)) {
                    int end = i + 1;
                    while (end < text.length() && isWordChar(text.charAt(end))) end++;
                    int type = wordType(i, end);
                    if (type >= 0) emit(i, end, type);
                    i = end;
                    continue;
                }
                i++;
            }
        }

        /** Highlight what can only be at the start of a line, and return where to continue. */
        int scanLineStart(int i) {
            int end = lineEnd(i);
            int start = skipSpaces(i, end);
            if (start >= end) return end;
            char c = text.charAt(start);
            if (language.hashMeta && c == '#') {
                emit(start, end, META);
                return end;
            }
            if (language.sectionHeaders && c == '[') {
                int close = indexOfInLine(']', start, end);
                if (close > 0) {
                    emit(start, close + 1, META);
                    return close + 1;
                }
            }
            if (language.lineKeySeparators != null) {
                if (language.lineKeySeparators.equals(":") && c == '-' && start + 1 < end && text.charAt(start + 1) == ' ')
                    start = skipSpaces(start + 1, end); // A YAML list item.
                if (startsWith(start, "---") || startsWith(start, "...")) {
                    emit(start, end, META);
                    return end;
                }
                if (isLineComment(start) || language.quotes.indexOf(text.charAt(start)) >= 0) return start;
                for (int k = start; k < end; k++) {
                    char kc = text.charAt(k);
                    if (language.lineKeySeparators.indexOf(kc) >= 0) {
                        // A YAML key ends with ": " or ":" at the end of the line, not like the ":" of a URL.
                        if (kc == ':' && language.lineKeySeparators.equals(":") && k + 1 < end && text.charAt(k + 1) != ' ' && text.charAt(k + 1) != '\t') continue;
                        if (k > start) {
                            emit(start, k, KEY);
                            return k + 1;
                        }
                        break;
                    }
                    if (kc == '#' || kc == '"' || kc == '\'' || kc == '$' || kc == '(' || kc == '{') break;
                    if ((kc == ' ' || kc == '\t') && !language.lineKeySeparators.equals(":")) {
                        // INI keys are single words, possibly followed by spaces before the separator.
                        int after = skipSpaces(k, end);
                        if (after < end && language.lineKeySeparators.indexOf(text.charAt(after)) >= 0) {
                            emit(start, k, KEY);
                            return after + 1;
                        }
                        break;
                    }
                }
            }
            return i;
        }

        int indexOfInLine(char c, int start, int end) {
            for (int k = start; k < end; k++)
                if (text.charAt(k) == c) return k;
            return -1;
        }

        boolean isLineComment(int i) {
            for (String comment : language.lineComments) {
                if (!startsWith(i, comment)) continue;
                // The --[[ of Lua is a block comment, checked before.
                if (comment.equals("#") && language.hashCommentAtWordStart && i > 0) {
                    char before = text.charAt(i - 1);
                    if (before != ' ' && before != '\t' && before != '\n' && before != ';' && before != '(') return false;
                }
                return true;
            }
            return false;
        }

        int scanString(int i) {
            char quote = text.charAt(i);
            int length = text.length();
            if (language.tripleQuotes && (quote == '"' || quote == '\'') && startsWith(i, "" + quote + quote + quote)) {
                String triple = "" + quote + quote + quote;
                int end = indexOf(triple, i + 3);
                return end < 0 ? length : end + 3;
            }
            boolean multiLine = language.multiLineQuotes.indexOf(quote) >= 0;
            // Shell does not escape in single quotes.
            boolean escapes = !(language.dollarVariables && quote == '\'');
            int j = i + 1;
            while (j < length) {
                char c = text.charAt(j);
                if (escapes && c == '\\') {
                    j += 2;
                    continue;
                }
                if (c == quote) return j + 1;
                if (c == '\n' && !multiLine) return j;
                j++;
            }
            return length;
        }

        int scanVariable(int i) {
            int length = text.length();
            char c = text.charAt(i + 1);
            if (c == '{' || c == '(') {
                char close = c == '{' ? '}' : ')';
                int end = i + 2;
                while (end < length && text.charAt(end) != close && text.charAt(end) != '\n') end++;
                return end < length && text.charAt(end) == close ? end + 1 : end;
            }
            if ("@*#?$!-0123456789".indexOf(c) >= 0) return i + 2;
            int end = i + 1;
            while (end < length && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
            return end;
        }

        int scanNumber(int i) {
            int length = text.length();
            int end = i;
            while (end < length) {
                char c = text.charAt(end);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                    end++;
                } else if ((c == '-' || c == '+') && end > i && (text.charAt(end - 1) == 'e' || text.charAt(end - 1) == 'E')
                    && !(text.charAt(i) == '0' && end > i + 1 && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X'))) {
                    end++;
                } else {
                    break;
                }
            }
            return end;
        }

        int wordType(int start, int end) {
            String word = text.subSequence(start, end).toString();
            if (language.caseInsensitive) word = word.toLowerCase(Locale.ROOT);
            if (language.keywords.contains(word)) return KEYWORD;
            if (language.literals.contains(word)) return LITERAL;
            int after = skipSpaces(end, Math.min(text.length(), end + 64));
            if (after < text.length()) {
                char next = text.charAt(after);
                if (language.colonKeys && next == ':' && !(after + 1 < text.length() && text.charAt(after + 1) == ':')) return KEY;
                if (next == '(' && after == end) return FUNCTION;
            }
            return -1;
        }

        void scanMarkup(int start) {
            int i = start;
            int length = text.length();
            while (i < to) {
                char c = text.charAt(i);
                if (c == '<') {
                    if (startsWith(i, "<!--")) {
                        int end = indexOf("-->", i + 4);
                        end = end < 0 ? length : end + 3;
                        emit(i, end, COMMENT);
                        i = end;
                        continue;
                    }
                    if (startsWith(i, "<![CDATA[")) {
                        int end = indexOf("]]>", i + 9);
                        end = end < 0 ? length : end + 3;
                        emit(i, end, STRING);
                        i = end;
                        continue;
                    }
                    if (i + 1 < length && (Character.isLetter(text.charAt(i + 1)) || "/!?".indexOf(text.charAt(i + 1)) >= 0)) {
                        i = scanTag(i);
                        continue;
                    }
                } else if (c == '&') {
                    int end = i + 1;
                    while (end < length && end < i + 12 && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '#')) end++;
                    if (end < length && text.charAt(end) == ';') {
                        emit(i, end + 1, LITERAL);
                        i = end + 1;
                        continue;
                    }
                }
                i++;
            }
        }

        int scanTag(int i) {
            int length = text.length();
            int end = i + 1;
            while (end < length && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '>' && !startsWith(end, "/>")) end++;
            emit(i, end, TAG);
            int j = end;
            while (j < length) {
                char c = text.charAt(j);
                if (c == '>') {
                    emit(j, j + 1, TAG);
                    return j + 1;
                }
                if (startsWith(j, "/>") || startsWith(j, "?>")) {
                    emit(j, j + 2, TAG);
                    return j + 2;
                }
                if (c == '"' || c == '\'') {
                    int close = j + 1;
                    while (close < length && text.charAt(close) != c) close++;
                    close = Math.min(length, close + 1);
                    emit(j, close, STRING);
                    j = close;
                    continue;
                }
                if (Character.isLetter(c) || c == '_' || c == ':' || c == '@') {
                    int nameEnd = j + 1;
                    while (nameEnd < length && (Character.isLetterOrDigit(text.charAt(nameEnd)) || "-_:.@".indexOf(text.charAt(nameEnd)) >= 0)) nameEnd++;
                    emit(j, nameEnd, KEY);
                    j = nameEnd;
                    continue;
                }
                if (c == '<') return j; // An unclosed tag.
                j++;
            }
            return length;
        }

        void scanMarkdown(int start) {
            int i = start;
            boolean inFence = false;
            String fence = null;
            while (i < to) {
                int end = lineEnd(i);
                int content = skipSpaces(i, end);
                if (inFence) {
                    if (startsWith(content, fence)) {
                        inFence = false;
                        emit(i, end, META);
                    } else {
                        emit(i, end, STRING);
                    }
                } else if (startsWith(content, "```") || startsWith(content, "~~~")) {
                    inFence = true;
                    fence = text.subSequence(content, content + 3).toString();
                    emit(i, end, META);
                } else if (content < end && text.charAt(content) == '#') {
                    emit(content, end, META);
                } else if (content < end && text.charAt(content) == '>') {
                    emit(content, end, COMMENT);
                } else {
                    int inline = content;
                    if (content + 1 < end && "-*+".indexOf(text.charAt(content)) >= 0 && text.charAt(content + 1) == ' ') {
                        emit(content, content + 1, KEYWORD);
                        inline = content + 2;
                    } else {
                        int digits = content;
                        while (digits < end && Character.isDigit(text.charAt(digits))) digits++;
                        if (digits > content && digits + 1 < end && (text.charAt(digits) == '.' || text.charAt(digits) == ')') && text.charAt(digits + 1) == ' ') {
                            emit(content, digits + 1, KEYWORD);
                            inline = digits + 2;
                        }
                    }
                    scanMarkdownInline(inline, end);
                }
                i = end + 1;
            }
        }

        void scanMarkdownInline(int i, int end) {
            while (i < end) {
                char c = text.charAt(i);
                if (c == '`') {
                    int close = indexOfInLine('`', i + 1, end);
                    if (close > 0) {
                        emit(i, close + 1, STRING);
                        i = close + 1;
                        continue;
                    }
                } else if (c == '[') {
                    int close = indexOfInLine(']', i + 1, end);
                    if (close > 0 && close + 1 < end && text.charAt(close + 1) == '(') {
                        int paren = indexOfInLine(')', close + 2, end);
                        if (paren > 0) {
                            emit(i, close + 1, KEY);
                            emit(close + 1, paren + 1, LITERAL);
                            i = paren + 1;
                            continue;
                        }
                    }
                } else if ((c == '*' || c == '_') && i + 1 < end && text.charAt(i + 1) == c) {
                    int close = indexOf("" + c + c, i + 2);
                    if (close > 0 && close < end) {
                        emit(i, close + 2, KEYWORD);
                        i = close + 2;
                        continue;
                    }
                }
                i++;
            }
        }

        void scanDiff(int start) {
            int i = start;
            while (i < to) {
                int end = lineEnd(i);
                if (startsWith(i, "+++") || startsWith(i, "---") || startsWith(i, "diff ") || startsWith(i, "index ") || startsWith(i, "@@"))
                    emit(i, end, META);
                else if (startsWith(i, "+"))
                    emit(i, end, ADDED);
                else if (startsWith(i, "-"))
                    emit(i, end, REMOVED);
                i = end + 1;
            }
        }
    }

}
