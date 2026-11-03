package com.example.speakersync

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var box: LinearLayout
    private lateinit var echoSw: Switch
    private var found = listOf<AudioDeviceInfo>()
    private val roleSp = mutableListOf<Spinner>()
    private val delaySb = mutableListOf<SeekBar>()
    private val roles = arrayOf("Main (full stereo)", "Left", "Right", "Subwoofer")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        status = TextView(this).apply { text = "Connect your Bluetooth speakers, then tap Scan."; textSize = 16f }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        echoSw = Switch(this).apply { text = "Echo mode"; setPadding(0, 24, 0, 24) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        root.addView(status)
        root.addView(btn("SCAN SPEAKERS") { scan() })
        root.addView(box); root.addView(echoSw)
        root.addView(btn("PICK SONG & PLAY ON ALL SPEAKERS") { pick() })
        root.addView(btn("MIRROR OTHER APPS TO SPEAKER 2") { mirror() })
        root.addView(btn("STOP") { stopService(Intent(this, AudioService::class.java)); status.text = "Stopped." })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun ensurePerms(): Boolean {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        val m = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (m.isNotEmpty()) { requestPermissions(m.toTypedArray(), 1); return false }
        return true
    }

    private fun scan() {
        if (!ensurePerms()) { status.text = "Allow permissions, then tap Scan again."; return }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        found = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }.take(4)
        box.removeAllViews(); roleSp.clear(); delaySb.clear()
        found.forEachIndexed { i, d ->
            box.addView(TextView(this).apply { text = "Speaker ${i + 1}: ${d.productName}"; textSize = 16f; setPadding(0, 24, 0, 0) })
            val sp = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, roles)
                setSelection(if (found.size == 2) i + 1 else 0)
            }
            val lab = TextView(this).apply { text = "Delay: 0 ms" }
            val sb = SeekBar(this).apply {
                max = 500
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { lab.text = "Delay: $p ms" }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            box.addView(sp); box.addView(lab); box.addView(sb); roleSp += sp; delaySb += sb
        }
        status.text = if (found.size < 2) "Found ${found.size} speaker(s). Connect at least 2." else "Found ${found.size} speakers. Set roles/delay, then play."
    }

    private fun putCfg(i: Intent, idx: List<Int>): Intent = i
        .putExtra("ids", idx.map { found[it].id }.toIntArray())
        .putExtra("roles", idx.map { roleSp[it].selectedItemPosition }.toIntArray())
        .putExtra("delays", idx.map { delaySb[it].progress }.toIntArray())
        .putExtra("echo", echoSw.isChecked)

    private fun pick() {
        if (found.size < 2) { status.text = "Scan first (need 2 speakers)."; return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 3)
    }

    private fun mirror() {
        if (found.size < 2) { status.text = "Scan first (need 2 speakers)."; return }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 2)
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        if (res != RESULT_OK || d == null) { status.text = "Cancelled."; return }
        if (rc == 3) {
            val uri = d.data!!
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            startForegroundService(putCfg(Intent(this, AudioService::class.java), found.indices.toList())
                .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            status.text = "Playing on ${found.size} speakers."
        } else if (rc == 2) {
            startForegroundService(putCfg(Intent(this, AudioService::class.java), listOf(1))
                .putExtra("mirror", true).putExtra("code", res).putExtra("proj", d))
            status.text = "Mirroring to speaker 2. Play anything now."
        }
    }
}
