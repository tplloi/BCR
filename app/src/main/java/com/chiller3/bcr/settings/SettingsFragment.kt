package com.chiller3.bcr.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.chiller3.bcr.BuildConfig
import com.chiller3.bcr.DirectBootMigrationService
import com.chiller3.bcr.Permissions
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.R
import com.chiller3.bcr.dialog.MinDurationDialogFragment
import com.chiller3.bcr.extension.formattedString
import com.chiller3.bcr.format.Format
import com.chiller3.bcr.format.NoParamInfo
import com.chiller3.bcr.format.RangedParamInfo
import com.chiller3.bcr.output.Retention
import com.chiller3.bcr.rule.RecordRulesActivity
import com.chiller3.bcr.view.LongClickablePreference
import com.chiller3.bcr.view.OnPreferenceLongClickListener
import com.google.android.material.snackbar.Snackbar

class SettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener, OnPreferenceLongClickListener,
    SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: Preferences
    private lateinit var categoryDebug: PreferenceCategory
    private lateinit var prefCallRecording: SwitchPreferenceCompat
    private lateinit var prefRecordRules: Preference
    private lateinit var prefOutputDir: LongClickablePreference
    private lateinit var prefOutputFormat: Preference
    private lateinit var prefMinDuration: Preference
    private lateinit var prefInhibitBatteryOpt: SwitchPreferenceCompat
    private lateinit var prefVersion: LongClickablePreference
    private lateinit var prefMigrateDirectBoot: Preference

    private val requestPermissionRequired =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Call recording can still be enabled if optional permissions were not granted
            if (granted.all { it.key !in Permissions.REQUIRED || it.value }) {
                prefCallRecording.isChecked = true
            } else {
                startActivity(Permissions.getAppInfoIntent(requireContext()))
            }
        }
    private val requestInhibitBatteryOpt =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshInhibitBatteryOptState()
        }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val view = super.onCreateRecyclerView(inflater, parent, savedInstanceState)

        view.clipToPadding = false

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )

            // This is a little bit ugly in landscape mode because the divider lines for categories
            // extend into the inset area. However, it's worth applying the left/right padding here
            // anyway because it allows the inset area to be used for scrolling instead of just
            // being a useless dead zone.
            v.updatePadding(
                bottom = insets.bottom,
                left = insets.left,
                right = insets.right,
            )

            WindowInsetsCompat.CONSUMED
        }

        return view
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()

        preferenceManager.setStorageDeviceProtected()
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        prefs = Preferences(context)

        categoryDebug = findPreference(Preferences.CATEGORY_DEBUG)!!

        // If the desired state is enabled, set to disabled if runtime permissions have been
        // denied. The user will have to grant permissions again to re-enable the features.

        prefCallRecording = findPreference(Preferences.PREF_CALL_RECORDING)!!
        if (prefCallRecording.isChecked && !Permissions.haveRequired(context)) {
            prefCallRecording.isChecked = false
        }
        prefCallRecording.onPreferenceChangeListener = this

        prefRecordRules = findPreference(Preferences.PREF_RECORD_RULES)!!
        prefRecordRules.onPreferenceClickListener = this

        prefOutputDir = findPreference(Preferences.PREF_OUTPUT_DIR)!!
        prefOutputDir.onPreferenceClickListener = this
        prefOutputDir.onPreferenceLongClickListener = this
        refreshOutputDir()

        prefOutputFormat = findPreference(Preferences.PREF_OUTPUT_FORMAT)!!
        prefOutputFormat.onPreferenceClickListener = this
        refreshOutputFormat()

        prefMinDuration = findPreference(Preferences.PREF_MIN_DURATION)!!
        prefMinDuration.onPreferenceClickListener = this
        refreshMinDuration()

        prefInhibitBatteryOpt = findPreference(Preferences.PREF_INHIBIT_BATT_OPT)!!
        prefInhibitBatteryOpt.onPreferenceChangeListener = this

        prefVersion = findPreference(Preferences.PREF_VERSION)!!
        prefVersion.onPreferenceClickListener = this
        prefVersion.onPreferenceLongClickListener = this
        refreshVersion()

        prefMigrateDirectBoot = findPreference(Preferences.PREF_MIGRATE_DIRECT_BOOT)!!
        prefMigrateDirectBoot.onPreferenceClickListener = this

        refreshDebugPrefs()
    }

    override fun onStart() {
        super.onStart()

        preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)

        // Changing the battery state does not cause a reload of the activity
        refreshInhibitBatteryOptState()
    }

    override fun onStop() {
        super.onStop()

        preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun refreshOutputDir() {
        val context = requireContext()
        val outputDirUri = prefs.outputDirOrDefault
        val outputRetention = Retention.fromPreferences(prefs).toFormattedString(context)

        val summary = getString(R.string.pref_output_dir_desc)
        prefOutputDir.summary = "${summary}\n\n${outputDirUri.formattedString} (${outputRetention})"
    }

    private fun refreshOutputFormat() {
        val (format, formatParamSaved, sampleRateSaved) = Format.fromPreferences(prefs)
        val formatParam = formatParamSaved ?: format.paramInfo.default
        val sampleRate = sampleRateSaved ?: format.sampleRateInfo.default

        val summary = getString(R.string.pref_output_format_desc)
        val prefix = when (val info = format.paramInfo) {
            is RangedParamInfo -> "${info.format(requireContext(), formatParam)}, "
            NoParamInfo -> ""
        }
        val sampleRateText = format.sampleRateInfo.format(requireContext(), sampleRate)

        prefOutputFormat.summary = "${summary}\n\n${format.name} (${prefix}${sampleRateText})"
    }

    private fun refreshMinDuration() {
        val minDuration = prefs.minDuration

        prefMinDuration.summary = if (minDuration == 0) {
            getString(R.string.pref_min_duration_desc_zero)
        } else {
            resources.getQuantityString(R.plurals.pref_min_duration_desc, minDuration, minDuration)
        }
    }

    private fun refreshVersion() {
        val suffix = if (prefs.isDebugMode) {
            "+debugmode"
        } else {
            ""
        }
        prefVersion.summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}${suffix})"
    }

    private fun refreshDebugPrefs() {
        categoryDebug.isVisible = prefs.isDebugMode
    }

    private fun refreshInhibitBatteryOptState() {
        val inhibiting = Permissions.isInhibitingBatteryOpt(requireContext())
        prefInhibitBatteryOpt.isChecked = inhibiting
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val context = requireContext()

        when (preference) {
            prefCallRecording -> if (newValue == false || Permissions.haveRequired(context)) {
                return true
            } else {
                // Ask for optional permissions the first time only
                requestPermissionRequired.launch(Permissions.REQUIRED + Permissions.OPTIONAL)
            }
            prefInhibitBatteryOpt -> {
                if (newValue == true) {
                    requestInhibitBatteryOpt.launch(
                        Permissions.getInhibitBatteryOptIntent(requireContext()))
                } else {
                    startActivity(Permissions.getBatteryOptSettingsIntent())
                }
            }
        }

        return false
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when (preference) {
            prefRecordRules -> {
                startActivity(Intent(requireContext(), RecordRulesActivity::class.java))
                return true
            }
            prefOutputDir -> {
                OutputDirectoryBottomSheetFragment().show(
                    childFragmentManager, OutputDirectoryBottomSheetFragment.TAG
                )
                return true
            }
            prefOutputFormat -> {
                OutputFormatBottomSheetFragment().show(
                    childFragmentManager, OutputFormatBottomSheetFragment.TAG
                )
                return true
            }
            prefMinDuration -> {
                MinDurationDialogFragment().show(
                    parentFragmentManager.beginTransaction(), MinDurationDialogFragment.TAG)
                return true
            }
            prefVersion -> {
                val uri = Uri.parse(BuildConfig.PROJECT_URL_AT_COMMIT)
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            prefMigrateDirectBoot -> {
                val context = requireContext()
                context.startService(Intent(context, DirectBootMigrationService::class.java))
                return true
            }
        }

        return false
    }

    override fun onPreferenceLongClick(preference: Preference): Boolean {
        when (preference) {
            prefOutputDir -> {
                try {
                    startActivity(prefs.outputDirOrDefaultIntent)
                } catch (e: ActivityNotFoundException) {
                    Snackbar.make(
                        requireView(),
                        R.string.documentsui_not_found,
                        Snackbar.LENGTH_LONG,
                    ).show()
                }
                return true
            }
            prefVersion -> {
                prefs.isDebugMode = !prefs.isDebugMode
                refreshVersion()
                refreshDebugPrefs()
                return true
            }
        }

        return false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when {
            key == null -> return
            // Update the switch state if it was toggled outside of the preference (eg. from the
            // quick settings toggle).
            key == prefCallRecording.key -> {
                val current = prefCallRecording.isChecked
                val expected = sharedPreferences.getBoolean(key, current)
                if (current != expected) {
                    prefCallRecording.isChecked = expected
                }
            }
            // Update the output directory state when it's changed by the bottom sheet.
            key == Preferences.PREF_OUTPUT_DIR || key == Preferences.PREF_OUTPUT_RETENTION -> {
                refreshOutputDir()
            }
            // Update the output format state when it's changed by the bottom sheet.
            Preferences.isFormatKey(key) || key == Preferences.PREF_SAMPLE_RATE -> {
                refreshOutputFormat()
            }
            // Update when it's changed by the dialog.
            key == Preferences.PREF_MIN_DURATION -> {
                refreshMinDuration()
            }
        }
    }
}
