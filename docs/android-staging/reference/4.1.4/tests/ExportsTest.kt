package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class ExportsTest {
    @Test fun endpointsWithoutNotesUseLocationNamesAndSingletonIsNotDuplicated(){
        val f=File.createTempFile("endpoint", ".kmz")
        try {
            for(rows in listOf(listOf(photo(10,"Khác").copy(locationName="Cuối"),photo(2,"Khác").copy(locationName="Đầu")),listOf(photo(1,"MX")))) {
                Exports.kmz(f,"Tuyến",rows)
                ZipFile(f).use {z->
                    val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(z.getInputStream(z.getEntry("doc.kml")))
                    assertEquals(rows.size,doc.getElementsByTagName("Point").length)
                    val marks=doc.getElementsByTagName("Placemark")
                    val names=(0 until marks.length).map{marks.item(it) as org.w3c.dom.Element}.filter{it.getElementsByTagName("Point").length>0}.map{it.getElementsByTagName("name").item(0).textContent}
                    assertEquals(rows.sortedBy{it.seq}.map{it.locationName},names)
                }
            }
        }finally{f.delete()}
    }
    @Test fun sheetNamesAreSafeAndKeepVietnamese(){
        assertEquals("Tuyến & sự cố",Exports.sheetName("Tuyến & sự cố"))
        assertEquals("A_B_C_",Exports.sheetName("'A/B:C?'"))
        assertEquals(31,Exports.sheetName("a".repeat(40)).length)
    }
    private fun photo(seq:Int,note:String,path:String="unused")=Photo(jobId=1,seq=seq,locationName="Vị trí & <cột>",lat=10.0+seq,lon=106.0+seq,accuracy=5f,note=note,path=path,capturedAt=0)
    @Test fun kmzOrdersCoordinatesAndFiltersMarkers() {
        val f=File.createTempFile("route", ".kmz")
        try { Exports.kmz(f,"Tuyến & trạm",listOf(photo(3,"DP 3"),photo(1,"mx 1"),photo(2,"Thông thường")))
            ZipFile(f).use { z -> val xml=z.getInputStream(z.getEntry("doc.kml")).readBytes().toString(Charsets.UTF_8)
                val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
                assertEquals(3,doc.getElementsByTagName("Placemark").length)
                val folders=doc.getElementsByTagName("Folder")
                assertEquals(1,folders.length)
                val folder=folders.item(0) as org.w3c.dom.Element
                assertEquals("Tuyến & trạm",folder.getElementsByTagName("name").item(0).textContent)
                assertEquals(3,folder.getElementsByTagName("Placemark").length)
                assertTrue(xml.contains("107.0,11.0,0 108.0,12.0,0 109.0,13.0,0"))
                assertTrue(xml.contains("Vị trí &amp; &lt;cột&gt;"))
            }
        }finally{f.delete()}
    }
    @Test fun xlsxEmbedsImagesAndPreservesNumericOrder() {
        val img=File.createTempFile("image", ".jpg").apply{writeBytes(byteArrayOf(1,2,3))};val f=File.createTempFile("excel", ".xlsx")
        try {Exports.xlsx(f,"Công việc",listOf(photo(10,"DP",img.path),photo(2,"MX",img.path)))
            ZipFile(f).use {z ->
                val workbook=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(z.getInputStream(z.getEntry("xl/workbook.xml")))
                assertEquals("Công việc",workbook.getElementsByTagName("sheet").item(0).attributes.getNamedItem("name").nodeValue)
                z.entries().asSequence().filter{it.name.endsWith(".xml") || it.name.endsWith(".rels")}.forEach {DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(z.getInputStream(it))}
                assertArrayEquals(img.readBytes(),z.getInputStream(z.getEntry("xl/media/image1.jpg")).readBytes())
                val sheet=z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readBytes().toString(Charsets.UTF_8)
                assertTrue(sheet.indexOf("<v>2</v>")<sheet.indexOf("<v>10</v>"))
                assertNotNull(z.getEntry("xl/drawings/_rels/drawing1.xml.rels"))
            }
        }finally{img.delete();f.delete()}
    }
}
