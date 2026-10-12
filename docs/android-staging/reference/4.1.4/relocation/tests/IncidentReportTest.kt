package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class IncidentReportTest {
    @Test fun addsSixAndEightFiberCableTypesForCableAndSplice(){
        assertEquals(listOf("2FO","4FO","6FO","8FO","12FO","24FO","48FO","96FO"),IncidentRules.cableTypes)
        listOf("6FO","8FO").forEach{type->
            val r=IncidentReport(start="100",end="150",cable=type)
            r.materials.add(IncidentMaterial("Măng xông",type=type,quantity="1",unit="cái"))
            val warnings=IncidentRules.warnings(r)
            assertFalse(warnings.any{it.contains("Chủng loại cáp chưa hợp lệ")||it.startsWith("Vật tư 1:")})
            assertEquals("50m cáp $type, 1 măng xông $type",IncidentRules.summary(r))
        }
    }
    @Test fun cableSummaryAndRecoveryUseEnteredValues(){
        val r=IncidentReport(start="3067",end="2777",cable="24FO")
        r.materials.add(IncidentMaterial("Néo",quantity="4",unit="bộ"));r.materials.add(IncidentMaterial("Treo",quantity="1",unit="bộ"))
        assertEquals(290.0,IncidentRules.length(r)!!,0.001);assertEquals("290m cáp 24FO, 4 néo, 1 treo",IncidentRules.summary(r))
        r.start="2777";r.end="3067";assertEquals(290.0,IncidentRules.length(r)!!,0.001)
        r.recovery=true;r.recoveryLength="290";r.recoveryCable="24FO"
        assertEquals("",IncidentRules.solution(r));assertEquals("Không sử dụng cáp mới và vật tư",IncidentRules.summary(r));assertEquals(2,r.materials.size)
        r.recoveryLength="";assertTrue(IncidentRules.warnings(r).any{it.contains("số mét")})
    }
    @Test fun incidentCanRecoverAndPullNewCableInTheSameReport(){
        val r=IncidentReport(location="Đắk Lắk",reporter="Kỹ thuật",date="01/10/2026",received="01/10/2026 08:00",restored="01/10/2026 09:00",cause="Đứt cáp",solution="Hàn nối măng xông",recovery=true,recoveryLength="50",recoveryCable="12FO",cable="24FO",start="3067",end="2917")
        r.materials.add(IncidentMaterial("Néo",quantity="4",unit="bộ"))
        IncidentRules.splitCable(r);assertTrue(r.splitRelocation);assertFalse(r.newCableEnabled);assertNull(IncidentRules.length(r))
        r.newCableEnabled=true
        assertEquals(150.0,IncidentRules.length(r)!!,0.001)
        assertEquals("Hàn nối măng xông",IncidentRules.solution(r))
        assertEquals("150m cáp 24FO, 4 néo",IncidentRules.summary(r))
        assertFalse(IncidentRules.warnings(r).any{it.contains("cáp mới")||it.contains("hai đầu số")||it.contains("thu hồi")||it.startsWith("Vật tư 1:")})
        val dir=kotlin.io.path.createTempDirectory("incident-mixed-cable").toFile();val file=File(dir,"report.xlsx")
        try{IncidentExport.xlsx(file,"Sự cố",r);ZipFile(file).use{zip->val sheet=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).reader().readText();assertFalse(sheet.contains("Thu hồi kéo lại 50m 12FO"));assertTrue(sheet.contains("Hàn nối măng xông"));assertTrue(sheet.contains("150m cáp 24FO, 4 néo"))}}finally{dir.deleteRecursively()}
    }
    @Test fun incidentSolutionKeepsEnteredRecoveryTextWithoutAddingAnotherPrefix(){
        val entered="  Thu hồi kéo lại đoạn cáp cũ\nHàn nối măng xông  "
        val r=IncidentReport(solution=entered,recovery=true,recoveryLength="50",recoveryCable="12FO")
        assertEquals(entered,IncidentRules.solution(r))
        r.recoveryLength="100";r.recoveryCable="24FO"
        assertEquals(entered,IncidentRules.solution(r))
        val restored=IncidentReport.parse(r.json())
        assertEquals(entered,IncidentRules.solution(restored))
        val dir=kotlin.io.path.createTempDirectory("incident-entered-solution").toFile();val file=File(dir,"report.xlsx")
        try{IncidentExport.xlsx(file,"Sự cố",restored);ZipFile(file).use{zip->val sheet=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).reader().readText();assertTrue(sheet.contains("Thu hồi kéo lại đoạn cáp cũ"));assertTrue(sheet.contains("Hàn nối măng xông"));assertFalse(sheet.contains("Thu hồi kéo lại 100m 24FO"))}}finally{dir.deleteRecursively()}
    }
    @Test fun recoveryDataUsesSharedFormatAndReadsLegacyBooleanReports(){
        val legacy=IncidentReport.parse("""{"recovery":true,"recoveryLength":"50","recoveryCable":"12FO","cable":"24FO","start":"3067","end":"2917","materials":[],"groups":[]}""")
        IncidentRules.prepare(legacy)
        assertTrue(legacy.recovery);assertEquals("50",legacy.recoveryLength);assertFalse(legacy.newCableEnabled)
        legacy.newCableEnabled=true
        val json=org.json.JSONObject(legacy.json());val recovery=json.getJSONObject("recovery")
        assertTrue(recovery.getBoolean("enabled"));assertEquals("50",recovery.getString("length"));assertEquals("12FO",recovery.getString("cable"))
        val synced=IncidentReport.parse(json.toString());assertTrue(synced.recovery);assertEquals("50",synced.recoveryLength);assertTrue(synced.newCableEnabled)
        assertEquals(150.0,IncidentRules.length(synced)!!,0.001)
    }
    @Test fun compactExportEmbedsUnlimitedMaterialPhotosInPairs(){
        val dir=kotlin.io.path.createTempDirectory("incident-test").toFile();val f=File(dir,"report.xlsx");val photo=File(dir,"photo.jpg").apply{writeBytes(byteArrayOf(1,2,3))}
        try {
            val r=IncidentReport(start="3067",end="2777",cable="24FO")
            repeat(11){r.groups[3].images.add(IncidentImage(photo.path,320,180,"Ảnh $it"))};r.groups[3].pairNotes.addAll(listOf("Hàng đầu","Hàng hai"))
            IncidentExport.xlsx(f,"Sự cố & A",r)
            ZipFile(f).use{z->
                assertEquals(11,z.entries().asSequence().count{it.name.startsWith("xl/media/")})
                z.entries().asSequence().filter{it.name.endsWith(".xml")||it.name.endsWith(".rels")}.forEach{e->DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(z.getInputStream(e))}
                val sheet=z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).reader().readText();assertFalse(sheet.contains("CÁP SỬ DỤNG"));assertFalse(sheet.contains("VẬT TƯ SỬ DỤNG"));assertTrue(sheet.contains("290m cáp 24FO"));assertTrue(sheet.contains("Hàng hai"))
                listOf("C2:F2","I2:L2","O2:R2","U2:X2","C3:F3","I3:L3","O3:R3","U3:X3").forEach{assertTrue(sheet.contains("ref=\"$it\""))}
                assertTrue(sheet.contains("ht=\"130\""));assertTrue(sheet.contains("width=\"4.140625\""))
                val styles=z.getInputStream(z.getEntry("xl/styles.xml")).reader().readText();assertTrue(styles.contains("FF194B5C"));assertTrue(styles.contains("horizontal=\"center\""))
                assertTrue(styles.contains("FF000000"));assertTrue(styles.contains("borderId=\"1\""))
                assertFalse(sheet.contains("NỘI DUNG CẦN BỔ SUNG"));assertFalse(sheet.contains("Chưa có ảnh"));assertFalse(sheet.contains("Thiếu người báo cáo"));assertTrue(IncidentRules.warnings(r).isNotEmpty())
                val drawing=z.getInputStream(z.getEntry("xl/drawings/drawing1.xml")).reader().readText();val rows=Regex("<xdr:row>(\\d+)</xdr:row>").findAll(drawing).map{it.groupValues[1]}.toList();assertEquals(6,rows.distinct().size);assertEquals(rows[0],rows[1]);assertNotEquals(rows[1],rows[2])
            }
            r.recovery=true;r.recoveryLength="290";r.recoveryCable="24FO";IncidentExport.xlsx(f,"Sự cố",r)
            ZipFile(f).use{z->val sheet=z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).reader().readText();assertFalse(sheet.contains("Thu hồi kéo lại 290m 24FO"));assertFalse(sheet.contains("CÁP SỬ DỤNG"))}
        }finally{dir.deleteRecursively()}
    }
    @Test fun excelPlacesMeasurementImagesLastForIncidentAndRelocation(){
        val dir=kotlin.io.path.createTempDirectory("measurement-last").toFile();val photo=File(dir,"photo.jpg").apply{writeBytes(byteArrayOf(1,2,3))}
        try {
            listOf(IncidentReport(),IncidentReport.relocation()).forEachIndexed{index,r->
                IncidentRules.prepare(r);r.groups.forEach{it.images.add(IncidentImage(photo.path,100,100))}
                val file=File(dir,"report-$index.xlsx");IncidentExport.xlsx(file,"Rapport",r)
                ZipFile(file).use{zip->
                    val sheet=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).reader().readText()
                    assertTrue(sheet.lastIndexOf("Kết quả đo kiểm")>sheet.lastIndexOf("Măng xông (MX)"))
                    assertTrue(sheet.lastIndexOf("Kết quả đo kiểm")>sheet.lastIndexOf("Hình ảnh thi công"))
                    if(r.relocation)assertTrue(sheet.lastIndexOf("Kết quả đo kiểm")>sheet.lastIndexOf("Sau hoàn thành"))
                }
            }
        }finally{dir.deleteRecursively()}
    }
    @Test fun missingFieldsWarnWithoutLimitingImageGroups(){
        assertTrue(IncidentRules.causes.contains("Bị chặt trúng cáp"));assertEquals(6,IncidentRules.causes.size)
        val pending=IncidentReport(pendingPhoto="held.jpg");assertTrue(IncidentRules.warnings(pending).any{it.contains("chờ ghi GPS")})
        val r=IncidentReport();assertTrue(IncidentRules.warnings(r).size>5);assertNull(IncidentRules.number("-1"));assertNull(IncidentRules.number("abc"));assertEquals(2.5,IncidentRules.number("2,5")!!,0.001)
        assertFalse(IncidentRules.validDate("29/02/2026",false));assertTrue(IncidentRules.validDate("29/02/2028",false))
        repeat(3){r.groups[0].images.add(IncidentImage("missing",100,100))}
        assertFalse(IncidentRules.warnings(r).any{it.contains("quá 2")})
    }
}
