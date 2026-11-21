package vn.vltelecom

import org.json.JSONArray
import org.json.JSONObject

data class IncidentRoute(var name:String,val report:IncidentReport,val overrides:MutableSet<String> = mutableSetOf())

/** A dossier owns shared information and independent route reports in the existing Room JSON row. */
class IncidentDossier(val shared:IncidentReport,val routes:MutableList<IncidentRoute>) {
    fun inherit(route:IncidentRoute){
        fields.forEach{if(it !in route.overrides)set(route.report,it,get(shared,it))}
        val privateGroups=route.report.groups.filter{g->shared.groups.none{it.name==g.name}}
        val groups=shared.groups.map{g->if(g.name in route.report.privateGroups)route.report.groups.firstOrNull{it.name==g.name}?:g else g}
        route.report.groups.clear();route.report.groups.addAll(groups);route.report.groups.addAll(privateGroups)
    }
    fun sync(){routes.forEach{inherit(it)}}
    fun json():String {sync();return JSONObject(shared.json()).apply{
        put("dossierVersion",1)
        put("routes",JSONArray().apply{routes.forEach{r->put(JSONObject().apply{put("name",r.name);put("report",JSONObject(r.report.json()));put("overrides",JSONArray(r.overrides.toList()))})}})
    }.toString()}
    fun add(name:String):IncidentRoute {
        val r=IncidentReport(relocation=shared.relocation,recovery=shared.relocation)
        r.groups.clear()
        r.groups.addAll((if(shared.relocation)listOf("Đầu số cáp","Kết quả đo kiểm")else IncidentRules.groupNames.drop(1)).map{IncidentGroup(it)})
        return IncidentRoute(name,r).also{routes.add(it);inherit(it)}
    }
    fun paths():Set<String> = (listOf(shared)+routes.map{it.report}).flatMap{r->r.groups.flatMap{it.images.map{p->p.path}}+listOf(r.pendingPhoto).filter{it.isNotBlank()}}.toSet()
    companion object {
        val fields=listOf("location","locationMode","distance","reporter","date","cause","solution")
        fun get(r:IncidentReport,key:String):String=when(key){"location"->r.location;"locationMode"->r.locationMode;"distance"->r.distance;"reporter"->r.reporter;"date"->r.date;"received"->r.received;"restored"->r.restored;"cause"->r.cause;else->r.solution}
        fun set(r:IncidentReport,key:String,value:String){when(key){"location"->r.location=value;"locationMode"->r.locationMode=value;"distance"->r.distance=value;"reporter"->r.reporter=value;"date"->r.date=value;"received"->r.received=value;"restored"->r.restored=value;"cause"->r.cause=value;"solution"->r.solution=value}}
        fun fromLegacy(report:IncidentReport,name:String):IncidentDossier {
            val common=IncidentReport(relocation=report.relocation)
            fields.forEach{set(common,it,get(report,it))}
            common.groups.clear();val sharedGroups=if(report.relocation)listOf("Trước di dời","Trong thi công","Sau hoàn thành")else listOf(IncidentRules.groupNames[0]);common.groups.addAll(report.groups.filter{it.name in sharedGroups})
            if(report.relocation){listOf("Đầu số cáp","Kết quả đo kiểm").forEach{name->if(report.groups.none{it.name==name})report.groups.add(IncidentGroup(name))}}
            return IncidentDossier(common,mutableListOf(IncidentRoute(name,report))).also{it.sync()}
        }
        fun parse(value:String,name:String):IncidentDossier {
            val o=JSONObject(value);val common=IncidentReport.parse(value)
            val a=o.optJSONArray("routes")?:return fromLegacy(common,name)
            val routes=mutableListOf<IncidentRoute>()
            for(i in 0 until a.length()){val item=a.getJSONObject(i);val overrides=mutableSetOf<String>();item.optJSONArray("overrides")?.let{keys->for(j in 0 until keys.length())overrides.add(keys.getString(j))};routes.add(IncidentRoute(item.getString("name"),IncidentReport.parse(item.getJSONObject("report").toString()),overrides))}
            return IncidentDossier(common,routes).also{if(routes.isEmpty())it.add(name);it.sync()}
        }
    }
}
