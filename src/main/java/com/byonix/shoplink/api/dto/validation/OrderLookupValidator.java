package com.byonix.shoplink.api.dto.validation;

import com.byonix.shoplink.api.dto.OrderDtos;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class OrderLookupValidator implements ConstraintValidator<ValidOrderLookup, OrderDtos.OrderLookupRequest> {
    @Override
    public boolean isValid(OrderDtos.OrderLookupRequest value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        boolean hasCode = value.orderCode() != null && !value.orderCode().isBlank();
        boolean hasEmail = value.email() != null && !value.email().isBlank();
        boolean hasPhone = value.phone() != null && !value.phone().isBlank();
        if (!hasCode) {
            return false;
        }
        return (hasEmail && !hasPhone) || (!hasEmail && hasPhone);
    }
}
