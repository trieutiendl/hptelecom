package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class MxReadingRegressionTest {
    private val actual=MxLocalParser.Point(12.7013,108.6644)
    @Test fun dlmd08StampTextIsReadWithoutExif(){
        assertEquals(actual,MxLocalParser.unique("9/10/26\n+12.7013,+108.6644\nX. Cư Yang"))
    }
    @Test fun nativeCropKeepsActual1920x2560UpperRightStamp(){
        val first=MxImageRegions.stamps(1920,2560).first()
        assertEquals(MxImageRegions.Region(120,0,1920,1000),first)
        assertTrue(first.left<=1100&&first.right>=1880&&first.top==0&&first.bottom>=500)
    }
    @Test fun everyRegionStaysBoundedOnRotatedTinyAndLargePhotos(){
        for((w,h)in listOf(1920 to 2560,2560 to 1920,4000 to 3000,12000 to 9000,1 to 1,200 to 100)){
            val regions=MxImageRegions.stamps(w,h)
            assertEquals(regions.distinct(),regions)
            assertTrue(regions.size in 1..6)
            regions.forEach{assertTrue(it.left>=0&&it.top>=0&&it.right<=w&&it.bottom<=h);assertTrue(it.right-it.left in 1..1800&&it.bottom-it.top in 1..1000)}
        }
    }
    @Test fun singleOcrPassIsNotEnough(){val e=MxReadEvidence();e.add(listOf(actual));assertNull(e.result());e.add(listOf(actual));assertEquals(actual,e.result())}
    @Test fun oneBadDigitDoesNotAbortTheRemainingScans(){
        val e=MxReadEvidence();e.add(listOf(MxLocalParser.Point(12.7013,108.6649)));e.add(listOf(actual));assertNull(e.result())
        e.add(listOf(actual));assertNull(e.result());e.add(listOf(actual));assertEquals(actual,e.result())
    }
    @Test fun repeatedConflictingCoordinatesAndMultiplePrintedPairsAreRejected(){
        val other=MxLocalParser.Point(12.7013,108.6649)
        val e=MxReadEvidence();repeat(3){e.add(listOf(actual))};repeat(2){e.add(listOf(other))};assertNull(e.result())
        val multi=MxReadEvidence();multi.add(listOf(actual,other));repeat(4){multi.add(listOf(actual))};assertNull(multi.result())
    }
    @Test fun failedOverviewDoesNotPreventNativeCropSuccess(){
        val e=MxReadEvidence();e.add(emptyList());e.add(listOf(actual));e.add(listOf(actual));assertEquals(actual,e.result())
    }
    @Test fun openEditorDoesNotExhaustSameRevisionGpsFollowups(){
        var state=MxRefreshSchedule.begin(1000)
        repeat(20){state=checkNotNull(MxRefreshSchedule.afterCheck(state,state.dueAt,true,false));assertEquals(0,state.attempts)}
        val received=checkNotNull(MxRefreshSchedule.afterCheck(state,state.dueAt,true,true));assertEquals(1,received.attempts)
        assertNull(MxRefreshSchedule.afterCheck(received,received.dueAt,false,true))
    }
}
