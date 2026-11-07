package vn.vltelecom

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class MxCoordinatesTest {
    @Test fun generatedCoordinatesBelongOnlyToTheRowAndSurviveRoundTrip(){
        val report=IncidentReport()
        val group=report.groups.first{IncidentRules.isMx(it)}
        val first=MxCoordinates(12.6016,108.1374,"Ảnh")
        val second=MxCoordinates(12.6021,108.1369,"ocr")
        listOf(first,second).forEachIndexed{index,point->
            group.images.add(IncidentImage("$index.jpg",100,100,point.captionWithoutCoordinates(point.description())))
            assertTrue(MxCoordinates.applyOnceToImage(group,index,point))
        }
        val restored=IncidentReport.parse(report.json())
        val mx=restored.groups.first{IncidentRules.isMx(it)}
        assertEquals(first.description()+"\n"+second.description(),mx.pairNotes.single())
        assertTrue(mx.images.all{it.caption.isEmpty()})
        assertEquals(first.description(),restored.closureLocation)
        assertEquals(second.description(),restored.closureLocation2)
    }
    @Test fun captionCleanupKeepsOtherNotesAndOnlyRemovesTheMatchingWholeGpsLine(){
        val point=MxCoordinates(12.640989,108.017517,"Ảnh")
        assertEquals("Ghi chú của tôi",point.captionWithoutCoordinates("Ghi chú của tôi\n${point.description()}"))
        assertEquals("",point.captionWithoutCoordinates("Lat: 12.640989 | Long: 108.017517"))
        val other="Tọa độ MX: 12.640988, 108.017508"
        assertEquals(other,point.captionWithoutCoordinates(other))
        val manual="Kiểm tra ${point.description()} sau khi nối"
        assertEquals(manual,point.captionWithoutCoordinates(manual))
    }
    @Test fun oldGeneratedCaptionIsCleanedOnceWithoutChangingAnEditedRow(){
        val point=MxCoordinates(12.640989,108.017517,"Ảnh")
        val report=IncidentReport()
        val group=report.groups.first{IncidentRules.isMx(it)}
        val metadata=JSONObject(point.metadata()).put("mxCoordinateDescriptionApplied",true).toString()
        group.images.add(IncidentImage("mx.jpg",100,100,"Ghi chú\n${point.description()}",metadata=metadata))
        group.pairNotes.add("Hàng ảnh đã sửa")
        val restored=IncidentReport.parse(report.json())
        val mx=restored.groups.first{IncidentRules.isMx(it)}
        assertEquals("Ghi chú",mx.images.single().caption)
        assertEquals("Hàng ảnh đã sửa",mx.pairNotes.single())
        mx.images[0]=mx.images[0].copy(caption=point.description()) // Explicit later edit remains the user's choice.
        val again=IncidentReport.parse(restored.json()).groups.first{IncidentRules.isMx(it)}
        assertEquals(point.description(),again.images.single().caption)
    }
    @Test fun unmarkedOrExplicitlyPreservedLegacyCaptionsAreNotErased(){
        val point=MxCoordinates(12.6016,108.1374,"ocr")
        val original=IncidentImage("a",10,10,point.description(),metadata=point.metadata())
        assertEquals(original,MxCoordinates.cleanAutomaticCaption(original))
        val group=IncidentGroup("Măng xông (MX)",images=mutableListOf(original))
        assertTrue(MxCoordinates.applyOnceToImage(group,0,point))
        assertTrue(JSONObject(group.images[0].metadata).getBoolean("mxLegacyCaptionPreserved"))
        assertEquals(point.description(),MxCoordinates.cleanAutomaticCaption(group.images[0]).caption)
    }
    @Test fun existingCoordinateWithDifferentPrecisionIsNotInsertedAgain(){
        val note="Kiểm tra\nTọa độ MX: 12.6016,108.1374"
        assertEquals(note,IncidentRules.mxDescription(note,12.601600,108.137400))
    }
    @Test fun validatesServerCoordinatesWithoutParsingPhotoText(){
        assertNull(MxCoordinates.fromMetadata("""{"lat":91,"lon":108}"""))
        assertNull(MxCoordinates.fromMetadata("""{"lat":12,"lon":181}"""))
        assertNull(MxCoordinates.fromMetadata("""{"lat":12}"""))
        assertNull(MxCoordinates.fromMetadata("""{"lat":"NaN","lon":108}"""))
        assertEquals(MxCoordinates(13.1721,108.2203,"ocr"),MxCoordinates.fromMetadata("""{"lat":13.1721,"lon":108.2203,"gpsSource":"ocr"}"""))
    }
    @Test fun readsNestedServerGpsAndKeepsValidFlatCoordinatesAuthoritative(){
        assertEquals(MxCoordinates(12.700209,108.327232,"ocr"),MxCoordinates.fromMetadata("""{"gps":{"lat":12.700209,"lon":108.327232,"source":"ocr"}}"""))
        assertEquals(MxCoordinates(12.8,108.2,"EXIF"),MxCoordinates.fromMetadata("""{"lat":12.8,"lon":108.2,"gpsSource":"EXIF","gps":{"lat":12.7,"lon":108.1,"source":"ocr"}}"""))
        assertEquals(MxCoordinates(12.7,108.1,"Ảnh"),MxCoordinates.fromMetadata("""{"lat":91,"lon":108,"gps":{"lat":12.7,"lon":108.1}}"""))
        assertNull(MxCoordinates.fromMetadata("""{"gps":{"lat":12.7}}"""))
    }
    @Test fun automaticDescriptionIsWrittenOnceAndPreservesManualEdits(){
        val point=MxCoordinates(12.6409882,108.0175083,"Ảnh")
        val group=IncidentGroup("Măng xông (MX)",images=mutableListOf(IncidentImage("a.jpg",10,10,metadata="""{"photographer":"A"}""")),pairNotes=mutableListOf("Ghi chú của tôi"))
        assertTrue(MxCoordinates.applyOnceToImage(group,0,point))
        assertEquals("Ghi chú của tôi\nTọa độ MX: 12.640988, 108.017508",group.pairNotes.single())
        assertEquals("A",JSONObject(group.images.single().metadata).getString("photographer"))
        assertFalse(MxCoordinates.applyOnceToImage(group,0,point))
        group.pairNotes[0]="Tọa độ đã kiểm tra thủ công"
        val stored=group.images.single().metadata
        assertFalse(MxCoordinates.applyOnceToImage(group,0,MxCoordinates(12.6,108.0,"OCR")))
        assertEquals("Tọa độ đã kiểm tra thủ công",group.pairNotes.single())
        assertEquals(stored,group.images.single().metadata)
    }
    @Test fun automaticDescriptionDoesNotReappearAfterUserClearsAndReloadsReport(){
        val report=IncidentReport()
        val point=MxCoordinates(12.6016,108.1374,"OCR")
        val group=report.groups.first{IncidentRules.isMx(it)}
        group.images.add(IncidentImage("a.jpg",10,10))
        assertTrue(MxCoordinates.applyOnceToImage(group,0,point))
        group.pairNotes[0]=""
        val restored=IncidentReport.parse(report.json()).groups.first{IncidentRules.isMx(it)}
        assertFalse(MxCoordinates.applyOnceToImage(restored,0,point))
        assertEquals("",restored.pairNotes.single())
        assertEquals(point,MxCoordinates.fromMetadata(restored.images.single().metadata))
    }
    @Test fun eachPhotoContributesOnlyOnceToItsOwnRow(){
        val a=MxCoordinates(12.6016,108.1374,"OCR")
        val b=MxCoordinates(12.6021,108.1369,"OCR")
        val group=IncidentGroup("Măng xông (MX)",images=mutableListOf(IncidentImage("a",10,10),IncidentImage("b",10,10),IncidentImage("c",10,10)),pairNotes=mutableListOf("Hàng 1","Hàng 2"))
        assertTrue(MxCoordinates.applyOnceToImage(group,0,a))
        assertTrue(MxCoordinates.applyOnceToImage(group,1,b))
        assertTrue(MxCoordinates.applyOnceToImage(group,2,a))
        assertEquals("Hàng 1\n${a.description()}\n${b.description()}",group.pairNotes[0])
        assertEquals("Hàng 2\n${a.description()}",group.pairNotes[1])
        assertFalse(MxCoordinates.applyOnceToImage(group,1,b))
        assertEquals("Hàng 1\n${a.description()}\n${b.description()}",group.pairNotes[0])
        assertFalse(MxCoordinates.applyOnceToImage(group,3,a))
    }
    @Test fun oldDescriptionOrSharedCoordinatesDoNotCreateDuplicateLines(){
        val point=MxCoordinates(12.6016,108.1374,"OCR")
        val group=IncidentGroup("Măng xông (MX)",images=mutableListOf(IncidentImage("a",10,10),IncidentImage("b",10,10)),pairNotes=mutableListOf("MX\n${point.description()}"))
        assertTrue(MxCoordinates.applyOnceToImage(group,0,point))
        assertTrue(MxCoordinates.applyOnceToImage(group,1,point))
        assertEquals("MX\n${point.description()}",group.pairNotes.single())
        assertTrue(group.images.all{JSONObject(it.metadata).getBoolean("mxCoordinateDescriptionApplied")})
    }
    @Test fun wrappingMultilineNoteIsIdempotentWithoutDeduplicatingManualText(){
        val point=MxCoordinates(12.6016,108.1374,"OCR")
        val note="Kiểm tra\nKiểm tra\n${point.description()}\n${point.description()}"
        val once=IncidentRules.mxDescription(note,point.lat,point.lon)
        assertEquals("Kiểm tra\nKiểm tra\n${point.description()}",once)
        assertEquals(once,IncidentRules.mxDescription(once,point.lat,point.lon))
    }
    @Test fun explicitCorrectionStillWorksAfterAutomaticFill(){
        val old=MxCoordinates(12.7,108.1,"EXIF")
        val next=MxCoordinates(12.8,108.2,"OCR")
        val group=IncidentGroup("Măng xông (MX)",images=mutableListOf(IncidentImage("a",10,10)))
        assertTrue(MxCoordinates.applyOnceToImage(group,0,old))
        MxCoordinates.applyToImage(group,0,next)
        assertEquals(next.description(),group.pairNotes.single())
        assertEquals(next,MxCoordinates.fromMetadata(group.images.single().metadata))
        assertFalse(MxCoordinates.applyOnceToImage(group,0,old))
        assertEquals(next.description(),group.pairNotes.single())
    }
    @Test fun legacyPhotoGpsBackfillsPreviouslyEmptySummaryCells(){
        val report=IncidentReport()
        val group=report.groups.first{IncidentRules.isMx(it)}
        group.images.add(IncidentImage("first.jpg",320,240,metadata=MxCoordinates(13.1721,108.2203,"OCR").metadata()))
        group.images.add(IncidentImage("second.jpg",320,240,metadata=MxCoordinates(12.70048,108.326998,"EXIF").metadata()))
        val legacy=JSONObject(report.json()).apply{remove("closureLocation");remove("closureLocation2")}
        val restored=IncidentReport.parse(legacy.toString())
        assertEquals("Tọa độ MX: 13.172100, 108.220300",restored.closureLocation)
        assertEquals("Tọa độ MX: 12.700480, 108.326998",restored.closureLocation2)
    }
    @Test fun mxPhotoCoordinatesFillAndPersistTheTwoSummaryLocationCells(){
        val report=IncidentReport()
        val first=MxCoordinates(13.1721,108.2203,"OCR")
        val second=MxCoordinates(12.70048,108.326998,"OCR")
        report.setMxSummaryLocation(0,first)
        report.setMxSummaryLocation(1,second)
        assertEquals(first.description(),report.closureLocation)
        assertEquals(second.description(),report.closureLocation2)
        val restored=IncidentReport.parse(report.json())
        assertEquals(first.description(),restored.closureLocation)
        assertEquals(second.description(),restored.closureLocation2)
    }
    @Test fun correctingOnePhotoPreservesSiblingCoordinatesAndAttribution(){
        val old=MxCoordinates(12.7,108.1,"EXIF")
        val next=MxCoordinates(12.8,108.2,"OCR")
        val metadata=old.metadata("""{"photographer":"A","uploadedByName":"B","serverPhotoId":"abc"}""")
        val group=IncidentGroup("Măng xông (MX)",images=mutableListOf(IncidentImage("a",10,10,metadata=metadata),IncidentImage("b",10,10,metadata=old.metadata())),pairNotes=mutableListOf("Măng xông số 1\n${old.description()}"))
        MxCoordinates.applyToImage(group,0,next)
        assertEquals("Măng xông số 1\n${old.description()}\n${next.description()}",group.pairNotes[0])
        val stored=JSONObject(group.images[0].metadata)
        assertEquals("A",stored.getString("photographer"));assertEquals("B",stored.getString("uploadedByName"));assertEquals("abc",stored.getString("serverPhotoId"))
        MxCoordinates.applyToImage(group,1,next)
        assertEquals("Măng xông số 1\n${next.description()}",group.pairNotes[0])
        assertEquals(old, MxCoordinates.fromMetadata(metadata))
    }
    @Test fun confirmedCoordinatesSurviveSaveSyncAndExcelForBothReportTypes(){
        val directory=kotlin.io.path.createTempDirectory("mx-coordinates").toFile()
        try{
            val image=File(directory,"mx.jpg").apply{writeBytes(byteArrayOf(1,2,3))}
            listOf(IncidentReport(),IncidentReport.relocation()).forEachIndexed{index,report->
                val group=report.groups.first{IncidentRules.isMx(it)}
                group.images.add(IncidentImage(image.path,320,240,metadata="""{"photographer":"Kỹ thuật"}"""))
                val point=MxCoordinates(12.654321,108.123456,"OCR")
                MxCoordinates.applyToImage(group,0,point)
                val restored=IncidentReport.parse(report.json())
                val mx=restored.groups.first{IncidentRules.isMx(it)}
                assertEquals(point,MxCoordinates.fromMetadata(mx.images.single().metadata))
                assertEquals(point.description(),mx.pairNotes.single())
                val excel=File(directory,"report-$index.xlsx")
                IncidentExport.xlsx(excel,"MX",restored)
                ZipFile(excel).use{zip->
                    val sheet=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).reader().readText()
                    assertTrue(sheet.contains(point.description()))
                }
            }
        }finally{directory.listFiles()?.forEach{it.delete()};directory.delete()}
    }
}
