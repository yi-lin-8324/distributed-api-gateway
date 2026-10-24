package com.example.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiKeyHasherTest {

    @Test
    void hashesApiKeyWithSha256() {
        String result = ApiKeyHasher.hash("abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result);
    }
}