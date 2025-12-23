package es.zelliot.epubeditor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.lang.StringBuilder
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList

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

    // raw OPF text and extracted metadata/package attrs (to preserve originals)
    private var rawOpfText: String? = null
    private var opfMetadataRaw: String? = null
    private var opfPackageAttrsRaw: String? = null

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

        // read raw OPF text
        val raw = try {
            contentResolver.openInputStream(opfDoc!!.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (t: Throwable) {
            null
        }

        if (raw == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@OrderActivity, "Can't open OPF file", Toast.LENGTH_LONG).show()
                tvInfo.text = "Can't open OPF."
            }
            return@withContext
        }

        rawOpfText = raw
        opfMetadataRaw = extractMetadataBlock(raw)
        opfPackageAttrsRaw = extractPackageAttrs(raw)

        // parse opf using existing parser (works from InputStream)
        val (manifest, spine) = parseOpf(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

        // map spine -> manifest entries and find matching DocumentFiles for chapters
        val manifestMap = manifest.associateBy { it.id }
        val chapters = mutableListOf<Chapter>()
        for (s in spine) {
            val item = manifestMap[s.idref]
            if (item != null) {
                val fname = item.href.substringAfterLast('/')
                // try to find file by name in opf directory first, then root
                val file = findFileByName(opfDoc?.parentFile ?: root, fname) ?: findFileByName(root, fname)
                val title = if (file != null) loadTitleFromXhtml(file) else fname
                chapters.add(Chapter(title = title, href = item.href, file = file))
            }
        }

        book = EpubBook(opfFile = opfDoc, baseTree = root)
        book!!.manifest.addAll(manifest) // keep full manifest unchanged
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

    private fun createChapterRow(index: Int, chapter: Chapter): android.view.View {
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
        // swap spine items to reflect new order
        if (index - 1 >= 0 && index < b.spine.size) {
            Collections.swap(b.spine, index, index - 1)
        }
        refreshChapterList()
    }

    private fun moveChapterDown(index: Int) {
        val b = book ?: return
        if (index >= b.chapters.size - 1) return
        Collections.swap(b.chapters, index, index + 1)
        if (index + 1 < b.spine.size) {
            Collections.swap(b.spine, index, index + 1)
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
        val intent = android.content.Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_HREF, chap.href)
        startActivity(intent)
    }

    private fun onAddChapterClicked() {
        val b = book ?: run {
            Toast.makeText(this, "No book loaded", Toast.LENGTH_SHORT).show()
            return
        }
        val root = treeDoc ?: return
        val opfParent = opfDoc?.parentFile ?: root

        lifecycleScope.launch(Dispatchers.IO) {
            // determine next available number by scanning existing files in opfParent
            val existingNums = mutableSetOf<Int>()
            opfParent.listFiles().forEach { f ->
                val nm = f.name ?: return@forEach
                val m = Pattern.compile("^chapter_new_(\\d+)\\.xhtml$", Pattern.CASE_INSENSITIVE).matcher(nm)
                if (m.find()) {
                    try { existingNums.add(m.group(1).toInt()) } catch (_: Exception) {}
                }
            }
            var next = 1
            while (existingNums.contains(next)) next++
            val candidate = "chapter_new_$next.xhtml"

            // create new file in opfParent (so it's next to existing content files)
            val newFile = try {
                opfParent.createFile("application/xhtml+xml", candidate)
            } catch (t: Throwable) {
                null
            }

            if (newFile == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OrderActivity, "Failed to create file", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // write minimal xhtml content
            try {
                val content = minimalXhtmlContent("New Chapter")
                contentResolver.openOutputStream(newFile.uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OrderActivity, "Failed to write new file: ${t.message}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // generate unique manifest id that doesn't collide
            val existingIds = b.manifest.mapNotNull { it.id }.mapNotNull { id ->
                // try to extract numeric suffix if present like item123
                val m = Regex(".*?(\\d+)$").find(id)
                m?.groups?.get(1)?.value?.toIntOrNull()
            }.filterNotNull()
            var newIdNum = if (existingIds.isEmpty()) 1 else (existingIds.maxOrNull() ?: 0) + 1
            var newId = "item$newIdNum"
            // ensure unique string id
            while (b.manifest.any { it.id == newId }) {
                newIdNum++
                newId = "item$newIdNum"
            }

            val newHref = candidate
            val newManifest = ManifestItem(newId, newHref, "application/xhtml+xml")
            // add to manifest (leave other manifest items intact)
            b.manifest.add(newManifest)
            // add to spine at end
            b.spine.add(SpineItem(newId))
            // add to chapters list (title from file)
            val newTitle = loadTitleFromXhtml(newFile) ?: "New Chapter"
            b.chapters.add(Chapter(title = newTitle, href = newHref, file = newFile))

            withContext(Dispatchers.Main) {
                refreshChapterList()
                Toast.makeText(this@OrderActivity, "Chapter created: $candidate", Toast.LENGTH_SHORT).show()
            }
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

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // create backup of original OPF (if available)
                rawOpfText?.let { raw ->
                    createOpfBackup(opf, raw)
                }

                // build new OPF text keeping original metadata & package attributes if present
                val packageAttrs = opfPackageAttrsRaw ?: " xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"bookid\""
                val metadataBlock = opfMetadataRaw ?: buildMinimalMetadata(b)

                val sb = StringBuilder()
                sb.append("""<?xml version="1.0" encoding="utf-8"?>""").append("\n")
                sb.append("<package").append(packageAttrs).append(">").append("\n")
                sb.append(metadataBlock).append("\n")
                // manifest: include all manifest items (we preserve original non-xhtml items as well)
                sb.append("<manifest>").append("\n")
                b.manifest.forEach { it ->
                    sb.append("  <item id=\"${escapeXml(it.id)}\" href=\"${escapeXml(it.href)}\" media-type=\"${escapeXml(it.mediaType)}\" />").append("\n")
                }
                sb.append("</manifest>").append("\n")
                // spine: reflect current spine order
                sb.append("<spine>").append("\n")
                b.spine.forEach { s ->
                    sb.append("  <itemref idref=\"${escapeXml(s.idref)}\"")
                    if (!s.linear) sb.append(" linear=\"no\"")
                    sb.append(" />").append("\n")
                }
                sb.append("</spine>").append("\n")
                sb.append("</package>").append("\n")

                val newOpfBytes = sb.toString().toByteArray(Charsets.UTF_8)

                // write into OPF file (overwrite)
                contentResolver.openOutputStream(opf.uri)?.use { out ->
                    out.write(newOpfBytes)
                    out.flush()
                } ?: throw Exception("openOutputStream returned null")

                // update local raw and metadata cache
                rawOpfText = String(newOpfBytes, Charsets.UTF_8)
                opfMetadataRaw = extractMetadataBlock(rawOpfText!!)
                opfPackageAttrsRaw = extractPackageAttrs(rawOpfText!!)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OrderActivity, "OPF updated and saved (backup created)", Toast.LENGTH_SHORT).show()
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

    // Extract metadata block raw (<metadata>...</metadata>) using regex (DOT_MATCHES_ALL)
    private fun extractMetadataBlock(opfText: String): String? {
        val regex = Regex("(?is)<metadata.*?>.*?</metadata>")
        return regex.find(opfText)?.value
    }

    // Extract attributes part inside <package ...> (group 1). Example: xmlns=... version=...
    private fun extractPackageAttrs(opfText: String): String? {
        val regex = Regex("(?is)<package([^>]*)>")
        val m = regex.find(opfText)
        return m?.groups?.get(1)?.value?.trim()
    }

    // Build a minimal metadata block if original not available
    private fun buildMinimalMetadata(b: EpubBook): String {
        val title = if (b.chapters.isNotEmpty()) b.chapters[0].title else "Untitled"
        return """
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title>$title</dc:title>
            </metadata>
        """.trimIndent()
    }

    // Create a backup file for OPF (opfFileName.bak, opfFileName.bak_1, etc.)
    private fun createOpfBackup(opf: DocumentFile, originalText: String) {
        try {
            val parent = opf.parentFile ?: return
            val baseName = opf.name ?: "content.opf"
            var bakName = "$baseName.bak"
            var idx = 0
            while (findFileByName(parent, bakName) != null) {
                idx++
                bakName = "$baseName.bak_$idx"
            }
            val bakFile = parent.createFile("application/octet-stream", bakName) ?: return
            contentResolver.openOutputStream(bakFile.uri)?.use { out ->
                out.write(originalText.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: Throwable) {
            // swallow backup errors silently (but don't crash the save)
        }
    }

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    }

    // Convenience wrapper to parse OPF from InputStream (we rely on existing parseOpf function if available,
    // but provide fallback simple parser here if not)
    private fun parseOpf(input: ByteArrayInputStream): Pair<List<ManifestItem>, List<SpineItem>> {
        // If there is a global parseOpf (from EpubModels), use it. Otherwise fallback to local implementation.
        return try {
            // try to call top-level parseOpf(InputStream) (defined in EpubModels.kt)
            parseOpf(input as java.io.InputStream)
        } catch (t: Throwable) {
            // fallback: very simple XMLPull parser for items/itemref
            val manifest = ArrayList<ManifestItem>()
            val spine = ArrayList<SpineItem>()
            try {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(input, "utf-8")
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        val name = parser.name
                        if (name.equals("item", ignoreCase = true)) {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            manifest.add(ManifestItem(id, href, mediaType))
                        } else if (name.equals("itemref", ignoreCase = true)) {
                            val idref = parser.getAttributeValue(null, "idref") ?: ""
                            val linear = parser.getAttributeValue(null, "linear")?.let { it != "no" } ?: true
                            spine.add(SpineItem(idref, linear))
                        }
                    }
                    event = parser.next()
                }
            } catch (_: Throwable) { }
            Pair(manifest, spine)
        }
    }
}
