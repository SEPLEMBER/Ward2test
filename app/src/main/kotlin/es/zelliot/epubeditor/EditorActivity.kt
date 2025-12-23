package es.zelliot.epubeditor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HREF = "extra_href"
    }

    private val prefsName = "epub_prefs"
    private val PREF_KEY_TREE = "epub_tree_uri"

    private lateinit var btnBold: Button
    private lateinit var btnItalic: Button
    private lateinit var btnCenter: Button
    private lateinit var btnSave: Button
    private lateinit var edtContent: EditText
    private lateinit var tvFileName: TextView

    private var treeDoc: DocumentFile? = null
    private var opfDoc: DocumentFile? = null
    private var currentFile: DocumentFile? = null
    private var currentHref: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        btnBold = findViewById(R.id.btn_bold)
        btnItalic = findViewById(R.id.btn_italic)
        btnCenter = findViewById(R.id.btn_center)
        btnSave = findViewById(R.id.btn_save_chapter)
        edtContent = findViewById(R.id.edit_chapter)
        tvFileName = findViewById(R.id.tv_editor_filename)

        btnBold.setOnClickListener { wrapSelection("<strong>", "</strong>") }
        btnItalic.setOnClickListener { wrapSelection("<em>", "</em>") }
        btnCenter.setOnClickListener { wrapSelection("<center>", "</center>") }
        btnSave.setOnClickListener { saveCurrentChapter() }

        val saved = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(PREF_KEY_TREE, null)
        if (saved == null) {
            Toast.makeText(this, "No EPUB folder selected. Open Settings.", Toast.LENGTH_LONG).show()
            return
        }

        val hrefFromIntent = intent.getStringExtra(EXTRA_HREF)
        currentHref = hrefFromIntent

        lifecycleScope.launch {
            val uri = Uri.parse(saved)
            loadFiles(uri)
            if (currentHref != null) {
                loadChapterByHref(currentHref!!)
            } else {
                // if no href provided, show simple chooser of chapters
                showChapterChooser()
            }
        }
    }

    private suspend fun loadFiles(treeUri: Uri) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(this@EditorActivity, treeUri)
        treeDoc = root
        opfDoc = findOpfFile(root)
    }

    private suspend fun showChapterChooser() = withContext(Dispatchers.Main) {
        val root = treeDoc
        val opf = opfDoc
        if (root == null || opf == null) {
            Toast.makeText(this@EditorActivity, "Book not loaded or OPF missing", Toast.LENGTH_LONG).show()
            return@withContext
        }
        // parse opf and present simple list for choice
        withContext(Dispatchers.IO) {
            val stream = contentResolver.openInputStream(opf.uri) ?: return@withContext
            val (manifest, spine) = parseOpf(stream)
            stream.close()
            val entries = spine.mapNotNull { sp ->
                manifest.find { it.id == sp.idref }
            }
            val labels = entries.map { it.href.substringAfterLast('/') }
            withContext(Dispatchers.Main) {
                val builder = android.app.AlertDialog.Builder(this@EditorActivity)
                builder.setTitle("Choose chapter")
                builder.setItems(labels.toTypedArray()) { _, which ->
                    val chosen = entries[which]
                    lifecycleScope.launch {
                        loadChapterByHref(chosen.href)
                    }
                }
                builder.setNegativeButton("Cancel", null)
                builder.show()
            }
        }
    }

    private suspend fun loadChapterByHref(href: String) = withContext(Dispatchers.IO) {
        val root = treeDoc ?: run {
            withContext(Dispatchers.Main) { Toast.makeText(this@EditorActivity, "Tree not available", Toast.LENGTH_SHORT).show() }
            return@withContext
        }
        // find file by name
        val fname = href.substringAfterLast('/')
        val file = findFileByName(root, fname)
        currentFile = file
        if (file == null) {
            withContext(Dispatchers.Main) { Toast.makeText(this@EditorActivity, "Chapter file not found: $fname", Toast.LENGTH_LONG).show() }
            return@withContext
        }
        val text = contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        // extract body content
        val body = extractBody(text)
        // keep only certain tags visible (<strong>, <em>, <center>), remove others
        val visible = body.replace(Regex("""<(?!/?(strong|em|center)\b)[^>]+>""", RegexOption.IGNORE_CASE), "")
        withContext(Dispatchers.Main) {
            tvFileName.text = fname
            edtContent.setText(visible)
            // move cursor to start
            edtContent.setSelection(0)
        }
    }

    private fun extractBody(xhtml: String): String {
        val bodyRegex = Regex("(?is)<body[^>]*>(.*?)</body>")
        val m = bodyRegex.find(xhtml)
        return m?.groups?.get(1)?.value?.trim() ?: xhtml
    }

    private fun wrapSelection(tagOpen: String, tagClose: String) {
        val text = edtContent.text
        val start = edtContent.selectionStart.coerceAtLeast(0)
        val end = edtContent.selectionEnd.coerceAtLeast(0)
        if (start == end) {
            text.insert(start, "$tagOpen$tagClose")
            val pos = start + tagOpen.length
            edtContent.setSelection(pos, pos)
        } else {
            val selected = text.subSequence(start, end)
            text.replace(start, end, "$tagOpen$selected$tagClose")
            val newPos = start + tagOpen.length + selected.length + tagClose.length
            edtContent.setSelection(newPos, newPos)
        }
    }

    private fun saveCurrentChapter() {
        val file = currentFile
        if (file == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
            return
        }
        val contentBody = edtContent.text.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val xhtml = buildXhtmlFromBody(contentBody, tvFileName.text.toString())
                contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
                    out.write(xhtml.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: throw Exception("Cannot open output")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Chapter saved", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Save failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildXhtmlFromBody(body: String, title: String): String {
        // body already may contain allowed tags; we wrap into full xhtml
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head>
                <title>$title</title>
                <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
              </head>
              <body>
                $body
              </body>
            </html>
        """.trimIndent()
    }

    // helper: find file in tree by name
    private fun findFileByName(root: DocumentFile?, name: String): DocumentFile? {
        if (root == null) return null
        root.listFiles().forEach { f ->
            if (f.isDirectory) {
                val r = findFileByName(f, name)
                if (r != null) return r
            } else if (f.name == name) {
                return f
            }
        }
        return null
    }
}
