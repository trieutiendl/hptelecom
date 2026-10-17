package vn.vltelecom
import org.junit.Assert.*
import org.junit.Test
class RouteMoveNumbersTest {
 @Test fun appendsAfterExistingPhotosAndPoints(){assertEquals(listOf(10,11,12),RouteMoveNumbers.allocate(listOf(1,4,9),3))}
 @Test fun emptyRouteStartsAtOne(){assertEquals(listOf(1,2),RouteMoveNumbers.allocate(emptyList(),2));assertTrue(RouteMoveNumbers.allocate(listOf(8),0).isEmpty())}
 @Test(expected=IllegalArgumentException::class) fun preventsOverflow(){RouteMoveNumbers.allocate(listOf(Int.MAX_VALUE),1)}
}
