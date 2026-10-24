package vn.vltelecom

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class SharedReportExportTest {
    @Test fun thirdContributorPhotoRemainsValidAndExportedForBothReportTypes() {
        val dir=kotlin.io.path.createTempDirectory("shared-report-export").toFile()
        val photo=File(dir,"photo.jpg").apply{writeBytes(byteArrayOf(1,2,3))}
        val previousUser=ServerSession.user
        try {
            ServerSession.user=JSONObject().put("display_name","Người cùng làm")
            for(relocation in listOf(false,true)){
                val report=if(relocation)IncidentReport.relocation() else IncidentReport()
                report.location="Đắk Lắk";report.reporter="Người lập ban đầu";report.date="27/09/2026"
                report.received="27/09/2026 08:00";report.restored="27/09/2026 09:00"
                report.cause="Bị chặt trúng cáp";report.solution="Nối cáp";report.start="100";report.end="200";report.cable="24FO";report.relocationLength="100"
                report.groups.forEach{it.images.add(IncidentImage(photo.path,320,200,"Ảnh gốc"))}
                val group=report.groups.first()
                group.images.add(IncidentImage(photo.path,320,200,"Ảnh thứ hai"))
                group.images.add(IncidentImage(photo.path,320,200,"Ảnh người cùng làm thứ ba",metadata=JSONObject().put("uploadedBy","contributor").put("uploadedByName","Người cùng làm").toString()))
                assertTrue(IncidentRules.warnings(report).toString(),IncidentRules.warnings(report).isEmpty())
                report.completed=true
                val output=File(dir,"${if(relocation)"relocation" else "incident"}.xlsx")
                IncidentExport.xlsx(output,"Tuyến dùng chung",report)
                assertEquals("Người lập ban đầu",report.reporter)
                ZipFile(output).use{zip->
                    assertEquals(report.groups.sumOf{it.images.size},zip.entries().asSequence().count{it.name.startsWith("xl/media/")})
                    val sheet=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).reader().use{it.readText()}
                    assertTrue(sheet.contains("Ảnh người cùng làm thứ ba"))
                    assertTrue(sheet.contains("Người lập ban đầu"))
                    zip.entries().asSequence().filter{it.name.endsWith(".xml")||it.name.endsWith(".rels")}.forEach{entry->
                        zip.getInputStream(entry).use{DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)}
                    }
                }
            }
        } finally {ServerSession.user=previousUser;dir.deleteRecursively()}
    }

    @Test fun imageRoundTripPreservesAttributionAndGpsMetadata() {
        val metadata=JSONObject().put("uploadedBy","user-1").put("uploadedByName","Người chụp")
            .put("lat",12.3).put("lon",108.1).put("overlayLines",org.json.JSONArray(listOf("Tên tuyến","Ngày chụp: 27/09/2026")))
        val report=IncidentReport()
        report.groups.first().images.add(IncidentImage("photo.jpg",100,200,metadata=metadata.toString()))
        val roundTripped=IncidentReport.parse(report.json())
        val image=JSONObject(roundTripped.json()).getJSONArray("groups").getJSONObject(0).getJSONArray("images").getJSONObject(0)
        assertEquals("user-1",image.getString("uploadedBy"))
        assertEquals("Người chụp",image.getString("uploadedByName"))
        assertEquals(12.3,image.getDouble("lat"),0.0)
        assertEquals(108.1,image.getDouble("lon"),0.0)
        assertEquals(2,image.getJSONArray("overlayLines").length())
    }
}
