package com.lifeos.ai.domain

import com.lifeos.ai.domain.usecase.AuthValidator
import com.lifeos.ai.domain.usecase.AuthValidator.EmailError
import com.lifeos.ai.domain.usecase.AuthValidator.NameError
import com.lifeos.ai.domain.usecase.AuthValidator.PasswordError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidatorTest {

    @Test fun `empty email is rejected`() = assertEquals(EmailError.EMPTY, AuthValidator.validateEmail("  "))

    @Test fun `malformed emails are rejected`() {
        listOf("abc", "a@b", "a@@b.com", "a b@c.com", "user@domain..com", "@x.com").forEach {
            assertEquals("expected invalid: $it", EmailError.INVALID, AuthValidator.validateEmail(it))
        }
    }

    @Test fun `valid emails pass, surrounding whitespace ignored`() {
        listOf("user@example.com", " first.last+tag@sub.domain.in ", "a_b-c@x.co").forEach {
            assertNull("expected valid: $it", AuthValidator.validateEmail(it))
        }
    }

    @Test fun `login only requires a password to be present`() {
        assertEquals(PasswordError.EMPTY, AuthValidator.validateLoginPassword(""))
        assertNull(AuthValidator.validateLoginPassword("x"))
    }

    @Test fun `new password strength rules`() {
        assertEquals(PasswordError.EMPTY, AuthValidator.validateNewPassword(""))
        assertEquals(PasswordError.TOO_SHORT, AuthValidator.validateNewPassword("abc12"))
        assertEquals(PasswordError.NO_DIGIT, AuthValidator.validateNewPassword("abcdefgh"))
        assertEquals(PasswordError.NO_LETTER, AuthValidator.validateNewPassword("12345678"))
        assertNull(AuthValidator.validateNewPassword("lifeos2026"))
    }

    @Test fun `password confirmation must match exactly`() {
        assertTrue(AuthValidator.passwordsMatch("Secret123", "Secret123"))
        assertFalse(AuthValidator.passwordsMatch("Secret123", "secret123"))
    }

    @Test fun `name validation`() {
        assertEquals(NameError.EMPTY, AuthValidator.validateName(" "))
        assertEquals(NameError.TOO_LONG, AuthValidator.validateName("x".repeat(51)))
        assertNull(AuthValidator.validateName("Niraj"))
    }
}
