package com.termux.app.terminal.sftp;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.termux.app.terminal.sftp.SyntaxHighlighter.*;

public class SyntaxHighlighterTest {

    private static final String[] NAMES = {"comment", "string", "number", "keyword", "literal", "function",
        "key", "tag", "variable", "meta", "added", "removed"};

    /** The tokens of a text as "type:text". */
    private static List<String> tokens(String languageName, String text) {
        return tokens(SyntaxHighlighter.find(languageName), text, 0, text.length());
    }

    private static List<String> tokens(Language language, String text, int from, int to) {
        Assert.assertNotNull(language);
        List<String> tokens = new ArrayList<>();
        SyntaxHighlighter.highlight(text, 0, from, to, language,
            (start, end, type) -> tokens.add(NAMES[type] + ":" + text.substring(start, end)));
        return tokens;
    }

    @Test
    public void testDetect() {
        Assert.assertEquals("Python", SyntaxHighlighter.detect("a.py", null).name);
        Assert.assertEquals("Python", SyntaxHighlighter.detect("run", "#!/usr/bin/env python3.11").name);
        Assert.assertEquals("Shell", SyntaxHighlighter.detect("run", "#!/data/data/com.termux/files/usr/bin/bash").name);
        Assert.assertEquals("Shell", SyntaxHighlighter.detect(".bashrc", null).name);
        Assert.assertEquals("Makefile", SyntaxHighlighter.detect("Makefile", null).name);
        Assert.assertEquals("Dockerfile", SyntaxHighlighter.detect("Dockerfile.dev", null).name);
        Assert.assertEquals("TOML/INI", SyntaxHighlighter.detect("termux.properties", null).name);
        Assert.assertEquals("JavaScript", SyntaxHighlighter.detect("App.TSX", null).name);
        Assert.assertNull(SyntaxHighlighter.detect("notes.txt", "hello"));
        Assert.assertNull(SyntaxHighlighter.detect(".hidden", null));
    }

    @Test
    public void testShell() {
        Assert.assertEquals(Arrays.asList("keyword:if", "string:\"$HOME\"", "keyword:then",
                "string:'it'", "string:'s'", "variable:${x}", "comment:# done", "keyword:fi"),
            tokens("Shell", "if [ -d \"$HOME\" ]; then echo 'it''s' ${x} # done\nfi"));
        // A # inside a word is not a comment.
        Assert.assertEquals(Arrays.asList("variable:${#a}", "variable:$#"), tokens("Shell", "x${#a}y $#"));
    }

    @Test
    public void testPython() {
        Assert.assertEquals(Arrays.asList("meta:@dataclass", "keyword:def", "function:f", "literal:self",
                "keyword:return", "string:\"\"\"a\n# not a comment\"\"\"", "comment:# c", "number:0x1F", "number:1e-3"),
            tokens("Python", "@dataclass\ndef f(self):\n    return \"\"\"a\n# not a comment\"\"\" # c\n0x1F 1e-3"));
    }

    @Test
    public void testBlockCommentBeforeRange() {
        String text = "/* start\nint x = 1;\n*/ int y;";
        int from = text.indexOf("int x");
        // The comment that starts before the range is known.
        Assert.assertEquals(Arrays.asList("comment:int x = 1;\n*/", "literal:int"),
            tokens(SyntaxHighlighter.find("C/C++"), text, from, text.length()));
    }

    @Test
    public void testCode() {
        Assert.assertEquals(Arrays.asList("meta:#include <stdio.h>", "literal:int", "function:main", "keyword:return", "number:0"),
            tokens("C/C++", "#include <stdio.h>\nint main() { return 0; }"));
        Assert.assertEquals(Arrays.asList("keyword:const", "string:`a\nb`", "comment:// c"),
            tokens("JavaScript", "const s = `a\nb`; // c"));
        Assert.assertEquals(Arrays.asList("keyword:SELECT", "keyword:from", "keyword:where", "string:'x'", "comment:-- c"),
            tokens("SQL", "SELECT * from t where a = 'x' -- c"));
    }

    @Test
    public void testJsonAndConfig() {
        Assert.assertEquals(Arrays.asList("key:\"a\"", "string:\"b\"", "key:\"n\"", "number:1", "key:\"t\"", "literal:true"),
            tokens("JSON", "{\"a\": \"b\", \"n\": 1, \"t\": true}"));
        Assert.assertEquals(Arrays.asList("meta:[core]", "key:name", "string:\"x\"", "comment:# c", "key:extra-keys", "literal:true"),
            tokens("TOML/INI", "[core]\nname = \"x\"\n# c\nextra-keys=true"));
        Assert.assertEquals(Arrays.asList("meta:---", "key:url", "key:list", "key:name", "literal:yes", "comment:# c"),
            tokens("YAML", "---\nurl: http://a.b/c\nlist:\n  - name: yes # c"));
    }

    @Test
    public void testMarkupMarkdownDiff() {
        Assert.assertEquals(Arrays.asList("tag:<a", "key:href", "string:\"x\"", "tag:>", "literal:&amp;", "tag:</a", "tag:>", "comment:<!-- c -->"),
            tokens("HTML/XML", "<a href=\"x\">&amp;</a><!-- c -->"));
        Assert.assertEquals(Arrays.asList("meta:# Title", "keyword:-", "string:`code`", "meta:```", "string:x = 1", "meta:```", "key:[a]", "literal:(http://b)"),
            tokens("Markdown", "# Title\n- `code`\n```\nx = 1\n```\n[a](http://b)"));
        Assert.assertEquals(Arrays.asList("meta:--- a", "meta:+++ b", "meta:@@ -1 +1 @@", "removed:-x", "added:+y"),
            tokens("Diff", "--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y\n z"));
    }

}
