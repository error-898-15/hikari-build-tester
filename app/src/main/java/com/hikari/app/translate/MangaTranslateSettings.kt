package com.hikari.app.translate

import com.hikari.app.HikariApp
import kotlinx.coroutines.launch

/**
 * One in-memory copy of the translate settings.
 *
 * The reader's page-cache file name depends on them (see
 * [TranslateConfig.cacheTag]) and that name is computed synchronously, in the
 * middle of a bind on the main thread, where a DataStore read is not possible.
 * So the store is observed once at start-up and the latest value is kept here;
 * the settings screen also pushes its change straight in via [update], so a
 * switch is felt by the very next page rather than after the store round-trip.
 */
object MangaTranslateSettings {

    @Volatile
    var current: TranslateConfig = TranslateConfig.OFF
        private set

    @Volatile
    private var started = false

    fun update(config: TranslateConfig) {
        current = config
    }

    /** Called once from [HikariApp.onCreate]. */
    fun start(app: HikariApp) {
        if (started) return
        started = true
        app.appScope.launch {
            runCatching {
                app.store.mangaTranslateFlow().collect { current = it }
            }
        }
    }
}
