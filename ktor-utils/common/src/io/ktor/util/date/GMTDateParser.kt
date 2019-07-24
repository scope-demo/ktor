/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.util.date

private typealias DateChunkParser = GMTDateBuilder.(String) -> Unit

/**
 * Build [GMTDate] parser using [pattern] string.
 *
 * Pattern string format:
 * | Unit     | pattern char | Description                                          |
 * | -------- | ------------ | ---------------------------------------------------- |
 * | Seconds  | s            | parse seconds 0 to 60                                |
 * | Minutes  | m            | parse minutes 0 to 60                                |
 * | Hours    | h            | parse hours 0 to 23                                  |
 * | Month    | M            | parse month from Jan to Dec(see [Month] for details) |
 * | Year     | Y            | parse year                                           |
 * | Any char | *            | Match any character                                  |
 *
// * All other chars are checked to match exact pattern. If you want to exact match any of pattern chars,
// * you should escape it with `\`:
// *
// * @sample: pattern "\s ss" matches string "s 15"
 */
class GMTDateParser(private val pattern: String) {
    private val format = mutableListOf<DateChunkParser>()

    init {
        check(pattern.isNotEmpty()) { "Date parser pattern shouldn't be empty." }

        var index = 1
        var start = 0
        var last = pattern[0]
        var escaped = false

        while (index < pattern.length) {
            val current = pattern[index]

            if (current == '\\') {
                escaped = true
                index++
                continue
            }

            if (current != last) {
                handleChunk(last, start, index)

                last = current
                start = 0
            }

            index++
        }

        handleChunk(last, index, pattern.length)
    }

    /**
     * Parse [GMTDate] from [dateString] using [pattern].
     */
    fun parse(dateString: String): GMTDate {
        val builder = GMTDateBuilder()
        format.forEach { parseChunk ->
            builder.parseChunk(dateString)
        }

        return builder.build()
    }

    private fun handleChunk(token: Char, start: Int, end: Int) {
        val parser: DateChunkParser = when (token) {
            SECONDS -> { input ->
                seconds = input.substring(start until end).toInt()
            }
            MINUTES -> { input ->
                minutes = input.substring(start until end).toInt()
            }
            HOURS -> { input ->
                hours = input.substring(start until end).toInt()
            }
            MONTH -> { input ->
                month = Month.from(input.substring(start until end))
            }
            YEAR -> { input ->
                year = input.substring(start until end).toInt()
            }
            ANY -> { _ -> }
            else -> { input ->
                input.subSequence(start, end).forEachIndexed { index, current ->
                    if (current != token) throw InvalidDateString(input, start + index, pattern)
                }
            }
        }

        format += parser
    }

    private companion object {
        const val SECONDS = 's'
        const val MINUTES = 'm'
        const val HOURS = 'h'

        const val MONTH = 'M'
        const val YEAR = 'Y'

        const val ANY = '*'
    }

}

internal class GMTDateBuilder {
    var seconds: Int? = null
    var minutes: Int? = null
    var hours: Int? = null

    var dayOfMonth: Int? = null
    lateinit var month: Month
    var year: Int? = null

    fun build(): GMTDate = GMTDate(seconds!!, minutes!!, hours!!, dayOfMonth!!, month, year!!)
}

@Suppress("KDocMissingDocumentation")
class InvalidDateString(
    data: String, at: Int, pattern: String
) : IllegalStateException("Failed to parse date string: \"${data}\" at index $at. Pattern: \"$pattern\"")
