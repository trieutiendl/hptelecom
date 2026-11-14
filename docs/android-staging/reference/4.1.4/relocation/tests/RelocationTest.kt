package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class RelocationTest {
    @Test fun reuseAndNewCableShareTypeButKeepLengthsSeparate(){
        val r=IncidentReport.relocation();r.cable="12FO";r.relocationLength="50";r.solution="hàn 2mx, kéo 150m cáp mới"
        IncidentRules.splitCable(r);assertEquals("50",r.recoveryLength);assertFalse(r.newCableEnabled)
        r.newCableEnabled=true;r.start="3067";r.end="2917"
        assertEquals(150.0,IncidentRules.length(r)!!,0.0)
        assertEquals("150m cáp 12FO",IncidentRules.summary(r))
        assertEquals("Thu hồi kéo lại 50m 12FO, hàn 2mx, kéo 150m cáp mới",IncidentRules.solution(r))
        r.start="2917";r.end="3067";assertEquals(150.0,IncidentRules.length(r)!!,0.0)
        r.end="";assertNull(IncidentRules.length(r));assertTrue(IncidentRules.warnings(r).contains("Chiều dài di dời chưa hợp lệ"))
        r.newCableEnabled=false;assertEquals("",IncidentRules.summary(r));assertEquals("50",r.recoveryLength)
        IncidentRules.splitCable(r);assertEquals("50",r.recoveryLength)
        val old=IncidentReport.relocation();old.recovery=false;old.cable="24FO";old.relocationLength="180";IncidentRules.splitCable(old)
        assertTrue(old.newCableEnabled);assertEquals(180.0,IncidentRules.length(old)!!,0.0)
    }
    @Test fun relocationKeepsMaterialsWhenReusingCable(){
        val r=IncidentReport.relocation();r.cable="24FO";r.relocationLength="290";r.materials.add(IncidentMaterial("Néo",quantity="4",unit="bộ"))
        assertEquals("290m cáp 24FO (thu hồi, kéo lại), 4 néo",IncidentRules.summary(r))
        assertEquals(listOf("Trước di dời","Trong thi công","Sau hoàn thành","Vật tư","Măng xông (MX)"),r.groups.map{it.name})
        r.cable="";r.relocationLength="";r.materials.clear()
        assertFalse(IncidentRules.warnings(r).any{it.contains("cáp")||it.contains("nguyên nhân")||it.contains("khôi phục")})
    }
    @Test fun relocationExcelOmitsIncidentFieldsAndKeepsAllThreePhotoGroups(){
        val folder=kotlin.io.path.createTempDirectory("relocation-test").toFile()
        try {
            val img=File(folder,"photo.jpg").apply{writeBytes(byteArrayOf(1,2))};val file=File(folder,"test.xlsx")
            val r=IncidentReport.relocation();r.location="Đường A";r.reporter="Người thực hiện";r.solution="Chuyển cáp sang hàng trụ mới"
            r.cable="24FO";r.relocationLength="290";r.materials.add(IncidentMaterial("Néo",quantity="4",unit="bộ"))
            r.groups.forEach{g->repeat(3){g.images.add(IncidentImage(img.path,320,200))}}
            assertTrue(IncidentRules.warnings(r).isEmpty())
            IncidentExport.xlsx(file,"Di dời A",r)
            ZipFile(file).use{z->val sheet=z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).reader().readText()
                assertTrue(sheet.contains("BÁO CÁO DI DỜI"));assertTrue(sheet.contains("Nội dung di dời"));assertTrue(sheet.contains("4 néo"))
                listOf("Nguyên nhân","Khôi phục dịch vụ","Thời gian nhận","Kết quả đo kiểm").forEach{assertFalse(sheet.contains(it))}
                assertEquals(15,z.entries().asSequence().count{it.name.startsWith("xl/media/")})
            }
        }finally{folder.deleteRecursively()}
    }
}
