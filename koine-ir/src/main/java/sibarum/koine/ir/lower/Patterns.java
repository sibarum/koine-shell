package sibarum.koine.ir.lower;

import java.util.Map;

/** Regular expressions between the portable IR's dialect (POSIX ERE) and the shells' own. */
final class Patterns {

    private Patterns() {
    }

    /** POSIX bracket classes as .NET writes them, inside a bracket expression. */
    private static final Map<String, String> CLASSES = Map.ofEntries(
            Map.entry("[:space:]", "\\s"), Map.entry("[:blank:]", " \\t"), Map.entry("[:digit:]", "0-9"),
            Map.entry("[:alpha:]", "a-zA-Z"), Map.entry("[:alnum:]", "a-zA-Z0-9"), Map.entry("[:upper:]", "A-Z"),
            Map.entry("[:lower:]", "a-z"), Map.entry("[:xdigit:]", "0-9A-Fa-f"),
            Map.entry("[:punct:]", "!-/:-@\\[-`{-~"), Map.entry("[:cntrl:]", "\\x00-\\x1f\\x7f"),
            Map.entry("[:print:]", "\\x20-\\x7e"), Map.entry("[:graph:]", "\\x21-\\x7e"));

    /**
     * An ERE as a .NET pattern, matching the same strings. POSIX classes become .NET ones. In a bracket expression a
     * backslash or {@code [} is escaped, being plain there in an ERE, and a leading {@code ]} too. Outside one,
     * {@code $} becomes {@code \z}, as .NET's {@code $} also matches before a final line break, and a pattern with
     * {@code .} starts with {@code (?s)}, as an ERE's {@code .} also matches a line break.
     */
    static String ereToDotNet(String ere) {
        StringBuilder out = new StringBuilder();
        boolean dot = false;
        for (int i = 0; i < ere.length(); i++) {
            char c = ere.charAt(i);
            if (c == '\\' && i + 1 < ere.length()) {
                out.append(c).append(ere.charAt(++i));
            } else if (c == '[') {
                i = bracket(ere, i, out);
            } else {
                if (c == '.') dot = true;
                out.append(c == '$' ? "\\z" : String.valueOf(c));
            }
        }
        return dot ? "(?s)" + out : out.toString();
    }

    /** Copies the bracket expression that opens at {@code start}, and returns the index of its closing {@code ]}. */
    private static int bracket(String ere, int start, StringBuilder out) {
        out.append('[');
        int i = start + 1;
        if (i < ere.length() && ere.charAt(i) == '^') out.append(ere.charAt(i++));
        if (i < ere.length() && ere.charAt(i) == ']') {
            out.append("\\]");
            i++;
        }
        for (; i < ere.length(); i++) {
            char c = ere.charAt(i);
            if (c == ']') {
                out.append(c);
                return i;
            }
            int close = c == '[' && i + 1 < ere.length() && ere.charAt(i + 1) == ':' ? ere.indexOf(":]", i + 2) : -1;
            String cls = close < 0 ? null : CLASSES.get(ere.substring(i, close + 2));
            if (cls != null) {
                out.append(cls);
                i = close + 1;
            } else {
                out.append(c == '\\' || c == '[' ? "\\" + c : String.valueOf(c));
            }
        }
        throw new IllegalArgumentException("a bracket expression is not closed: " + ere);
    }
}
