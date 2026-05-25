package com.byonix.shoplink.api.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = OrderLookupValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidOrderLookup {
    String message() default "Provide orderCode with email, or orderCode with phone";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
