package com.hikari.app.dub

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.hikari.app.data.Translator
import com.hikari.app.i18n.I18n

/** Remembers the last dub choices. */
object DubPrefs {
    private fun p(c: Context) = c.getSharedPreferences("dub_prefs", Context.MODE_PRIVATE)
    fun lang(c: Context): String = p(c).getString("lang", "en") ?: "en"
    fun mode(c: Context): VoiceMode =
        runCatching { VoiceMode.valueOf(p(c).getString("mode", "AUTO")!!) }.getOrDefault(VoiceMode.AUTO)
    fun duck(c: Context): Float = p(c).getFloat("duck", 0.15f)
    /** Own voice for every character + edited audio (on by default). */
    fun smart(c: Context): Boolean = p(c).getBoolean("smart", true)
    fun saveSmart(c: Context, v: Boolean) { p(c).edit().putBoolean("smart", v).apply() }
    fun save(c: Context, lang: String, mode: VoiceMode, duck: Float) {
        p(c).edit().putString("lang", lang).putString("mode", mode.name).putFloat("duck", duck).apply()
    }
}

/**
 * The "Dub audio" panel: pick which subtitle to read, which language to speak,
 * and which voice. [candidates] are (label, raw subtitle text) pairs of the
 * subtitles already loaded in the player; [onUpload] opens the file picker so
 * the person can add their own subtitle file.
 */
object DubDialog {

    fun show(
        activity: Activity,
        engine: DubEngine,
        candidates: List<Pair<String, String>>,
        onUpload: () -> Unit,
        onStart: (cues: List<DubCue>, lang: String, mode: VoiceMode, duck: Float) -> Unit,
    ) {
        val d = activity.resources.displayMetrics.density
        fun pad(v: Int) = (v * d).toInt()

        fun label(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 12f
            setPadding(0, pad(12), 0, pad(2))
        }
        fun spinner(items: List<String>, selected: Int) = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(8), pad(20), 0)
        }

        // 1) which subtitle to read
        val subSpinner: Spinner?
        if (candidates.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = I18n.t("No subtitle is loaded yet. Upload a subtitle file (.srt / .vtt) and it will be read out in the language you choose.")
                textSize = 13f
                setPadding(0, pad(8), 0, pad(8))
            })
            subSpinner = null
        } else {
            root.addView(label(I18n.t("Read this subtitle")))
            subSpinner = spinner(candidates.map { it.first }, 0)
            root.addView(subSpinner)
        }

        // Which characters the chosen subtitle names (and the voice type each gets).
        val castInfo = TextView(activity).apply {
            textSize = 12f
            setPadding(0, pad(6), 0, 0)
        }
        fun refreshCast(index: Int) {
            val text = runCatching {
                val cues = SubtitleCues.parse(candidates[index].second)
                val desc = CastDirector.assign(cues).describe()
                if (desc.isBlank()) {
                    I18n.t("No character names found in this subtitle. Lines use the main voice; two-person exchanges (- Hi / - Hello) get a man and a woman voice.")
                } else {
                    I18n.t("Characters found") + ": " + desc
                }
            }.getOrDefault("")
            castInfo.text = text
        }
        if (subSpinner != null) {
            root.addView(castInfo)
            refreshCast(0)
            subSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    refreshCast(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        // 2) language
        val langs = Translator.LANGUAGES
        root.addView(label(I18n.t("Dub language")))
        val langSpinner = spinner(
            langs.map { it.second },
            langs.indexOfFirst { it.first == DubPrefs.lang(activity) }.coerceAtLeast(0),
        )
        root.addView(langSpinner)

        // 3) voice
        val voiceNames = listOf(
            I18n.t("Automatic (Google, then phone voice)"),
            I18n.t("Google Translate voice (online)"),
            I18n.t("Phone voice (offline)"),
        )
        root.addView(label(I18n.t("Voice")))
        val voiceSpinner = spinner(voiceNames, DubPrefs.mode(activity).ordinal)
        root.addView(voiceSpinner)

        // 4) original sound while the dub speaks
        val duckValues = listOf(0f, 0.15f, 0.35f)
        val duckNames = listOf(I18n.t("Silent"), I18n.t("Quiet"), I18n.t("Medium"))
        root.addView(label(I18n.t("Original sound while dub speaks")))
        val duckSpinner = spinner(
            duckNames,
            duckValues.indexOfFirst { it == DubPrefs.duck(activity) }.takeIf { it >= 0 } ?: 1,
        )
        root.addView(duckSpinner)

        // 5) own voice per character + audio polish
        val smartBox = CheckBox(activity).apply {
            text = I18n.t("Different voice for each character (auto)")
            isChecked = DubPrefs.smart(activity)
            setPadding(0, pad(12), 0, 0)
        }
        root.addView(smartBox)

        val builder = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(I18n.t("Dub audio"))
            .setView(root)
            .setNegativeButton(I18n.t("Close"), null)
            .setNeutralButton(I18n.t("Upload subtitle…")) { _, _ -> onUpload() }

        if (engine.active) {
            builder.setPositiveButton(I18n.t("Stop dubbing")) { _, _ -> engine.stop() }
        } else if (subSpinner != null) {
            builder.setPositiveButton(I18n.t("Start dubbing")) { _, _ ->
                val raw = candidates[subSpinner.selectedItemPosition].second
                val lang = langs[langSpinner.selectedItemPosition].first
                val mode = VoiceMode.values()[voiceSpinner.selectedItemPosition]
                val duck = duckValues[duckSpinner.selectedItemPosition]
                DubPrefs.save(activity, lang, mode, duck)
                DubPrefs.saveSmart(activity, smartBox.isChecked)
                onStart(SubtitleCues.parse(raw), lang, mode, duck)
            }
        }
        builder.show()
    }
}
