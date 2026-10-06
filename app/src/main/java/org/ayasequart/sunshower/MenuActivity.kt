package org.ayasequart.sunshower

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.ayasequart.sunshower.edit.EditorActivity
import org.ayasequart.sunshower.kuroba.KurobaActivity
import org.ayasequart.sunshower.settings.SettingsActivity

class MenuActivity : ComponentActivity() {

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            startActivity(
                Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_URI, uri.toString())
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)
        findViewById<Button>(R.id.take_gif_button).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.edit_menu_button).setOnClickListener {
            pickMedia.launch("image/*")
        }
        findViewById<Button>(R.id.kuroba_menu_button).setOnClickListener {
            startActivity(Intent(this, KurobaActivity::class.java))
        }
        findViewById<Button>(R.id.settings_menu_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
