package com.hikari.app.dub

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.hikari.app.data.Translator
import com.hikari.app.i18n.I18n

/**
 * The subtitle the dub will read, found automatically from the caption track
 * that is switched on in the player.
 *
 * @param raw        the subtitle file's text, or null for a track that lives
 *                   inside the video (then [live] is true and lines are
 *                   captured as they appear).
 * @param imageBased the track is pictures (PGS / DVB), which cannot be read.
 */
class DubPlan(
    val label: String,
    val raw: String?,
    val live: Boolean,
    val imageBased: Boolean = false,
)

/** Remembers the last dub choices. */
object DubPrefs {
    private fun p(c: Context) = c.getSharedPreferences("dub_prefs", Context.MODE_PRIVATE)
    fun lang(c: Context): String = p(c).getString("lang", "en") ?: "en"
    fun mode(c: Context): VoiceMode =
        runCatching { VoiceMode.valueOf(p(c).getString("mode", "AUTO")!!) }.getOrDefault(VoiceMode.AUTO)
    fun duck(c: Context): Float = p(c).getFloat("duck", 0.15f)
    fun multi(c: Context): Boolean = p(c).getBoolean("multi", true)
    fun save(c: Context, lang: String, mode: VoiceMode, duck: Float, multi: Boolean) {
        p(c).edit().putString("lang", lang).putString("mode", mode.name)
            .putFloat("duck", duck).putBoolean("multi", multi).apply()
    }
}

/** The "Dub audio" panel: language, voice, characters, original sound. */
object DubDialog {

    fun show(
        activity: Activity,
        engine: DubEngine,
        plan: DubPlan?,
        onUpload: () -> Unit,
        onStop: () -> Unit,
        onStart: (lang: String, mode: VoiceMode, duck: Float, multi: Boolean) -> Unit,
    ) {
        val d = activity.resources.displayMetrics.density
        fun pad(v: Int) = (v * d).toInt()

        fun label(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 12f
            setPadding(0, pad(12), 0, pad(2))
        }
        fun note(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 12f
            alpha = 0.7f
            setPadding(0, pad(2), 0, pad(4))
        }
        fun spinner(items: List<String>, selected: Int) = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(8), pad(20), 0)
        }

        // Which subtitle is used — found automatically.
        val usable = plan != null && !plan.imageBased
        when {
            plan == null -> root.addView(TextView(activity).apply {
                text = I18n.t("Turn a subtitle on first (Subtitles → pick a track), then come back here. The dub reads the subtitle that is on.")
                textSize = 13f
                setPadding(0, pad(8), 0, pad(8))
            })
            plan.imageBased -> root.addView(TextView(activity).apply {
                text = I18n.t("This subtitle is made of pictures, so it cannot be read aloud. Pick a text subtitle (or upload a .srt / .vtt file).")
                textSize = 13f
                setPadding(0, pad(8), 0, pad(8))
            })
            else -> {
                root.addView(label(I18n.t("Subtitle in use")))
                root.addView(TextView(activity).apply {
                    text = plan.label
                    textSize = 15f
                })
                root.addView(note(
                    if (plan.live) I18n.t("Read as each line appears, so the voice starts about a second after it.")
                    else I18n.t("Read from the subtitle file, so the timing is exact.")
                ))
            }
        }

        // Language
        val langs = Translator.LANGUAGES
        root.addView(label(I18n.t("Dub language")))
        val langSpinner = spinner(
            langs.map { it.second },
            langs.indexOfFirst { it.first == DubPrefs.lang(activity) }.coerceAtLeast(0),
        )
        root.addView(langSpinner)

        // Voice source
        val voiceNames = listOf(
            I18n.t("Automatic (Google, then phone voice)"),
            I18n.t("Google Translate voice (online)"),
            I18n.t("Phone voice (offline)"),
        )
        root.addView(label(I18n.t("Voice")))
        val voiceSpinner = spinner(voiceNames, DubPrefs.mode(activity).ordinal)
        root.addView(voiceSpinner)

        // Characters
        val multi = CheckBox(activity).apply {
            text = I18n.t("Different voice for each character")
            isChecked = DubPrefs.multi(activity)
        }
        root.addView(multi)
        root.addView(note(I18n.t("Uses names written in the subtitle (like MAYA: or [John]) to choose male / female voices.")))

        // Original sound
        val duckValues = listOf(0f, 0.15f, 0.35f)
        val duckNames = listOf(I18n.t("Silent"), I18n.t("Quiet"), I18n.t("Medium"))
        root.addView(label(I18n.t("Original sound while dub speaks")))
        val duckSpinner = spinner(
            duckNames,
            duckValues.indexOfFirst { it == DubPrefs.duck(activity) }.takeIf { it >= 0 } ?: 1,
        )
        root.addView(duckSpinner)

        val builder = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(I18n.t("Dub audio"))
            .setView(root)
            .setNegativeButton(I18n.t("Close"), null)
            .setNeutralButton(I18n.t("Upload subtitle…")) { _, _ -> onUpload() }

        if (engine.active) {
            builder.setPositiveButton(I18n.t("Stop dubbing")) { _, _ -> onStop() }
        } else if (usable) {
            builder.setPositiveButton(I18n.t("Start dubbing")) { _, _ ->
                val lang = langs[langSpinner.selectedItemPosition].first
                val mode = VoiceMode.values()[voiceSpinner.selectedItemPosition]
                val duck = duckValues[duckSpinner.selectedItemPosition]
                val perCharacter = multi.isChecked
                DubPrefs.save(activity, lang, mode, duck, perCharacter)
                onStart(lang, mode, duck, perCharacter)
            }
        }
        builder.show()
    }
}
