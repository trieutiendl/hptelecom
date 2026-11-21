package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class MxLocalParserTest {
    private fun point(text:String,lat:Double,lon:Double){
        val value=MxLocalParser.unique(text)
        assertNotNull(text,value)
        assertEquals(text,lat,value!!.lat,0.0000005)
        assertEquals(text,lon,value.lon,0.0000005)
    }
    @Test fun actualMxStamps(){
        point("2/10/26\n12.700480° N, 108.326998° E\n148° SE\nX. Krông Pắc",12.700480,108.326998)
        point("2/10/26\n12.700209° N, 108.327232° E\n23° NE",12.700209,108.327232)
        point("+12.716338, +108.223640\nX. Ea Knuếc",12.716338,108.223640)
        point("+12.715716,+108.222712",12.715716,108.222712)
        point("12.6016,108.1374",12.6016,108.1374)
        point("12.6021,108.1369",12.6021,108.1369)
        point("GPS: 12.6016,108.1374",12.6016,108.1374)
        point("Tọa độ MX: 12.640988, 108.017508",12.640988,108.017508)
    }
    @Test fun labelledAndDirectionalFormats(){
        point("Lat: 12.6409882 | Long: 108.0175083",12.6409882,108.0175083)
        point("Vĩ độ: 12,640988\nKinh độ: 108,017508",12.640988,108.017508)
        point("N 12.700480 E 108.326998",12.700480,108.326998)
        point("108.326998 E, 12.700480 N",12.700480,108.326998)
        point("12.700480 S 108.326998 W",-12.700480,-108.326998)
        point("-12.700480,-108.326998",-12.700480,-108.326998)
    }
    @Test fun degreesMinutesSecondsAndDecimalMinutes(){
        point("12°42′01.728″N,108°19′37.1928″E",12.70048,108.326998)
        point("12°42.0288'N,108°19.61988'E",12.70048,108.326998)
        point("12°42'01.728\"S\n108°19'37.1928\"W",-12.70048,-108.326998)
    }
    @Test fun rejectMissingAxesDatesRangesAndConflictingPairs(){
        for(text in listOf("02/10/2026 14:29", "148° SE", "12.700480 N", "91.000000 N 108.000000 E",
            "-12.700480 N,108.326998 E", "12°70'1\"N,108°19'37\"E", "12.1234\n108.1234",
            "12.1234,108.1234\n13.1234,109.1234", "12.1234 N 108.1234 E\n13.1234 N 109.1234 E")){
            assertNull(text,MxLocalParser.unique(text))
        }
    }
    @Test fun repeatedSameStampIsOneCandidate(){
        assertEquals(1,MxLocalParser.candidates("12.6016,108.1374\n12.601600,108.137400").size)
    }
}
