package com.example.dpfmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Menu
import android.view.MenuItem
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import java.util.*
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.util.Log

class MainActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null
    private lateinit var debugOverlay: TextView
    private fun playAlertSound(resId: Int) {
        player?.release()
        player = MediaPlayer.create(this, resId)
        player?.start()
    }
    private lateinit var sootValue: TextView
    private lateinit var loadValue: TextView
    private lateinit var tempValue: TextView
    private lateinit var regenValue: TextView

    private lateinit var iconObd: ImageView
    private lateinit var connectionStatus: TextView

    private var refreshItem: MenuItem? = null
    private lateinit var tts: TextToSpeech

    private val blinkAnim by lazy {
        AlphaAnimation(0f, 1f).apply {
            duration = 500
            repeatMode = AlphaAnimation.REVERSE
            repeatCount = AlphaAnimation.INFINITE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        debugOverlay = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#AA000000")) // półprzezroczyste tło
            setTextColor(Color.GREEN)
            textSize = 18f
            setPadding(20, 20, 20, 20)
            visibility = TextView.GONE
        }

// dodaj overlay na główny layout
        addContentView(
            debugOverlay,
            Toolbar.LayoutParams(
                Toolbar.LayoutParams.MATCH_PARENT,
                Toolbar.LayoutParams.WRAP_CONTENT
            )
        )
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        sootValue = findViewById(R.id.sootValue)
        loadValue = findViewById(R.id.loadValue)
        tempValue = findViewById(R.id.tempValue)
        regenValue = findViewById(R.id.regenValue)

        iconObd = findViewById(R.id.iconObd)
        connectionStatus = findViewById(R.id.connectionStatus)

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale.getDefault() }
        checkAndRequestBluetoothPermissions()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, IntentFilter("DPF_DATA"), RECEIVER_NOT_EXPORTED)
            registerReceiver(alertReceiver, IntentFilter("DPF_ALERT"), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(dataReceiver, IntentFilter("DPF_DATA"))
            @Suppress("DEPRECATION")
            registerReceiver(alertReceiver, IntentFilter("DPF_ALERT"))
        }

        // Start serwisu (uruchomi tryb OBD albo symulację)
        startService(Intent(this, DPFService::class.java))
    }
    private fun checkAndRequestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = mutableListOf<String>()

            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (permissions.isNotEmpty()) {
                requestPermissions(permissions.toTypedArray(), 101)
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(dataReceiver)
        unregisterReceiver(alertReceiver)
        tts.shutdown()
        player?.release()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        refreshItem = menu?.findItem(R.id.action_refresh)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                startRefreshAnimation()
                sendBroadcast(Intent("REFRESH_DPF"))
                window.decorView.postDelayed({ stopRefreshAnimation() }, 1600)
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startRefreshAnimation() {
        refreshItem?.let { mi ->
            val v = layoutInflater.inflate(R.layout.refresh_action_view, null)
            mi.actionView = v
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate))
        }
    }

    private fun stopRefreshAnimation() {
        refreshItem?.actionView?.clearAnimation()
        refreshItem?.actionView = null
    }

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val soot = intent?.getStringExtra("SOOT") ?: "---"
            val load = intent?.getStringExtra("LOAD") ?: "---"
            val temp = intent?.getStringExtra("TEMP") ?: "---"
            val regen = intent?.getBooleanExtra("REGEN", false) ?: false
            val connected = intent?.getBooleanExtra("CONNECTED", false) ?: false
            Log.d("MainActivity", "Otrzymano: soot=$soot, load=$load, temp=$temp, regen=$regen, connected=$connected")

            sootValue.text = soot
            loadValue.text = load
            tempValue.text = temp

            if (regen) {
                if (regenValue.text != "Wypalanie") {
                    // start wypalania – zagraj dźwięk
                    playAlertSound(R.raw.start_regen)
                }
                regenValue.text = "Wypalanie"
                regenValue.setTextColor(Color.RED)
                regenValue.startAnimation(blinkAnim)
            } else {
                if (regenValue.text == "Wypalanie") {
                    // koniec wypalania – zagraj dźwięk
                    playAlertSound(R.raw.stop_regen)
                }
                regenValue.text = "Nie"
                regenValue.setTextColor(Color.parseColor("#76FF03"))
                regenValue.clearAnimation()
            }

            if (connected) {
                iconObd.setImageResource(R.drawable.ic_obd_on)
                connectionStatus.text = "Połączono"
                Log.d("MainActivity", "Otrzymano: soot=$soot, load=$load, temp=$temp, regen=$regen, connected=$connected")
            } else {
                iconObd.setImageResource(R.drawable.ic_obd_off)
                connectionStatus.text = "Brak połączenia"
                Log.d("MainActivity", "Otrzymano: soot=$soot, load=$load, temp=$temp, regen=$regen, connected=$connected")
            }
        }
    }

    private val alertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("MSG") ?: return

            debugOverlay.text = msg
            debugOverlay.visibility = TextView.VISIBLE

            // schowaj po 5 sek.
            debugOverlay.removeCallbacks(null)
            debugOverlay.postDelayed({
                debugOverlay.visibility = TextView.GONE
            }, 5000)
        }
    }
}
