package com.example.pddpricemonitor.matcher

import com.example.pddpricemonitor.data.ProductPrice

class TitleMatcher(
    private val threshold: Double = 0.78
) {
    fun findBestMatch(title: String, existing: List<ProductPrice>): ProductPrice? =
        existing
            .map { item -> item to similarity(title, item.normalizedTitle) }
            .filter { (_, score) -> score >= threshold }
            .maxByOrNull { (_, score) -> score }
            ?.first

    fun similarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        val distance = levenshtein(left, right)
        val maxLength = maxOf(left.length, right.length)
        return 1.0 - distance.toDouble() / maxLength.toDouble()
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }
}
