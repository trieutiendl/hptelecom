package vn.vltelecom

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class IncidentDossierTest {
    @Test fun legacyPhotosAndMaterialsStayOnFirstRoute(){
        val old=IncidentReport(location="Địa điểm A",cable="24FO",start="3067",end="2777")
        old.groups[0].images.add(IncidentImage("shared.jpg",100,100))
        old.groups[1].images.add(IncidentImage("private.jpg",100,100))
        old.materials.add(IncidentMaterial("Néo",quantity="4",unit="bộ"))
        val d=IncidentDossier.fromLegacy(old,"A");val second=d.add("B")
        assertSame(old,d.routes[0].report)
        assertEquals("Địa điểm A",second.report.location)
        assertTrue(second.report.materials.isEmpty())
        assertTrue(second.report.groups.first{it.name=="Đầu số cáp"}.images.isEmpty())
        assertSame(d.shared.groups[0],second.report.groups.first{it.name=="Hiện trường sự cố"})
        assertEquals(setOf("shared.jpg","private.jpg"),d.paths())
        second.report.cable="48FO";assertEquals("24FO",old.cable)
    }
    @Test fun overridesProtectOnlyChangedFieldsAndCanBeReset(){
        val d=IncidentDossier.fromLegacy(IncidentReport(location="A",reporter="Người A",solution="Chung"),"A")
        val b=d.add("B");b.overrides.add("solution");b.report.solution="Riêng B"
        d.shared.solution="Chung mới";d.shared.reporter="Người B";d.sync()
        assertEquals("Riêng B",b.report.solution);assertEquals("Người B",b.report.reporter)
        assertEquals("Chung mới",d.routes[0].report.solution)
        b.overrides.clear();d.sync();assertEquals("Chung mới",b.report.solution)
    }
    @Test fun relocationHasSharedSitePhotosAndPrivateCablePhotos(){
        val d=IncidentDossier.fromLegacy(IncidentReport.relocation(),"A");val b=d.add("B")
        assertEquals(3,d.shared.groups.size)
        assertEquals(5,b.report.groups.size)
        assertNotSame(d.routes[0].report.groups.first{it.name=="Đầu số cáp"},b.report.groups.first{it.name=="Đầu số cáp"})
    }
    @Test fun eachExcelContainsItsOwnCableAndMaterial(){
        val folder=kotlin.io.path.createTempDirectory("dossier").toFile()
        try{
            val d=IncidentDossier.fromLegacy(IncidentReport(location="Chung",cable="24FO",start="3067",end="2777"),"Tuyến A")
            d.routes[0].report.materials.add(IncidentMaterial("Néo",quantity="4",unit="bộ"))
            val b=d.add("Tuyến B");b.report.cable="48FO";b.report.start="1180";b.report.end="1000";b.report.materials.add(IncidentMaterial("Treo",quantity="1",unit="bộ"))
            d.sync()
            d.routes.forEachIndexed{i,r->
                val file=File(folder,"$i.xlsx");IncidentExport.xlsx(file,r.name,r.report)
                ZipFile(file).use{z->val sheet=z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).reader().readText()
                    assertTrue(sheet.contains(r.name));assertTrue(sheet.contains("Chung"));assertTrue(sheet.contains(if(i==0)"24FO" else "48FO"))
                    assertFalse(sheet.contains(if(i==0)"48FO" else "24FO"));assertTrue(sheet.contains(if(i==0)"4 néo" else "1 treo"))
                }
            }
        }finally{folder.deleteRecursively()}
    }
}
