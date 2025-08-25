package com.example.dpfmonitor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Ustawienia"
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<ListPreference>("refresh_sec")?.summaryProvider =
                ListPreference.SimpleSummaryProvider.getInstance()

            arrayOf("pid_soot","pid_load","pid_temp","pid_regen","device_mac").forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.hint = when (key) {
                        "device_mac" -> "np. 00:11:22:33:AA:BB"
                        else -> "np. 221330"
                    }
                }
            }

            findPreference<SwitchPreferenceCompat>("voice_alerts")?.let {
                // summary dynamiczny
                it.summary = if (it.isChecked) "Powiadomienia głosowe włączone" else "Powiadomienia głosowe wyłączone"
                it.setOnPreferenceChangeListener { pref, newValue ->
                    pref.summary = if (newValue as Boolean) "Powiadomienia głosowe włączone" else "Powiadomienia głosowe wyłączone"
                    true
                }
            }
        }
    }
}
