package vn.vltelecom
import org.junit.Assert.*
import org.junit.Test
class RulesTest {
    @Test fun reusesDeletedSequenceWithoutRenumberingRemainingPhotos(){
        assertEquals(2,Rules.nextSequence(listOf(1,3,4)))
        assertEquals(1,Rules.nextSequence(emptyList()))
        assertEquals(4,Rules.nextSequence(listOf(3,1,2)))
        assertEquals(1,Rules.nextSequence(listOf(Int.MAX_VALUE)))
    }
    @Test fun requiresRealCoordinatesButAllowsZero(){assertNotNull(Rules.validate("1","A","MX",null,null));assertNull(Rules.validate("1","A","MX",0.0,0.0))}
    @Test fun rejectsInvalidNumbers(){for(seq in listOf("0","-1","1.5","2147483648",""))assertNotNull(Rules.validate(seq,"A","DP",10.0,106.0));assertNotNull(Rules.validate("1","A","DP",Double.NaN,106.0));assertNotNull(Rules.validate("1","A","DP",91.0,106.0))}
    @Test fun requiresLocationAndNote(){assertNotNull(Rules.validate("1"," ","DP",10.0,106.0));assertNotNull(Rules.validate("1","A"," ",10.0,106.0))}
}
