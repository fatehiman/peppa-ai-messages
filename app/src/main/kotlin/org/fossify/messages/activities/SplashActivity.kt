package org.fossify.messages.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.isAutoTheme
import org.fossify.commons.extensions.isSystemInDarkMode
import org.fossify.commons.extensions.syncGlobalConfig

/**
 * sms-man: does what Fossify's BaseSplashActivity does (sync the theme colors, open the main
 * screen) but without its "You are using a fake version of the app" check. This app is an own
 * fork with its own app id, not a fake, and with this the warning dialog is not in the APK at all.
 */
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncGlobalConfig {
            if (isAutoTheme()) {
                val isDark = isSystemInDarkMode()
                baseConfig.textColor = resources.getColor(
                    if (isDark) org.fossify.commons.R.color.theme_dark_text_color
                    else org.fossify.commons.R.color.theme_light_text_color,
                    theme
                )
                baseConfig.backgroundColor = resources.getColor(
                    if (isDark) org.fossify.commons.R.color.theme_dark_background_color
                    else org.fossify.commons.R.color.theme_light_background_color,
                    theme
                )
            }
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
