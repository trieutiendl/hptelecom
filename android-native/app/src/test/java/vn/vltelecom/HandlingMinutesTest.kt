package vn.vltelecom
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
class HandlingMinutesTest {
 @Test fun handlesDatesAndMissingValues(){
  assertEquals(120L,IncidentRules.handlingMinutes("13/09/2026 08:00","13/09/2026 10:00"))
  assertEquals(35L,IncidentRules.handlingMinutes("13/09/2026 23:40","14/09/2026 00:15"))
  assertEquals(2940L,IncidentRules.handlingMinutes("13/09/2026 08:00","15/09/2026 09:00"))
  assertEquals(0L,IncidentRules.handlingMinutes("13/09/2026 08:00","13/09/2026 08:00"))
  assertNull(IncidentRules.handlingMinutes("","13/09/2026 08:00"))
  assertNull(IncidentRules.handlingMinutes("31/02/2026 08:00","13/09/2026 08:00"))
  assertNull(IncidentRules.handlingMinutes("13/09/2026 10:00","13/09/2026 08:00"))
 }
 @Test fun excelStoresTotalAsNumber(){
  val f=File.createTempFile("minutes", ".xlsx")
  try{IncidentExport.xlsx(f,"A",IncidentReport(received="13/09/2026 08:00",restored="13/09/2026 10:00"));ZipFile(f).use{z->val s=z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).reader().readText();assertTrue(s.contains("Tổng phút xử lý"));assertTrue(s.contains("<c r=\"U3\" s=\"3\" t=\"n\"><v>120</v>"))}}finally{f.delete()}
 }
}
