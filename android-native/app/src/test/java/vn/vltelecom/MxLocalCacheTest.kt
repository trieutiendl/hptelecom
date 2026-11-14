package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MxLocalCacheTest {
    private fun inDirectory(test:(File)->Unit){
        val dir=File(System.getProperty("java.io.tmpdir"),"mx-cache-${UUID.randomUUID()}")
        check(dir.mkdir())
        try{test(dir)}finally{dir.walkBottomUp().forEach{it.delete()}}
    }
    @Test fun sameOriginalBytesReusePersistedResultAfterReopen(){inDirectory{dir->
        val first=File(dir,"first.jpg").apply{writeText("original image bytes")}
        val second=File(dir,"second.jpg").apply{writeBytes(first.readBytes())}
        val a=MxLocalCache.sha256(first);val b=MxLocalCache.sha256(second)
        assertEquals(a,b)
        val cacheDir=File(dir,"account-a")
        val result=MxLocalCache.Entry(MxLocalParser.Point(12.6409882,108.0175083),"ocr")
        MxLocalCache(cacheDir).put(a,result)
        assertEquals(result,MxLocalCache(cacheDir).get(b))
        assertNull(MxLocalCache(File(dir,"account-b")).get(b))
    }}
    @Test fun changedPhotoHasNewIdentityAndUnreadableCanBeRetried(){inDirectory{dir->
        val source=File(dir,"mx.jpg").apply{writeText("first")}
        val a=MxLocalCache.sha256(source)
        source.writeText("second")
        assertNotEquals(a,MxLocalCache.sha256(source))
        val cache=MxLocalCache(File(dir,"cache"))
        cache.put(a,MxLocalCache.Entry(null,""))
        assertEquals(MxLocalCache.Entry(null,""),cache.get(a))
        val result=MxLocalCache.Entry(MxLocalParser.Point(12.6,108.1),"EXIF")
        cache.put(a,result)
        assertEquals(result,cache.get(a))
    }}
    @Test fun corruptCacheAndInvalidHashAreIgnored(){inDirectory{dir->
        val cache=MxLocalCache(dir);val hash="a".repeat(64)
        File(dir,"${MxLocalCache.VERSION}-$hash.txt").writeText("${MxLocalCache.VERSION}\nreadable\n91\n108\nocr")
        assertNull(cache.get(hash));assertNull(cache.get("../outside"))
    }}
    @Test fun failedResultFromOldReaderDoesNotBlockNewOriginalImageScan(){inDirectory{dir->
        val hash="b".repeat(64)
        File(dir,"android-mx-local-1-$hash.txt").writeText("android-mx-local-1\nunreadable\n\n\n")
        val cache=MxLocalCache(dir)
        assertNull(cache.get(hash))
        val readable=MxLocalCache.Entry(MxLocalParser.Point(12.7013,108.6644),"ocr")
        cache.put(hash,readable)
        assertEquals(readable,MxLocalCache(dir).get(hash))
    }}
}
