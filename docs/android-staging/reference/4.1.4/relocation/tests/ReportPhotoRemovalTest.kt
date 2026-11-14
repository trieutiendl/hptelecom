package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class ReportPhotoRemovalTest {
    private fun photo(id:String,lat:Double?=null)=IncidentImage(id,320,400,metadata=lat?.let{MxCoordinates(it,108.1,"ocr").metadata()}.orEmpty())
    @Test fun deletingFirstPhotoKeepsManualNotesWithTheirSurvivingRows(){
        val group=IncidentGroup("Vật tư",images=mutableListOf(photo("a"),photo("b"),photo("c"),photo("d")),pairNotes=mutableListOf("row one","row two"))
        ReportPhotoRemoval.remove(IncidentReport(),group,setOf(0))
        assertEquals(listOf("b","c","d"),group.images.map{it.path});assertEquals(listOf("row one\nrow two","row two"),group.pairNotes)
    }
    @Test fun deletingAllPhotosPreservesManualNotesAndClearsOnlyMatchingGeneratedGps(){
        val group=IncidentGroup("Măng xông (MX)",note="general",images=mutableListOf(photo("a",12.1),photo("b",12.2)),pairNotes=mutableListOf("repair completed\nTọa độ MX: 12.100000, 108.100000\nTọa độ MX: 12.200000, 108.100000"))
        val report=IncidentReport(groups=mutableListOf(group),closureLocation="Tọa độ MX: 12.100000, 108.100000",closureLocation2="manual location")
        ReportPhotoRemoval.remove(report,group,setOf(0,1))
        assertTrue(group.images.isEmpty());assertTrue(group.pairNotes.isEmpty());assertEquals("general\nrepair completed",group.note)
        assertEquals("",report.closureLocation);assertEquals("manual location",report.closureLocation2)
    }
    @Test fun sharedCoordinateIsKeptWhileAnotherPhotoStillUsesIt(){
        val gps="Tọa độ MX: 12.100000, 108.100000"
        val group=IncidentGroup("Măng xông (MX)",images=mutableListOf(photo("a",12.1),photo("b",12.1)),pairNotes=mutableListOf(gps))
        val report=IncidentReport(groups=mutableListOf(group),closureLocation=gps,closureLocation2=gps)
        ReportPhotoRemoval.remove(report,group,setOf(0))
        assertEquals(gps,group.pairNotes.single());assertEquals(gps,report.closureLocation);assertEquals("",report.closureLocation2)
    }
    @Test fun summaryMovesToNewFirstAndSecondPhotosAfterRemoval(){
        val group=IncidentGroup("Măng xông (MX)",images=mutableListOf(photo("a",12.1),photo("b",12.2),photo("c",12.3)))
        val report=IncidentReport(groups=mutableListOf(group));report.backfillMxSummaryLocations()
        ReportPhotoRemoval.remove(report,group,setOf(0))
        assertEquals(MxCoordinates(12.2,108.1,"ocr").description(),report.closureLocation)
        assertEquals(MxCoordinates(12.3,108.1,"ocr").description(),report.closureLocation2)
    }
    @Test fun deletingEntireRowDoesNotDiscardItsOnlyManualText(){
        val group=IncidentGroup("Vật tư",images=mutableListOf(photo("a"),photo("b"),photo("c")),pairNotes=mutableListOf("keep old inspection","new row"))
        ReportPhotoRemoval.remove(IncidentReport(),group,setOf(0,1))
        assertEquals("keep old inspection",group.note);assertEquals(listOf("new row"),group.pairNotes)
    }
    @Test fun unrelatedGpsLookingUserTextIsPreserved(){
        val group=IncidentGroup("Măng xông (MX)",images=mutableListOf(photo("a",12.1)),pairNotes=mutableListOf("Tọa độ MX: 13.100000, 108.100000\ncheck Tọa độ MX: 12.100000, 108.100000"))
        ReportPhotoRemoval.remove(IncidentReport(),group,setOf(0))
        assertEquals("Tọa độ MX: 13.100000, 108.100000\ncheck Tọa độ MX: 12.100000, 108.100000",group.note)
    }
    @Test fun shiftingRowsKeepsOnlyCoordinatesBelongingToEachNewPair(){
        fun gps(lat:Double)=MxCoordinates(lat,108.1,"ocr").description()
        val group=IncidentGroup("Măng xông (MX)",images=mutableListOf(photo("a",12.1),photo("b",12.2),photo("c",12.3),photo("d",12.4)),pairNotes=mutableListOf("first\n${gps(12.1)}\n${gps(12.2)}","second\n${gps(12.3)}\n${gps(12.4)}"))
        ReportPhotoRemoval.remove(IncidentReport(groups=mutableListOf(group)),group,setOf(0))
        assertEquals("first\n${gps(12.2)}\nsecond\n${gps(12.3)}",group.pairNotes[0])
        assertEquals("second\n${gps(12.4)}",group.pairNotes[1])
    }
    @Test fun manualRepeatedLinesAndBlankLinesAreNotReformatted(){
        val text="Inspect\n\nInspect\nkeep spacing"
        val group=IncidentGroup("Vật tư",images=mutableListOf(photo("a"),photo("b")),pairNotes=mutableListOf(text))
        ReportPhotoRemoval.remove(IncidentReport(),group,setOf(0))
        assertEquals(text,group.pairNotes.single())
        ReportPhotoRemoval.remove(IncidentReport(),group,setOf(0))
        assertEquals(text,group.note)
    }
}
