package vn.vltelecom

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exports {
    fun sheetName(name:String):String {
        val clean=name.map{if(it.code<32 || it in "\\/:*?[]") '_' else it}.joinToString("").trim().trim('\'')
        val short=clean.take(31).trimEnd {it.isHighSurrogate()}.trimEnd('\'')
        return if(short.isEmpty()) "Báo cáo" else if(short.equals("History",true)) "History_" else short
    }

    fun xml(s: String): String = s.filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun ZipOutputStream.entry(name: String, content: String) { putNextEntry(ZipEntry(name)); write(content.toByteArray(Charsets.UTF_8)); closeEntry() }
    private fun ZipOutputStream.binary(name: String, file: File) { putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(this) }; closeEntry() }
    fun kmz(file: File, title: String, input: List<Photo>) {
        val rows = input.sortedBy { it.seq }
        ZipOutputStream(file.outputStream()).use { zip ->
            val points = rows.filterIndexed { index,it -> index==0 || index==rows.lastIndex || it.note.contains("MX", true) || it.note.contains("DP", true) }.joinToString("") {
                "<Placemark><name>${xml(if(it===rows.first() || it===rows.last()) it.locationName else "${it.seq} • ${it.locationName}")}</name><description>${xml(it.note)}</description><styleUrl>#marker</styleUrl><Point><coordinates>${it.lon},${it.lat},0</coordinates></Point></Placemark>"
            }
            val line = if (rows.size >= 2) "<Placemark><name>${xml(title)}</name><styleUrl>#route</styleUrl><LineString><tessellate>1</tessellate><coordinates>${rows.joinToString(" ") { "${it.lon},${it.lat},0" }}</coordinates></LineString></Placemark>" else ""
            zip.entry("doc.kml", """<?xml version="1.0" encoding="UTF-8"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>${xml(title)}</name><Style id="route"><LineStyle><color>ff007fff</color><width>4</width></LineStyle></Style><Style id="marker"><IconStyle><color>ff00aaff</color><scale>1.2</scale></IconStyle></Style><Folder><name>${xml(title)}</name><open>1</open>$line$points</Folder></Document></kml>""")
        }
    }
    // OOXML package: embedded JPEGs and two-cell drawing anchors; no desktop-only AWT dependency.
    fun xlsx(file: File, title: String, input: List<Photo>) {
        val rows = input.sortedBy { it.seq }
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        fun textCell(col: String, row: Int, text: String) = "<c r=\"$col$row\" t=\"inlineStr\" s=\"1\"><is><t xml:space=\"preserve\">${xml(text)}</t></is></c>"
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.entry("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/></Types>""")
            zip.entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            zip.entry("xl/workbook.xml", """<workbook xmlns="$ns" xmlns:r="$rel"><sheets><sheet name="${xml(sheetName(title))}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            zip.entry("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$rel/styles" Target="styles.xml"/></Relationships>""")
            zip.entry("xl/styles.xml", """<styleSheet xmlns="$ns"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf xfId="0"/><xf xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            val data = rows.mapIndexed { i, p -> val r = i + 3
                "<row r=\"$r\" ht=\"150\" customHeight=\"1\"><c r=\"A$r\"><v>${p.seq}</v></c>${textCell("C",r,p.locationName)}<c r=\"D$r\"><v>${p.lat}</v></c><c r=\"E$r\"><v>${p.lon}</v></c>${textCell("F",r,p.note)}</row>"
            }.joinToString("")
            val headers = listOf("STT","Hình ảnh","Tên vị trí chụp hình","Latitude","Longitude","Ghi chú").mapIndexed { i, h -> textCell(('A'+i).toString(),2,h) }.joinToString("")
            zip.entry("xl/worksheets/sheet1.xml", """<worksheet xmlns="$ns" xmlns:r="$rel"><sheetViews><sheetView workbookViewId="0"><pane ySplit="2" topLeftCell="A3" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="1" width="8" customWidth="1"/><col min="2" max="2" width="34" customWidth="1"/><col min="3" max="3" width="30" customWidth="1"/><col min="4" max="5" width="22" customWidth="1"/><col min="6" max="6" width="42" customWidth="1"/></cols><sheetData><row r="1">${textCell("A",1,title)}</row><row r="2" ht="30" customHeight="1">$headers</row>$data</sheetData><autoFilter ref="A2:F${rows.size+2}"/><mergeCells count="1"><mergeCell ref="A1:F1"/></mergeCells><drawing r:id="rId1"/></worksheet>""")
            zip.entry("xl/worksheets/_rels/sheet1.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/drawing" Target="../drawings/drawing1.xml"/></Relationships>""")
            val anchors = rows.mapIndexed { i, p ->
                val n=i+1; val r=i+2
                """<xdr:twoCellAnchor editAs="oneCell"><xdr:from><xdr:col>1</xdr:col><xdr:colOff>38100</xdr:colOff><xdr:row>$r</xdr:row><xdr:rowOff>38100</xdr:rowOff></xdr:from><xdr:to><xdr:col>2</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${r+1}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="$n" name="Anh ${p.seq}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId$n"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:twoCellAnchor>"""
            }.joinToString("")
            zip.entry("xl/drawings/drawing1.xml", """<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="$rel">$anchors</xdr:wsDr>""")
            zip.entry("xl/drawings/_rels/drawing1.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${rows.indices.joinToString("") { "<Relationship Id=\"rId${it+1}\" Type=\"$rel/image\" Target=\"../media/image${it+1}.jpg\"/>" }}</Relationships>""")
            rows.forEachIndexed { i,p -> zip.binary("xl/media/image${i+1}.jpg", File(p.path)) }
        }
    }
}
