package vn.vltelecom
import org.junit.Assert.*
import org.junit.Test
class RouteSequenceTest {
 @Test fun moveSevenToTwo(){assertEquals(mapOf(2 to 3,3 to 4,4 to 5,5 to 6,6 to 7),RouteSequence.shifts((1..7).toList(),7,2))}
 @Test fun moveTwoToSeven(){assertEquals(mapOf(3 to 2,4 to 3,5 to 4,6 to 5,7 to 6),RouteSequence.shifts((1..7).toList(),2,7))}
 @Test fun insertNewAtTwo(){assertEquals(mapOf(2 to 3,3 to 4),RouteSequence.shifts(listOf(1,2,3),null,2))}
 @Test fun unchanged(){assertTrue(RouteSequence.shifts((1..7).toList(),2,2).isEmpty())}
 @Test fun append(){assertTrue(RouteSequence.shifts(listOf(1,2,3),null,4).isEmpty())}
 @Test(expected=ArithmeticException::class) fun overflow(){RouteSequence.shifts(listOf(Int.MAX_VALUE),null,2)}
}
