package com.byonix.shoplink.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class OrderCodeGenerator {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] code = new char[8];
        for (int i = 0; i < code.length; i++) {
            code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }
}
