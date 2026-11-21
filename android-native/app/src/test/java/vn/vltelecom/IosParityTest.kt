package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class IosParityTest {
 @Test fun excelContainsWholeLandscapeAndPortraitImages(){
  val folder=kotlin.io.path.createTempDirectory("image-fit").toFile()
  try{val data=byteArrayOf(1,2,3);val img=File(folder,"a.jpg").apply{writeBytes(data)};val r=IncidentReport();r.groups[0].images.add(IncidentImage(img.path,1600,900));r.groups[0].images.add(IncidentImage(img.path,600,1600));val file=File(folder,"a.xlsx");IncidentExport.xlsx(file,"A",r)
   ZipFile(file).use{z->val drawing=z.getInputStream(z.getEntry("xl/drawings/drawing1.xml")).reader().readText();assertFalse(drawing.contains("srcRect"));val ext=Regex("<xdr:ext cx=\"(\\d+)\" cy=\"(\\d+)\"").findAll(drawing).map{it.groupValues[1].toDouble() to it.groupValues[2].toDouble()}.toList();assertEquals(2,ext.size);assertEquals(1600.0/900,ext[0].first/ext[0].second,0.00001);assertEquals(600.0/1600,ext[1].first/ext[1].second,0.00001);ext.forEach{assertTrue(it.first<=430*9525&&it.second<=544*9525)};assertArrayEquals(data,z.getInputStream(z.getEntry("xl/media/image1.jpg")).readBytes())}
  }finally{folder.deleteRecursively()}
 }
 @Test fun constructionIsBeforeMeasurementWithoutChangingStoredIndices(){
  val r=IncidentReport();val before=r.groups.map{it.name};IncidentRules.prepare(r)
  val display=IncidentRules.ordered(r)
  assertEquals(display.indexOfFirst{it.value.name=="Kết quả đo kiểm"}-1,display.indexOfFirst{it.value.name=="Trong thi công"})
  display.forEach{assertSame(r.groups[it.index],it.value)}
  assertEquals(before,r.groups.map{it.name});assertTrue(IncidentRules.materialGroup(r.groups.first{it.name=="Trong thi công"}))
 }
 @Test fun capturedNotesAppendToTheirPhotoPairOnly(){
  val g=IncidentGroup("Hiện trường sự cố")
  g.images.add(IncidentImage("a",1,1));IncidentRules.addNote(g,"Néo mới")
  g.images.add(IncidentImage("b",1,1));IncidentRules.addNote(g,"Néo mới\nTreo cáp")
  assertEquals("Néo mới\nTreo cáp",g.pairNotes[0])
  g.images.add(IncidentImage("c",1,1));IncidentRules.addNote(g,"Hoàn thành")
  assertEquals("Hoàn thành",g.pairNotes[1]);assertEquals("Néo mới\nTreo cáp",g.pairNotes[0])
 }
 @Test fun relocationExcelHasNoTimesAndUsesNewAndRecoveredCableSeparately(){
  val folder=kotlin.io.path.createTempDirectory("parity-test").toFile()
  try{
   val r=IncidentReport.relocation();r.cable="12FO";r.relocationLength="50";r.received="01/01/2026 08:00";r.restored="01/01/2026 10:00";r.solution="hàn 2mx";r.cause="Điện lực yêu cầu";IncidentRules.prepare(r)
   r.newCableEnabled=true;r.start="3067";r.end="2917"
   val img=File(folder,"a.jpg").apply{writeBytes(byteArrayOf(1,2))}
   r.groups.forEach{it.images.add(IncidentImage(img.path,600,800))}
   val file=File(folder,"a.xlsx");IncidentExport.xlsx(file,"Di dời A",r)
   ZipFile(file).use{zip->val sheet=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).reader().readText()
    assertFalse(sheet.contains("Thời gian bắt đầu"));assertFalse(sheet.contains("Thời gian kết thúc"));assertFalse(sheet.contains(r.received))
    assertTrue(sheet.contains("Thu hồi kéo lại 50m 12FO, hàn 2mx"));assertTrue(sheet.contains("150m cáp 12FO"));assertTrue(sheet.contains(r.cause))
    assertTrue(sheet.indexOf("Hình ảnh thi công")<sheet.indexOf("Kết quả đo kiểm"))
   }
  }finally{folder.deleteRecursively()}
 }
}
