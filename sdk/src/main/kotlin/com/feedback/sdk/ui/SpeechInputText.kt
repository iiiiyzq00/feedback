package com.feedback.sdk.ui

internal object SpeechInputText {
    fun insert(current: String, selectionStart: Int, recognized: String): Pair<String, Int> {
        val insertion = selectionStart.coerceIn(0, current.length)
        val clean = recognized.trim()
        val separator = if (insertion > 0 && clean.isNotEmpty() && !current[insertion - 1].isWhitespace()) " " else ""
        val addition = separator + clean
        return (current.substring(0, insertion) + addition + current.substring(insertion)) to (insertion + addition.length)
    }
}
