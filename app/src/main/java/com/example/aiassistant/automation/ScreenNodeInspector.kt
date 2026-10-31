package com.example.aiassistant.automation

import android.view.accessibility.AccessibilityNodeInfo

object ScreenNodeInspector {
    enum class Mode { CLICKABLE, EDITABLE }

    sealed interface Lookup {
        data class Found(val node: AccessibilityNodeInfo, val label: String, val needsGestureTap: Boolean) : Lookup
        data class Ambiguous(val labels: List<String>) : Lookup
        data object NotFound : Lookup
    }

    fun labelOf(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString())
            .firstOrNull { it.isNotBlank() }.orEmpty()

    /** Breadth-first walk of the visible accessibility tree, capped to avoid runaway trees. */
    fun collect(root: AccessibilityNodeInfo, limit: Int = 1500): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && out.size < limit) {
            val n = queue.removeFirst()
            if (!n.isVisibleToUser) continue
            out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    fun find(root: AccessibilityNodeInfo, query: String, mode: Mode): Lookup {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return Lookup.NotFound
        data class Cand(val target: AccessibilityNodeInfo, val label: String, val score: Int, val gesture: Boolean)
        val cands = ArrayList<Cand>()
        for (n in collect(root)) {
            val label = labelOf(n)
            val s = score(label.lowercase().trim(), q)
            if (s < 60) continue
            when (mode) {
                Mode.EDITABLE -> if (n.isEditable) cands.add(Cand(n, label, s, false))
                Mode.CLICKABLE -> {
                    val t = clickTarget(n)
                    if (t != null) cands.add(Cand(t, label, s, false))
                    else if (s >= 80) cands.add(Cand(n, label, s, true))
                }
            }
        }
        if (cands.isEmpty()) return Lookup.NotFound
        val distinct = cands.distinctBy { it.target }.sortedByDescending { it.score }
        val best = distinct.first()
        val rivals = distinct.drop(1).filter { it.score >= best.score - 10 }
        if (rivals.isNotEmpty()) return Lookup.Ambiguous((listOf(best) + rivals).map { it.label }.distinct().take(4))
        return Lookup.Found(best.target, best.label, best.gesture)
    }

    private fun score(label: String, q: String): Int = when {
        label.isEmpty() -> 0
        label == q -> 100
        label.startsWith(q) -> 80
        label.contains(q) -> 60
        else -> 0
    }

    private fun clickTarget(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent; depth++
        }
        return null
    }
}
