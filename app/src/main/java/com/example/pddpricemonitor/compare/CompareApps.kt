package com.example.pddpricemonitor.compare

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class CompareApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

/**
 * 比价跳转应用：长按商品卡片弹出径向菜单，滑到图标松手即复制标题并唤起该应用。
 * 选择的应用按顺序存 SharedPreferences（包名列表，上限 5 个）。
 */
object CompareApps {
    private const val PREFS = "compare_apps"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_LAST_LAUNCH_INDEX = "lastLaunchIndex"
    private const val KEY_AUTO_COPY = "autoCopyTitle"
    private const val KEY_DIRECT_SEARCH = "directSearch"
    const val MAX_APPS = 5

    // 已知搜索 deeplink：包名 → (原始关键词) → 搜索结果页 URI。
    // 每项自行完成 URL 编码（京东是 JSON 整体编码，不能收到预编码的关键词，否则双重编码变乱码）。
    // 未登记的应用（如快手、抖音商城）走启动页兜底。
    // 京东为 virtual?params= JSON 格式（des/keyWord/from/category 四参数缺一不可，
    // 已实测直达 ProductListActivity 搜索结果页）
    private val SEARCH_URIS: Map<String, (String) -> String> = mapOf(
        "com.taobao.taobao" to { q -> "taobao://s.taobao.com/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}" },
        "com.tmall.wireless" to { q -> "tmall://page.tm/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}" },
        "com.jingdong.app.mall" to { q ->
            "openapp.jdmobile://virtual?params=" + java.net.URLEncoder.encode(
                "{\"des\":\"productList\",\"keyWord\":\"$q\",\"from\":\"search\",\"category\":\"jump\"}", "UTF-8"
            )
        },
        "com.achievo.vipshop" to { q -> "vipshop://search?keyword=${java.net.URLEncoder.encode(q, "UTF-8")}" },
        "com.xunmeng.pinduoduo" to { q -> "pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=${java.net.URLEncoder.encode(q, "UTF-8")}" },
        "com.netease.yanxuan" to { q -> "yanxuan://search?keyword=${java.net.URLEncoder.encode(q, "UTF-8")}" },
        "com.sankuai.meituan" to { q -> "imeituan://www.meituan.com/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}" },
        "com.dianping.v1" to { q -> "dianping://searchlist?keyword=${java.net.URLEncoder.encode(q, "UTF-8")}" },
        "com.xingin.xhs" to { q -> "xhsdiscover://search/result?keyword=${java.net.URLEncoder.encode(q, "UTF-8")}" }
    )

    fun selectedPackages(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PACKAGES, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun saveSelected(context: Context, packages: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGES, packages.take(MAX_APPS).joinToString(","))
            .apply()
    }

    /** 双击悬浮球轮换跳转：按已选顺序循环，返回本次应启动的包名；一个都没选返回 null */
    fun nextPackageName(context: Context): String? {
        val pkgs = selectedPackages(context)
        if (pkgs.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = (prefs.getInt(KEY_LAST_LAUNCH_INDEX, -1) + 1).mod(pkgs.size)
        prefs.edit().putInt(KEY_LAST_LAUNCH_INDEX, next).apply()
        return pkgs[next]
    }

    /** 长按悬浮球回到拼多多时调用：本轮比价结束，轮换位置归零——下次双击从第一个应用重新开始 */
    fun resetLaunchIndex(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_LAUNCH_INDEX, -1)
            .apply()
    }

    fun appLabel(context: Context, packageName: String): String = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
            .loadLabel(context.packageManager).toString()
    }.getOrDefault(packageName)

    /** 识别后自动复制标题（默认关，需用户在设置里主动开启） */
    fun isAutoCopyTitle(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_COPY, false)

    fun setAutoCopyTitle(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_COPY, enabled)
            .apply()
    }

    /** 跳转直达搜索结果（默认关）：开启后跳比价应用时带标题直达其搜索结果页，省去手动粘贴搜索 */
    fun isDirectSearch(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DIRECT_SEARCH, false)

    fun setDirectSearch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DIRECT_SEARCH, enabled)
            .apply()
    }

    // —— 剪贴板意图账本（进程内共享、不落盘）——
    // desiredTitle：用户最新想放进剪贴板的标题——识别、面板编辑、长按卡片复制都会更新它
    // knownClipTitle：最后一次实际写进剪贴板的标题
    // 双击时两者不一致才复制。关键：双击只读这里的账本，不读悬浮面板的 EditText——
    // 面板文本是识别时的旧视图，若它优先级最高，「识别A后长按复制B再双击」会把旧A盖掉新B
    @Volatile var desiredTitle: String? = null
    @Volatile var knownClipTitle: String? = null

    /** 登记剪贴板意图：识别完成、面板编辑标题时调用 */
    fun recordDesiredTitle(title: String) {
        desiredTitle = title
    }

    /** 前台复制完成（长按卡片 / 中转页写入后调用）：意图与剪贴板同步为该标题 */
    fun markCopied(title: String) {
        desiredTitle = title
        knownClipTitle = title
    }

    /** 已选且仍安装的应用（图标已转 ImageBitmap）；卸载的自动跳过 */
    fun resolveSelected(context: Context, iconSizePx: Int = 128): List<CompareApp> {
        val pm = context.packageManager
        return selectedPackages(context).take(MAX_APPS).mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                CompareApp(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    icon = drawableToImageBitmap(pm.getApplicationIcon(info), iconSizePx)
                )
            }.getOrNull()
        }
    }

    /** 手机上全部可启动应用（排除本应用），按名称排序，供选择器展示 */
    fun listLaunchable(context: Context, iconSizePx: Int = 96): List<CompareApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                runCatching {
                    val info = pm.getApplicationInfo(pkg, 0)
                    CompareApp(
                        packageName = pkg,
                        label = info.loadLabel(pm).toString(),
                        icon = drawableToImageBitmap(pm.getApplicationIcon(info), iconSizePx)
                    )
                }.getOrNull()
            }
            .sortedBy { it.label }
            .toList()
    }

    /** 不支持搜索 deeplink、但把 ACTION_SEND 文本当搜索词处理的应用：分享即搜索，无需粘贴 */
    private val SEARCH_BY_SHARE = setOf("com.taobao.idlefish")

    /**
     * 唤起应用。「跳转直达搜索」开启且该应用有已知搜索 deeplink 时，带标题直达搜索结果页；
     * 否则落到其启动页。deeplink 不可达（应用版本不支持）自动退回启动页。
     * 未安装/无可启动入口返回 false。
     */
    fun launch(context: Context, packageName: String, query: String? = null): Boolean {
        if (isDirectSearch(context) && !query.isNullOrBlank()) {
            SEARCH_URIS[packageName]?.invoke(query)?.let { uri ->
                if (launchUri(context, uri)) return true
                Log.w(TAG, "deeplink failed, fall back: pkg=$packageName")
            }
            if (packageName in SEARCH_BY_SHARE && launchShareSearch(context, packageName, query)) return true
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    // 闲鱼等应用的搜索入口：ACTION_SEND 文本直达搜索结果页（真机实测落
    // search_implement.SearchResultActivity，关键词已填好），无需剪贴板粘贴
    private fun launchShareSearch(context: Context, packageName: String, query: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, query)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.onFailure { Log.w(TAG, "share-search failed: pkg=$packageName", it) }
            .getOrDefault(false)
    }

    private fun launchUri(context: Context, uri: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.onFailure { Log.w(TAG, "deeplink start failed: $uri", it) }
            .getOrDefault(false)
    }

    private const val TAG = "CompareApps"

    private fun drawableToImageBitmap(drawable: Drawable, sizePx: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }
}
