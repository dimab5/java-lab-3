package ru.mephi.shortener.core;

import java.util.regex.Pattern;

public final class ShortCode {
    public static final String PREFIX = "https://short.example/";
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern CODE = Pattern.compile("[0-9a-zA-Z]{6,11}");

    private ShortCode() {}

    static String encode(long sequence) {
        StringBuilder result = new StringBuilder();
        do {
            result.append(ALPHABET.charAt((int) (sequence % ALPHABET.length())));
            sequence /= ALPHABET.length();
        } while (sequence > 0);
        while (result.length() < 6) {
            result.append('0');
        }
        return result.reverse().toString();
    }

    public static boolean accepts(String value) {
        String code = value.startsWith(PREFIX) ? value.substring(PREFIX.length()) : value;
        return CODE.matcher(code).matches();
    }

    public static String parse(String value) {
        if (!accepts(value)) {
            throw new IllegalArgumentException("Введите короткую ссылку " + PREFIX + "000001 или её код");
        }
        return value.startsWith(PREFIX) ? value.substring(PREFIX.length()) : value;
    }
}
