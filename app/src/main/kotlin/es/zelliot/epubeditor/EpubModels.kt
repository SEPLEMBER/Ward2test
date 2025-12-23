package es.zelliot.epubeditor

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

data class ManifestItem(val id: String, val href: String, val mediaType: String)
data class SpineItem(val idref: String, val linear: Boolean = true)
data class Chapter(var title: String, var href: String, var file: DocumentFile?)
data class EpubBook(
    val opfFile: DocumentFile?,
    val baseTree: DocumentFile?,
    val manifest: MutableList<ManifestItem> = mutableListOf(),
    val spine: MutableList<SpineItem> = mutableListOf(),
    val chapters: MutableList<Chapter> = mutableListOf()
)

/**
 * Находит первый .opf файл рекурсивно в DocumentFile дереве.
 */
fun findOpfFile(root: DocumentFile?): DocumentFile? {
    if (root == null || !root.isDirectory) return null
    root.listFiles().forEach { f ->
        if (f.isDirectory) {
            val inner = findOpfFile(f)
            if (inner != null) return inner
        } else {
            val name = f.name ?: ""
            if (name.endsWith(".opf", ignoreCase = true)) return f
        }
    }
    return null
}

/**
 * Парсит OPF (manifest + spine). Упрощённо: собираем items и itemref.
 */
fun parseOpf(opfStream: InputStream): Pair<List<ManifestItem>, List<SpineItem>> {
    val manifest = mutableListOf<ManifestItem>()
    val spine = mutableListOf<SpineItem>()

    val factory = XmlPullParserFactory.newInstance()
    val parser = factory.newPullParser()
    parser.setInput(opfStream, "utf-8")

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
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
    return Pair(manifest, spine)
}

/**
 * Упрощённая генерация OPF: создаём минимальный OPF с manifest+spine.
 * Не претендует на сохранение полной метаинформации оригинала.
 */
fun generateOpfBytes(packageUniqueId: String = "bookid", title: String = "Untitled", manifest: List<ManifestItem>, spine: List<SpineItem>): ByteArray {
    val factory = XmlPullParserFactory.newInstance()
    val serializer: XmlSerializer = factory.newSerializer()
    val writer = java.io.StringWriter()
    serializer.setOutput(writer)
    serializer.startDocument("utf-8", true)
    serializer.text("\n")
    serializer.startTag("", "package")
    serializer.attribute("", "xmlns", "http://www.idpf.org/2007/opf")
    serializer.attribute("", "version", "2.0")
    serializer.attribute("", "unique-identifier", packageUniqueId)
    serializer.text("\n")

    // metadata (very small)
    serializer.startTag("", "metadata")
    serializer.attribute("", "xmlns:dc", "http://purl.org/dc/elements/1.1/")
    serializer.startTag("", "dc:title")
    serializer.text(title)
    serializer.endTag("", "dc:title")
    serializer.endTag("", "metadata")
    serializer.text("\n")

    // manifest
    serializer.startTag("", "manifest")
    manifest.forEach { it ->
        serializer.startTag("", "item")
        serializer.attribute("", "id", it.id)
        serializer.attribute("", "href", it.href)
        serializer.attribute("", "media-type", it.mediaType)
        serializer.endTag("", "item")
        serializer.text("\n")
    }
    serializer.endTag("", "manifest")
    serializer.text("\n")

    // spine
    serializer.startTag("", "spine")
    spine.forEach { s ->
        serializer.startTag("", "itemref")
        serializer.attribute("", "idref", s.idref)
        if (!s.linear) serializer.attribute("", "linear", "no")
        serializer.endTag("", "itemref")
        serializer.text("\n")
    }
    serializer.endTag("", "spine")
    serializer.text("\n")

    serializer.endTag("", "package")
    serializer.endDocument()
    return writer.toString().toByteArray(Charsets.UTF_8)
}
