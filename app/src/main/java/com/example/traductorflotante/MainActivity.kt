package com.example.traductorflotante

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val languages = linkedMapOf(
        "English" to "en", "Español" to "es", "Français" to "fr",
        "Português" to "pt", "Italiano" to "it", "Deutsch" to "de"
    )
    private lateinit var spinner: Spinner

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                val target = languages.values.toList()[spinner.selectedItemPosition]
                getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("target", target).apply()
                val i = Intent(this, OverlayService::class.java)
                    .putExtra("code", res.resultCode)
                    .putExtra("data", data)
                ContextCompat.startForegroundService(this, i)
                finish()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Traductor flotante"; textSize = 26f })
        root.addView(TextView(this).apply { text = "Traducir a:"; setPadding(0, pad, 0, 0) })
        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                languages.keys.toList()
            )
        }
        val saved = getSharedPreferences("prefs", MODE_PRIVATE).getString("target", "en")
        spinner.setSelection(languages.values.indexOf(saved).coerceAtLeast(0))
        root.addView(spinner)
        root.addView(Button(this).apply {
            text = "Iniciar burbuja"
            setOnClickListener { start() }
        })
        root.addView(TextView(this).apply {
            text = "Toca la burbuja para traducir la pantalla. Toca el resultado para cerrarlo. " +
                "Mantén presionada la burbuja para apagar todo."
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this, "Activa «Mostrar sobre otras apps» y vuelve a tocar Iniciar",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        askProjection()
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
