package com.larzos.os

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * The one screen: a full-window terminal attached to a proot process that
 * booted the LarzOS userland into `larzsh`.
 */
class TerminalActivity : AppCompatActivity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var view: TerminalView
    private var session: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = TerminalView(this, null)
        view.setTerminalViewClient(this)
        view.setTextSize((resources.displayMetrics.density * 14).toInt())
        setContentView(view)

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
        view.requestFocus()
        startService(Intent(this, LarzSessionService::class.java))
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        stopService(Intent(this, LarzSessionService::class.java))
        super.onDestroy()
    }

    // --- TerminalSessionClient ---
    override fun onTextChanged(changedSession: TerminalSession) { view.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) { finish() }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
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
    override fun onSingleTapUp(e: MotionEvent?) { view.requestFocus() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}
}
