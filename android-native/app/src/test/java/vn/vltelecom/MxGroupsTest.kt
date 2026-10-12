package vn.vltelecom
import org.junit.Assert.*
import org.junit.Test
class MxGroupsTest {
 @Test fun bothKindsHaveSeparateGroups(){
  listOf(IncidentReport(),IncidentReport.relocation()).forEach{r->IncidentRules.prepare(r);IncidentRules.prepare(r);assertEquals(1,r.groups.count{it.name=="Vật tư"});assertEquals(1,r.groups.count{IncidentRules.isMx(it)})}
 }
 @Test fun migrationRetainsPhotosNotesAndPendingIndex(){
  val r=IncidentReport();r.groups.clear();val old=IncidentGroup("Măng xông / treo néo",note="Cũ",images=mutableListOf(IncidentImage("a.jpg",10,10)),pairNotes=mutableListOf("Mô tả cũ"));r.groups.add(old);r.pendingGroup=0
  IncidentRules.prepare(r);assertEquals("Vật tư",r.groups[0].name);assertEquals(old.images,r.groups[0].images);assertEquals(old.pairNotes,r.groups[0].pairNotes);assertEquals(0,r.pendingGroup)
 }
 @Test fun decimalCoordinatesKeepNote(){assertEquals("MX 1\nTọa độ MX: 12.882000, 108.386100",IncidentRules.mxDescription("MX 1",12.882,108.3861))}
 @Test(expected=IllegalArgumentException::class) fun invalidGpsIsNotInvented(){IncidentRules.mxDescription("",Double.NaN,108.0)}
}
