package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class PhotoMutationDeltaTest {
    private fun image(id:String,caption:String="")=mapOf<String,Any?>("file" to id,"caption" to caption,"uploadedBy" to "someone-else")
    private fun payload(vararg photos:Map<String,Any?>)=mapOf<String,Any?>("report" to mapOf("groups" to listOf(mapOf("name" to "MX","images" to photos.toList()))))
    private fun plan(base:Map<String,Any?>,local:Map<String,Any?>)=PhotoMutationDelta.plan(base,local,base)
    private fun rejected(action:()->Unit){try{action();fail("Unsafe mutation accepted")}catch(_:IllegalArgumentException){}}

    @Test fun allAssignedPhotosCanBeEditedRegardlessOfUploader(){
        val a=image("a","before");val p=plan(payload(a),payload(a+mapOf("caption" to "after")))
        assertTrue(p.images.isEmpty());assertEquals("edit",p.mutations.single().action)
        assertEquals(a,p.mutations.single().expected);assertEquals("someone-else",p.mutations.single().image?.get("uploadedBy"))
    }
    @Test fun additionsEditsAndDeletesShareOneAtomicPlan(){
        val a=image("a");val b=image("b","before");val c=image("new")
        val p=plan(payload(a,b),payload(b+mapOf("caption" to "after"),c))
        assertEquals(listOf("delete","edit"),p.mutations.map{it.action});assertEquals(c,p.images.single().image)
        assertEquals(p,PhotoMutationDelta.fromWire(p.wire()))
    }
    @Test fun deleteAllDoesNotCreateAdditions(){
        val p=plan(payload(image("a"),image("b")),payload())
        assertEquals(2,p.mutations.size);assertTrue(p.mutations.all{it.action=="delete"});assertTrue(p.images.isEmpty())
    }
    @Test fun localDefaultsAreNotSentAsEditsAndExpectedRetainsCanonicalFields(){
        val raw=mapOf<String,Any?>("file" to "a","lat" to 12.0)
        val normalized=raw+mapOf("caption" to "","width" to 320,"height" to 400,"originalFile" to "")
        assertTrue(PhotoMutationDelta.plan(payload(raw),payload(normalized),payload(raw)).empty)
        val p=PhotoMutationDelta.plan(payload(normalized),payload(normalized+mapOf("caption" to "new")),payload(raw))
        assertEquals(raw,p.mutations.single().expected);assertFalse(p.mutations.single().image!!.containsKey("width"))
    }
    @Test fun attributionGpsOrAssetChangesCannotSneakThroughCaptionEdit(){
        val a=image("a")+mapOf("lat" to 12.0,"originalFile" to "raw")
        for(change in listOf(mapOf("uploadedBy" to "me"),mapOf("lat" to 13.0),mapOf("originalFile" to "other"))) {
            rejected{plan(payload(a),payload(a+change))}
        }
    }
    @Test fun unchangedLegacyDuplicatesPermitAdditionsButNotAmbiguousDeletion(){
        val a=image("a");assertEquals(1,plan(payload(a,a),payload(a,a,image("new"))).images.size)
        rejected{plan(payload(a,a),payload(a))}
    }
    @Test fun movingOrDuplicatingExistingPhotoIntoAnotherGroupIsRejected(){
        val base=payload(image("a"));val moved=mapOf<String,Any?>("report" to mapOf("groups" to listOf(mapOf("name" to "other","images" to listOf(image("a"))))))
        rejected{plan(base,moved)}
        val copied=mapOf<String,Any?>("report" to mapOf("groups" to listOf(mapOf("name" to "MX","images" to listOf(image("a"))),mapOf("name" to "other","images" to listOf(image("a"))))))
        rejected{plan(base,copied)}
    }
    @Test fun latestUnrelatedAdditionAndCaptionEditAreRetainedWhenRebasingDeletion(){
        val a=image("a");val b=image("b","before");val p=plan(payload(a,b),payload(b))
        val latest=payload(a,b+mapOf("caption" to "remote"),image("new"))
        val rebased=PhotoMutationDelta.rebase(p,latest)
        assertEquals(listOf("a"),rebased.mutations.map{it.file});assertTrue(rebased.images.isEmpty())
    }
    @Test fun remoteDeletionConflictsWithLocalCaptionEditAndNeverReaddsPhoto(){
        val a=image("a");val p=plan(payload(a),payload(a+mapOf("caption" to "edit")))
        rejected{PhotoMutationDelta.rebase(p,payload())}
    }
    @Test fun repeatedDeletionBecomesNoOp(){
        val p=plan(payload(image("a")),payload())
        assertTrue(PhotoMutationDelta.rebase(p,payload()).empty)
    }
    @Test fun localDeleteCannotEraseSomeoneElsesChangedCaption(){
        val p=plan(payload(image("a","old")),payload())
        rejected{PhotoMutationDelta.rebase(p,payload(image("a","remote")))}
    }
    @Test fun concurrentDifferentCaptionsConflictButMatchingCaptionIsNoOp(){
        val p=plan(payload(image("a","old")),payload(image("a","local")))
        rejected{PhotoMutationDelta.rebase(p,payload(image("a","remote")))}
        assertTrue(PhotoMutationDelta.rebase(p,payload(image("a","local"))).empty)
    }
    @Test fun remoteGpsEnrichmentSurvivesCaptionOnlyRebase(){
        val a=image("a","old");val p=plan(payload(a),payload(a+mapOf("caption" to "local")))
        val enriched=a+mapOf("lat" to 12.3,"lon" to 108.2,"gpsSource" to "ocr")
        val rebased=PhotoMutationDelta.rebase(p,payload(enriched)).mutations.single()
        assertEquals(enriched,rebased.expected);assertEquals(enriched+mapOf("caption" to "local"),rebased.image)
    }
    @Test fun duplicateNewAssetIsRejectedAndReportFieldsAreNeverInPhotoRequest(){
        rejected{plan(payload(),payload(image("a"),image("a")))}
        val p=plan(payload(),payload(image("a"))+mapOf("name" to "illicit rename","completed" to true))
        assertEquals(setOf("images","mutations"),p.wire().keys)
    }
    @Test fun numericJsonRepresentationDoesNotCreateFalseEdit(){
        val a=image("a")+mapOf("width" to 320,"lat" to 12.0)
        assertTrue(plan(payload(a),payload(a+mapOf("width" to 320L,"lat" to 12))).empty)
    }
}
