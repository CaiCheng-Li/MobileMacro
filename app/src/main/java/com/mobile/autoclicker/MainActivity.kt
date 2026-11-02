package com.mobile.autoclicker

import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import com.google.android.material.R as MR

class MainActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private lateinit var macros: Snapshots
    private lateinit var profiles: Snapshots
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var primaryAction: MaterialButton

    // Re-fill each settings control from storage, used after a macro or profile is loaded.
    private val refreshers = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        macros = Snapshots.macros(this)
        profiles = Snapshots.profiles(this)

        statusCard = findViewById(R.id.statusCard)
        statusIcon = findViewById(R.id.statusIcon)
        statusTitle = findViewById(R.id.statusTitle)
        statusText = findViewById(R.id.statusText)
        primaryAction = findViewById(R.id.primaryAction)

        // Two columns on wide screens such as tablets in landscape.
        if (resources.configuration.screenWidthDp >= 840) {
            findViewById<LinearLayout>(R.id.columns).orientation = LinearLayout.HORIZONTAL
            findViewById<View>(R.id.columnLeft).layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            findViewById<View>(R.id.columnRight).layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (24 * resources.displayMetrics.density).toInt() }
        }

        val scroll = findViewById<NestedScrollView>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }

        statusCard.setOnClickListener { startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS)) }
        primaryAction.setOnClickListener {
            val service = ClickService.instance
            if (service == null) {
                startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                service.showPanel()
                moveTaskToBack(true)
            }
        }

        bindNumber(R.id.intervalLayout, R.id.interval, { settings.intervalMs }, { if (it < Settings.MIN_DELAY_MS) getString(R.string.error_interval) else null }) { settings.intervalMs = it }
        bindNumber(R.id.randomIntervalLayout, R.id.randomInterval, { settings.randomIntervalMs }, null) { settings.randomIntervalMs = it }
        bindNumber(R.id.holdLayout, R.id.hold, { settings.holdMs }, { if (it !in 1..Settings.MAX_GESTURE_MS) getString(R.string.error_duration) else null }) { settings.holdMs = it }
        bindNumber(R.id.swipeLayout, R.id.swipe, { settings.swipeMs }, { if (it !in 1..Settings.MAX_GESTURE_MS) getString(R.string.error_duration) else null }) { settings.swipeMs = it }
        bindNumber(R.id.startDelayLayout, R.id.startDelay, { settings.startDelaySec.toLong() }, { if (it > Settings.MAX_START_DELAY_SEC) getString(R.string.error_start_delay) else null }) { settings.startDelaySec = it.toInt() }
        bindNumber(R.id.timeLimitLayout, R.id.timeLimit, { settings.timeLimitSec }, null) { settings.timeLimitSec = it }
        bindNumber(R.id.repeatLayout, R.id.repeat, { settings.repeat.toLong() }, null) { settings.repeat = it.toInt() }

        val randomValue = findViewById<TextView>(R.id.randomValue)
        val random = findViewById<Slider>(R.id.random)
        random.setLabelFormatter { getString(R.string.unit_px, it.toInt()) }
        random.addOnChangeListener { _, value, fromUser ->
            randomValue.text = getString(R.string.unit_px, value.toInt())
            if (fromUser) settings.randomPx = value.toInt()
        }
        val refreshRandom = {
            random.value = settings.randomPx.toFloat()
            randomValue.text = getString(R.string.unit_px, settings.randomPx)
        }
        refreshRandom()
        refreshers.add(refreshRandom)

        // Holding the joystick finger needs Android 8.0.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            bindSlider(R.id.joystickSize, R.id.joystickSizeValue, R.string.unit_dp, { settings.joystickSizeDp }) {
                settings.joystickSizeDp = it
                ClickService.instance?.refreshJoystickSize()
            }
            bindSlider(R.id.joystickReach, R.id.joystickReachValue, R.string.unit_percent, { settings.joystickReach }) { settings.joystickReach = it }
        } else {
            findViewById<View>(R.id.joystickSettings).isVisible = false
            findViewById<View>(R.id.joystickUnsupported).isVisible = true
        }

        // The mouse camera needs Android 14's mouse events for accessibility services.
        val mouseLook = findViewById<MaterialSwitch>(R.id.mouseLook)
        if (Build.VERSION.SDK_INT >= 34) {
            val refreshMouseLook = { mouseLook.isChecked = settings.mouseLook }
            refreshMouseLook()
            refreshers.add(refreshMouseLook)
            mouseLook.setOnCheckedChangeListener { _, checked ->
                settings.mouseLook = checked
                ClickService.instance?.refreshMouseLook()
            }
            bindSlider(R.id.mouseSensitivity, R.id.mouseSensitivityValue, 0, { settings.mouseSensitivity }) { settings.mouseSensitivity = it }
        } else {
            mouseLook.isEnabled = false
            findViewById<TextView>(R.id.mouseLookHelper).setText(R.string.mouse_look_unsupported)
            findViewById<View>(R.id.mouseSensitivityRow).isVisible = false
            findViewById<View>(R.id.mouseSensitivity).isVisible = false
        }

        findViewById<MaterialButton>(R.id.saveProfile).setOnClickListener { showSaveProfileDialog() }
        renderProfiles()

        findViewById<MaterialButton>(R.id.saveMacro).setOnClickListener { showSaveDialog() }
        renderMacros()

        val steps = findViewById<LinearLayout>(R.id.steps)
        resources.getStringArray(R.array.steps).forEachIndexed { i, text ->
            val row = layoutInflater.inflate(R.layout.item_step, steps, false)
            row.findViewById<TextView>(R.id.stepNumber).text = (i + 1).toString()
            row.findViewById<TextView>(R.id.stepText).text = text
            steps.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        ClickService.stateListener = { updateStatus() }
        updateStatus()
    }

    override fun onPause() {
        ClickService.stateListener = null
        super.onPause()
    }

    // Fills a number field and saves it whenever the text is a valid value.
    private fun bindNumber(layoutId: Int, fieldId: Int, get: () -> Long, check: ((Long) -> String?)?, save: (Long) -> Unit) {
        val layout = findViewById<TextInputLayout>(layoutId)
        val field = findViewById<TextInputEditText>(fieldId)
        val refresh = { field.setText(get().toString()) }
        refresh()
        refreshers.add(refresh)
        field.doAfterTextChanged {
            val value = it?.toString()?.toLongOrNull()
            val error = if (value == null) getString(R.string.error_number) else check?.invoke(value)
            layout.error = error
            if (value != null && error == null) save(value)
        }
    }

    // A slider with its value shown beside its title. format 0 shows the plain number.
    private fun bindSlider(sliderId: Int, valueId: Int, format: Int, get: () -> Int, save: (Int) -> Unit) {
        val slider = findViewById<Slider>(sliderId)
        val value = findViewById<TextView>(valueId)
        val text = { v: Int -> if (format != 0) getString(format, v) else v.toString() }
        slider.setLabelFormatter { text(it.toInt()) }
        slider.addOnChangeListener { _, v, fromUser ->
            value.text = text(v.toInt())
            if (fromUser) save(v.toInt())
        }
        val refresh = {
            slider.value = get().toFloat()
            value.text = text(get())
        }
        refresh()
        refreshers.add(refresh)
    }

    private fun updateStatus() {
        val on = ClickService.instance != null
        val background = MaterialColors.getColor(statusCard, if (on) MR.attr.colorPrimaryContainer else MR.attr.colorSurfaceContainerHighest)
        val foreground = MaterialColors.getColor(statusCard, if (on) MR.attr.colorOnPrimaryContainer else MR.attr.colorOnSurface)
        val iconColor = if (on) foreground else MaterialColors.getColor(statusCard, MR.attr.colorError)
        statusCard.setCardBackgroundColor(background)
        statusTitle.setTextColor(foreground)
        statusText.setTextColor(foreground)
        statusIcon.imageTintList = ColorStateList.valueOf(iconColor)
        statusIcon.setImageResource(if (on) R.drawable.ic_check_circle else R.drawable.ic_warning)
        statusTitle.setText(if (on) R.string.status_on_title else R.string.status_off_title)
        statusText.setText(if (on) R.string.status_on_text else R.string.status_off_text)
        primaryAction.setText(if (on) R.string.action_show_panel else R.string.action_enable)
        primaryAction.setIconResource(if (on) R.drawable.ic_touch_app else R.drawable.ic_open_in_new)
    }

    // ---------- Macros ----------

    private fun renderMacros() {
        val list = findViewById<LinearLayout>(R.id.macroList)
        list.removeAllViews()
        val names = macros.names()
        findViewById<View>(R.id.macroEmpty).isVisible = names.isEmpty()
        names.forEach { name ->
            val row = layoutInflater.inflate(R.layout.item_macro, list, false)
            row.findViewById<TextView>(R.id.macroName).text = name
            row.findViewById<TextView>(R.id.macroSummary).text = macros.get(name)?.let { summary(it) } ?: ""
            row.findViewById<View>(R.id.macroLoad).setOnClickListener { loadMacro(name) }
            row.findViewById<View>(R.id.macroDelete).setOnClickListener { confirmDelete(name) }
            list.addView(row)
        }
    }

    private fun summary(macro: JSONObject): String {
        val count = macro.optJSONArray("actions")?.length() ?: 0
        return resources.getQuantityString(R.plurals.macro_summary, count, count, macro.optLong("interval_ms", settings.intervalMs))
    }

    private fun showSaveDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_save_macro, null)
        val layout = view.findViewById<TextInputLayout>(R.id.macroNameLayout)
        val field = view.findViewById<TextInputEditText>(R.id.macroNameField)
        val existing = macros.names()
        var n = existing.size + 1
        while (getString(R.string.macro_default_name, n) in existing) n++
        field.setText(getString(R.string.macro_default_name, n))
        field.selectAll()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.macro_save_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = field.text.toString().trim()
            if (name.isEmpty()) {
                layout.error = getString(R.string.error_name)
                return@setOnClickListener
            }
            macros.put(name, settings.toJson())
            renderMacros()
            dialog.dismiss()
            showMessage(getString(R.string.macro_saved, name))
        }
    }

    private fun loadMacro(name: String) {
        val macro = macros.get(name) ?: return
        settings.load(macro)
        refreshers.forEach { it() }
        ClickService.instance?.reloadActions()
        showMessage(getString(R.string.macro_loaded, name))
    }

    private fun confirmDelete(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.macro_delete_title, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                macros.delete(name)
                renderMacros()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Keyboard profiles ----------

    private fun renderProfiles() {
        val list = findViewById<LinearLayout>(R.id.profileList)
        list.removeAllViews()
        val names = profiles.names()
        findViewById<View>(R.id.profileEmpty).isVisible = names.isEmpty()
        names.forEach { name ->
            val row = layoutInflater.inflate(R.layout.item_macro, list, false)
            row.findViewById<TextView>(R.id.macroName).text = name
            row.findViewById<TextView>(R.id.macroSummary).text = profiles.get(name)?.let { profileSummary(it) } ?: ""
            row.findViewById<View>(R.id.macroLoad).setOnClickListener { loadProfile(name) }
            row.findViewById<View>(R.id.macroEdit).apply {
                isVisible = true
                setOnClickListener { showEditProfileDialog(name) }
            }
            row.findViewById<View>(R.id.macroDelete).setOnClickListener { confirmDeleteProfile(name) }
            list.addView(row)
        }
    }

    // For example "3 key points · joystick · mouse camera".
    private fun profileSummary(profile: JSONObject): String {
        val keys = profile.optJSONArray("key_points")?.length() ?: 0
        val parts = mutableListOf(resources.getQuantityString(R.plurals.profile_keys, keys, keys))
        if (profile.optBoolean("joystick_on")) parts.add(getString(R.string.profile_joystick))
        if (profile.optBoolean("mouse_look") && Build.VERSION.SDK_INT >= 34) parts.add(getString(R.string.profile_mouse))
        return parts.joinToString(getString(R.string.profile_separator))
    }

    // The panel keeps marker positions in memory while they are dragged; store them first.
    private fun currentLayout(): JSONObject {
        ClickService.instance?.saveLayout()
        return settings.layoutToJson()
    }

    private fun showSaveProfileDialog() {
        val existing = profiles.names()
        var n = existing.size + 1
        while (getString(R.string.profile_default_name, n) in existing) n++
        nameDialog(R.string.profile_save_title, getString(R.string.profile_default_name, n), showReplace = false) { name, _, dialog ->
            if (name in existing) {
                // Saving over another profile asks first.
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.profile_overwrite_title, name))
                    .setMessage(R.string.profile_overwrite_text)
                    .setPositiveButton(R.string.replace) { _, _ ->
                        saveProfile(name)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                saveProfile(name)
                dialog.dismiss()
            }
        }
    }

    private fun saveProfile(name: String) {
        profiles.put(name, currentLayout())
        renderProfiles()
        showMessage(getString(R.string.profile_saved, name))
    }

    // Rename the profile, and optionally replace what it holds with the current layout.
    private fun showEditProfileDialog(name: String) {
        nameDialog(R.string.profile_edit_title, name, showReplace = true) { newName, replace, dialog ->
            if (newName != name && newName in profiles.names()) {
                dialog.findViewById<TextInputLayout>(R.id.macroNameLayout)?.error = getString(R.string.error_name_taken)
                return@nameDialog
            }
            if (newName != name) profiles.rename(name, newName)
            if (replace) profiles.put(newName, currentLayout())
            renderProfiles()
            dialog.dismiss()
            showMessage(getString(R.string.profile_updated, newName))
        }
    }

    private fun loadProfile(name: String) {
        val profile = profiles.get(name) ?: return
        settings.loadLayout(profile)
        refreshers.forEach { it() }
        ClickService.instance?.reloadLayout()
        showMessage(getString(R.string.profile_loaded, name))
    }

    private fun confirmDeleteProfile(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.profile_delete_title, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                profiles.delete(name)
                renderProfiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // A dialog with a name field and, for editing, the replace checkbox. onSave gets the trimmed
    // name, whether replace is checked and the dialog, and dismisses it when done.
    private fun nameDialog(title: Int, initial: String, showReplace: Boolean, onSave: (String, Boolean, AlertDialog) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_save_macro, null)
        val layout = view.findViewById<TextInputLayout>(R.id.macroNameLayout)
        val field = view.findViewById<TextInputEditText>(R.id.macroNameField)
        val replace = view.findViewById<MaterialCheckBox>(R.id.replaceLayout)
        replace.isVisible = showReplace
        field.setText(initial)
        field.selectAll()
        field.doAfterTextChanged { layout.error = null }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = field.text.toString().trim()
            if (name.isEmpty()) {
                layout.error = getString(R.string.error_name)
                return@setOnClickListener
            }
            onSave(name, replace.isChecked, dialog)
        }
    }

    private fun showMessage(text: String) {
        Snackbar.make(findViewById(R.id.root), text, Snackbar.LENGTH_SHORT).show()
    }
}
