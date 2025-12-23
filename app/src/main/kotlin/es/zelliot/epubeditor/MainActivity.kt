package es.zelliot.epubeditor

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // Имена активити, которые будут реализованы позже.
    // Мы пытаемся стартовать их по полному имени пакета; если их нет — показываем Toast.
    private val metadataActivityName = "MetadataActivity"
    private val orderActivityName = "OrderActivity"
    private val editorActivityName = "EditorActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvMetadata = findViewById<TextView>(R.id.link_metadata)
        val tvOrder = findViewById<TextView>(R.id.link_order)
        val tvEdit = findViewById<TextView>(R.id.link_edit)

        tvMetadata.setOnClickListener {
            tryLaunchByClassName(metadataActivityName)
        }
        tvOrder.setOnClickListener {
            tryLaunchByClassName(orderActivityName)
        }
        tvEdit.setOnClickListener {
            tryLaunchByClassName(editorActivityName)
        }
    }

    private fun tryLaunchByClassName(classSimpleName: String) {
        // Формируем полный ComponentName: package + "." + classSimpleName
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
