package com.termux.app.language;

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Checks the translations against the English strings: each translated file has the same
 * strings, and each string the same format placeholders, so that formatting can't fail at
 * runtime with a missing or extra argument.
 */
public class TranslationsTest {

    private static final String[] LANGUAGES = {"ko", "ja", "zh"};
    private static final String[] RES_DIRS = {"src/main/res", "../termux-shared/src/main/res", "../terminal-view/src/main/res"};
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[sdf%]");

    /** The translatable strings of a file, by name. */
    private static Map<String, String> load(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(true);
        Document document = factory.newDocumentBuilder().parse(file);
        Map<String, String> strings = new LinkedHashMap<>();
        NodeList nodes = document.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if ("false".equals(element.getAttribute("translatable"))) continue;
            strings.put(element.getAttribute("name"), element.getTextContent());
        }
        return strings;
    }

    private static List<String> placeholders(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) found.add(matcher.group());
        Collections.sort(found);
        return found;
    }

    @Test
    public void testTranslationsMatchTheEnglishStrings() throws Exception {
        int checked = 0;
        for (String resDir : RES_DIRS) {
            File[] files = new File(resDir, "values").listFiles((dir, name) -> name.endsWith(".xml"));
            Assert.assertNotNull(resDir, files);
            for (File source : files) {
                Map<String, String> english = load(source);
                if (english.isEmpty()) continue;
                for (String language : LANGUAGES) {
                    File translated = new File(new File(resDir, "values-" + language), source.getName());
                    Assert.assertTrue("Missing " + translated, translated.isFile());
                    Map<String, String> strings = load(translated);
                    Assert.assertEquals("Strings of " + translated, english.keySet(), strings.keySet());
                    for (Map.Entry<String, String> entry : english.entrySet()) {
                        Assert.assertEquals("Placeholders of " + entry.getKey() + " in " + translated,
                            placeholders(entry.getValue()), placeholders(strings.get(entry.getKey())));
                        Assert.assertFalse("Empty " + entry.getKey() + " in " + translated, strings.get(entry.getKey()).trim().isEmpty());
                    }
                    checked += strings.size();
                }
            }
        }
        Assert.assertTrue(checked > 1000);
    }

}
