package com.larzos.os

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val env = (application as LarzApp).env
        val next = if (env.isInstalled && env.proot.exists())
            Intent(this, TerminalActivity::class.java)
        else
            Intent(this, SetupActivity::class.java)
        startActivity(next)
        finish()
    }
}
