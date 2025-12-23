package es.zelliot.epubeditor

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // Имена активити (простые имена — ожидается, что классы находятся в том же пакете)
    private val metadataActivityName = "MetadataActivity"
    private val orderActivityName = "OrderActivity"
    private val editorActivityName = "EditorActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnMetadata = findViewById<Button>(R.id.btn_metadata)
        val btnOrder = findViewById<Button>(R.id.btn_order)
        val btnEdit = findViewById<Button>(R.id.btn_edit)

        btnMetadata.setOnClickListener { tryLaunchByClassName(metadataActivityName) }
        btnOrder.setOnClickListener { tryLaunchByClassName(orderActivityName) }
        btnEdit.setOnClickListener { tryLaunchByClassName(editorActivityName) }
    }

    private fun tryLaunchByClassName(classSimpleName: String) {
        val fullName = "${packageName}.$classSimpleName"
        val component = ComponentName(packageName, fullName)
        val intent = Intent().setComponent(component)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "$classSimpleName not implemented yet", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Can't start $classSimpleName: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
