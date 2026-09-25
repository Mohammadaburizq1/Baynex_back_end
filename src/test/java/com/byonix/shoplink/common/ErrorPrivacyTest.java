package com.byonix.shoplink.common;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ErrorPrivacyTest {
    @Test void unexpectedErrorDoesNotLogExceptionPayload(CapturedOutput output) {
        var result = new GlobalExceptionHandler().generic(new RuntimeException("token=super-sensitive-value"));
        assertEquals(500, result.getStatusCode().value());
        assertNotNull(result.getHeaders().getFirst("X-Error-Id"));
        assertFalse(output.getAll().contains("super-sensitive-value"));
        assertFalse(result.getBody().toString().contains("super-sensitive-value"));
    }
}
