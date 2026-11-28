package com.mapeador.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class InstalledApp(
    val packageName: String,
    val label: String,
)

class InstalledAppRepository(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun loadLauncherApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == appContext.packageName) return@mapNotNull null
                InstalledApp(
                    packageName = packageName,
                    label = resolveInfo.loadLabel(packageManager).toString(),
                )
            }
            .distinctBy(InstalledApp::packageName)
            .sortedBy { it.label.lowercase() }
    }

    fun getAppLabel(packageName: String): String? =
        try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
}
