package com.larzos.os

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val env = (application as LarzApp).env

        val ready = env.isInstalled && env.proot.exists() && !env.needsRootfsUpdate
        val next = if (ready) {
            Intent(this, TerminalActivity::class.java)
        } else {
            Intent(this, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_UPDATE, env.needsRootfsUpdate)
        }
        startActivity(next)
        finish()
    }
}
