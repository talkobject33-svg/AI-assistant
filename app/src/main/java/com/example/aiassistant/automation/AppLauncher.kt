package com.example.aiassistant.automation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class AppLauncher(private val context: Context) {
    data class InstalledApp(val label: String, val packageName: String)

    sealed interface Resolution {
        data class Found(val app: InstalledApp) : Resolution
        data class Ambiguous(val options: List<InstalledApp>) : Resolution
        data object NotFound : Resolution
    }

    /** Apps with a launcher entry (visible via the <queries> declaration in the manifest). */
    fun installedApps(): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
    }

    fun resolve(query: String): Resolution {
        val q = normalize(query)
        if (q.isEmpty()) return Resolution.NotFound
        val scored = installedApps().map { it to score(normalize(it.label), q) }
            .filter { it.second >= 55 }.sortedByDescending { it.second }
        if (scored.isEmpty()) return Resolution.NotFound
        val best = scored.first()
        val close = scored.filter { it.second >= best.second - 5 }
        return if (close.size > 1 && close.map { normalize(it.first.label) }.distinct().size > 1)
            Resolution.Ambiguous(close.take(4).map { it.first }) else Resolution.Found(best.first)
    }

    /** Returns null on success (launch REQUESTED), or an error message. */
    fun launch(app: InstalledApp, launcherContext: Context): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return "${app.label} can't be launched directly."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            launcherContext.startActivity(intent); null
        } catch (e: ActivityNotFoundException) {
            "${app.label} has no launchable screen."
        } catch (e: SecurityException) {
            "Android blocked launching ${app.label}."
        }
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("\\b(app|application|browser)\\b"), " ").filter { it.isLetterOrDigit() }

    private fun score(label: String, q: String): Int {
        if (label.isEmpty()) return 0
        return when {
            label == q -> 100
            label.startsWith(q) -> 85
            label.contains(q) && q.length >= 3 -> 70
            q.contains(label) && label.length >= 4 -> 60
            q.length >= 5 && label.length >= 5 &&
                1.0 - levenshtein(label, q).toDouble() / maxOf(label.length, q.length) >= 0.8 -> 58
            else -> 0
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
