package com.example.pddpricemonitor.capture

import android.content.Context
import com.example.pddpricemonitor.ocr.ProductParseResult
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// 诊断转储：每次 OCR 识别把全部行数据（文本/坐标/颜色比例）连同解析结果写成 JSON，
// 用于离线排查"真实标题行死在哪道闸门"，也是将来回归测试的素材库。
// 文件在 app 外部私有目录 ocr_dumps/，可 adb pull 直接取出，仅保留最近 50 个
@Singleton
class OcrDumpWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun write(
        result: ProductParseResult,
        firstPassTitle: String? = null,
        secondPassRawLines: List<String>? = null,
        refineReason: String? = null,
        fusion: List<TitleFuser.Disagreement>? = null,
        priceRefine: OcrCaptureInteractor.PriceRefineDump? = null
    ) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "ocr_dumps")
        if (!dir.exists()) dir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "ocr_dump_$stamp.json")
        file.writeText(buildJson(result, stamp, firstPassTitle, secondPassRawLines, refineReason, fusion, priceRefine))

        rotate(dir)
    }

    private fun buildJson(
        result: ProductParseResult,
        stamp: String,
        firstPassTitle: String?,
        secondPassRawLines: List<String>?,
        refineReason: String?,
        fusion: List<TitleFuser.Disagreement>?,
        priceRefine: OcrCaptureInteractor.PriceRefineDump?
    ): String {
        val screen = result.lines.firstOrNull { it.screenHeight > 0 }
        val linesJson = JSONArray().apply {
            result.lines.forEach { line ->
                put(
                    JSONObject()
                        .put("text", line.text)
                        .put("left", line.left)
                        .put("top", line.top)
                        .put("right", line.right)
                        .put("bottom", line.bottom)
                        .put("dark", round4(line.darkRatio))
                        .put("light", round4(line.lightRatio))
                        .put("red", round4(line.redRatio))
                        .put("green", round4(line.greenRatio))
                )
            }
        }
        val productJson = result.products.firstOrNull()?.let {
            JSONObject()
                .put("title", it.title)
                .put("priceCents", it.priceCents)
                .put("priceLine", it.rawText)
        }
        val json = JSONObject()
            .put("timestamp", stamp)
            .put("screenWidth", screen?.screenWidth ?: 0)
            .put("screenHeight", screen?.screenHeight ?: 0)
            .put("result", productJson ?: JSONObject.NULL)
            .put("skippedReason", result.skippedReason ?: JSONObject.NULL)
            .put("titleRect", result.titleRect?.let {
                JSONObject()
                    .put("left", it.left)
                    .put("top", it.top)
                    .put("right", it.right)
                    .put("bottom", it.bottom)
            } ?: JSONObject.NULL)
            .put("firstPassTitle", firstPassTitle ?: JSONObject.NULL)
            .put("secondPassRawLines", secondPassRawLines?.takeIf { it.isNotEmpty() }?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("refineReason", refineReason ?: JSONObject.NULL)
            .put("fusion", fusion?.takeIf { it.isNotEmpty() }?.let { list ->
                JSONArray().apply {
                    list.forEach { d ->
                        put(
                            JSONObject()
                                .put("first", d.firstChar)
                                .put("second", d.secondChar)
                                .put("winner", d.winner)
                                .put("firstVotes", d.firstVotes)
                                .put("secondVotes", d.secondVotes)
                                .put("source", d.source)
                        )
                    }
                }
            } ?: JSONObject.NULL)
            .put("priceRefine", priceRefine?.let { pr ->
                JSONObject()
                    .put("zoomRawText", pr.zoomRawText)
                    .put("firstCents", pr.firstCents)
                    .put("firstHasDecimal", pr.firstHasDecimal)
                    .put("candidates", JSONArray().apply {
                        pr.candidates.forEach { c ->
                            put(JSONObject().put("cents", c.cents).put("hasDecimal", c.hasDecimal))
                        }
                    })
                    .put("chosenCents", pr.chosenCents ?: JSONObject.NULL)
                    .put("applied", pr.applied)
            } ?: JSONObject.NULL)
            .put("lines", linesJson)
        return json.toString(2)
    }

    private fun round4(value: Double): Double = Math.round(value * 10000) / 10000.0

    private fun rotate(dir: File) {
        val files = dir.listFiles { file -> file.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }

    private companion object {
        const val MAX_FILES = 50
    }
}
