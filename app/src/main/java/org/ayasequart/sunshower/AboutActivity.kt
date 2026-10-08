package org.ayasequart.sunshower

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, version)
    }
}