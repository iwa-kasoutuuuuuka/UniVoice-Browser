package com.univoice.browser

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * UniVoice Browser Application
 * アプリケーション全体の基底ロケールを日本語 (ja-JP) に強制設定
 */
class UniVoiceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        enforceJapaneseLocale(this)
    }

    override fun attachBaseContext(base: Context) {
        val locale = Locale.JAPANESE
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    companion object {
        fun enforceJapaneseLocale(context: Context): Context {
            val locale = Locale.JAPANESE
            Locale.setDefault(locale)
            val resources = context.resources
            val config = Configuration(resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
    }
}
