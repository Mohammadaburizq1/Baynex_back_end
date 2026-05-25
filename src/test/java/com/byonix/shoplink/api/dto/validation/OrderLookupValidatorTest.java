package com.byonix.shoplink.api.dto.validation;

import com.byonix.shoplink.api.dto.OrderDtos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderLookupValidatorTest {
    private final OrderLookupValidator validator = new OrderLookupValidator();

    @Test
    void rejectsEmailOnly() {
        assertFalse(validator.isValid(new OrderDtos.OrderLookupRequest(null, "a@b.com", null), null));
        assertFalse(validator.isValid(new OrderDtos.OrderLookupRequest("", "a@b.com", null), null));
    }

    @Test
    void acceptsOrderCodeAndEmail() {
        assertTrue(validator.isValid(new OrderDtos.OrderLookupRequest("ABC12345", "a@b.com", null), null));
    }

    @Test
    void acceptsOrderCodeAndPhone() {
        assertTrue(validator.isValid(new OrderDtos.OrderLookupRequest("ABC12345", null, "+962790000000"), null));
    }

    @Test
    void rejectsOrderCodeWithBothEmailAndPhone() {
        assertFalse(validator.isValid(new OrderDtos.OrderLookupRequest("ABC12345", "a@b.com", "+962790000000"), null));
    }
}
