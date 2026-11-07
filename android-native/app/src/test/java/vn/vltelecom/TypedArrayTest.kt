package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test

class TypedArrayTest {
 interface Filter
 class Length:Filter
 class Uppercase:Filter
 @Suppress("UNCHECKED_CAST")
 private fun androidFilters():Array<Filter> = arrayOf(Length()) as Array<Filter>

 @Test(expected=ArrayStoreException::class)
 fun reproduceCameraFilterCrash(){androidFilters()+Uppercase()}

 @Test fun appendPreservesLengthAndAcceptsUppercase(){
  val original=androidFilters();val caps=Uppercase()
  val combined=appendTypedElement<Filter>(original,caps)
  assertEquals(Filter::class.java,combined.javaClass.componentType)
  assertEquals(2,combined.size);assertSame(original[0],combined[0]);assertSame(caps,combined[1])
  assertEquals(1,original.size)
 }
 @Test fun alreadyWideAndEmptyArraysWork(){
  assertEquals(1,appendTypedElement<Filter>(emptyArray(),Uppercase()).size)
  assertEquals(3,appendTypedElement<Filter>(arrayOf(Length(),Uppercase()),Length()).size)
 }
}
