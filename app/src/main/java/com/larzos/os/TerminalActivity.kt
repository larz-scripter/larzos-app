package com.larzos.os

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * The one screen: a full-window terminal attached to a proot process that
 * booted the LarzOS userland into `larzsh`, plus a row of keys a touch
 * keyboard doesn't give you (Esc, Ctrl, Alt, Tab, arrows, ...).
 */
class TerminalActivity : AppCompatActivity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var view: TerminalView
    private var session: TerminalSession? = null

    // Sticky modifiers driven by the extra-keys row. One-shot: applied to the
    // next key then cleared (TerminalView polls read*Key() while handling input).
    private var ctrl = false
    private var alt = false
    private var shift = false
    private var fn = false
    private var ctrlBtn: Button? = null
    private var altBtn: Button? = null
    private var shiftBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )

        view = TerminalView(this, null)
        view.setTerminalViewClient(this)
        view.setTextSize((resources.displayMetrics.density * 14).toInt())
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildExtraKeys(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        val env = (application as LarzApp).env
        val argv = LarzSession.prootArgv(env)
        val s = TerminalSession(
            argv[0],
            env.root.absolutePath,
            argv.drop(1).toTypedArray(),
            LarzSession.prootEnv(env),
            2000,               // transcript rows
            this
        )
        session = s
        view.attachSession(s)
        view.post { showKeyboard() }
        startService(Intent(this, LarzSessionService::class.java))
        startService(Intent(this, LarzPrivService::class.java))
    }

    override fun onResume() {
        super.onResume()
        view.post { showKeyboard() }
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        stopService(Intent(this, LarzSessionService::class.java))
        stopService(Intent(this, LarzPrivService::class.java))
        super.onDestroy()
    }

    /** Bring up the soft keyboard, focused on the terminal. */
    private fun showKeyboard() {
        view.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    // --- extra keys row -----------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun keyButton(label: String, onClick: (Button) -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#CFE8F3"))
            setBackgroundColor(Color.parseColor("#141c2e"))
            minWidth = dp(44)
            minimumWidth = dp(44)
            minHeight = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(2), dp(3), dp(2), dp(3))
            layoutParams = lp
            setOnClickListener { onClick(this) }
        }

    private fun buildExtraKeys(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        bar.addView(keyButton("Esc") { code(KeyEvent.KEYCODE_ESCAPE) })
        ctrlBtn = keyButton("Ctrl") { ctrl = !ctrl; paintToggle(it, ctrl) }
        altBtn = keyButton("Alt") { alt = !alt; paintToggle(it, alt) }
        shiftBtn = keyButton("Shift") { shift = !shift; paintToggle(it, shift) }
        bar.addView(ctrlBtn)
        bar.addView(altBtn)
        bar.addView(shiftBtn)
        bar.addView(keyButton("Tab") { code(KeyEvent.KEYCODE_TAB) })
        bar.addView(keyButton("←") { code(KeyEvent.KEYCODE_DPAD_LEFT) })
        bar.addView(keyButton("↑") { code(KeyEvent.KEYCODE_DPAD_UP) })
        bar.addView(keyButton("↓") { code(KeyEvent.KEYCODE_DPAD_DOWN) })
        bar.addView(keyButton("→") { code(KeyEvent.KEYCODE_DPAD_RIGHT) })
        bar.addView(keyButton("Home") { code(KeyEvent.KEYCODE_MOVE_HOME) })
        bar.addView(keyButton("End") { code(KeyEvent.KEYCODE_MOVE_END) })
        bar.addView(keyButton("PgUp") { code(KeyEvent.KEYCODE_PAGE_UP) })
        bar.addView(keyButton("PgDn") { code(KeyEvent.KEYCODE_PAGE_DOWN) })
        bar.addView(keyButton("|") { chars("|") })
        bar.addView(keyButton("/") { chars("/") })
        bar.addView(keyButton("-") { chars("-") })
        bar.addView(keyButton("~") { chars("~") })

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            addView(bar)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Voice + System access are app navigation, not keyboard input - kept
        // permanently visible instead of living inside the scrollable key row,
        // where they were previously the last two of 18 buttons and easy to
        // never notice at all.
        val navColor = Color.parseColor("#1d3a2e")
        val voiceBtn = keyButton("🎤") { startActivity(Intent(this, VoiceActivity::class.java)) }
            .apply { setBackgroundColor(navColor) }
        val settingsBtn = keyButton("⚙") { startActivity(Intent(this, SystemAccessActivity::class.java)) }
            .apply { setBackgroundColor(navColor) }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(28))
            setBackgroundColor(Color.parseColor("#26303d"))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0B1020"))
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(voiceBtn)
            addView(settingsBtn)
            addView(divider)
            addView(scroller)
        }
    }

    private fun paintToggle(b: Button, on: Boolean) {
        b.setBackgroundColor(Color.parseColor(if (on) "#22D3EE" else "#141c2e"))
        b.setTextColor(Color.parseColor(if (on) "#08131c" else "#CFE8F3"))
    }

    private fun keyMod(): Int =
        (if (ctrl) KeyHandler.KEYMOD_CTRL else 0) or
            (if (alt) KeyHandler.KEYMOD_ALT else 0) or
            (if (shift) KeyHandler.KEYMOD_SHIFT else 0)

    private fun clearMods() {
        if (ctrl) { ctrl = false; ctrlBtn?.let { paintToggle(it, false) } }
        if (alt) { alt = false; altBtn?.let { paintToggle(it, false) } }
        if (shift) { shift = false; shiftBtn?.let { paintToggle(it, false) } }
        fn = false
    }

    /** Feed a keycode (arrows / esc / tab / ...) to the terminal with the sticky modifiers. */
    private fun code(keyCode: Int) {
        view.handleKeyCode(keyCode, keyMod())
        clearMods()
        showKeyboard()
    }

    private fun chars(s: String) {
        val sess = session ?: return
        if (s.length == 1 && (ctrl || alt)) {
            var cp = s[0].code
            if (ctrl) cp = cp and 0x1f
            sess.writeCodePoint(alt, cp)   // prependEscape = alt
        } else {
            sess.write(s)
        }
        clearMods()
        showKeyboard()
    }

    // --- TerminalSessionClient ---
    override fun onTextChanged(changedSession: TerminalSession) { view.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) { finish() }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("LarzOS", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = clipboard().primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        (session ?: this.session)?.emulator?.paste(text)
    }

    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null

    // --- shared logging (both client interfaces declare these) ---
    override fun logError(tag: String?, message: String?) { android.util.Log.e(tag, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { android.util.Log.i(tag, message ?: "") }
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { android.util.Log.e(tag, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag, "", e) }

    // --- TerminalViewClient ---
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent?) { showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean { clearMods(); return false }
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = ctrl
    override fun readAltKey(): Boolean = alt
    override fun readShiftKey(): Boolean = shift
    override fun readFnKey(): Boolean = fn
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        clearMods()
        return false
    }
    override fun onEmulatorSet() {}
}
