package es.zelliot.epubeditor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.regex.Pattern

/**
 * MetadataActivity
 * - Рекурсивно ищет метки metadata в OPF и <meta ...> в XHTML
 * - Отображает все найденные метки (label + EditText)
 * - Сохраняет изменения только при нажатии Save (создаёт .bak)
 *
 * UI: фон #0A0A0A, кнопки — серебристые прямоугольники с чёрным текстом
 */
class MetadataActivity : AppCompatActivity() {

    private val prefsName = "epub_prefs"
    private val PREF_KEY_TREE = "epub_tree_uri"

    private lateinit var container: LinearLayout
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
    private lateinit var tvInfo: TextView

    // overlay working indicator
    private lateinit var workingBox: TextView

    private var treeRoot: DocumentFile? = null
    private var opfFile: DocumentFile? = null
    private var rawOpfText: String? = null
    private var opfMetadataRaw: String? = null
    private var opfPackageAttrsRaw: String? = null

    // in-memory list of editable metadata items
    private val entries = mutableListOf<MetaEntry>()

    // Structure describing a metadata item to show & save
    private data class MetaEntry(
        val key: String,               // e.g. "dc:creator" or "meta[name=author]"
        val sourceType: SourceType,
        val sourceFile: DocumentFile?, // OPF -> opfFile ; XHTML -> file containing meta
        val originalMatch: String?,    // for XHTML: full matched meta tag to be replaced on save
        var value: String,             // editable value
        val extra: String? = null      // for XHTML: attribute name ("name" or "property"), for OPF: null
    )

    private enum class SourceType { OPF, XHTML }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_metadata)

        container = findViewById(R.id.ll_metadata_list)
        btnSave = findViewById(R.id.btn_save_metadata)
        btnCancel = findViewById(R.id.btn_cancel_metadata)
        tvInfo = findViewById(R.id.tv_metadata_info)
        workingBox = findViewById(R.id.tv_working)

        btnSave.setOnClickListener { onSaveClicked() }
        btnCancel.setOnClickListener { finish() }

        // restore SAF Uri
        val saved = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(PREF_KEY_TREE, null)
        if (saved == null) {
            Toast.makeText(this, "No EPUB folder selected. Open Settings.", Toast.LENGTH_LONG).show()
            tvInfo.text = "No folder selected. Go to Settings."
            return
        }

        lifecycleScope.launch {
            val uri = Uri.parse(saved)
            scanAllMetadata(uri)
        }
    }

    /**
     * Scan OPF metadata and XHTML files for meta tags.
     * Populates `entries` list and builds UI rows (on main thread).
     */
    private suspend fun scanAllMetadata(treeUri: Uri) = withContext(Dispatchers.IO) {
        showWorking(true)
        try {
            val root = DocumentFile.fromTreeUri(this@MetadataActivity, treeUri)
            treeRoot = root
            if (root == null) {
                withContext(Dispatchers.Main) {
                    tvInfo.text = "Selected folder not accessible"
                }
                return@withContext
            }

            // find OPF
            opfFile = findOpfFile(root)
            if (opfFile != null) {
                rawOpfText = contentResolver.openInputStream(opfFile!!.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                opfMetadataRaw = rawOpfText?.let { extractMetadataBlock(it) }
                opfPackageAttrsRaw = rawOpfText?.let { extractPackageAttrs(it) }

                // parse OPF metadata tags via XmlPullParser
                rawOpfText?.let { raw ->
                    val metaList = parseOpfMetadata(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
                    metaList.forEachIndexed { idx, pair ->
                        // pair.first = tagName (maybe with namespace), pair.second = value
                        entries.add(MetaEntry(key = pair.first, sourceType = SourceType.OPF, sourceFile = opfFile, originalMatch = null, value = pair.second))
                    }
                }
            }

            // scan for XHTML files and find <meta name="..." content="..."> or property="..." etc.
            val xhtmlFiles = mutableListOf<DocumentFile>()
            collectFilesRecursive(root, xhtmlFiles) // gather all files then filter
            xhtmlFiles.filter { it.name?.endsWith(".xhtml", true) == true || it.name?.endsWith(".html", true) == true || it.name?.endsWith(".htm", true) == true }
                .forEach { file ->
                    try {
                        val raw = contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                        // find meta tags <meta name="..." content="..."> and property
                        val metaRegex = Pattern.compile("(?i)<meta\\s+([^>]*?)>", Pattern.DOTALL)
                        val matcher = metaRegex.matcher(raw)
                        while (matcher.find()) {
                            val attrs = matcher.group(1) ?: continue
                            val nameMatch = Pattern.compile("(?i)\\bname\\s*=\\s*\"([^\"]+)\"").matcher(attrs)
                            val propMatch = Pattern.compile("(?i)\\bproperty\\s*=\\s*\"([^\"]+)\"").matcher(attrs)
                            val contentMatch = Pattern.compile("(?i)\\bcontent\\s*=\\s*\"([^\"]*)\"").matcher(attrs)
                            val key = when {
                                nameMatch.find() -> nameMatch.group(1)
                                propMatch.find() -> propMatch.group(1)
                                else -> null
                            }
                            val contentVal = if (contentMatch.find()) contentMatch.group(1) else null
                            if (key != null && contentVal != null) {
                                val fullMatch = matcher.group(0)
                                // Use key prefixed so viewing is clearer, e.g. meta[name=author]
                                val entryKey = "meta[${key}]"
                                entries.add(MetaEntry(key = entryKey, sourceType = SourceType.XHTML, sourceFile = file, originalMatch = fullMatch, value = contentVal, extra = key))
                            }
                        }
                    } catch (_: Throwable) {
                        // skip problematic files
                    }
                }

            // Now we have a flat list of entries; switch to main to render
            withContext(Dispatchers.Main) {
                tvInfo.text = "Found ${entries.size} metadata fields"
                buildUiFromEntries()
            }
        } finally {
            showWorking(false)
        }
    }

    private fun showWorking(show: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (show) {
                workingBox.visibility = View.VISIBLE
                // animate ellipsis
                lifecycleScope.launch {
                    var dots = 0
                    while (isActive && workingBox.visibility == View.VISIBLE) {
                        val base = "Working"
                        val dotsStr = ".".repeat(dots % 4)
                        workingBox.text = base + dotsStr
                        dots++
                        delay(400)
                    }
                }
            } else {
                workingBox.visibility = View.GONE
            }
        }
    }

    /**
     * Build UI rows from entries list.
     * For each MetaEntry create a row: label (TextView) + EditText with current value.
     * Store reference to EditText in the view tag so we can collect values on Save.
     */
    private fun buildUiFromEntries() {
        container.removeAllViews()
        entries.forEachIndexed { idx, e ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(4, 6, 4, 6)
            row.layoutParams = lp

            val tvKey = TextView(this)
            tvKey.text = e.key
            tvKey.setTextColor(resources.getColor(android.R.color.white))
            tvKey.textSize = 14f
            val tvLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
            tvKey.layoutParams = tvLp

            val edt = EditText(this)
            edt.setText(e.value)
            edt.setTextColor(resources.getColor(android.R.color.black))
            edt.setBackgroundColor(0xFFBFBFBF.toInt()) // silver bg
            edt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            edt.minLines = 1
            edt.maxLines = 4
            edt.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            val edLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 7f)
            edt.layoutParams = edLp

            // store index ref so we can write back
            edt.tag = idx

            row.addView(tvKey)
            row.addView(edt)

            container.addView(row)
        }
    }

    /**
     * On Save: collect values from EditTexts, update in-memory entries, then write changes:
     * - OPF: rebuild <metadata> block (preserving package attrs if present) and overwrite opf file (with .bak)
     * - XHTML: replace matched meta tag's content attribute with updated value and write back (with .bak)
     */
    private fun onSaveClicked() {
        lifecycleScope.launch(Dispatchers.IO) {
            showWorking(true)
            try {
                // collect current values from UI
                val updatedValues = mutableMapOf<Int, String>()
                // iterate container children, find EditTexts and their tag
                for (i in 0 until container.childCount) {
                    val row = container.getChildAt(i) as LinearLayout
                    // row children: [TextView, EditText]
                    if (row.childCount >= 2) {
                        val edt = row.getChildAt(1) as? EditText ?: continue
                        val idx = (edt.tag as? Int) ?: continue
                        updatedValues[idx] = edt.text.toString()
                    }
                }
                // apply to entries in memory
                updatedValues.forEach { (idx, v) ->
                    if (idx >= 0 && idx < entries.size) entries[idx].value = v
                }

                // Save OPF metadata first (gather opf entries)
                opfFile?.let { opf ->
                    // build metadata xml from entries that have sourceType OPF
                    val opfEntries = entries.withIndex().filter { it.value.sourceType == SourceType.OPF }.map { it.value }
                    if (opfEntries.isNotEmpty()) {
                        // create backup
                        rawOpfText?.let { createBackupFile(opf, it) }
                        // generate metadata block string
                        val sb = StringBuilder()
                        // keep xmlns:dc in metadata block to be friendly
                        sb.append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">").append("\n")
                        opfEntries.forEach { me ->
                            // tag might be like "dc:creator" or "dc:title" or others; write as-is
                            val tagName = me.key
                            val safeVal = escapeXml(me.value)
                            sb.append("  <$tagName>$safeVal</$tagName>").append("\n")
                        }
                        sb.append("</metadata>").append("\n")

                        // rebuild full OPF: replace metadata block if present, otherwise inject after package open
                        val original = rawOpfText ?: ""
                        val newOpf = if (opfMetadataRaw != null) {
                            // replace old metadata with sb content
                            original.replaceFirst(Regex("(?is)<metadata.*?>.*?</metadata>"), sb.toString())
                        } else {
                            // try to place after <package ...>
                            original.replaceFirst(Regex("(?is)<package([^>]*)>"), "<package$1>\n${sb.toString()}")
                        }

                        // write new OPF
                        try {
                            contentResolver.openOutputStream(opf.uri)?.use { out ->
                                out.write(newOpf.toByteArray(Charsets.UTF_8))
                            }
                            // update cached raw
                            rawOpfText = newOpf
                            opfMetadataRaw = extractMetadataBlock(newOpf)
                        } catch (t: Throwable) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MetadataActivity, "Failed to save OPF: ${t.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                // Save XHTML meta updates
                val xhtmlGroups = entries.withIndex().filter { it.value.sourceType == SourceType.XHTML }.groupBy { it.value.sourceFile }
                xhtmlGroups.forEach { (file, list) ->
                    if (file == null) return@forEach
                    try {
                        val raw = contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                        // create backup before writing
                        createBackupFile(file, raw)

                        var updatedRaw = raw
                        // for each meta entry in this file, replace content="..." within the matched meta tag
                        list.forEach { pair ->
                            val me = pair.value
                            val originalTag = me.originalMatch ?: return@forEach
                            val keyName = me.extra ?: return@forEach
                            // Build regex to find meta tag with this key and replace content attribute
                            // Will match name="keyName" or property="keyName" (case-insensitive)
                            val metaPattern = Pattern.compile("(?i)(<meta\\s+[^>]*?(?:name|property)\\s*=\\s*\"${Pattern.quote(keyName)}\"[^>]*>)", Pattern.DOTALL)
                            val m = metaPattern.matcher(updatedRaw)
                            var found = false
                            val sb = StringBuffer()
                            while (m.find()) {
                                val full = m.group(1)
                                // replace or set content attribute inside full
                                val contentAttrPattern = Pattern.compile("(?i)\\bcontent\\s*=\\s*\"([^\"]*)\"")
                                val cm = contentAttrPattern.matcher(full)
                                val replacementTag = if (cm.find()) {
                                    // replace existing content
                                    full.replaceFirst(contentAttrPattern.toRegex(), "content=\"${escapeXml(me.value)}\"")
                                } else {
                                    // insert content before closing '>'
                                    full.replaceFirst(Regex("\\s*>$"), " content=\"${escapeXml(me.value)}\">")
                                }
                                m.appendReplacement(sb, MatcherQuote(replacementTag))
                                found = true
                            }
                            m.appendTail(sb)
                            if (found) {
                                updatedRaw = sb.toString()
                            } else {
                                // fallback: replace by matching originalMatch directly
                                if (updatedRaw.contains(originalTag)) {
                                    val replaced = originalTag.replaceFirst(Regex("(?i)\\bcontent\\s*=\\s*\"([^\"]*)\""), "content=\"${escapeXml(me.value)}\"")
                                    updatedRaw = updatedRaw.replaceFirst(originalTag, replaced)
                                }
                            }
                        }

                        // write updatedRaw back to file
                        contentResolver.openOutputStream(file.uri)?.use { out ->
                            out.write(updatedRaw.toByteArray(Charsets.UTF_8))
                        }

                    } catch (t: Throwable) {
                        // continue others, but inform user later
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MetadataActivity, "Metadata saved", Toast.LENGTH_SHORT).show()
                    tvInfo.text = "Saved metadata"
                }

            } finally {
                showWorking(false)
            }
        }
    }

    // create .bak backup next to file; if name exists, add suffix _1, _2 ...
    private fun createBackupFile(file: DocumentFile, content: String) {
        try {
            val parent = file.parentFile ?: return
            val base = file.name ?: "file"
            var bakName = "$base.bak"
            var idx = 0
            while (findFileByName(parent, bakName) != null) {
                idx++
                bakName = "$base.bak_$idx"
            }
            val bak = parent.createFile("application/octet-stream", bakName) ?: return
            contentResolver.openOutputStream(bak.uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
            // don't crash on backup failure
        }
    }

    // Helper: find OPF metadata block using regex
    private fun extractMetadataBlock(opfText: String): String? {
        val regex = Regex("(?is)<metadata.*?>.*?</metadata>")
        return regex.find(opfText)?.value
    }

    private fun extractPackageAttrs(opfText: String): String? {
        val regex = Regex("(?is)<package([^>]*)>")
        val m = regex.find(opfText)
        return m?.groups?.get(1)?.value?.trim()
    }

    // Parse OPF metadata children using XmlPullParser
    private fun parseOpfMetadata(input: ByteArrayInputStream): List<Pair<String, String>> {
        val res = mutableListOf<Pair<String, String>>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(input, "utf-8")
            var event = parser.eventType
            var insideMetadata = false
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    val name = parser.name
                    if (name.equals("metadata", true)) {
                        insideMetadata = true
                    } else if (insideMetadata) {
                        // read tag text
                        val tagName = name
                        val text = run {
                            parser.next()
                            if (parser.eventType == org.xmlpull.v1.XmlPullParser.TEXT) {
                                val t = parser.text ?: ""
                                t
                            } else ""
                        }
                        res.add(Pair(tagName, text))
                    }
                } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    if (parser.name.equals("metadata", true)) {
                        insideMetadata = false
                    }
                }
                event = parser.next()
            }
        } catch (_: Throwable) { }
        return res
    }

    // Recursive file collection (collect all files under root)
    private fun collectFilesRecursive(root: DocumentFile?, out: MutableList<DocumentFile>) {
        if (root == null) return
        root.listFiles().forEach { f ->
            if (f.isDirectory) {
                collectFilesRecursive(f, out)
            } else {
                out.add(f)
            }
        }
    }

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

    // Utility: escape XML entities for safe insertion
    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    // Helper to safely quote replacement string for regex appendReplacement
    private fun MatcherQuote(input: String): String {
        // From java.util.regex.Matcher.quoteReplacement but simplified:
        return input.replace("\\", "\\\\").replace("$", "\\$")
    }
}
