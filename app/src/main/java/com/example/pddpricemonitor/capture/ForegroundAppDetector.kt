package com.example.pddpricemonitor.capture

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

class ForegroundAppDetector(
    private val context: Context
) {
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun currentPackageName(): String? {
        if (!hasUsageAccess()) return null

        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var currentPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            ) {
                currentPackage = event.packageName
            }
        }

        return currentPackage
    }

    fun isPddForeground(): Boolean =
        currentPackageName() == PddForegroundState.PDD_PACKAGE_NAME

    companion object {
        private const val LOOKBACK_MS = 2 * 60_000L
    }
}
