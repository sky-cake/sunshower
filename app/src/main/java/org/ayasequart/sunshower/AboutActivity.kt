package org.ayasequart.sunshower

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.ayasequart.sunshower.R
import org.ayasequart.sunshower.ui.applySystemBarInsets

class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        findViewById<View>(android.R.id.content).applySystemBarInsets(
            (16 * resources.displayMetrics.density).toInt()
        )
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, version)
    }
}