package com.apkmcp.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.apkmcp.app.core.Logs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 唯一的「手」：点击 / 长按 / 滑动 / 输入 / 返回。
 * 全部走无障碍 API，不需要 root，不需要 adb。
 *
 * 手势返回值语义：true = 系统确认手势已执行完成；false = 被取消 / 被拒 / 超时未确认。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logs.add("无障碍服务已连接")
    }

    /** 系统的「大脑信号」全部进入事件总线（此前被直接丢弃） */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { EventBus.push(this, it) }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Logs.add("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ── 基本信息 ────────────────────────────────────────────

    fun rootNode(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        null
    }

    fun currentPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (t: Throwable) {
        null
    }

    fun realSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    fun uiTree(maxNodes: Int = 300): String = UiTreeReader.dump(rootNode(), maxNodes)

    // ── 手势 ────────────────────────────────────────────────

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0L, 60L)
    }

    fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0L, 650L)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, 0L, durationMs.coerceIn(50L, 5000L))
    }

    /**
     * 派发一条手势，并等它真正执行完才返回。
     *
     * dispatchGesture 的返回值只代表「已受理」，不代表「已落地」；真正的成败由
     * GestureResultCallback 异步给出（被用户触摸打断、被安全策略拦截都会走 onCancelled）。
     * 这里用闭锁等到回调再返回（超时 = 手势时长 + 2 秒），调用方拿到的就是真实结果。
     *
     * 注：dispatchGesture 默认会取消仍在进行中的前一条手势 —— 由于 ToolRegistry
     * 已把所有工具调用串行化（见其 lock），本服务自己的手势不会互相打断。
     *
     * 防御：若在主线程调用则不能等（回调也走主线程，等了就是死锁），
     * 退化为只返回「已受理」。当前所有调用方都在 HTTP 工作线程，不走这条分支。
     */
    private fun dispatch(path: Path, start: Long, duration: Long): Boolean {
        EventBus.markAgent(1000L + duration)   // 由此产生的系统事件会被打上 [agent] 标记
        var completed = false
        val done = CountDownLatch(1)
        val onMain = Looper.getMainLooper() === Looper.myLooper()
        return try {
            val stroke = GestureDescription.StrokeDescription(path, start, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val accepted = dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed = true
                        done.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Logs.add("手势被取消（可能被用户触摸或安全策略打断）")
                        done.countDown()
                    }
                },
                null
            )

            if (!accepted) {
                Logs.add("手势派发被系统拒绝")
                return false
            }
            if (onMain) {
                Logs.add("警告：主线程调用手势，无法等待真实结果，仅报已受理")
                return true
            }
            if (!done.await(duration + 2000L, TimeUnit.MILLISECONDS)) {
                Logs.add("手势结果超时未确认（时长 ${duration}ms）")
                return false
            }
            completed
        } catch (t: Throwable) {
            Logs.add("手势失败: ${t.message}")
            false
        }
    }

    // ── 控件查找 ────────────────────────────────────────────

    fun findNode(needle: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val root = rootNode() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val n = queue.removeFirst()
            visited++
            if (matches(n.text?.toString(), needle, exact) ||
                matches(n.contentDescription?.toString(), needle, exact) ||
                matches(n.hintText?.toString(), needle, exact)
            ) {
                return n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun matches(value: String?, needle: String, exact: Boolean): Boolean {
        if (value.isNullOrBlank()) return false
        return if (exact) value.equals(needle, ignoreCase = true)
        else value.contains(needle, ignoreCase = true)
    }

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        return tap(cx, cy)
    }

    fun scrollNode(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        return try {
            node.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
        } catch (t: Throwable) {
            false
        }
    }

    // ── 输入 ────────────────────────────────────────────────

    fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootNode() ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (it.isEditable) return it
        }
        // WebView 虚拟树可能很深（ChatGPT 的 contenteditable 深度>30）：
        // 与 findNode 一致的 BFS，无深度限制，只受节点预算约束
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val n = queue.removeFirst()
            visited++
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun typeText(text: String, replace: Boolean = true): Boolean {
        val target = focusedEditable() ?: return false
        EventBus.markAgent(1500L)
        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            Logs.add("输入失败: ${t.message}")
            false
        }
    }

    // ── 事件层新能力：光标 / 选区 / 粘贴 / 节点点击 ─────────

    /** 键盘是否打开（TYPE_INPUT_METHOD 窗口存在即开） */
    fun keyboardState(): Triple<Boolean, String?, String?> {
        return try {
            var open = false
            var imePkg: String? = null
            var activePkg: String? = null
            for (w in windows) {
                if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    open = true
                    imePkg = w.root?.packageName?.toString()
                }
                if (w.isActive && w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    activePkg = w.root?.packageName?.toString()
                }
            }
            Triple(open, imePkg, activePkg)
        } catch (t: Throwable) {
            Triple(false, null, null)
        }
    }

    /**
     * 移动光标 / 选中文本（字符偏移，非像素！）。start==end 即光标。
     * @param find 可选：按文字找目标输入框；不传则用当前聚焦的输入框
     * @return 三元组：成功、事件回显验证、目标框当前文字长度（-1=未知）
     */
    fun setSelection(start: Int, end: Int, find: String? = null, exact: Boolean = false): Triple<Boolean, Boolean, Int> {
        val target = (find?.let { findNode(it, exact) } ?: focusedEditable())
            ?: return Triple(false, false, -1)
        EventBus.markAgent(1200L)
        val len = target.text?.length ?: -1
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        val ok = try {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (t: Throwable) {
            false
        }
        // 从事件流里验证系统真的把光标放上去了（人类「看见光标闪」的机器版）
        val echoed = EventBus.waitFor(
            EventBus.nowMono() - 500L, 900L
        ) {
            it.type == "VIEW_TEXT_SELECTION_CHANGED" && it.selStart == start && it.selEnd == end
        } != null
        return Triple(ok, echoed, len)
    }

    /**
     * 走「真输入法路线」：写入剪贴板 → 光标定位 → ACTION_PASTE。
     * 比 ACTION_SET_TEXT 更接近人手输入，部分 App（如 Telegram）只认这条路。
     * @param append true=追加到末尾，false=全量替换
     */
    fun pasteText(text: String, append: Boolean): Boolean {
        val target = focusedEditable() ?: return false
        EventBus.markAgent(2000L)
        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("apkmcp", text))
            if (append) {
                val cur = target.text?.toString() ?: ""
                val argsEnd = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cur.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cur.length)
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, argsEnd)
                Thread.sleep(120)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (t: Throwable) {
            Logs.add("粘贴失败: ${t.message}")
            false
        }
    }

    /** 节点级点击（performAction），与手势点击是两条不同的路，手势失灵时用它 */
    fun nodeClick(find: String, exact: Boolean = false): Boolean {
        val node = findNode(find, exact) ?: return false
        EventBus.markAgent(1200L)
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (t: Throwable) {
            false
        }
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun ready(): Boolean = instance != null
    }
}
