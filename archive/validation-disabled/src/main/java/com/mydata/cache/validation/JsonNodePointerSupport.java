package com.mydata.cache.validation;

import com.fasterxml.jackson.databind.JsonNode;

final class JsonNodePointerSupport {

    private JsonNodePointerSupport() {
    }

    static boolean isPresentNonNullAtJsonPointer(JsonNode root, String pointer) {
        if (root == null) {
            return false;
        }
        if (pointer == null) {
            return false;
        }
        if (pointer.isEmpty()) {
            return !root.isNull();
        }
        if (pointer.charAt(0) != '/') {
            return false;
        }

        String[] tokens = splitPointerTokens(pointer);
        return isPresentNonNullAtTokens(root, tokens, 0);
    }

    private static boolean isPresentNonNullAtTokens(JsonNode node, String[] tokens, int index) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (index >= tokens.length) {
            return true;
        }

        String token = tokens[index];
        if (token == null) {
            return false;
        }

        if ("*".equals(token)) {
            if (!node.isArray()) {
                return false;
            }
            if (node.size() == 0) {
                return true;
            }
            for (JsonNode el : node) {
                if (!isPresentNonNullAtTokens(el, tokens, index + 1)) {
                    return false;
                }
            }
            return true;
        }

        JsonNode next;
        if (node.isObject()) {
            next = node.get(token);
        } else if (node.isArray()) {
            int idx = parseArrayIndexOrMinusOne(token);
            if (idx < 0) {
                return false;
            }
            next = node.get(idx);
        } else {
            return false;
        }

        if (next == null || next.isNull()) {
            return false;
        }

        return isPresentNonNullAtTokens(next, tokens, index + 1);
    }

    private static int parseArrayIndexOrMinusOne(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = (n * 10) + (c - '0');
        }
        return n;
    }

    private static String[] splitPointerTokens(String pointer) {
        int len = pointer.length();
        if (len <= 1) {
            return new String[0];
        }
        java.util.ArrayList<String> tokens = new java.util.ArrayList<String>();
        int i = 1;
        while (i <= len) {
            int nextSlash = pointer.indexOf('/', i);
            String raw;
            if (nextSlash == -1) {
                raw = pointer.substring(i);
                i = len + 1;
            } else {
                raw = pointer.substring(i, nextSlash);
                i = nextSlash + 1;
            }
            if (raw.isEmpty()) {
                continue;
            }
            tokens.add(unescapePointerToken(raw));
        }
        return tokens.toArray(new String[tokens.size()]);
    }

    private static String unescapePointerToken(String token) {
        int tilda = token.indexOf('~');
        if (tilda < 0) {
            return token;
        }
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '~') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= token.length()) {
                sb.append('~');
                continue;
            }
            char n = token.charAt(i + 1);
            if (n == '0') {
                sb.append('~');
                i++;
            } else if (n == '1') {
                sb.append('/');
                i++;
            } else {
                sb.append('~');
            }
        }
        return sb.toString();
    }
}
