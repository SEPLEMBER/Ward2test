package es.zelliot.epubeditor

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnMetadata = findViewById<Button>(R.id.btn_metadata)
        val btnOrder = findViewById<Button>(R.id.btn_order)
        val btnEdit = findViewById<Button>(R.id.btn_edit)
        val btnSettings = findViewById<Button>(R.id.btn_settings)

        btnMetadata.setOnClickListener {
            startActivity(Intent(this, MetadataActivity::class.java))
        }

        btnOrder.setOnClickListener {
            startActivity(Intent(this, OrderActivity::class.java))
        }

        btnEdit.setOnClickListener {
            startActivity(Intent(this, EditorActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
