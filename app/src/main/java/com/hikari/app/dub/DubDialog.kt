package com.hikari.app.dub

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.hikari.app.data.Translator
import com.hikari.app.i18n.I18n

/** Remembers the last dub choices. */
object DubPrefs {
    private fun p(c: Context) = c.getSharedPreferences("dub_prefs", Context.MODE_PRIVATE)
    fun lang(c: Context): String = p(c).getString("lang", "en") ?: "en"
    fun mode(c: Context): VoiceMode =
        runCatching { VoiceMode.valueOf(p(c).getString("mode", "AUTO")!!) }.getOrDefault(VoiceMode.AUTO)
    /** How much of the original is left while the dub speaks. Silent (0) by default:
     *  the original dialogue is taken out, the music and effects stay. The key is
     *  "duck2" so the old default (Quiet) does not carry over. */
    fun duck(c: Context): Float = p(c).getFloat("duck2", 0f)
    /** Own voice for every character + edited audio (on by default). */
    fun smart(c: Context): Boolean = p(c).getBoolean("smart", true)
    fun saveSmart(c: Context, v: Boolean) { p(c).edit().putBoolean("smart", v).apply() }
    /** Listen to the ORIGINAL voices first, to tell men from women and to find the characters (on by default). */
    fun scan(c: Context): Boolean = p(c).getBoolean("scan", true)
    fun saveScan(c: Context, v: Boolean) { p(c).edit().putBoolean("scan", v).apply() }
    fun save(c: Context, lang: String, mode: VoiceMode, duck: Float) {
        p(c).edit().putString("lang", lang).putString("mode", mode.name).putFloat("duck2", duck).apply()
    }
}

/**
 * What the dub panel will read: the subtitle that is switched on in the player.
 *
 *  - [label]      name shown to the person ("English · Full")
 *  - [raw]        the subtitle text when it is available (exact timing),
 *                 or null when it lives inside the video
 *  - [live]       true when the lines are read as the captions appear
 *  - [imageBased] true for picture subtitles (PGS / DVB), which cannot be read
 */
data class DubPlan(
    val label: String,
    val raw: String?,
    val live: Boolean,
    val imageBased: Boolean = false,
)

/**
 * The "Dub audio" panel: shows which subtitle will be read, and lets the
 * person pick the language and the voice. [plan] is null when subtitles are
 * off; [onUpload] opens the file picker so the person can add their own
 * subtitle file; [onStop] stops a running dub.
 */
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
            textSize = 13f
            setPadding(0, pad(8), 0, pad(8))
        }
        fun spinner(items: List<String>, selected: Int) = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(8), pad(20), 0)
        }

        // 1) which subtitle is read
        val canStart = plan != null && !plan.imageBased
        when {
            plan == null -> root.addView(
                note(I18n.t("No subtitle is switched on. Turn on a subtitle in the player, or upload a subtitle file (.srt / .vtt), and it will be read out in the language you choose."))
            )
            plan.imageBased -> root.addView(
                note(I18n.t("This subtitle is made of pictures, so it cannot be read out. Pick a text subtitle, or upload a subtitle file (.srt / .vtt).") + "\n\n" + plan.label)
            )
            else -> {
                root.addView(label(I18n.t("Read this subtitle")))
                root.addView(note(plan.label))
                if (plan.raw == null) {
                    root.addView(note(I18n.t("This subtitle is inside the video, so each line is read when it appears on screen.")))
                }
            }
        }

        // Which characters the subtitle names (only when its text is available).
        val castInfo = TextView(activity).apply {
            textSize = 12f
            setPadding(0, pad(6), 0, 0)
        }
        val rawText = plan?.raw
        if (canStart && rawText != null) {
            castInfo.text = runCatching {
                val cues = SubtitleCues.parse(rawText)
                val desc = CastDirector.assign(cues).describe()
                if (desc.isBlank()) {
                    I18n.t("No character names found in this subtitle. Each new speaker turn gets a man or a woman voice at random; two-person exchanges (- Hi / - Hello) always get one of each.")
                } else {
                    I18n.t("Characters found") + ": " + desc
                }
            }.getOrDefault("")
            root.addView(castInfo)
        }

        // The neural voice stopped last time: say why (so it can be fixed).
        engine.neuralReport()?.let { r ->
            root.addView(
                note(I18n.t("Last neural voice problem") + ": " + r)
            )
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
            I18n.t("Neural voice - offline, real actors (Hindi: 3 voices, English: 2 voices)"),
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

        // 6) listen to the original voices (man / woman and who is who)
        val scanBox = CheckBox(activity).apply {
            text = I18n.t("Listen to the original voices first (tells men from women; takes about 2 minutes, downloads a ~40 MB model once)")
            isChecked = DubPrefs.scan(activity)
            setPadding(0, pad(8), 0, 0)
        }
        root.addView(scanBox)

        val builder = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(I18n.t("Dub audio"))
            .setView(root)
            .setNegativeButton(I18n.t("Close"), null)
            .setNeutralButton(I18n.t("Upload subtitle…")) { _, _ -> onUpload() }

        if (engine.active) {
            builder.setPositiveButton(I18n.t("Stop dubbing")) { _, _ -> onStop() }
        } else if (canStart) {
            builder.setPositiveButton(I18n.t("Start dubbing")) { _, _ ->
                val lang = langs[langSpinner.selectedItemPosition].first
                val mode = VoiceMode.values()[voiceSpinner.selectedItemPosition]
                val duck = duckValues[duckSpinner.selectedItemPosition]
                val multi = smartBox.isChecked
                DubPrefs.save(activity, lang, mode, duck)
                DubPrefs.saveSmart(activity, multi)
                DubPrefs.saveScan(activity, scanBox.isChecked)
                val isHindi = lang.substringBefore('-').equals("hi", ignoreCase = true)
                val isEnglish = lang.substringBefore('-').equals("en", ignoreCase = true)
                when {
                    mode != VoiceMode.NEURAL -> onStart(lang, mode, duck, multi)
                    !isHindi && !isEnglish -> {
                        // The neural voices speak Hindi and English only for now.
                        Toast.makeText(
                            activity,
                            I18n.t("The neural voice is Hindi and English only for now, so the normal voice is used."),
                            Toast.LENGTH_LONG,
                        ).show()
                        onStart(lang, VoiceMode.AUTO, duck, multi)
                    }
                    SherpaModels.isInstalledFor(activity, lang) -> onStart(lang, mode, duck, multi)
                    else -> NeuralDownload.show(activity, isEnglish) { ok ->
                        if (ok) onStart(lang, mode, duck, multi)
                    }
                }
            }
        }
        builder.show()
    }
}

/**
 * First use of the neural voice: downloads the voices once (Hindi: three
 * voices, about 190 MB; English: two voices, about 130 MB, from GitHub) and
 * shows the progress. [onDone] gets true when it is ready.
 */
internal object NeuralDownload {

    fun show(activity: Activity, english: Boolean, onDone: (Boolean) -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun pad(v: Int) = (v * d).toInt()

        val status = TextView(activity).apply {
            textSize = 13f
            text = if (english) {
                I18n.t("Downloading the English voices once (2 voices, about 130 MB). Keep this screen open and stay on Wi-Fi.")
            } else {
                I18n.t("Downloading the voices once (3 voices, about 190 MB). Keep this screen open and stay on Wi-Fi.")
            }
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            setPadding(0, pad(12), 0, 0)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(12), pad(20), pad(4))
            addView(status)
            addView(bar)
        }

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: MainScope()
        var job: Job? = null
        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (english) I18n.t("Neural voice (English)") else I18n.t("Neural voice (Hindi)"))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(I18n.t("Cancel")) { _, _ -> job?.cancel() }
            .create()
        dlg.show()
        // keep the screen on while the voice pack downloads / unpacks, otherwise the phone
        // sleeps and the work stops
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val running = scope.launch {
            val progress: (Int, Boolean) -> Unit = { pct, unpacking ->
                activity.runOnUiThread {
                    bar.progress = pct
                    status.text = (if (unpacking) I18n.t("Unpacking") else I18n.t("Downloading")) + "… " + pct + "%"
                }
            }
            val err = if (english) {
                SherpaModels.installKokoro(activity.applicationContext, progress)
            } else {
                SherpaModels.install(activity.applicationContext, progress)
            }
            runCatching { dlg.dismiss() }
            if (err == null) {
                Toast.makeText(activity, I18n.t("Neural voice is ready."), Toast.LENGTH_SHORT).show()
                onDone(true)
            } else {
                Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                onDone(false)
            }
        }
        job = running
        running.invokeOnCompletion { runCatching { activity.runOnUiThread { runCatching { dlg.dismiss() } } } }
    }
}
