package es.zelliot.epubeditor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var chooseBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var folderText: TextView

    private val prefsName = "epub_prefs"
    private val PREF_KEY_TREE = "epub_tree_uri"

    // ActivityResult для выбора папки через SAF
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
            if (treeUri == null) {
                Toast.makeText(this, "Folder not selected", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            // даём persistable permission
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // сохраняем в SharedPreferences
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
                .putString(PREF_KEY_TREE, treeUri.toString())
                .apply()

            // показываем имя папки (в background)
            displayFolderName(treeUri)
            Toast.makeText(this, "Folder saved", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        chooseBtn = findViewById(R.id.btn_choose_folder)
        clearBtn = findViewById(R.id.btn_clear_folder)
        folderText = findViewById(R.id.tv_folder_name)

        chooseBtn.setOnClickListener {
            // Запускаем SAF диалог выбора папки
            pickFolder.launch(null)
        }

        clearBtn.setOnClickListener {
            // Удаляем сохранённый Uri и обновляем UI
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
                .remove(PREF_KEY_TREE)
                .apply()
            folderText.text = "No folder selected"
            Toast.makeText(this, "Selection cleared", Toast.LENGTH_SHORT).show()
        }

        // При старте — показываем, если уже есть сохранённый Uri
        val saved = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(PREF_KEY_TREE, null)
        if (saved != null) {
            val uri = Uri.parse(saved)
            displayFolderName(uri)
        } else {
            folderText.text = "No folder selected"
        }
    }

    private fun displayFolderName(treeUri: Uri) {
        // Получаем имя через DocumentFile (в фоне)
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                try {
                    val df = DocumentFile.fromTreeUri(this@SettingsActivity, treeUri)
                    df?.name ?: treeUri.path ?: "Unknown"
                } catch (t: Throwable) {
                    treeUri.path ?: "Unknown"
                }
            }
            folderText.text = "Selected: $name"
        }
    }
}
