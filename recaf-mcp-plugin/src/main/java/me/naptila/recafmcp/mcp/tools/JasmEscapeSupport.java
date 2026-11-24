package me.naptila.recafmcp.mcp.tools;

import me.darknet.assembler.parser.Token;
import me.darknet.assembler.parser.TokenType;
import me.darknet.assembler.parser.Tokenizer;
import me.darknet.assembler.descriptor.DescriptorParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Restores Jasm unicode escapes inside identifier tokens that Jasm's printer
 * emits via {@code EscapeUtil.escapeLiteral} but {@code OperandValues} and
 * {@code DescriptorParser} read via raw {@code Token.content()}.
 */
final class JasmEscapeSupport {
    private JasmEscapeSupport() {
    }

    static List<Token> normalizeIdentifierTokens(List<Token> tokens) {
        boolean changed = false;
        List<Token> normalized = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            if (token.type() == TokenType.IDENTIFIER && token.content().contains("\\u")) {
                String unescaped = unescapeSafeIdentifierUnicode(token.content());
                if (!unescaped.equals(token.content())) {
                    normalized.add(new Token(
                            token.range(),
                            token.location(),
                            token.type(),
                            unescaped
                    ));
                    changed = true;
                    continue;
                }
            }
            normalized.add(token);
        }
        return changed ? List.copyOf(normalized) : tokens;
    }

    static String unescapeSafeIdentifierUnicode(String content) {
        int length = content.length();
        StringBuilder out = null;
        int index = 0;
        while (index < length) {
            char current = content.charAt(index);
            if (current == '\\' && index + 1 < length) {
                char next = content.charAt(index + 1);
                if (next == '\\') {
                    if (out != null) {
                        out.append("\\\\");
                    }
                    index += 2;
                    continue;
                }
                if (next == 'u' && index + 5 < length && isHexQuad(content, index + 2)) {
                    char decoded = (char) Integer.parseInt(
                            content.substring(index + 2, index + 6),
                            16
                    );
                    if (isSafeRawIdentifierChar(decoded)) {
                        if (out == null) {
                            out = new StringBuilder(length);
                            out.append(content, 0, index);
                        }
                        out.append(decoded);
                        index += 6;
                        continue;
                    }
                }
            }
            if (out != null) {
                out.append(current);
            }
            index++;
        }
        return out == null ? content : out.toString();
    }

    /**
     * Unescapes Java/Jasm {@code \\uXXXX} sequences (preserving literal {@code \\\\uXXXX})
     * so search queries match consistently across raw Unicode and escaped renderings.
     */
    static String unescapeUnicodeLiterals(String content) {
        if (content == null || content.indexOf("\\u") < 0) {
            return content;
        }
        int length = content.length();
        StringBuilder out = null;
        int index = 0;
        while (index < length) {
            char current = content.charAt(index);
            if (current == '\\' && index + 1 < length) {
                char next = content.charAt(index + 1);
                if (next == '\\') {
                    if (out != null) {
                        out.append("\\\\");
                    }
                    index += 2;
                    continue;
                }
                if (next == 'u') {
                    int uEnd = index + 2;
                    while (uEnd < length && content.charAt(uEnd) == 'u') {
                        uEnd++;
                    }
                    if (uEnd + 4 <= length && isHexQuad(content, uEnd)) {
                        char decoded = (char) Integer.parseInt(
                                content.substring(uEnd, uEnd + 4),
                                16
                        );
                        if (out == null) {
                            out = new StringBuilder(length);
                            out.append(content, 0, index);
                        }
                        out.append(decoded);
                        index = uEnd + 4;
                        continue;
                    }
                }
            }
            if (out != null) {
                out.append(current);
            }
            index++;
        }
        return out == null ? content : out.toString();
    }

    private static boolean isHexQuad(String value, int start) {
        for (int offset = 0; offset < 4; offset++) {
            if (!Tokenizer.isHex(value.charAt(start + offset))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeRawIdentifierChar(char ch) {
        if (DescriptorParser.isInvalidInternalNameChar(ch)) {
            return false;
        }
        if (Tokenizer.isOperator(ch)) {
            return false;
        }
        return ch != '"'
                && ch != '\''
                && ch != '\\'
                && ch != '\n'
                && ch != '\r';
    }
}
