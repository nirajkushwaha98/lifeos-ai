package com.lifeos.ai.domain

import com.lifeos.ai.feature.assistant.looksLikeCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHintsTest {

    @Test fun `everyday questions do not cost an extra interpretation request`() {
        for (question in listOf(
            "Productive din ke liye 3 tips do",
            "Ek creative poem likho",
            "Mera kaam kaise jaldi khatam karun?",
            "Office ka address kya hai",
            "Mujhe dar laga",
        )) assertFalse(question, looksLikeCommand(question))
    }

    @Test fun `text naming an action is still interpreted`() {
        for (command in listOf(
            "Kal 9 baje meeting ka reminder",
            "Doodh shopping list mein daalo",
            "Add 500 for petrol",
            "दवाई लेना याद दिलाना",
            "Is note ko save kar do",
        )) assertTrue(command, looksLikeCommand(command))
    }
}
