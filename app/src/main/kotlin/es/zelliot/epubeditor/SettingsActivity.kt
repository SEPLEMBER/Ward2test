package org.syndes.terminal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.documentfile.provider.DocumentFile

/**
 * SettingsActivity — улучшенная версия:
 *  - выбор work-папки через SAF (ACTION_OPEN_DOCUMENT_TREE)
 *  - сохраняет persistable permission (read + write)
 *  - создаёт внутри work-папки базовые папки: "scripts" и "logs" (если отсутствуют)
 *  - сохраняет work_dir_uri и current_dir_uri в SharedPreferences("terminal_prefs")
 *
 * Замечание: по принципу приватности/безопасности приложение не пишет логов и не отправляет аналитику.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var workFolderUriView: TextView
    private lateinit var chooseFolderBtn: Button
    private lateinit var autoScrollSwitch: SwitchCompat
    private lateinit var aliasesField: EditText
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button

    private val PREFS_NAME = "terminal_prefs"
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        workFolderUriView = findViewById(R.id.workFolderUri)
        chooseFolderBtn = findViewById(R.id.chooseFolderBtn)
        autoScrollSwitch = findViewById(R.id.autoScrollSwitch)
        aliasesField = findViewById(R.id.aliasesField)
        saveButton = findViewById(R.id.saveButton)
        resetButton = findViewById(R.id.resetButton)

        // Launcher для выбора директории (SAF)
        folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val treeUri: Uri? = result.data?.data
                if (treeUri != null) {
                    handlePickedTreeUri(treeUri)
                } else {
                    Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Обработчики кнопок
        chooseFolderBtn.setOnClickListener {
            // Сбрасываем преднамеренно флагы: ACTION_OPEN_DOCUMENT_TREE
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                // попросим persistable permission
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            folderPickerLauncher.launch(intent)
        }

        saveButton.setOnClickListener {
            saveValues()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        resetButton.setOnClickListener {
            resetValues()
        }

        // Загрузить текущие значения (и проверить права на work dir)
        loadValues()
    }

    // Обработка выбора папки — присваиваем persistable permission, сохраняем prefs, создаём поддиры
    private fun handlePickedTreeUri(treeUri: Uri) {
        try {
            // take persistable permission (read + write)
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(treeUri, takeFlags)

            // Сохраняем URI в prefs
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            prefs.putString("work_dir_uri", treeUri.toString())
            prefs.putString("current_dir_uri", treeUri.toString())
            prefs.apply()

            // Попробуем создать стандартные подпапки scripts/ и logs/
            ensureWorkSubfolders(treeUri)

            // Обновляем UI
            workFolderUriView.text = buildFriendlyName(treeUri) ?: treeUri.toString()
            Toast.makeText(this, "Work folder set", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // не логируем — просто показываем юзеру
            Toast.makeText(this, "Failed to set work folder", Toast.LENGTH_SHORT).show()
        }
    }

    // Проверяем сохранённые prefs и отображаем friendly name или "(not set)"
    private fun loadValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString("work_dir_uri", null)
        if (uriStr != null) {
            val uri = try { Uri.parse(uriStr) } catch (e: Exception) { null }
            if (uri != null && hasPersistedPermission(uri)) {
                workFolderUriView.text = buildFriendlyName(uri) ?: uri.toString()
            } else {
                // permission lost or bad URI
                workFolderUriView.text = "(not set)"
            }
        } else {
            workFolderUriView.text = "(not set)"
        }

        // Theme removed — не устанавливаем/не читаем параметры темы

        autoScrollSwitch.isChecked = prefs.getBoolean("scroll_behavior", true)
        aliasesField.setText(prefs.getString("aliases", ""))
    }

    // Save other prefs (scroll, aliases)
    private fun saveValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()

        // Theme removed — не сохраняем параметр темы
        prefs.putBoolean("scroll_behavior", autoScrollSwitch.isChecked)
        prefs.putString("aliases", aliasesField.text.toString().trim())
        prefs.apply()
    }

    // сброс всех настроек и work dir (не отзывает persistable permission)
    private fun resetValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.remove("work_dir_uri")
        prefs.remove("current_dir_uri")
        prefs.remove("aliases")
        // prefs.remove("theme")  // удалено: тема больше не используется
        prefs.remove("scroll_behavior")
        prefs.apply()
        loadValues()
        Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show()
    }

    // Проверка: есть ли persistable permission на URI
    private fun hasPersistedPermission(uri: Uri): Boolean {
        val perms = contentResolver.persistedUriPermissions
        for (p in perms) {
            if (p.uri == uri && p.isReadPermission && p.isWritePermission) return true
        }
        return false
    }

    // Пытаемся создать внутри выбранной work папки подпапки scripts и logs (если не существуют).
    // Это обеспечивает "изолированное" окружение: пользователь хранит все скрипты в workDir/scripts.
    private fun ensureWorkSubfolders(treeUri: Uri) {
        try {
            val tree = DocumentFile.fromTreeUri(this, treeUri) ?: return
            // create "scripts" and "logs" if missing
            val scripts = tree.findFile("scripts") ?: tree.createDirectory("scripts")
            val logs = tree.findFile("logs") ?: tree.createDirectory("logs")
            // также можно создать скрытую папку для внутренних метаданных
            val meta = tree.findFile(".syd_meta") ?: tree.createDirectory(".syd_meta")
            val trash = tree.findFile(".syndes_trash") ?: tree.createDirectory(".syndes_trash")
            // no further action; creation is best-effort
        } catch (t: Throwable) {
            // молча игнорируем ошибки создания — не критично
        }
    }

    // Пытаемся получить удобочитаемое имя для UI (name of DocumentFile), иначе null
    private fun buildFriendlyName(uri: Uri): String? {
        return try {
            val df = DocumentFile.fromTreeUri(this, uri)
            df?.name
        } catch (t: Throwable) {
            null
        }
    }
}
