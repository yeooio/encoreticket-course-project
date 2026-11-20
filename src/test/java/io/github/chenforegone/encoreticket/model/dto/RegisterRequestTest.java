package io.github.chenforegone.encoreticket.model.dto;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegisterRequestTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void rejectsMismatchedConfirmationWithoutExposingPasswords() {
        RegisterRequest request = new RegisterRequest("Alice", "secret123", "different123", "Alice", null, "", "");

        assertFalse(FACTORY.getValidator().validate(request).isEmpty());
        assertFalse(request.toString().contains("secret123"));
        assertFalse(request.toString().contains("different123"));
    }

    @Test
    void rejectsBlankNickname() {
        RegisterRequest request = new RegisterRequest(
                "Alice", "secret123", "secret123", "   ", null, null, null);

        assertTrue(FACTORY.getValidator().validate(request).stream()
                .anyMatch(error -> error.getPropertyPath().toString().equals("nickname")));
    }
}
