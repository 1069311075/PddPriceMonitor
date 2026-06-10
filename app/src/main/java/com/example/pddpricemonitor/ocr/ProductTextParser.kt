package com.example.pddpricemonitor.ocr

import android.graphics.Rect
import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text

class ProductTextParser {
    private data class OcrLine(
        val text: String,
        val rect: Rect,
        val darkRatio: Double,
        val lightRatio: Double,
        val redRatio: Double,
        val greenRatio: Double,
        val screenHeight: Int,
        val screenWidth: Int,
        val elements: List<OcrElement>
    )

    private data class OcrElement(
        val text: String,
        val rect: Rect
    )

    private data class PricePart(
        val digits: String,
        val rect: Rect
    )

    private data class BottomPrice(
        val line: OcrLine,
        val cents: Long
    )

    private data class BottomNumber(
        val line: OcrLine,
        val text: String,
        val rect: Rect
    )

    private val priceRegex = Regex("(?:[\\u00A5\\uFFE5]|RMB|CNY)\\s*(\\d{1,6})(?:[.,](\\d{1,2}))?")
    private val detailPriceHintRegex = Regex("(\\u5238\\u540E|\\u5355\\u72EC\\u8D2D\\u4E70|\\u53D1\\u8D77\\u62FC\\u5355)")
    private val mainPriceHintRegex = Regex(
        "(\\u5238\\u540E|\\u6700\\u540E\\d*\\u5206\\u949F|\\u53EA\\u5269\\d*\\u5929|" +
            "\\u9996\\u4EF6|\\u9650\\u65F6|\\u5927\\u4FC3|\\u52A0\\u500D\\u8865|" +
            "\\u7ACB\\u5373\\u53C2\\u4E0E|\\u60CA\\u559C\\u7279\\u4EF7)"
    )
    private val suspiciousMissingDecimalHintRegex = Regex(
        "(\\u5238\\u540E|\\u6700\\u540E\\d*\\u5206\\u949F|\\u5DF2\\u62A2|\\u5B98\\u65B9\\u8865\\u8D34|" +
            "\\u53EA\\u5269\\d*\\u5929|\\u9996\\u4EF6|\\u9650\\u65F6|\\u5927\\u4FC3|" +
            "\\u52A0\\u500D\\u8865|\\u60CA\\u559C\\u7279\\u4EF7)"
    )
    private val detailPageSignalRegex = Regex(
        "(\\u53D1\\u8D77\\u62FC\\u5355|\\u5355\\u72EC\\u8D2D\\u4E70|\\u5BA2\\u670D|" +
            "\\u6536\\u85CF|\\u5E97\\u94FA|100%\\u6B63\\u54C1)"
    )
    private val installmentRegex = Regex("(\\u5206\\u671F|\\u4F4E\\u81F3|/\\u671F|\\u5143/\\u671F)")
    private val couponNoiseRegex = Regex(
        "(\\u6EE1\\d+\\u51CF\\d+|\\u5E73\\u53F0\\u5238|\\u5373\\u5C06\\u6062\\u590D|" +
            "\\u5DF2\\u552E|\\u5355\\u4EF6\\u5230\\u624B\\u4EF7|\\u5230\\u624B\\u4EF7|" +
            "\\u4E00\\u5E74\\u8D28\\u4FDD|\\u552E\\u540E|\\u5143\\u8D77)"
    )
    private val titleStartRegex = Regex(
        "(\\u54C1\\u724C|\\u4E13\\u5356\\u5E97|\\u3010[^\\u3011]{1,12}\\u3011)"
    )
    private val titleStopRegex = Regex(
        "(\\u98DF\\u54C1\\u56DE\\u5934\\u5BA2\\u597D\\u5E97|\\u9632\\u8150\\u5242|" +
            "\\u540C\\u6B3E\\u70ED\\u9500|\\u6700\\u8FD1\\d*\\u6708\\u751F\\u4EA7|" +
            "\\u4F18\\u9009\\u539F|\\u4EBA\\u597D\\u8BC4|\\u7269\\u6D41|" +
            "\\u4EF7\\u683C\\u5B9E\\u60E0|\\u8BE5\\u5E97|\\u8FDE\\u7EED\\d*|" +
            "\\d+\\u4EBA\\u5728(\\u62A2)?\\u4F18\\u60E0|\\u6700\\u540E\\d+\\u5929|" +
            "\\u7ACB\\u5373\\u53C2\\u4E0E\\u4E07\\u4EBA\\u56E2|" +
            "\\u6B63\\u54C1\\u9669|\\u6B63\\u54C1\\u4FDD\\u969C|" +
            "\\u4E2D\\u56FD\\u4EBA\\u5BFF|\\u627F\\u4FDD|\\u7B2C\\d+\\u540D|" +
            "\\u9500\\u699C|\\u6708\\u5361\\u4E13\\u4EAB|\\u9000\\u8D27\\u5305\\u8FD0\\u8D39|" +
            "\\u8BC4\\u4EF7\\u8BE5\\u54C1\\u724C\\u5546\\u54C1|\\u70ED\\u9500\\u77E5\\u540D\\u54C1\\u724C|" +
            "\\u8BE5\\u54C1\\u724C\\u7D2F\\u8BA1\\u70ED\\u9500|\\u5E97\\u94FA\\u4FDD\\u969C|" +
            "\\u4E13\\u5C5E\\u552E\\u540E|\\u95EA\\u7535\\u9000\\u6362|\\u5546\\u54C1\\u8BE6\\u60C5)"
    )
    private val leadingTitleBadgeRegex = Regex(
        "^\\s*(\\u54C1\\u724C\\s*[^\\s\\u3010]{1,12}\\s*)?(\\u4E13\\u5356\\u5E97\\s*)?"
    )
    private val obviousNonTitleRegex = Regex(
        "(pinduoduo|rmb|cny|intel\\s*\\u7CFB\\u5217|amd\\s*\\u7CFB\\u5217|" +
            "\\u6765\\u81EA|\\u5238\\u540E|\\u5E73\\u53F0\\u5238|\\u53D1\\u8D77\\u62FC\\u5355|" +
            "\\u5373\\u5C06\\u5356\\u5B8C|\\u4EBA\\u60F3\\u62FC|\\u4EBA\\u5728\\u62FC|\\u597D\\u8BC4|" +
            "\\u5929\\u5185|\\u4EBA\\u4E70\\u8FC7|\\u6708\\u9500|\\u4EBA\\u770B\\u8FC7|" +
            "\\u76F4\\u64AD|\\u5206\\u671F|\\u4F4E\\u81F3|\\u5E73\\u53F0\\u5238|" +
            "\\u53C2\\u6570|\\u9891\\u7387|mhz|gbps|gddr|\\u4F4D\\u5BBD|\\u7535\\u6E90|" +
            "\\u8BC4\\u4EF7\\u8BE5\\u54C1\\u724C\\u5546\\u54C1|\\u70ED\\u9500\\u77E5\\u540D\\u54C1\\u724C|" +
            "\\u8BE5\\u54C1\\u724C\\u7D2F\\u8BA1\\u70ED\\u9500|\\u5546\\u54C1\\u8BE6\\u60C5)",
        RegexOption.IGNORE_CASE
    )
    private val titleNoiseRegex = Regex(
        "(\\u767E\\u4EBF\\u8865\\u8D34|\\u79D2\\u6740|\\u54C1\\u724C|\\u5B98\\u65B9|" +
            "\\u65D7\\u8230\\u5E97|\\u6B63\\u54C1|\\u5305\\u90AE|\\u5238\\u540E|" +
            "\\u65B0\\u6B3E|\\u70ED\\u5356|\\u9886\\u5238|\\u987A\\u4E30\\u5305\\u90AE)"
    )

    fun parse(text: Text, bitmap: Bitmap? = null): List<DetectedProduct> {
        val result = parseWithReason(text, bitmap)
        return result.products
    }

    fun parseWithReason(text: Text, bitmap: Bitmap? = null): ProductParseResult {
        val lines = flattenLines(text, bitmap)
        parseDetailProduct(lines)?.let { return ProductParseResult(listOf(it)) }
        return ProductParseResult(emptyList(), "Skipped: not a clear product detail page")
    }

    private fun flattenLines(text: Text, bitmap: Bitmap?): List<OcrLine> =
        text.textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val rect = line.boundingBox ?: return@mapNotNull null
                line.text.trim().takeIf { it.isNotBlank() }?.let {
                    val color = sampleTextColor(bitmap, rect)
                    OcrLine(
                        it,
                        rect,
                        color.darkRatio,
                        color.lightRatio,
                        color.redRatio,
                        color.greenRatio,
                        bitmap?.height ?: 0,
                        bitmap?.width ?: 0,
                        line.elements.mapNotNull { element ->
                            val elementRect = element.boundingBox ?: return@mapNotNull null
                            element.text.trim().takeIf { text -> text.isNotBlank() }?.let { text ->
                                OcrElement(text, elementRect)
                            }
                        }
                    )
                }
            }
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))

    private fun parseDetailProduct(lines: List<OcrLine>): DetectedProduct? {
        return parseByBlackTitle(lines)
    }

    private fun parseByBlackTitle(lines: List<OcrLine>): DetectedProduct? {
        val bottomPrice = findBottomRightPrice(lines) ?: return null
        val priceLine = bottomPrice.line
        val titleLine = findMainTitleLineAboveBottomBar(lines, priceLine) ?: return null
        val title = collectTitleFromBlackLines(lines, titleLine) ?: return null
        val normalized = normalizeTitle(title)
        if (normalized.length < 6) return null

        return DetectedProduct(
            title = title,
            normalizedTitle = normalized,
            priceCents = bottomPrice.cents,
            rawText = priceLine.text
        )
    }

    private fun findMainBlackTitleLine(lines: List<OcrLine>): OcrLine? {
        val screenHeight = lines.firstOrNull { it.screenHeight > 0 }?.screenHeight ?: return null
        return lines
            .filter { it.rect.top in (screenHeight * 0.46).toInt()..(screenHeight * 0.80).toInt() }
            .filter { isStructuredTitleLine(it) }
            .filter { normalizeTitle(cleanupTitleText(it.text)).length >= 8 }
            .maxWithOrNull(
                compareBy<OcrLine> { normalizeTitle(cleanupTitleText(it.text)).length }
                    .thenBy { it.darkRatio }
            )
    }

    private fun findMainTitleLineAboveBottomBar(lines: List<OcrLine>, priceLine: OcrLine): OcrLine? {
        val screenHeight = priceLine.screenHeight.takeIf { it > 0 } ?: return null
        val minTop = (screenHeight * 0.46).toInt()
        val maxTop = (screenHeight * 0.76).toInt()
        return lines
            .filter { it.rect.top in minTop..maxTop }
            .filter { it.rect.bottom < priceLine.rect.top }
            .filter { isLooseTitleLine(it) }
            .maxByOrNull { titleLineScore(it, priceLine) }
    }

    private fun findBottomRightPrice(lines: List<OcrLine>): BottomPrice? {
        val sample = lines.firstOrNull { it.screenHeight > 0 && it.screenWidth > 0 } ?: return null
        val minTop = (sample.screenHeight * 0.89).toInt()
        val minLeft = (sample.screenWidth * 0.48).toInt()
        val linePrice = lines
            .filter { it.rect.top >= minTop || it.rect.bottom >= sample.screenHeight * 0.94 }
            .filter { it.rect.right >= minLeft }
            .filterNot { isBadPriceContext(it.text) }
            .filterNot { isForbiddenBottomNumberContext(it.text) }
            .mapNotNull { line ->
                val cents = extractBottomBarPriceCents(line) ?: return@mapNotNull null
                BottomPrice(line, cents)
            }
            .maxByOrNull { bottomPriceLineScore(it.line) }

        return linePrice ?: findBottomRightPriceFromElements(lines, minTop, minLeft)
    }

    private fun findBottomRightPriceFromElements(lines: List<OcrLine>, minTop: Int, minLeft: Int): BottomPrice? {
        val bottomLines = lines
            .filter { it.rect.top >= minTop || it.rect.bottom >= it.screenHeight * 0.94 }
            .filter { it.rect.right >= minLeft }
            .filterNot { isForbiddenBottomNumberContext(it.text) }

        val explicitLinePrice = bottomLines
            .mapNotNull { line ->
                val cents = extractBottomBarPriceCents(line) ?: return@mapNotNull null
                BottomPrice(line, cents)
            }
            .maxByOrNull { bottomPriceLineScore(it.line) }
        if (explicitLinePrice != null) return explicitLinePrice

        val candidates = bottomLines.flatMap { line ->
            line.elements.flatMap { element ->
                Regex("\\d{1,6}(?:[.,]\\d{1,2})?").findAll(element.text).mapNotNull { match ->
                    val text = match.value
                    if (isForbiddenBottomNumber(text, element.text, line.text)) return@mapNotNull null
                    BottomNumber(line, text, element.rect)
                }
            }
        }

        val best = candidates.maxByOrNull { bottomNumberScore(it) } ?: return null
        val cents = parsePlainPriceText(best.text) ?: return null
        return BottomPrice(best.line, cents)
    }

    private fun extractBottomBarPriceCents(line: OcrLine): Long? {
        extractPriceCents(line)?.let { return it }
        val text = line.text
        if (Regex("\\d+\\s*%|100%|\\u6B63\\u54C1|\\u4FDD\\u969C|\\u627F\\u4FDD").containsMatchIn(text)) {
            return null
        }
        if (!Regex(
                "(\\u5238\\u540E|\\u9650\\u65F6|\\u60CA\\u559C\\u7279\\u4EF7|" +
                    "\\u5373\\u5C06\\u5356\\u5B8C|\\u53D1\\u8D77\\u62FC\\u5355|\\u7ACB\\u5373\\u62FC\\u5355)"
            ).containsMatchIn(text)
        ) {
            return null
        }
        return Regex("\\d{1,6}(?:[.,]\\d{1,2})?")
            .findAll(text)
            .mapNotNull { parsePlainPriceText(it.value) }
            .filter { it in 1..999_999_00 }
            .maxOrNull()
    }

    private fun parsePlainPriceText(text: String): Long? {
        val match = Regex("\\d{1,6}(?:[.,]\\d{1,2})?").find(text) ?: return null
        val parts = match.value.split('.', ',', limit = 2)
        val yuan = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val cents = parts.getOrNull(1).orEmpty().padEnd(2, '0').take(2).toLongOrNull() ?: 0L
        return yuan * 100 + cents
    }

    private fun isForbiddenBottomNumber(text: String, elementText: String, lineText: String): Boolean {
        val yuan = text.substringBefore('.').substringBefore(',').toLongOrNull() ?: return true
        if (yuan == 100L || yuan == 618L) return true
        if (!text.contains('.') && !text.contains(',') && Regex("[\\u00A5\\uFFE5]\\s*0[.,]\\d{1,2}").containsMatchIn(lineText)) return true
        if (Regex("\\d+\\s*%|100%|\\d{1,2}:\\d{2}|618").containsMatchIn(elementText)) return true
        if (isForbiddenBottomNumberContext(lineText)) return true
        if (Regex("(\\u4EBA|\\u4EF6|\\u5DF2\\u552E|\\u9500\\u91CF|\\u5269\\u4F59|\\u5012\\u8BA1\\u65F6)").containsMatchIn(lineText)) return true
        return yuan !in 1..999_999
    }

    private fun isForbiddenBottomNumberContext(text: String): Boolean =
        Regex(
            "\\d+\\s*%|100%|618|\\u767E\\u4EBF\\u8865\\u8D34|\\u4F18\\u60E0|" +
                "\\u6B63\\u54C1|\\u4FDD\\u969C|\\u627F\\u4FDD|\\u70ED\\u5356|\\u8BC4\\u4EF7"
        ).containsMatchIn(text)

    private fun bottomNumberScore(number: BottomNumber): Int {
        var score = 0
        if (number.text.contains('.') || number.text.contains(',')) score += 70
        score += number.rect.height().coerceAtMost(90)
        score += number.rect.width().coerceAtMost(220) / 3
        score += number.rect.bottom / 18
        if (Regex("(\\u5238\\u540E|\\u9650\\u65F6|\\u60CA\\u559C\\u7279\\u4EF7|\\u53D1\\u8D77\\u62FC\\u5355)").containsMatchIn(number.line.text)) {
            score += 40
        }
        if (number.text.substringBefore('.').substringBefore(',').length >= 2) score += 20
        return score
    }

    private fun bottomPriceLineScore(line: OcrLine): Int {
        var score = 0
        if (line.text.any { it == '\u00A5' || it == '\uFFE5' }) score += 140
        if (Regex("(\\u5238\\u540E|\\u9650\\u65F6|\\u60CA\\u559C\\u7279\\u4EF7)").containsMatchIn(line.text)) score += 50
        if (Regex("(\\u53D1\\u8D77\\u62FC\\u5355|\\u7ACB\\u5373\\u62FC\\u5355)").containsMatchIn(line.text)) score += 35
        score += priceTextHeight(line).coerceAtMost(90)
        score += (line.rect.bottom / 20)
        score += (line.rect.width() / 80)
        if (isForbiddenBottomNumberContext(line.text)) score -= 220
        if (Regex("\\d{1,2}:\\d{2}:\\d{2}").containsMatchIn(line.text)) score -= 80
        return score
    }

    private fun collectTitleFromBlackLines(lines: List<OcrLine>, firstLine: OcrLine): String? {
        val nearby = lines
            .filter { it.rect.top >= firstLine.rect.top - 130 }
            .filter { it.rect.top <= firstLine.rect.top + 220 }
            .filterNot { isBottomOverlayLine(it) }
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))

        val index = nearby.indexOfFirst { it == firstLine }.takeIf { it >= 0 } ?: return null
        val titleLines = mutableListOf(firstLine)

        var previousTop = firstLine.rect.top
        for (i in index - 1 downTo 0) {
            val line = nearby[i]
            if (previousTop - line.rect.bottom > 120) break
            if (!isPreviousTitleContinuation(firstLine, line)) break
            titleLines.add(0, line)
            previousTop = line.rect.top
        }

        var previousBottom = firstLine.rect.bottom
        for (i in index + 1 until nearby.size) {
            val line = nearby[i]
            if (line.rect.top - previousBottom > 120) break
            if (!isNextTitleContinuation(firstLine, line)) break
            titleLines += line
            previousBottom = line.rect.bottom
        }

        val title = normalizeDisplayTitle(titleLines.joinToString("") { cleanupTitleText(it.text) })
        return title.takeIf { isLikelyTitle(it) }
    }

    private fun findMainDetailPriceLine(lines: List<OcrLine>): OcrLine? {
        val screenHeight = lines.firstOrNull { it.screenHeight > 0 }?.screenHeight ?: return null
        val minTop = (screenHeight * 0.38).toInt()
        val maxBottom = (screenHeight * 0.60).toInt()

        return lines
            .filter { it.rect.top >= minTop && it.rect.bottom <= maxBottom }
            .filter { isMainPriceBandLine(it) }
            .filter { isDetailPriceLine(it) }
            .maxWithOrNull(
                compareBy<OcrLine> { detailPriceScore(it.text) }
                    .thenByDescending { it.redRatio }
                    .thenBy { priceTextHeight(it) }
            )
    }

    private fun findStructuredTitleBelowPrice(lines: List<OcrLine>, priceLine: OcrLine): String? {
        val screenHeight = priceLine.screenHeight.takeIf { it > 0 } ?: return null
        val minTop = maxOf(priceLine.rect.bottom, (screenHeight * 0.54).toInt())
        val maxTop = (screenHeight * 0.78).toInt()
        val region = lines
            .filter { it.rect.top in minTop..maxTop }
            .filterNot { isBottomOverlayLine(it) }
            .filterNot { isShopOrGuaranteeLine(it.text) }

        findProductTitleBlock(region)?.let { return it }

        val usable = region.filter { isStructuredTitleLine(it) }
        if (usable.isEmpty()) return null

        val start = usable.firstOrNull { titleStartRegex.containsMatchIn(it.text) } ?: usable.first()
        val titleLines = mutableListOf<String>()
        var previousBottom = start.rect.bottom
        var started = false

        for (line in usable) {
            if (!started && line != start) continue
            started = true
            if (line.rect.top - previousBottom > 95) break
            if (titleLines.isNotEmpty() && !isHorizontallyAligned(start, line)) break

            val cleaned = cleanupTitleText(line.text)
            if (cleaned.isNotBlank()) titleLines += cleaned
            previousBottom = line.rect.bottom

            if (titleLines.joinToString("").length >= 90) break
        }

        val title = normalizeDisplayTitle(titleLines.joinToString(""))
        return title.takeIf { isLikelyTitle(it) }
    }

    private fun parseListProducts(lines: List<OcrLine>): List<DetectedProduct> {
        val candidates = mutableListOf<DetectedProduct>()

        for (index in lines.indices) {
            val line = lines[index]
            if (isBottomOverlayLine(line)) continue
            val price = extractPriceCents(line) ?: continue
            if (isBadPriceContext(line.text)) continue

            val title = findTitleAboveIndex(lines, index) ?: findTitleBelowIndex(lines, index) ?: continue
            val normalized = normalizeTitle(title)
            if (normalized.length < 4) continue

            candidates += DetectedProduct(
                title = title,
                normalizedTitle = normalized,
                priceCents = price,
                rawText = line.text
            )
        }

        return candidates
            .distinctBy { it.normalizedTitle + ":" + it.priceCents }
            .take(8)
    }

    private fun isDetailPriceLine(line: OcrLine): Boolean {
        val text = line.text
        if (isBottomOverlayLine(line)) return false
        if (isBadPriceContext(text)) return false
        val price = extractPriceCents(line) ?: return false
        return price >= 100 && (
            detailPriceHintRegex.containsMatchIn(text) ||
                text.count { it == '\u00A5' || it == '\uFFE5' } >= 1
            )
    }

    private fun isMainPriceBandLine(line: OcrLine): Boolean =
        mainPriceHintRegex.containsMatchIn(line.text) ||
            (line.redRatio >= 0.12 && line.rect.left < line.screenWidth * 0.45)

    private fun priceTextHeight(line: OcrLine): Int =
        line.elements
            .filter { it.text.any(Char::isDigit) }
            .maxOfOrNull { it.rect.height() }
            ?: line.rect.height()

    private fun detailPriceScore(text: String): Int {
        var score = 0
        if (text.contains("\u5238\u540E")) score += 5
        if (text.contains("\u53D1\u8D77\u62FC\u5355")) score += 4
        if (text.contains("\u5355\u72EC\u8D2D\u4E70")) score += 2
        score += text.count { it == '\u00A5' || it == '\uFFE5' }
        return score
    }

    private fun isBadPriceContext(text: String): Boolean =
        installmentRegex.containsMatchIn(text) ||
            couponNoiseRegex.containsMatchIn(text)

    private fun isShopOrGuaranteeLine(text: String): Boolean =
        Regex(
            "(\\u6765\\u81EA|\\u65D7\\u8230\\u5E97|\\u5B98\\u65B9\\u65D7\\u8230|" +
                "100%\\u6B63\\u54C1|\\u6B63\\u54C1\\u9669|\\u4EBA\\u5728\\u62FC|" +
                "\\u4EBA\\u5728\\u62A2\\u4F18\\u60E0|\\u7ACB\\u5373\\u62FC\\u5355)"
        ).containsMatchIn(text)

    private fun isStructuredTitleLine(line: OcrLine): Boolean =
        isTitleCandidateLine(line) &&
            !isShopOrGuaranteeLine(line.text) &&
            extractPriceCents(line.text) == null

    private fun isLooseTitleLine(line: OcrLine): Boolean {
        val cleaned = cleanupTitleText(line.text)
        val compact = normalizeTitle(cleaned)
        return compact.length >= 8 &&
            !isTitleStopLine(cleaned) &&
            !isShopOrGuaranteeLine(cleaned) &&
            !obviousNonTitleRegex.containsMatchIn(cleaned) &&
            extractPriceCents(cleaned) == null &&
            cleaned.any { it in '\u4e00'..'\u9fff' || it.isLetter() } &&
            line.redRatio < 0.18 &&
            line.greenRatio < 0.16 &&
            !isDarkBackgroundLine(line) &&
            (isBlackTitleLine(line) || isWeakBlackTitleLine(line))
    }

    private fun titleLineScore(line: OcrLine, priceLine: OcrLine): Int {
        val cleaned = cleanupTitleText(line.text)
        val compactLength = normalizeTitle(cleaned).length
        var score = compactLength * 3
        when {
            isBlackTitleLine(line) -> score += 260
            isWeakBlackTitleLine(line) -> score += 55
            else -> score -= 160
        }
        if (line.darkRatio >= 0.055) score += 70
        if (line.lightRatio >= 0.38) score += 55 else score -= 85
        if (line.redRatio < 0.08) score += 35 else score -= 70
        if (line.greenRatio < 0.08) score += 35 else score -= 70
        score += line.rect.height().coerceAtMost(80)

        val distance = (priceLine.rect.top - line.rect.bottom).coerceAtLeast(0)
        score -= distance / 18

        if (titleStopRegex.containsMatchIn(cleaned)) score -= 120
        if (Regex("(\\u9000\\u8D27|\\u53D1\\u8D27|\\u4FDD\\u969C|\\u5BA2\\u670D|\\u597D\\u8D27|\\u54C1\\u724C\\u56DE\\u5934)").containsMatchIn(cleaned)) {
            score -= 100
        }
        return score
    }

    private fun isStrictTitleContinuation(firstLine: OcrLine, line: OcrLine): Boolean {
        if (!isLooseTitleLine(line)) return false
        if (line == firstLine) return true
        if (!isTitleLineRelated(firstLine, line)) return false
        if (line.greenRatio >= 0.12 || line.redRatio >= 0.14) return false
        return isBlackTitleLine(line)
    }

    private fun isPreviousTitleContinuation(firstLine: OcrLine, line: OcrLine): Boolean {
        val cleaned = cleanupTitleText(line.text)
        val compact = normalizeTitle(cleaned)
        val hasTitlePrefix = titleStartRegex.containsMatchIn(cleaned) || cleaned.contains('\u3010')
        if (compact.length < 6) return false
        if (isTitleStopLine(cleaned)) return false
        if (isShopOrGuaranteeLine(cleaned) && !hasTitlePrefix) return false
        if (obviousNonTitleRegex.containsMatchIn(cleaned)) return false
        if (extractPriceCents(cleaned) != null) return false
        if (!cleaned.any { it in '\u4e00'..'\u9fff' || it.isLetter() }) return false
        if (!isTitleLineRelated(firstLine, line)) return false

        val colorLooksTitle =
            isBlackTitleLine(line) ||
                isWeakBlackTitleLine(line) ||
                (
                    hasTitlePrefix &&
                        !isDarkBackgroundLine(line) &&
                        line.redRatio < 0.35 &&
                        line.greenRatio < 0.24
                    )

        return colorLooksTitle
    }

    private fun isNextTitleContinuation(firstLine: OcrLine, line: OcrLine): Boolean {
        val cleaned = cleanupTitleText(line.text)
        val compact = normalizeTitle(cleaned)
        if (compact.length < 6) return false
        if (isTitleStopLine(cleaned)) return false
        if (isShopOrGuaranteeLine(cleaned)) return false
        if (obviousNonTitleRegex.containsMatchIn(cleaned)) return false
        if (extractPriceCents(cleaned) != null) return false
        if (!cleaned.any { it in '\u4e00'..'\u9fff' || it.isLetter() }) return false
        if (!isTitleLineRelated(firstLine, line)) return false
        if (isDarkBackgroundLine(line)) return false

        return isBlackTitleLine(line) ||
            isWeakBlackTitleLine(line) ||
            (
                compact.length >= 10 &&
                    line.redRatio < 0.26 &&
                    line.greenRatio < 0.34 &&
                    line.lightRatio >= 0.18
                )
    }

    private fun isHorizontallyAligned(first: OcrLine, next: OcrLine): Boolean {
        val tolerance = (first.screenWidth * 0.12).toInt().coerceAtLeast(80)
        return kotlin.math.abs(first.rect.left - next.rect.left) <= tolerance
    }

    private fun isTitleLineRelated(first: OcrLine, next: OcrLine): Boolean {
        if (isHorizontallyAligned(first, next)) return true
        val overlap = minOf(first.rect.right, next.rect.right) - maxOf(first.rect.left, next.rect.left)
        val minWidth = minOf(first.rect.width(), next.rect.width()).coerceAtLeast(1)
        return overlap > minWidth * 0.45
    }

    private fun findTitleBelowPrice(lines: List<OcrLine>, priceLine: OcrLine): String? {
        val maxTop = priceLine.rect.bottom + 520
        val region = lines
            .filter { it.rect.top in priceLine.rect.bottom..maxTop }
            .filterNot { isBottomOverlayLine(it) }

        findProductTitleBlock(region)?.let { return it }

        return region
            .filter { isTitleCandidateLine(it) }
            .filterNot { isTitleStopLine(it.text) }
            .map { cleanupTitleText(it.text) }
            .filter { isLikelyTitle(it) }
            .maxByOrNull { titleScore(it) }
    }

    private fun findTitleAbovePrice(lines: List<OcrLine>, priceLine: OcrLine): String? {
        val minTop = priceLine.rect.top - 700
        return lines
            .filter { it.rect.top in minTop..priceLine.rect.top }
            .filterNot { isBottomOverlayLine(it) }
            .filter { isTitleCandidateLine(it) }
            .filterNot { isTitleStopLine(it.text) }
            .map { cleanupTitleText(it.text) }
            .filter { isLikelyTitle(it) }
            .maxByOrNull { titleScore(it) }
    }

    private fun findTitleAboveIndex(lines: List<OcrLine>, priceLineIndex: Int): String? {
        val searchStart = (priceLineIndex - 4).coerceAtLeast(0)
        val region = lines.subList(searchStart, priceLineIndex)
            .filterNot { isBottomOverlayLine(it) }

        findProductTitleBlock(region)?.let { return it }

        return region
            .filter { isTitleCandidateLine(it) }
            .filterNot { isTitleStopLine(it.text) }
            .map { cleanupTitleText(it.text) }
            .filter { isLikelyTitle(it) }
            .takeLast(2)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
    }

    private fun findTitleBelowIndex(lines: List<OcrLine>, priceLineIndex: Int): String? {
        val searchEnd = (priceLineIndex + 5).coerceAtMost(lines.size)
        val region = lines.subList(priceLineIndex + 1, searchEnd)
            .filterNot { isBottomOverlayLine(it) }

        findProductTitleBlock(region)?.let { return it }

        return region
            .filter { isTitleCandidateLine(it) }
            .filterNot { isTitleStopLine(it.text) }
            .map { cleanupTitleText(it.text) }
            .filter { isLikelyTitle(it) }
            .maxByOrNull { titleScore(it) }
    }

    private fun extractPriceCents(line: OcrLine): Long? =
        extractExplicitDecimalPrice(line.text) ?: extractPriceCentsFromElements(line) ?: extractPriceCents(line.text)

    private fun extractExplicitDecimalPrice(text: String): Long? =
        priceRegex.findAll(text)
            .mapNotNull { match ->
                val decimalText = match.groupValues.getOrNull(2).orEmpty()
                if (decimalText.isBlank()) return@mapNotNull null
                val yuan = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val cents = decimalText.padEnd(2, '0').take(2).toLongOrNull() ?: return@mapNotNull null
                yuan * 100 + cents
            }
            .filter { it in 1..999_999_00 }
            .minOrNull()

    private fun extractPriceCents(text: String): Long? =
        priceRegex.findAll(text)
            .mapNotNull { match ->
                val yuan = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val decimalText = match.groupValues.getOrNull(2).orEmpty()
                if (decimalText.isBlank() && isSuspiciousMissingDecimal(text, match.groupValues[1])) {
                    return@mapNotNull null
                }
                val centsText = decimalText.padEnd(2, '0').take(2)
                val cents = centsText.toLongOrNull() ?: 0L
                yuan * 100 + cents
            }
            .filter { it in 1..999_999_00 }
            .minOrNull()

    private fun extractPriceCentsFromElements(line: OcrLine): Long? {
        if (line.elements.isEmpty()) return null
        if (isBadPriceContext(line.text)) return null
        if (!line.text.any { it == '\u00A5' || it == '\uFFE5' }) return null

        val marker = line.elements
            .filter { it.text.contains('\u00A5') || it.text.contains('\uFFE5') }
            .minByOrNull { it.rect.left }
            ?: return null
        val nextMarkerLeft = line.elements
            .filter { it != marker }
            .filter { it.text.contains('\u00A5') || it.text.contains('\uFFE5') }
            .filter { it.rect.left > marker.rect.left }
            .minOfOrNull { it.rect.left }
            ?: Int.MAX_VALUE

        val numberParts = line.elements
            .asSequence()
            .filter { it.rect.left >= marker.rect.left }
            .filter { it.rect.left < nextMarkerLeft }
            .mapNotNull { element ->
                val digits = element.text.filter(Char::isDigit)
                if (digits.isBlank()) null else PricePart(digits, element.rect)
            }
            .sortedBy { it.rect.left }
            .toList()
        if (numberParts.size < 2) return null

        val maxHeight = numberParts.maxOf { it.rect.height() }.coerceAtLeast(1)
        val main = numberParts
            .filter { it.rect.height() >= maxHeight * 0.82 }
            .maxWithOrNull(
                compareBy<PricePart> { it.rect.height() }
                    .thenByDescending { it.rect.left }
            )
            ?: return null
        val mainDigits = main.digits
        if (mainDigits.isBlank()) return null

        val mainHeight = main.rect.height().coerceAtLeast(1)
        val decimal = numberParts
            .filter { it != main }
            .filter { it.rect.left > main.rect.left }
            .filter { it.rect.height() in (mainHeight * 0.35).toInt()..(mainHeight * 0.88).toInt().coerceAtLeast(1) }
            .filter { (it.rect.left - main.rect.right).toDouble() <= mainHeight * 1.35 }
            .filter { it.rect.top > main.rect.top + mainHeight * 0.12 }
            .filter { kotlin.math.abs((it.rect.bottom - main.rect.bottom).toDouble()) <= mainHeight * 0.55 }
            .minWithOrNull(
                compareBy<PricePart> { it.rect.left }
                    .thenByDescending { it.rect.height() }
            )
            ?: return null

        val decimalDigits = decimal.digits.take(2)
        if (decimalDigits.isBlank()) return null

        val yuan = mainDigits.toLongOrNull() ?: return null
        val cents = decimalDigits.padEnd(2, '0').take(2).toLongOrNull() ?: 0L
        return (yuan * 100 + cents).takeIf { it in 1..999_999_00 }
    }

    private fun isBlackTitleLine(line: OcrLine): Boolean {
        val compactLength = normalizeTitle(cleanupTitleText(line.text)).length
        return line.redRatio < 0.13 &&
            line.greenRatio < 0.11 &&
            line.lightRatio >= 0.30 &&
            line.darkRatio <= 0.42 &&
            (
                line.darkRatio >= 0.045 ||
                    (line.darkRatio >= 0.032 && compactLength >= 12)
                )
    }

    private fun isWeakBlackTitleLine(line: OcrLine): Boolean {
        val compactLength = normalizeTitle(cleanupTitleText(line.text)).length
        return compactLength >= 12 &&
            line.redRatio < 0.18 &&
            line.greenRatio < 0.16 &&
            line.lightRatio >= 0.24 &&
            line.darkRatio <= 0.38 &&
            line.darkRatio >= 0.024
    }

    private fun isDarkBackgroundLine(line: OcrLine): Boolean =
        line.darkRatio > 0.42 && line.lightRatio < 0.28

    private fun isBottomOverlayLine(line: OcrLine): Boolean =
        line.screenHeight > 0 && line.rect.top > line.screenHeight * 0.82

    private fun isSuspiciousMissingDecimal(text: String, digits: String): Boolean =
        digits.length in 3..5 &&
            suspiciousMissingDecimalHintRegex.containsMatchIn(text) &&
            text.any { it == '\u00A5' || it == '\uFFE5' }

    private fun isTitleCandidateLine(line: OcrLine): Boolean =
        !isTitleStopLine(line.text) &&
            (isBlackTitleLine(line) || titleStartRegex.containsMatchIn(line.text))

    private fun findProductTitleBlock(lines: List<OcrLine>): String? {
        if (lines.isEmpty()) return null

        val startIndex = lines.indexOfFirst { line ->
            titleStartRegex.containsMatchIn(line.text) &&
                !isTitleStopLine(line.text)
        }
        if (startIndex < 0) return null

        val titleLines = mutableListOf<String>()
        var previousBottom = lines[startIndex].rect.bottom

        for (index in startIndex until lines.size) {
            val line = lines[index]
            if (index > startIndex && line.rect.top - previousBottom > 90) break
            if (isTitleStopLine(line.text)) break
            if (index > startIndex && !isTitleCandidateLine(line)) break

            val cleaned = cleanupTitleText(line.text)
            if (cleaned.isNotBlank()) titleLines += cleaned
            previousBottom = line.rect.bottom

            if (titleLines.joinToString("").length >= 80) break
        }

        val title = normalizeDisplayTitle(titleLines.joinToString(""))
        return title.takeIf { isLikelyTitle(it) }
    }

    private fun isTitleStopLine(text: String): Boolean =
        titleStopRegex.containsMatchIn(text)

    private fun cleanupTitleText(text: String): String =
        text.replace(priceRegex, " ")
            .replace(Regex("\\u54C1\\u724C\\u597D\\u8BC4\\s*\\d+(?:\\.\\d+)?[\\u4E07\\u5343]?\\+?\\u6761\\s*[|\\uFF5C]?"), " ")
            .replace(Regex("\\u8BE5\\u54C1\\u724C\\u7D2F\\u8BA1\\u70ED\\u9500\\s*\\d+(?:\\.\\d+)?[\\u4E07\\u5343]?\\+?\\u4EF6"), " ")
            .replace(Regex("(\\u6708\\u5361\\u4E13\\u4EAB|\\u9000\\u8D27\\u5305\\u8FD0\\u8D39|\\u964D\\u4EF7\\u8865\\u5DEE|\\u6B63\\u54C1\\u53D1\\u7968|\\u987A\\u4E30\\u5305\\u90AE|\\u540E\\u5929\\u8FBE|24\\u5C0F\\u65F6\\u53D1\\u8D27|\\u4E70\\u8D35\\u53CC\\u500D\\u8D54)"), " ")
            .replace(Regex("[\\[\\]{}()\\uFF08\\uFF09|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeDisplayTitle(text: String): String =
        text.substringFromBrandBracket()
            .replace(leadingTitleBadgeRegex, "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.substringFromBrandBracket(): String {
        val index = indexOf('\u3010')
        return if (index >= 0) substring(index) else this
    }

    private fun isLikelyTitle(text: String): Boolean {
        val compact = normalizeTitle(text)
        return compact.length >= 6 &&
            !isTitleStopLine(text) &&
            !obviousNonTitleRegex.containsMatchIn(text) &&
            extractPriceCents(text) == null &&
            text.any { it in '\u4e00'..'\u9fff' || it.isLetter() } &&
            titleScore(text) >= 10
    }

    private fun titleScore(text: String): Int {
        var score = normalizeTitle(text).length
        if (text.contains("ROG", ignoreCase = true)) score += 8
        if (text.contains("U9", ignoreCase = true)) score += 5
        if (text.contains("5090", ignoreCase = true) || text.contains("5080", ignoreCase = true)) score += 5
        if (text.contains("DIY", ignoreCase = true)) score += 4
        if (text.contains("\u4E3B\u673A")) score += 4
        if (text.contains("\u7535\u8111")) score += 4
        if (titleStartRegex.containsMatchIn(text)) score += 12
        return score
    }

    fun normalizeTitle(title: String): String =
        title.lowercase()
            .replace(titleNoiseRegex, "")
            .replace(Regex("[^\\p{IsHan}a-z0-9]+"), "")
            .trim()

    private data class ColorSample(
        val darkRatio: Double,
        val lightRatio: Double,
        val redRatio: Double,
        val greenRatio: Double
    )

    private fun sampleTextColor(bitmap: Bitmap?, rect: Rect): ColorSample {
        if (bitmap == null || rect.isEmpty) return ColorSample(0.08, 0.65, 0.0, 0.0)

        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        val step = 3

        var total = 0
        var dark = 0
        var light = 0
        var red = 0
        var green = 0

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                val r = pixel shr 16 and 0xff
                val g = pixel shr 8 and 0xff
                val b = pixel and 0xff
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b)

                if (luminance < 110) dark++
                if (luminance > 205) light++
                if (r > 150 && g < 120 && b < 120) red++
                if (g > 130 && r < 140 && b < 140) green++
                total++
                x += step
            }
            y += step
        }

        if (total == 0) return ColorSample(0.08, 0.65, 0.0, 0.0)
        return ColorSample(
            darkRatio = dark.toDouble() / total,
            lightRatio = light.toDouble() / total,
            redRatio = red.toDouble() / total,
            greenRatio = green.toDouble() / total
        )
    }
}
