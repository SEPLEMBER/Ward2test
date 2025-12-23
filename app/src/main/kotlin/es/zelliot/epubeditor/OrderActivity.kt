package es.zelliot.epubeditor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class OrderActivity : AppCompatActivity() {

    private val prefsName = "epub_prefs"
    private val PREF_KEY_TREE = "epub_tree_uri"

    private lateinit var container: LinearLayout
    private lateinit var btnSaveOrder: Button
    private lateinit var btnAddChapter: Button
    private lateinit var tvInfo: TextView

    private var book: EpubBook? = null
    private var treeDoc: DocumentFile? = null
    private var opfDoc: DocumentFile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order)

        container = findViewById(R.id.ll_chapter_list)
        btnSaveOrder = findViewById(R.id.btn_save_order)
        btnAddChapter = findViewById(R.id.btn_add_chapter)
        tvInfo = findViewById(R.id.tv_order_info)

        btnSaveOrder.setOnClickListener { onSaveOrderClicked() }
        btnAddChapter.setOnClickListener { onAddChapterClicked() }

        val saved = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(PREF_KEY_TREE, null)
        if (saved == null) {
            Toast.makeText(this, "No EPUB folder selected. Open Settings.", Toast.LENGTH_LONG).show()
            tvInfo.text = "No folder selected. Go to Settings to choose book folder."
            return
        }

        lifecycleScope.launch {
            val uri = Uri.parse(saved)
            loadBook(uri)
        }
    }

    private suspend fun loadBook(treeUri: Uri) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(this@OrderActivity, treeUri)
        treeDoc = root
        opfDoc = findOpfFile(root)
        if (opfDoc == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@OrderActivity, "OPF file not found in selected folder", Toast.LENGTH_LONG).show()
                tvInfo.text = "OPF not found in selected folder."
            }
            return@withContext
        }

        // parse opf
        val stream = contentResolver.openInputStream(opfDoc!!.uri)
        if (stream == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@OrderActivity, "Can't open OPF file", Toast.LENGTH_LONG).show()
                tvInfo.text = "Can't open OPF."
            }
            return@withContext
        }

        val (manifest, spine) = parseOpf(stream)
        stream.close()

        // map spine -> manifest entries and find matching DocumentFiles for chapters
        val manifestMap = manifest.associateBy { it.id }
        val chapters = mutableListOf<Chapter>()
        for (s in spine) {
            val item = manifestMap[s.idref]
            if (item != null) {
                val fname = item.href.substringAfterLast('/')
                // try to find file by name in tree
                val file = findFileByName(root, fname)
                val title = if (file != null) loadTitleFromXhtml(file) else fname
                chapters.add(Chapter(title = title, href = item.href, file = file))
            }
        }

        book = EpubBook(opfFile = opfDoc, baseTree = root)
        book!!.manifest.addAll(manifest)
        book!!.spine.addAll(spine)
        book!!.chapters.addAll(chapters)

        withContext(Dispatchers.Main) {
            tvInfo.text = "Loaded ${book!!.chapters.size} chapters"
            refreshChapterList()
        }
    }

    private fun refreshChapterList() {
        container.removeAllViews()
        val current = book ?: return
        current.chapters.forEachIndexed { idx, chap ->
            container.addView(createChapterRow(idx, chap))
        }
    }

    private fun createChapterRow(index: Int, chapter: Chapter): View {
        val row = LinearLayout(this)
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(8, 12, 8, 12)
        row.weightSum = 10f

        val tvTitle = TextView(this)
        tvTitle.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 5f)
        tvTitle.text = chapter.title
        tvTitle.setTextColor(resources.getColor(android.R.color.white))
        tvTitle.textSize = 16f

        val tvFile = TextView(this)
        tvFile.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
        tvFile.text = chapter.href.substringAfterLast('/')
        tvFile.setTextColor(resources.getColor(android.R.color.darker_gray))
        tvFile.textSize = 12f

        val btnUp = Button(this)
        btnUp.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        btnUp.text = "↑"
        btnUp.setOnClickListener {
            moveChapterUp(index)
        }

        val btnDown = Button(this)
        btnDown.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        btnDown.text = "↓"
        btnDown.setOnClickListener {
            moveChapterDown(index)
        }

        val btnEdit = Button(this)
        btnEdit.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnEdit.text = "Edit"
        btnEdit.setOnClickListener {
            openEditorForChapter(index)
        }

        // Add in order: title, file, up, down, edit
        row.addView(tvTitle)
        row.addView(tvFile)
        row.addView(btnUp)
        row.addView(btnDown)
        row.addView(btnEdit)

        return row
    }

    private fun moveChapterUp(index: Int) {
        val b = book ?: return
        if (index <= 0) return
        Collections.swap(b.chapters, index, index - 1)
        // also swap spine ordering to keep in sync (simple approach)
        if (index - 1 >= 0 && index < b.spine.size) {
            Collections.swap(b.spine, index, index - 1)
            Collections.swap(b.manifest, index, index - 1) // best-effort; manifest order not critical but keep aligned
        }
        refreshChapterList()
    }

    private fun moveChapterDown(index: Int) {
        val b = book ?: return
        if (index >= b.chapters.size - 1) return
        Collections.swap(b.chapters, index, index + 1)
        if (index + 1 < b.spine.size) {
            Collections.swap(b.spine, index, index + 1)
            Collections.swap(b.manifest, index, index + 1)
        }
        refreshChapterList()
    }

    private fun openEditorForChapter(index: Int) {
        val b = book ?: return
        if (index < 0 || index >= b.chapters.size) return
        val chap = b.chapters[index]
        if (chap.file == null) {
            Toast.makeText(this, "Chapter file not found for ${chap.href}", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_HREF, chap.href)
        startActivity(intent)
    }

    private fun onAddChapterClicked() {
        val b = book ?: run {
            Toast.makeText(this, "No book loaded", Toast.LENGTH_SHORT).show()
            return
        }
        val root = treeDoc ?: return

        // create new file with unique name
        var idx = 1
        while (true) {
            val candidate = "chapter_new_$idx.xhtml"
            if (findFileByName(root, candidate) == null) {
                // create file
                val newFile = root.createFile("application/xhtml+xml", candidate)
                if (newFile == null) {
                    Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
                    return
                }
                // write minimal xhtml content
                lifecycleScope.launch(Dispatchers.IO) {
                    val content = minimalXhtmlContent("New Chapter")
                    contentResolver.openOutputStream(newFile.uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    // update in-memory manifest/spine/chapters
                    val newId = "item${b.manifest.size + 1}"
                    val newHref = candidate
                    val newManifest = ManifestItem(newId, newHref, "application/xhtml+xml")
                    b.manifest.add(newManifest)
                    b.spine.add(SpineItem(newId))
                    b.chapters.add(Chapter(title = "New Chapter", href = newHref, file = newFile))
                    withContext(Dispatchers.Main) {
                        refreshChapterList()
                        Toast.makeText(this@OrderActivity, "Chapter created", Toast.LENGTH_SHORT).show()
                    }
                }
                break
            }
            idx++
        }
    }

    private fun minimalXhtmlContent(title: String): String {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head>
                <title>$title</title>
                <meta http-equiv="Content-Type" content="text/html; charset=utf-8"/>
              </head>
              <body>
                <p></p>
              </body>
            </html>
        """.trimIndent()
    }

    private fun onSaveOrderClicked() {
        val b = book ?: run {
            Toast.makeText(this, "No book loaded", Toast.LENGTH_SHORT).show()
            return
        }
        val opf = opfDoc ?: run {
            Toast.makeText(this, "OPF file not found", Toast.LENGTH_SHORT).show()
            return
        }

        // regenerate minimal opf and write to opf file
        lifecycleScope.launch(Dispatchers.IO) {
            // choose a simple title from first chapter
            val title = if (b.chapters.isNotEmpty()) b.chapters[0].title else "Book"
            val bytes = generateOpfBytes(packageUniqueId = "bookid", title = title, manifest = b.manifest, spine = b.spine)

            try {
                contentResolver.openOutputStream(opf.uri, "wt")?.use { out ->
                    out.write(bytes)
                } ?: throw Exception("openOutputStream returned null")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OrderActivity, "OPF updated (saved)", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OrderActivity, "Failed to save OPF: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // helper: find file by exact name anywhere under root
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

    // helper: load <title> or first <h1> from xhtml
    private fun loadTitleFromXhtml(file: DocumentFile): String {
        return try {
            val input = contentResolver.openInputStream(file.uri) ?: return file.name ?: "chapter"
            val raw = input.bufferedReader(Charsets.UTF_8).readText()
            input.close()
            // try <title>
            val titleRegex = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL)
            val h1Regex = Regex("<h1[^>]*>(.*?)</h1>", RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL)
            when {
                titleRegex.containsMatchIn(raw) -> titleRegex.find(raw)?.groups?.get(1)?.value?.trim() ?: file.name ?: "chapter"
                h1Regex.containsMatchIn(raw) -> h1Regex.find(raw)?.groups?.get(1)?.value?.trim() ?: file.name ?: "chapter"
                else -> file.name ?: "chapter"
            }
        } catch (t: Throwable) {
            file.name ?: "chapter"
        }
    }
}
