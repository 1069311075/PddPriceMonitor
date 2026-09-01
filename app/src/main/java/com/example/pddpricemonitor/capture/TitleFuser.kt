package com.example.pddpricemonitor.capture

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 标题两遍逐字融合（方案A·融合层）：
 * - 编辑距离对齐第一遍/第二遍标题，找出 1:1 分歧字符
 * - 分歧字符用第一遍整页 OCR 文本做证据投票（分歧字符±上下文作探针计数）：
 *   品牌名在详情页反复出现（店铺名/角标/标题），多数读法胜出，能同时纠正
 *   "一遍错二遍对"（李→季）和"一遍对二遍错"（味→昧）两个方向的误读
 * - 品牌名店铺锚交叉校验：两遍可能读错同一字（李/季双双误读），此时无分歧可投票；
 *   但 PDD 标题行前的店铺名胶囊"品牌李子园旗舰店"是独立读法，两遍均可靠。
 *   标题【X】段无店铺锚佐证、且存在仅差一字的锚读法时，采纳店铺名读法
 * - 两遍标题本身不参与计数：它们是争议方不是证人（投票语料剔除与标题区域重叠的行，
 *   标题行自身的误读会命中自己的探针给自己投票）
 * - 有证据的平票保守取第一遍：二遍要"赢得"替换权；双方零证据（页面无独立证人）时
 *   取二遍——裁剪放大重识别的单字读法物理上更清晰
 * - 插入/删除段取第二遍（大图看到的字符增删更可信，如"饮次品"→"饮品"）
 * - 纯字符串运算，零额外 OCR 开销
 */
@Singleton
class TitleFuser @Inject constructor() {

    data class Disagreement(
        val firstChar: String,
        val secondChar: String,
        val winner: String,
        val firstVotes: Int,
        val secondVotes: Int,
        val source: String = "char"
    )

    data class FusionResult(
        val title: String,
        val disagreements: List<Disagreement>
    )

    /**
     * @param pageLines 第一遍整页行文本：品牌锚交叉校验的语料（店铺名胶囊常与标题同行，
     *        必须保留，否则锚证据丢失）
     * @param witnessLines 逐字投票的证人语料：应剔除与标题区域重叠的行——标题行自身的
     *        误读会给自己作证（同一次误读命中自己的探针，2:0 自我加冕），传 null 时
     *        回退为整页行（旧行为）
     */
    fun fuse(
        firstTitle: String,
        secondTitle: String,
        pageLines: List<String>,
        witnessLines: List<String>? = null
    ): FusionResult {
        if (firstTitle.isEmpty() || secondTitle.isEmpty()) {
            return FusionResult(secondTitle, emptyList())
        }
        // 域规则归一化先行：「0脂/0糖/0卡/0添加」是电商标准营销词而「O脂」不是合法词，
        // ML Kit 把夹在汉字间的 0 误读成 O 的分歧在投票前就该消失（详见 normalizeDomainGlyphs）
        val normalizedFirst = normalizeDomainGlyphs(firstTitle)
        val normalizedSecond = normalizeDomainGlyphs(secondTitle)
        val corpus = (witnessLines ?: pageLines).joinToString("\n")
        val ops = align(normalizedFirst, normalizedSecond)
        val secondChars = normalizedSecond.toCharArray()
        val out = StringBuilder()
        val disagreements = ArrayList<Disagreement>()
        var si = 0
        for ((a, b) in ops) {
            if (b == null) continue
            val left = if (out.isEmpty()) "" else out.substring(out.length - minOf(2, out.length))
            val right = buildString {
                if (si + 1 < secondChars.size) append(secondChars[si + 1])
                if (si + 2 < secondChars.size) append(secondChars[si + 2])
            }
            if (a != null && a != b) {
                val (winner, firstVotes, secondVotes) = vote(a, b, left, right, corpus)
                out.append(winner)
                disagreements += Disagreement(a.toString(), b.toString(), winner.toString(), firstVotes, secondVotes)
            } else {
                out.append(b)
            }
            si++
        }
        val (fixedTitle, brandFix) = crossCheckBrand(out.toString(), pageLines)
        return FusionResult(fixedTitle, if (brandFix != null) disagreements + brandFix else disagreements)
    }

    /**
     * 品牌名店铺锚交叉校验：标题首段【X】（≥3 个汉字）在整页店铺锚
     * （"Y旗舰店/专卖店/专营店"）中无佐证，且某锚读法 Y 与 X 等长仅差一字时，
     * 用 Y 替换标题品牌段。短段（<3 字）不修正——"红米/小米"这类两字近邻品牌
     * 跨品牌误替换风险大于收益。
     */
    private fun crossCheckBrand(title: String, pageLines: List<String>): Pair<String, Disagreement?> {
        val match = BRAND_REGEX.find(title) ?: return title to null
        val x = match.groupValues[1]
        if (x.length < 3 || x.any { it.code !in CJK_START..CJK_END }) return title to null
        val brands = anchoredBrands(pageLines)
        if (brands.isEmpty() || brands.any { it.contains(x) }) return title to null
        for (brand in brands) {
            if (brand.length < x.length) continue
            val candidate = brand.windowed(x.length).firstOrNull { w -> w != x && editDistance(w, x) == 1 }
            if (candidate != null) {
                val fixed = title.replaceRange(match.range, "【$candidate】")
                return fixed to Disagreement(x, candidate, candidate, 0, 1, "brandAnchor")
            }
        }
        return title to null
    }

    private fun anchoredBrands(lines: List<String>): List<String> {
        val brands = ArrayList<String>()
        for (line in lines) {
            for (suffix in STORE_SUFFIXES) {
                var idx = line.indexOf(suffix)
                while (idx >= 0) {
                    val raw = line.substring(maxOf(0, idx - 12), idx)
                    val brand = raw
                        .filter { it.code in CJK_START..CJK_END || it in 'A'..'Z' || it in 'a'..'z' }
                        .removePrefix("来自").removePrefix("品牌").removePrefix("官方").removePrefix("授权")
                        .removeSuffix("官方").removeSuffix("授权").removeSuffix("品牌")
                    if (brand.length in 2..8) brands += brand
                    idx = line.indexOf(suffix, idx + 1)
                }
            }
        }
        return brands
    }

    private fun editDistance(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) {
                cur[j] = minOf(
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                    prev[j] + 1,
                    cur[j - 1] + 1
                )
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[m]
    }

    /**
     * 域规则归一化（融合前置）：OCR 把夹在汉字间的数字 0 误读为大写拉丁 O 的字形混淆。
     * 规则：O 前邻不是拉丁字母（防止误伤 XO酱/AD钙 这类真含 O 的词）、后邻紧接域词
     * （脂/糖/卡/添/酒精/蔗糖/反式脂肪）时，O 替换为数字 0——「0脂0糖0卡」是电商
     * 标题高频营销词，「O脂」不是合法词，判定无歧义；同时消除该字位在融合对齐中
     * 制造的伪分歧与自我作证（证人语料含标题行自身，同一次误读会给自己投两票）
     */
    internal fun normalizeDomainGlyphs(title: String): String {
        val sb = StringBuilder(title.length)
        for (i in title.indices) {
            val c = title[i]
            if (c == 'O' && i + 1 < title.length && title[i + 1] in DOMAIN_WORD_STARTS) {
                val prev = title.getOrNull(i - 1)
                if (prev == null || prev !in 'a'..'z' && prev !in 'A'..'Z') {
                    sb.append('0')
                    continue
                }
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private fun vote(first: Char, second: Char, left: String, right: String, corpus: String): Triple<Char, Int, Int> {
        var fv = 0
        var sv = 0
        if (right.isNotEmpty()) {
            fv += count(corpus, "$first$right")
            sv += count(corpus, "$second$right")
        }
        if (left.isNotEmpty()) {
            fv += count(corpus, "$left$first")
            sv += count(corpus, "$left$second")
        }
        if (left.isEmpty() && right.isEmpty()) {
            fv += count(corpus, first.toString())
            sv += count(corpus, second.toString())
        }
        // 双方都零票 = 页面没有任何独立证据：信二遍——裁剪放大后笔画更清晰，单字识别
        // 物理上优于整页缩略图（速/遠案例：一遍误读只出现在标题行自身，剔除自证后 0:0）。
        // 有证据的平票（如 1:1）仍取第一遍，保持"二遍要赢得替换权"的保守基线
        val winner = when {
            sv > fv -> second
            fv == 0 && sv == 0 -> second
            else -> first
        }
        return Triple(winner, fv, sv)
    }

    companion object {
        private val BRAND_REGEX = Regex("【([^】]{2,8})】")
        private val STORE_SUFFIXES = listOf("旗舰店", "专卖店", "专营店")
        private const val CJK_START = 0x4E00
        private const val CJK_END = 0x9FA5
        // 「O→0」替换的后邻域词首字：脂(肪)/糖/卡(路里)/添(加)/酒(精)/蔗(糖)——
        // 覆盖 0脂0糖0卡0添加0酒精0蔗糖 六个电商高频营销前缀
        private val DOMAIN_WORD_STARTS = listOf('脂', '糖', '卡', '添', '酒', '蔗')
    }

    private fun count(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var index = 0
        var found = 0
        while (true) {
            index = haystack.indexOf(needle, index)
            if (index < 0) return found
            found++
            index += needle.length
        }
    }

    // 编辑距离回溯对齐：match / substitute(1:1) / delete(仅第一遍) / insert(仅第二遍)
    private fun align(a: String, b: String): List<Pair<Char?, Char?>> {
        val n = a.length
        val m = b.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val substitution = dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(substitution, dp[i - 1][j] + 1, dp[i][j - 1] + 1)
        }
        val ops = ArrayList<Pair<Char?, Char?>>(n + m)
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] -> { ops += a[i - 1] to b[j - 1]; i--; j-- }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> { ops += a[i - 1] to b[j - 1]; i--; j-- }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> { ops += a[i - 1] to null; i-- }
                else -> { ops += null to b[j - 1]; j-- }
            }
        }
        ops.reverse()
        return ops
    }
}
