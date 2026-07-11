package com.dbboys.remote;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RemotePasswordUtil {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "@#_+-=";
    private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;
    private static final int DEFAULT_LENGTH = 12;

    private RemotePasswordUtil() {
    }

    public static String generateComplexPassword() {
        return generateComplexPassword(DEFAULT_LENGTH);
    }

    public static String generateComplexPassword(int length) {
        int safeLength = Math.max(8, length);
        List<Character> chars = new ArrayList<>(safeLength);
        chars.add(randomChar(UPPER));
        chars.add(randomChar(LOWER));
        chars.add(randomChar(DIGITS));
        chars.add(randomChar(SYMBOLS));
        while (chars.size() < safeLength) {
            chars.add(randomChar(ALL));
        }
        Collections.shuffle(chars, RANDOM);
        StringBuilder password = new StringBuilder(safeLength);
        for (Character ch : chars) {
            password.append(ch);
        }
        return password.toString();
    }

    private static char randomChar(String chars) {
        return chars.charAt(RANDOM.nextInt(chars.length()));
    }
}
