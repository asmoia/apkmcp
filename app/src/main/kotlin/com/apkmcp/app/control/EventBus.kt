package com.apkmcp.app.control

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 「大脑信号」：把系统 AccessibilityEvent 变成 AI 可读的事件流。
 *
 * - 环形缓冲最近 MAX 条事件（纯文本、省 token）
 * - WINDOW_CONTENT_CHANGED 风暴合并（同源 300ms 内合并成一条）
 * - wait_event 在服务端阻塞等待，客户端一次往返
 * - 标记「可能是 Agent 自己造成的」事件，用于区分用户操作
 */
object EventBus {

    /** 一条精简事件。字段都可为空/无效(-1)，按类型取用。 */
    class Ev(
        val tMs: Long,          // 墙钟 System.currentTimeMillis()
        var monoMs: Long,       // SystemClock.elapsedRealtime()
        val type: String,       // 如 VIEW_TEXT_SELECTION_CHANGED（已去掉 TYPE_ 前缀）
        val pkg: String?,
        val cls: String?,
        val text: String?,      // 事件文本，截断 160 字
        val fromIndex: Int,     // TEXT_CHANGED：变更起点
        val added: Int,         // TEXT_CHANGED：加了几个字
        val removed: Int,       // TEXT_CHANGED：删了几个字
        val selStart: Int,      // SELECTION_CHANGED：光标/选区起点（-1=未知）
        val selEnd: Int,        // SELECTION_CHANGED：终点
        val scrollX: Int,
        val scrollY: Int,
        val changeTypes: Int,   // WINDOW_CONTENT_CHANGED 细分 bitmask
        val byAgent: Boolean,   // 猜测：由我们自己的手势/输入触发
        var merged: Int = 1     // 合并了几条同类事件
    ) {
        fun line(): String = buildString {
            val hm = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(tMs))
            append(hm).append(' ').append(type)
            if (pkg != null) append(" pkg=").append(pkg)
            if (cls != null && cls.length < 40) append(" cls=").append(cls.substringAfterLast('.'))
            if (text != null && text.isNotBlank()) append(" text=\"").append(text).append('"')
            if (selStart >= 0) append(" sel=").append(selStart).append("..").append(selEnd)
            if (fromIndex >= 0) append(" ch@").append(fromIndex)
                .append(" +").append(added).append(" -").append(removed)
            if (scrollY != 0 || scrollX != 0) append(" scroll=").append(scrollX).append(',').append(scrollY)
            if (merged > 1) append(" ×").append(merged)
            if (byAgent) append(" [agent]")
        }
    }

    private const val MAX = 500
    private val buf = ArrayDeque<Ev>(MAX)
    private val lk = ReentrantLock()
    private val cond = lk.newCondition()

    /** 我们自己的手势/输入生效期间，新事件都会带上 byAgent 标记 */
    @Volatile private var agentUntilMono = 0L

    @Volatile var lastWindowState: Ev? = null
        private set
    @Volatile var lastUserActivityMono: Long = -1L
    @Volatile private var lastPushMono: Long = -1L
    @Volatile var pushedCount: Long = 0
    @Volatile var mergedCount: Long = 0

    fun markAgent(windowMs: Long = 1500L) {
        agentUntilMono = SystemClock.elapsedRealtime() + windowMs
    }

    fun nowMono(): Long = SystemClock.elapsedRealtime()

    // ── 事件名（不用隐藏 API，自己映射）─────────────────────
    private fun typeName(t: Int): String = when (t) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "VIEW_HOVER_ENTER"
        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "VIEW_HOVER_EXIT"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> "TOUCH_GESTURE_START"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> "TOUCH_GESTURE_END"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "VIEW_TEXT_SELECTION_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "TEXT_TRAVERSED"
        else -> "EVENT_0x" + Integer.toHexString(t)
    }

    // ── 入口：由 AgentAccessibilityService.onAccessibilityEvent 调用 ──
    fun push(svc: AgentAccessibilityService?, e: AccessibilityEvent) {
        try {
            val now = SystemClock.elapsedRealtime()
            val byAgent = now <= agentUntilMono
            val wall = System.currentTimeMillis()

            var text: String? = null
            val sb = StringBuilder()
            for (c in e.text) sb.append(c)
            if (sb.isNotEmpty()) {
                var s = sb.toString().replace('\n', ' ').trim()
                if (s.length > 160) s = s.take(160) + "…"
                text = s.ifBlank { null }
            }

            var selStart = -1
            var selEnd = -1
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                val src: AccessibilityNodeInfo? = try { e.source } catch (t: Throwable) { null }
                selStart = src?.textSelectionStart ?: -1
                selEnd = src?.textSelectionEnd ?: -1
                if (selStart < 0 || selEnd < 0) {
                    val f = try { svc?.focusedEditable() } catch (t: Throwable) { null }
                    if (f != null) {
                        selStart = f.textSelectionStart
                        selEnd = f.textSelectionEnd
                    }
                }
            }

            val ev = Ev(
                tMs = wall, monoMs = now, type = typeName(e.eventType),
                pkg = e.packageName?.toString(), cls = e.className?.toString(),
                text = text,
                fromIndex = if (e.fromIndex >= 0) e.fromIndex else -1,
                added = e.addedCount, removed = e.removedCount,
                selStart = selStart, selEnd = selEnd,
                scrollX = e.scrollX, scrollY = e.scrollY,
                changeTypes = e.contentChangeTypes,
                byAgent = byAgent
            )

            lk.withLock {
                // 合并 CONTENT_CHANGED 风暴：同包同类 300ms 内 → 就地累加
                if (ev.type == "WINDOW_CONTENT_CHANGED") {
                    val tail = buf.lastOrNull()
                    if (tail != null && tail.type == ev.type && tail.pkg == ev.pkg &&
                        tail.cls == ev.cls && now - tail.monoMs < 300
                    ) {
                        tail.monoMs = now
                        tail.merged++
                        mergedCount++
                        lastPushMono = now
                        cond.signalAll()
                        return
                    }
                }
                if (buf.size >= MAX) buf.removeFirst()
                buf.addLast(ev)
                pushedCount++
                lastPushMono = now
                if (ev.type == "WINDOW_STATE_CHANGED") lastWindowState = ev
                if (!byAgent && ev.type in USER_SIGNALS) lastUserActivityMono = now
                cond.signalAll()
            }
        } catch (t: Throwable) {
            // 事件层绝不许崩掉服务
        }
    }

    private val USER_SIGNALS = setOf(
        "VIEW_CLICKED", "VIEW_LONG_CLICKED", "VIEW_FOCUSED",
        "VIEW_TEXT_SELECTION_CHANGED", "VIEW_SCROLLED"
    )

    // ── 读取 ───────────────────────────────────────────────

    /** 墙钟 → 单调钟换算（同一时刻取的样本） */
    private fun wallToMono(tMs: Long): Long =
        tMs - (System.currentTimeMillis() - SystemClock.elapsedRealtime())

    fun snapshot(
        sinceMs: Long? = null,
        typeContains: String? = null,
        pkgContains: String? = null,
        limit: Int = 40
    ): List<Ev> {
        val sinceMono = sinceMs?.let { wallToMono(it) } ?: Long.MIN_VALUE
        lk.withLock {
            return buf.asReversed()
                .filter { it.monoMs >= sinceMono }
                .filter { typeContains == null || it.type.contains(typeContains, true) }
                .filter { pkgContains == null || (it.pkg ?: "").contains(pkgContains, true) }
                .take(limit.coerceIn(1, MAX))
        }
    }

    /**
     * 服务端阻塞等待一条匹配事件。
     * @param sinceMono 只考虑这个单调钟时刻之后的事件（= 调用时刻则只等新事件）
     * @return 命中的事件；超时返回 null
     */
    fun waitFor(sinceMono: Long, timeoutMs: Long, pred: (Ev) -> Boolean): Ev? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(0L, 120000L)
        lk.withLock {
            while (true) {
                val hit = buf.lastOrNull { it.monoMs >= sinceMono && pred(it) }
                if (hit != null) return hit
                val remain = deadline - SystemClock.elapsedRealtime()
                if (remain <= 0) return null
                try {
                    cond.await(remain, TimeUnit.MILLISECONDS)
                } catch (ie: InterruptedException) {
                    return null
                }
            }
        }
    }

    /** 等事件流安静 quietMs（页面稳定）。@return 是否在超时前安静下来 */
    fun waitQuiet(sinceMono: Long, quietMs: Long, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(0L, 120000L)
        lk.withLock {
            while (true) {
                val last = maxOf(lastPushMono, sinceMono)
                val quietFor = SystemClock.elapsedRealtime() - last
                if (quietFor >= quietMs) return true
                val remain = deadline - SystemClock.elapsedRealtime()
                if (remain <= 0) return false
                try {
                    cond.await(minOf(remain, maxOf(quietMs - quietFor + 50, 50L)), TimeUnit.MILLISECONDS)
                } catch (ie: InterruptedException) {
                    return false
                }
            }
        }
    }

    fun stats(): String =
        "buffered=${bufSize()} pushed=$pushedCount merged=$mergedCount " +
            "user_idle_ms=${userIdleMs()}"
            .replace("user_idle_ms=-1", "user_idle_ms=unknown")

    fun bufSize(): Int = lk.withLock { buf.size }

    /** 用户（非 Agent）多久没动了；-1 = 从未见过用户活动 */
    fun userIdleMs(): Long {
        val u = lastUserActivityMono
        if (u < 0) return -1
        return SystemClock.elapsedRealtime() - u
    }
}
