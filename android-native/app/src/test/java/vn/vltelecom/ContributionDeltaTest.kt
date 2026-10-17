package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class ContributionDeltaTest {
    private fun image(id:String,caption:String="") = mapOf<String,Any?>("file" to id,"width" to 100,"height" to 200,"caption" to caption)
    private fun report(vararg groups:Pair<String,List<Map<String,Any?>>>) = mapOf<String,Any?>("report" to mapOf("groups" to groups.map{mapOf("name" to it.first,"images" to it.second)}))
    private fun rejected(block:()->Unit) { try { block(); fail("Unsafe contribution was accepted") } catch (_: IllegalArgumentException) {} }

    @Test fun unchangedReportHasNoContribution() {
        val base=report("MX" to listOf(image("a")))
        assertTrue(ContributionDelta.added(base,base).isEmpty())
    }
    @Test fun sendsOnlyNewImagesInTheirExactGroups() {
        val a=image("a");val b=image("b","new GPS");val c=image("c")
        val base=report("MX" to listOf(a),"Vật tư" to emptyList())
        val local=report("MX" to listOf(a,b),"Vật tư" to listOf(c))
        assertEquals(listOf(ContributionDelta.Image("MX",b),ContributionDelta.Image("Vật tư",c)),ContributionDelta.added(base,local))
    }
    @Test fun acknowledgedImagesAreNotRepeatedWhenANewerPhotoIsAdded() {
        val initial=report("MX" to listOf(image("a")))
        val sent=report("MX" to listOf(image("a"),image("b")))
        val later=report("MX" to listOf(image("a"),image("b"),image("c")))
        assertEquals(listOf("b"),ContributionDelta.added(initial,sent).map{it.image["file"]})
        assertEquals(listOf("c"),ContributionDelta.added(sent,later).map{it.image["file"]})
    }
    @Test fun removalOfAcceptedImageIsNeverSentAsAnAddition() {
        rejected { ContributionDelta.added(report("MX" to listOf(image("a"))),report("MX" to emptyList())) }
    }
    @Test fun editsToAcceptedCaptionAreRejectedInsteadOfOverwritingOwnerData() {
        rejected { ContributionDelta.added(report("MX" to listOf(image("a","before"))),report("MX" to listOf(image("a","after")))) }
    }
    @Test fun movingAcceptedImageToAnotherGroupIsRejected() {
        val before=report("MX" to listOf(image("a")),"Vật tư" to emptyList())
        val after=report("MX" to emptyList(),"Vật tư" to listOf(image("a")))
        rejected { ContributionDelta.added(before,after) }
    }
    @Test fun duplicateImageOrGroupCannotCreateAnAmbiguousContribution() {
        val base=report("MX" to emptyList())
        rejected { ContributionDelta.added(base,report("MX" to listOf(image("a"),image("a")))) }
        rejected { ContributionDelta.added(base,report("MX" to emptyList(),"MX" to listOf(image("a")))) }
    }
    @Test fun uploaderAndGpsMetadataSurviveInNewImage() {
        val photo=image("raw").toMutableMap().apply{put("originalFile","original");put("lat",12.25);put("lon",108.5);put("overlayLines",listOf("Tuyến A","Ngày chụp: 27/09/2026"))}
        val added=ContributionDelta.added(report("MX" to emptyList()),report("MX" to listOf(photo)))
        assertEquals(photo,added.single().image)
    }
    @Test fun manyContributorsCanAppendBeyondAnOldTwoPhotoGroup() {
        val old=(1..7).map{image("a$it")}
        assertEquals(listOf("new"),ContributionDelta.added(report("Hiện trường sự cố" to old),report("Hiện trường sự cố" to old+image("new"))).map{it.image["file"]})
    }
    @Test fun legacyRepeatedImageReferencesRemainUntouched() {
        val repeated=listOf(image("same"),image("same"))
        val before=report("MX" to repeated,"Vật tư" to listOf(image("same")))
        val after=report("MX" to repeated+image("new"),"Vật tư" to listOf(image("same")))
        assertEquals(listOf("new"),ContributionDelta.added(before,after).map{it.image["file"]})
    }
    @Test fun harmlessDefaultsFromOpeningALegacyReportDoNotBlockNewImages() {
        val legacy=mapOf<String,Any?>("file" to "a","uploadedBy" to "user1","uploadedByName" to "Người chụp","lat" to 12.3)
        val roundTripped=legacy+mapOf("caption" to "","originalFile" to "","width" to 320,"height" to 400)
        val base=report("MX" to listOf(legacy))
        val current=report("MX" to listOf(roundTripped,image("new")))
        assertEquals(listOf("new"),ContributionDelta.added(base,current).map{it.image["file"]})
        assertTrue(ContributionDelta.added(base,report("MX" to listOf(roundTripped))).isEmpty())
    }
    @Test fun defaultsDoNotPermitChangesToExistingDimensionsOrMetadata() {
        val legacy=mapOf<String,Any?>("file" to "a","uploadedByName" to "Người chụp","lat" to 12.3)
        val base=report("MX" to listOf(legacy))
        for(change in listOf(mapOf("caption" to "changed"),mapOf("width" to 500),mapOf("lat" to 13.0),mapOf("uploadedByName" to "Other"))){
            rejected { ContributionDelta.added(base,report("MX" to listOf(legacy+change))) }
        }
    }
}
