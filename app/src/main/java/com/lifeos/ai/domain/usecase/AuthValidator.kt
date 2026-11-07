package com.lifeos.ai.domain.usecase

/** Pure validation rules for auth forms (unit tested). */
object AuthValidator {

    enum class EmailError { EMPTY, INVALID }
    enum class PasswordError { EMPTY, TOO_SHORT, NO_LETTER, NO_DIGIT }
    enum class NameError { EMPTY, TOO_LONG }

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
    const val MIN_PASSWORD_LENGTH = 8

    fun validateEmail(email: String): EmailError? {
        val e = email.trim()
        return when {
            e.isEmpty() -> EmailError.EMPTY
            !EMAIL_REGEX.matches(e) || e.contains("..") -> EmailError.INVALID
            else -> null
        }
    }

    /** Login only checks presence; strength rules apply at registration. */
    fun validateLoginPassword(password: String): PasswordError? =
        if (password.isEmpty()) PasswordError.EMPTY else null

    fun validateNewPassword(password: String): PasswordError? = when {
        password.isEmpty() -> PasswordError.EMPTY
        password.length < MIN_PASSWORD_LENGTH -> PasswordError.TOO_SHORT
        password.none { it.isLetter() } -> PasswordError.NO_LETTER
        password.none { it.isDigit() } -> PasswordError.NO_DIGIT
        else -> null
    }

    fun passwordsMatch(password: String, confirm: String): Boolean = password == confirm

    fun validateName(name: String): NameError? {
        val n = name.trim()
        return when {
            n.isEmpty() -> NameError.EMPTY
            n.length > 50 -> NameError.TOO_LONG
            else -> null
        }
    }
}
