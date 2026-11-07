package com.lifeos.ai.domain.usecase

import com.lifeos.ai.domain.model.DefaultCategories
import com.lifeos.ai.domain.model.RepeatType
import com.lifeos.ai.domain.model.TransactionType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Structured, validated actions the assistant may perform (always after user confirmation). */
sealed interface AssistantIntent {
    data class CreateReminder(
        val title: String,
        /** Null when the user didn't say when - the UI must ask before saving. */
        val at: Long?,
        val repeat: RepeatType = RepeatType.NONE,
    ) : AssistantIntent

    data class AddTransaction(
        val amountMinor: Long,
        val type: TransactionType,
        val category: String,
        val description: String,
    ) : AssistantIntent

    data class AddShoppingItems(val items: List<String>) : AssistantIntent
    data class CreateNote(val title: String, val content: String) : AssistantIntent
    data class CreateTask(val title: String, val dueAt: Long?) : AssistantIntent

    /** Read-only queries - answered from the local database, no confirmation needed. */
    data class QueryExpenses(val period: Period) : AssistantIntent
    data class QuerySchedule(val dayOffset: Int) : AssistantIntent

    /** The user asked to create something but gave no details: open the editor. */
    data class OpenEditor(val target: Target) : AssistantIntent

    enum class Period { TODAY, MONTH }
    enum class Target { REMINDER, NOTE, TASK, EXPENSE }

    val requiresConfirmation: Boolean
        get() = this is CreateReminder || this is AddTransaction || this is AddShoppingItems ||
            this is CreateNote || this is CreateTask
}

/**
 * Deterministic parser for common English / Hindi / Hinglish commands. It runs fully offline and
 * is the first line of intent detection; ambiguous input falls through to the AI backend or chat.
 */
class CommandParser(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> LocalDateTime = { LocalDateTime.now(zone) },
) {

    fun parse(raw: String): AssistantIntent? {
        val text = normalize(raw)
        if (text.isBlank()) return null
        return parseQuery(text)
            ?: parseShopping(text)
            ?: parseTransaction(text)
            ?: parseReminder(text)
            ?: parseNote(text, raw)
            ?: parseTask(text)
    }

    // ---------------------------------------------------------------- normalization

    internal fun normalize(input: String): String {
        var s = input.lowercase().trim()
        // Devanagari digits -> ASCII
        s = s.map { c -> if (c in '०'..'९') ('0' + (c - '०')) else c }.joinToString("")
        s = s.replace("a.m.", "am").replace("p.m.", "pm")
        s = s.replace(Regex("(?<=\\d),(?=\\d{2,3}\\b)"), "")      // 1,200 -> 1200
        s = s.replace(",", " , ")                                 // keep commas as list separators
        s = s.replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")       // drop sentence dots, keep decimals
        s = s.replace(Regex(":(?=\\s|$)"), " : ")                 // "note: x" -> "note : x" (keeps 6:30)
        s = s.replace(Regex("[!?।\"“”]+"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun containsAny(text: String, words: List<String>): Boolean =
        words.any { w -> Regex("(^|\\s)${Regex.escape(w)}(\\s|$)").containsMatchIn(text) || (w.contains(' ') && text.contains(w)) }

    // ---------------------------------------------------------------- queries

    private val showWords = listOf(
        "show", "dikhao", "dikha", "batao", "bata", "kitna", "kitne", "how much", "what is", "what's",
        "दिखाओ", "दिखा", "बताओ", "कितना", "कितने", "list",
    )
    private val expenseWords = listOf(
        "expense", "expenses", "kharch", "kharcha", "kharche", "spending", "spent", "खर्च", "खर्चा", "ख़र्च",
    )

    private fun parseQuery(text: String): AssistantIntent? {
        val asksExpense = containsAny(text, expenseWords)
        val isQuestion = containsAny(text, showWords)
        if (asksExpense && isQuestion && !Regex("\\d").containsMatchIn(text)) {
            val month = containsAny(text, listOf("month", "monthly", "mahine", "mahina", "महीने", "महीना", "मासिक"))
            return AssistantIntent.QueryExpenses(if (month) AssistantIntent.Period.MONTH else AssistantIntent.Period.TODAY)
        }
        val asksSchedule = containsAny(text, listOf("schedule", "plan", "agenda", "शेड्यूल", "कार्यक्रम", "program"))
        if (asksSchedule && (isQuestion || containsAny(text, listOf("kal", "aaj", "today", "tomorrow", "कल", "आज", "parso", "परसों")))) {
            return AssistantIntent.QuerySchedule(dayOffset(text) ?: 0)
        }
        return null
    }

    // ---------------------------------------------------------------- shopping list

    private fun parseShopping(text: String): AssistantIntent? {
        val match = Regex("(shopping list|shopping|शॉपिंग लिस्ट|खरीदारी सूची|kharidari list)").find(text) ?: return null
        if (!containsAny(text, listOf("add", "daalo", "dalo", "daal", "jodo", "likho", "put", "जोड़ो", "डालो", "लिखो", "ऐड"))) return null
        var rest = text.removeRange(match.range)
        rest = removeWords(rest, listOf(
            "add", "karo", "kar", "do", "daalo", "dalo", "daal", "jodo", "likho", "put", "to", "my", "the", "in",
            "into", "mein", "me", "mai", "list", "please", "plz", "में", "मे", "जोड़ो", "डालो", "लिखो", "करो",
            "कर", "दो", "ऐड", "मेरी", "meri", "item", "items",
        ))
        val items = rest.split(Regex("\\s(?:and|aur|और|&)\\s|\\s*,\\s*"))
            .map { it.trim(' ', ',', ':') }.filter { it.isNotEmpty() && it.length <= 60 }
        if (items.isEmpty()) return null
        return AssistantIntent.AddShoppingItems(items.map { it.replaceFirstChar { c -> c.uppercase() } })
    }

    // ---------------------------------------------------------------- income / expense

    private val amountRegex = Regex("(?:₹|rs\\.?|inr)\\s*(\\d+(?:\\.\\d{1,2})?)|(\\d+(?:\\.\\d{1,2})?)\\s*(?:rupees|rupee|rupaye|rupay|rs\\.?|₹|रुपये|रुपए|रूपये|रु)?")

    private fun parseTransaction(text: String): AssistantIntent? {
        val incomeWords = listOf("income", "salary", "earned", "received", "kamaya", "kamai", "mile", "mila", "आमदनी", "कमाई", "सैलरी", "मिले", "मिला")
        val isIncome = containsAny(text, incomeWords)
        val isExpense = containsAny(text, expenseWords + listOf("paid", "pay", "kharcha", "diye", "lagaye", "दिए", "लगाए", "spend"))
        if (!isIncome && !isExpense) return null
        val hasCurrency = Regex("(₹|rs\\b|rs\\.|inr|rupee|rupaye|rupay|रुपये|रुपए|रूपये)").containsMatchIn(text)
        val m = amountRegex.findAll(text).firstOrNull { (it.groupValues[1] + it.groupValues[2]).isNotEmpty() } ?: return null
        val number = m.groupValues[1].ifEmpty { m.groupValues[2] }
        // Without a currency word we still accept "add 200 expense for food".
        if (!hasCurrency && !containsAny(text, listOf("add", "jodo", "likho", "daalo", "जोड़ो", "लिखो"))) return null
        val minor = number.toBigDecimalOrNull()?.movePointRight(2)?.toLong() ?: return null
        if (minor <= 0 || minor > 10_000_000_000L) return null
        val type = if (isIncome && !containsAny(text, expenseWords)) TransactionType.INCOME else TransactionType.EXPENSE
        val category = if (type == TransactionType.INCOME) incomeCategory(text) else expenseCategory(text)
        val desc = extractDescription(text.removeRange(m.range))
        return AssistantIntent.AddTransaction(minor, type, category, desc)
    }

    private fun extractDescription(text: String): String {
        val forMatch = Regex("(?:for|on|ke liye|ka|ki|के लिए|पर)\\s+([\\p{L} ]{2,40})").find(text)
        val candidate = forMatch?.groupValues?.get(1) ?: return ""
        return removeWords(candidate, fillerWords + expenseWords + listOf("add", "karo", "expense", "income", "today", "aaj", "आज")).trim()
            .replaceFirstChar { it.uppercase() }
    }

    private val categoryKeywords: Map<String, List<String>> = mapOf(
        DefaultCategories.FOOD to listOf("food", "khana", "khane", "lunch", "dinner", "breakfast", "nashta", "snacks", "tea", "chai", "coffee", "restaurant", "grocery", "groceries", "sabzi", "खाना", "खाने", "नाश्ता", "चाय", "राशन"),
        DefaultCategories.TRAVEL to listOf("travel", "petrol", "diesel", "fuel", "taxi", "cab", "auto", "bus", "train", "metro", "uber", "ola", "flight", "safar", "किराया", "पेट्रोल", "यात्रा", "सफर"),
        DefaultCategories.SHOPPING to listOf("shopping", "clothes", "kapde", "shoes", "amazon", "flipkart", "कपड़े", "खरीदारी"),
        DefaultCategories.BILLS to listOf("bill", "bills", "electricity", "bijli", "recharge", "rent", "kiraya", "wifi", "internet", "gas", "water", "बिजली", "बिल", "रिचार्ज"),
        DefaultCategories.HEALTH to listOf("medicine", "medicines", "dawai", "dawa", "doctor", "hospital", "pharmacy", "gym", "दवाई", "दवा", "डॉक्टर"),
        DefaultCategories.EDUCATION to listOf("book", "books", "fees", "fee", "school", "college", "course", "tuition", "kitab", "किताब", "फीस", "पढ़ाई"),
        DefaultCategories.ENTERTAINMENT to listOf("movie", "film", "netflix", "game", "games", "party", "concert", "फिल्म", "मूवी"),
    )

    internal fun expenseCategory(text: String): String =
        categoryKeywords.entries.firstOrNull { (_, words) -> containsAny(text, words) }?.key ?: DefaultCategories.OTHER

    private fun incomeCategory(text: String): String = when {
        containsAny(text, listOf("salary", "सैलरी", "tankhwah", "वेतन")) -> DefaultCategories.SALARY
        containsAny(text, listOf("business", "dukaan", "shop", "sale", "बिक्री", "दुकान")) -> DefaultCategories.BUSINESS
        containsAny(text, listOf("gift", "तोहफा", "uphaar")) -> DefaultCategories.GIFT
        else -> DefaultCategories.OTHER
    }

    // ---------------------------------------------------------------- reminders

    private val reminderWords = listOf(
        "remind", "reminder", "alarm", "yaad", "yad", "याद", "रिमाइंडर", "अलार्म", "remember",
    )

    private fun parseReminder(text: String): AssistantIntent? {
        if (!containsAny(text, reminderWords) && !text.contains("remind me")) return null
        val repeat = when {
            containsAny(text, listOf("daily", "every day", "everyday", "roz", "rozana", "har din", "रोज़", "रोज", "हर दिन", "प्रतिदिन")) -> RepeatType.DAILY
            containsAny(text, listOf("weekly", "every week", "har hafte", "हर हफ्ते", "साप्ताहिक")) -> RepeatType.WEEKLY
            containsAny(text, listOf("monthly", "every month", "har mahine", "हर महीने", "मासिक")) -> RepeatType.MONTHLY
            else -> RepeatType.NONE
        }
        val time = parseTime(text)
        val offset = dayOffset(text)
        val at: Long? = if (time == null && offset == null) null else {
            val now = clock()
            var date = now.toLocalDate().plusDays((offset ?: 0).toLong())
            val t = time ?: LocalTime.of(9, 0)
            if (offset == null && LocalDateTime.of(date, t) <= now) date = date.plusDays(1)
            LocalDateTime.of(date, t).atZone(zone).toInstant().toEpochMilli()
        }
        val title = reminderTitle(text)
        if (title.isBlank() && at == null) return AssistantIntent.OpenEditor(AssistantIntent.Target.REMINDER)
        return AssistantIntent.CreateReminder(title.ifBlank { "Reminder" }, at, repeat)
    }

    private fun reminderTitle(text: String): String {
        // "remind me to call mom at 5 pm" -> "call mom"
        Regex("remind me (?:to |about )?(.+)").find(text)?.let { m ->
            val t = stripTimeWords(m.groupValues[1])
            if (t.isNotBlank()) return t.replaceFirstChar { it.uppercase() }
        }
        var t = stripTimeWords(text)
        t = removeWords(t, reminderWords + fillerWords + listOf(
            "set", "create", "add", "laga", "lagao", "lagado", "laga do", "bana", "banao", "do", "dena", "dijiye",
            "karo", "kar", "new", "a", "an", "mujhe", "muje", "mere", "liye", "ke", "ka", "ki", "ko", "for",
            "me", "to", "please", "लगा", "दो", "लगाओ", "मुझे", "के", "का", "की", "को", "लिए", "बनाओ", "करो",
            "daily", "weekly", "monthly", "roz", "har", "din", "every", "day", "dilana", "dilao", "dila",
        ))
        return t.trim().replaceFirstChar { it.uppercase() }
    }

    // ---------------------------------------------------------------- notes & tasks

    private fun parseNote(text: String, raw: String): AssistantIntent? {
        val noteWord = containsAny(text, listOf("note", "notes", "नोट"))
        val createWord = containsAny(text, listOf("create", "make", "new", "banao", "bana", "likho", "add", "write", "बनाओ", "लिखो", "नया"))
        if (!noteWord || !createWord) return null
        val after = Regex("(?:note|नोट)\\s*[:\\-]\\s*(.+)").find(raw.trim())?.groupValues?.get(1)
            ?: Regex("(?:that|ki|कि)\\s+(.+)").find(text)?.groupValues?.get(1)
        val content = after?.trim().orEmpty()
        if (content.isEmpty()) return AssistantIntent.OpenEditor(AssistantIntent.Target.NOTE)
        return AssistantIntent.CreateNote(content.take(40).replaceFirstChar { it.uppercase() }, content)
    }

    private fun parseTask(text: String): AssistantIntent? {
        if (!containsAny(text, listOf("task", "todo", "to-do", "to do", "kaam", "काम", "टास्क"))) return null
        if (!containsAny(text, listOf("add", "create", "new", "banao", "bana", "jodo", "likho", "बनाओ", "जोड़ो", "नया"))) return null
        val offset = dayOffset(text)
        val time = parseTime(text)
        var title = stripTimeWords(text)
        title = removeWords(title, fillerWords + listOf(
            "task", "todo", "to-do", "kaam", "काम", "टास्क", "add", "create", "new", "banao", "bana", "jodo",
            "likho", "a", "an", "to", "my", "list", "karo", "ka", "ki", "ke", "बनाओ", "जोड़ो", "नया", "करो", "please",
        )).trim()
        if (title.isBlank()) return AssistantIntent.OpenEditor(AssistantIntent.Target.TASK)
        val due = if (offset == null && time == null) null else {
            val d = clock().toLocalDate().plusDays((offset ?: 0).toLong())
            LocalDateTime.of(d, time ?: LocalTime.of(23, 59)).atZone(zone).toInstant().toEpochMilli()
        }
        return AssistantIntent.CreateTask(title.replaceFirstChar { it.uppercase() }, due)
    }

    // ---------------------------------------------------------------- date/time helpers

    private val fillerWords = listOf("please", "plz", "pls", "zara", "ज़रा", "kripya", "कृपया", "hey", "lifeos")

    internal fun dayOffset(text: String): Int? = when {
        containsAny(text, listOf("day after tomorrow", "parso", "parson", "परसों")) -> 2
        containsAny(text, listOf("tomorrow", "kal", "कल", "tmrw")) -> 1
        containsAny(text, listOf("today", "aaj", "आज", "tonight")) -> 0
        else -> null
    }

    /** Parses "7 baje", "7:30 pm", "subah 7", "शाम 6 बजे", "at 19:00". */
    internal fun parseTime(text: String): LocalTime? {
        val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|baje|bje|बजे|o'clock|oclock)").find(text)
            ?: Regex("(?:at|@|subah|sham|shaam|raat|dopahar|सुबह|शाम|रात|दोपहर)\\s*(\\d{1,2})(?:[:.](\\d{2}))?").find(text)
            ?: Regex("\\b(\\d{1,2})[:.](\\d{2})\\b").find(text)
            ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour > 23 || minute > 59) return null
        val suffix = m.groupValues.getOrNull(3).orEmpty()
        val pm = suffix.startsWith("p") ||
            containsAny(text, listOf("pm", "evening", "night", "sham", "shaam", "raat", "शाम", "रात", "tonight")) ||
            (containsAny(text, listOf("dopahar", "afternoon", "दोपहर")) && hour < 12)
        val am = suffix.startsWith("a") || containsAny(text, listOf("am", "morning", "subah", "सुबह"))
        if (pm && hour < 12 && !(containsAny(text, listOf("raat", "रात", "night")) && hour < 4)) hour += 12
        if (am && hour == 12) hour = 0
        return LocalTime.of(hour, minute)
    }

    private fun stripTimeWords(text: String): String {
        var t = text
        t = t.replace(Regex("(?:at|@)?\\s*\\d{1,2}(?:[:.]\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.|baje|bje|बजे|o'clock|oclock)"), " ")
        t = t.replace(Regex("\\b\\d{1,2}[:.]\\d{2}\\b"), " ")
        t = t.replace(Regex("(?:at|@|subah|sham|shaam|raat|dopahar|सुबह|शाम|रात|दोपहर)\\s*\\d{1,2}"), " ")
        t = removeWords(t, listOf(
            "today", "tomorrow", "tonight", "day after tomorrow", "aaj", "kal", "parso", "parson", "आज", "कल", "परसों",
            "subah", "sham", "shaam", "raat", "dopahar", "morning", "evening", "night", "afternoon",
            "सुबह", "शाम", "रात", "दोपहर", "at", "on", "baje", "बजे",
        ))
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun removeWords(text: String, words: List<String>): String {
        var t = " $text "
        // Longer phrases first so "laga do" is removed before "do".
        for (w in words.sortedByDescending { it.length }) {
            t = t.replace(Regex("(?<=\\s)${Regex.escape(w)}(?=\\s)"), " ")
        }
        return t.replace(Regex("\\s+"), " ").trim().trim(',', ':', ' ').replace(Regex("\\s+,"), ",")
    }

    companion object {
        fun todayIn(zone: ZoneId): LocalDate = LocalDate.now(zone)
    }
}
