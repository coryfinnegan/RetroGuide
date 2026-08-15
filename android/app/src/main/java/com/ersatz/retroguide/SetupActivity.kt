package com.ersatz.retroguide

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ersatz.retroguide.databinding.ActivitySetupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run setup, and the place to come back to when the server moves.
 *
 * Scanning starts on its own, because on a fresh install the useful default is
 * "find it for me" rather than an empty text box the user has to fill in with a
 * remote control.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var ui: ActivitySetupBinding
    private val found = mutableListOf<Discovery.Found>()
    private lateinit var adapter: FoundAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(ui.root)

        adapter = FoundAdapter()
        ui.foundList.layoutManager = LinearLayoutManager(this)
        ui.foundList.adapter = adapter

        Prefs.host(this)?.let { ui.hostInput.setText(it) }

        ui.btnScan.setOnClickListener { scan() }
        ui.btnConnect.setOnClickListener { connectManual() }

        scan()
    }

    private fun scan() {
        found.clear(); adapter.notifyDataSetChanged()
        ui.subtitle.text = getString(R.string.setup_scanning)
        ui.btnScan.isEnabled = false
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                Discovery.scan { done, total ->
                    if (done % 25 == 0) runOnUiThread {
                        ui.subtitle.text = getString(R.string.setup_progress, done, total)
                    }
                }
            }
            found.clear(); found.addAll(results); adapter.notifyDataSetChanged()
            ui.btnScan.isEnabled = true
            ui.subtitle.text = if (results.isEmpty())
                getString(R.string.setup_none)
            else
                getString(R.string.setup_found, results.size)
            if (results.isNotEmpty()) {
                ui.foundList.post {
                    ui.foundList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun connectManual() {
        var text = ui.hostInput.text.toString().trim()
        if (text.isEmpty()) {
            ui.subtitle.text = getString(R.string.setup_enter_address)
            return
        }
        text = text.removePrefix("http://").removePrefix("https://").trimEnd('/')
        // A bare IP is the common case; assume ErsatzTV's default port.
        if (!text.contains(':')) text = "$text:${Discovery.DEFAULT_PORT}"
        ui.subtitle.text = getString(R.string.setup_checking, text)
        val target = text
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Discovery.verify(target) }
            if (ok != null) accept(target)
            else ui.subtitle.text = getString(R.string.setup_no_server, target)
        }
    }

    private fun accept(hostPort: String) {
        Prefs.setHost(this, hostPort)
        setResult(Activity.RESULT_OK)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    inner class FoundAdapter : RecyclerView.Adapter<FoundAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val line: TextView = v.findViewById(R.id.foundLine)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.row_found, parent, false))

        override fun getItemCount() = found.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = found[position]
            h.line.text = "${f.host}   ${f.version}"
            h.itemView.setOnClickListener { accept(f.host) }
            h.itemView.setOnFocusChangeListener { v, has -> v.alpha = if (has) 1f else 0.75f }
        }
    }
}
