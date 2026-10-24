package vn.vltelecom
import org.junit.Assert.assertEquals
import org.junit.Test
class CableSyncCheckTest {
 @Test fun serverMissingPhotosMustResend(){assertEquals(CableSyncCheck.RESEND,reconcileCableSync(4,4,7,0,true,false))}
 @Test fun completionOnlyMustResend(){assertEquals(CableSyncCheck.RESEND,reconcileCableSync(4,4,7,7,true,false))}
 @Test fun newerServerMustNotBeOverwritten(){assertEquals(CableSyncCheck.CONFLICT,reconcileCableSync(4,5,7,0,true,false))}
 @Test fun restoredServerRevisionRequiresReview(){assertEquals(CableSyncCheck.CONFLICT,reconcileCableSync(4,2,7,0,true,false))}
 @Test fun matchingServerIsSynced(){assertEquals(CableSyncCheck.MATCH,reconcileCableSync(4,4,7,7,true,true))}
}
