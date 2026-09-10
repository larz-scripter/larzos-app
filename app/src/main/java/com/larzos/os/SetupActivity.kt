package com.larzos.os

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupActivity : AppCompatActivity() {

    companion object { const val EXTRA_UPDATE = "update" }

    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        status = findViewById(R.id.status)
        bar = findViewById(R.id.bar)
        button = findViewById(R.id.action)

        if (LarzSession.supportedAbi == null) {
            status.text = getString(R.string.err_arch)
            button.visibility = View.GONE
            return
        }

        button.setOnClickListener { startInstall() }

        // A base-system update - explain, and just start it.
        if (intent.getBooleanExtra(EXTRA_UPDATE, false)) {
            status.text = "Updating the LarzOS base system…"
            button.text = "Update now"
            startInstall()
        }
    }

    private fun startInstall() {
        button.isEnabled = false
        bar.visibility = View.VISIBLE
        val env = (application as LarzApp).env
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    Installer(env).install { pct, msg ->
                        runOnUiThread {
                            status.text = msg
                            bar.isIndeterminate = pct < 0
                            if (pct >= 0) bar.progress = pct
                        }
                    }
                }.onFailure { it.printStackTrace() }.isSuccess
            }
            if (ok) {
                startActivity(Intent(this@SetupActivity, TerminalActivity::class.java))
                finish()
            } else {
                status.text = "Setup failed. Check your connection and retry."
                button.text = getString(R.string.setup_retry)
                button.isEnabled = true
                bar.visibility = View.GONE
            }
        }
    }
}
