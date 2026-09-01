package com.example.pddpricemonitor.matcher

import com.example.pddpricemonitor.data.ProductPrice

class TitleMatcher(
    private val threshold: Double = 0.78
) {
    /**
     * 双路匹配：路1 用展示标题对商品标题（同名合并），路2 用 OCR 原文对商品 OCR 签名。
     * 用户大幅编辑标题后路1断裂（相似度跌破阈值），但同一商品的 OCR 识别串天然相近，
     * 路2 仍能命中——编辑标题不再导致下次识别被当成新商品
     */
    fun findBestMatch(
        editedTitle: String,
        ocrTitle: String,
        existing: List<ProductPrice>
    ): ProductPrice? =
        existing
            .map { item ->
                val byTitle = if (numbersConflict(editedTitle, item.normalizedTitle)) {
                    0.0
                } else {
                    similarity(editedTitle, item.normalizedTitle)
                }
                val byOcr = when {
                    ocrTitle.isBlank() || item.ocrTitle.isBlank() -> 0.0
                    numbersConflict(ocrTitle, item.ocrTitle) -> 0.0
                    else -> similarity(ocrTitle, item.ocrTitle)
                }
                item to maxOf(byTitle, byOcr)
            }
            .filter { (_, score) -> score >= threshold }
            .maxByOrNull { (_, score) -> score }
            ?.first

    /**
     * 数字守卫：规格/容量/数量是商品的命根——「24盒」与「12盒」相似度高达 0.95，
     * 纯相似度会把同款不同规误并成一个商品、价格历史互相污染。
     * 两侧都含数字且数字集合不同 → 判定不同商品。
     * 只挡双侧都有数字的情况：单侧无数字多半是 OCR 漏读或用户编辑删掉了数字，交回相似度判定
     */
    private fun numbersConflict(a: String, b: String): Boolean {
        val na = extractNumbers(a)
        val nb = extractNumbers(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        return na != nb
    }

    private fun extractNumbers(s: String): List<String> =
        NUMBER_REGEX.findAll(s).map { it.value }.sorted().toList()

    private companion object {
        val NUMBER_REGEX = Regex("\\d+(?:\\.\\d+)?")
    }

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
