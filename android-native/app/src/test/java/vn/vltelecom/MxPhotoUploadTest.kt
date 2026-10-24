package vn.vltelecom

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class MxPhotoUploadTest {
 private fun payload(image:JSONObject,module:String="incident",name:String="Măng xông (MX)")=JSONObject().put("module",module)
  .put("report",JSONObject().put("groups",JSONArray().put(JSONObject().put("name",name).put("images",JSONArray().put(image)))))
 @Test fun cachedMxAssetWithNoCoordinatesNeedsOneEnrichedReceive(){
  val image=JSONObject().put("file","a".repeat(32))
  assertTrue(MxPhotoUpload.needsCoordinates(payload(image)))
  assertTrue(MxPhotoUpload.needsCoordinates(payload(image,"relocation")))
  assertFalse(MxPhotoUpload.needsCoordinates(payload(image,"cable")))
  assertFalse(MxPhotoUpload.needsCoordinates(payload(image,name="Hiện trường")))
 }
 @Test fun validServerGpsSkipsExtraReceiveAndInvalidGpsDoesNot(){
  assertFalse(MxPhotoUpload.needsCoordinates(payload(JSONObject().put("lat",13.1721).put("lon",108.2203))))
  assertFalse(MxPhotoUpload.needsCoordinates(payload(JSONObject().put("gps",JSONObject().put("lat",0.0).put("lon",0.0)))))
  assertTrue(MxPhotoUpload.needsCoordinates(payload(JSONObject().put("lat",13.1721))))
  assertTrue(MxPhotoUpload.needsCoordinates(payload(JSONObject().put("lat",91).put("lon",108))))
 }
 @Test fun originalJpegAndPngBytesAreCopiedWithoutRecompression(){
  val directory=kotlin.io.path.createTempDirectory("mx-upload").toFile()
  try{
   val samples=listOf(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xe1.toByte(),1,2,3,4,5),
    byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,10,20,30))
   for((i,bytes)in samples.withIndex()){
    val source=File(directory,"source$i").apply{writeBytes(bytes)};val target=File(directory,"target$i")
    assertTrue(MxPhotoUpload.copyOriginal(source,target));assertArrayEquals(bytes,target.readBytes())
    assertEquals(if(i==0)"jpg" else "png",MxPhotoUpload.extension(target))
   }
   val unsupported=File(directory,"other").apply{writeText("not a JPEG or PNG")}
   val target=File(directory,"unused")
   assertFalse(MxPhotoUpload.copyOriginal(unsupported,target));assertFalse(target.exists())
  }finally{directory.listFiles()?.forEach{it.delete()};directory.delete()}
 }
 @Test fun oversizedOriginalCannotBypassNasUploadLimit(){
  val directory=kotlin.io.path.createTempDirectory("mx-upload-limit").toFile()
  try{
   val source=File(directory,"large.jpg")
   java.io.RandomAccessFile(source,"rw").use{it.write(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()));it.setLength(MxPhotoUpload.maxBytes+1)}
   val target=File(directory,"unused.jpg")
   assertFalse(MxPhotoUpload.copyOriginal(source,target));assertFalse(target.exists())
  }finally{directory.listFiles()?.forEach{it.delete()};directory.delete()}
 }
 @Test fun originalPngHasCorrectExcelMediaTypeWithoutChangingEvidence(){
  val directory=kotlin.io.path.createTempDirectory("mx-png-export").toFile()
  try{
   val bytes=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1,2,3)
   val image=File(directory,"image.jpg").apply{writeBytes(bytes)}
   val report=IncidentReport();report.groups.first{IncidentRules.isMx(it)}.images.add(IncidentImage(image.path,320,240))
   val out=File(directory,"export.xlsx");IncidentExport.xlsx(out,"MX",report)
   ZipFile(out).use{zip->
    assertArrayEquals(bytes,zip.getInputStream(zip.getEntry("xl/media/image1.png")).readBytes())
    assertTrue(zip.getInputStream(zip.getEntry("[Content_Types].xml")).reader().readText().contains("Extension=\"png\" ContentType=\"image/png\""))
    assertTrue(zip.getInputStream(zip.getEntry("xl/drawings/_rels/drawing1.xml.rels")).reader().readText().contains("image1.png"))
   }
  }finally{directory.listFiles()?.forEach{it.delete()};directory.delete()}
 }
}
