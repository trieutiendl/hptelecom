package vn.hptelecom.map;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Device smoke checks: packaged UI, real WebView rendering and role-gated controls.
 * All route fixtures are test-only and are never included in the app APK.
 */
public final class MapSmokeRunner extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private WebView web;
    private String js(String code) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        runOnMainSync(() -> web.evaluateJavascript(code, result -> { value.set(result); done.countDown(); }));
        if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("WebView response timed out");
        return value.get();
    }
    private void check(String code, String message) throws Exception {
        if (!"true".equals(js(code))) throw new AssertionError(message);
    }
    private void checkPatrolMarkers() throws Exception {
        check("(function(){var labels=Array.from(document.querySelectorAll('.leaflet-marker-pane .hp-marker')).map(function(e){return e.textContent.trim();});return labels.length===4&&['MX','DP','QA-A','QA-B'].every(function(v){return labels.includes(v);})&&!labels.some(function(v){return v.includes('GPS');});})()",
                "Map must hide generic GPS while retaining MX, DP and named endpoints");
        check("(function(){var xml=new DOMParser().parseFromString(HPMapApp.makeKml(true),'text/xml');if(xml.querySelector('parsererror'))return false;var hidden=Array.from(xml.getElementsByTagName('Placemark')).find(function(p){var n=p.getElementsByTagName('name')[0];return n&&n.textContent.startsWith('GPS-QA-HIDDEN');});var coords=hidden&&hidden.getElementsByTagName('coordinates')[0];var line=Array.from(xml.getElementsByTagName('LineString')).some(function(l){var c=l.getElementsByTagName('coordinates')[0];return c&&c.textContent.trim().split(/\\s+/).includes('108.052,12.682,0');});return !!coords&&coords.textContent.trim()==='108.052,12.682,0'&&line;})()",
                "Hidden GPS must remain in exported KML and full cable-line geometry");
    }
    private void checkDirections(boolean local, boolean capture) throws Exception {
        String selected = local
                ? "document.querySelector('.leaflet-marker-pane .hp-marker.local')"
                : "Array.from(document.querySelectorAll('.leaflet-marker-pane .hp-marker')).find(function(e){return e.textContent.trim()==='MX';})";
        check("(function(){var point=" + selected + ";if(!point)return false;point.click();return !!document.getElementById('point-directions')&&!document.getElementById('modal-backdrop').hidden;})()",
                "Selected point has no directions button");
        if (capture) screenshot("05-point-directions");
        // Intercept only the navigation request. Never open an external app during QA.
        check("(function(){var original=window.HPMap,captured=null,spy={request:function(id,action,args){if(action==='directions'){captured=JSON.parse(args);window.HPMapResult(id,{ok:true});return;}return original.request(id,action,args);}};try{window.HPMap=spy;if(window.HPMap!==spy)return false;document.getElementById('point-directions').click();return !!captured&&Math.abs(captured.lat-12.681)<1e-9&&Math.abs(captured.lon-108.051)<1e-9;}finally{window.HPMap=original;}})()",
                "Directions request does not match selected point coordinates");
        js("document.getElementById('close-modal').click();true");
    }
    private void checkLayers(boolean admin) throws Exception {
        check("(function(){document.getElementById('layers').click();var a=document.getElementById('configure-basemap');if(Boolean(a)!==" + admin
                        + ")return false;document.getElementById('basemap-terrain').click();return HPMapApp.getBasemap()==='terrain'&&JSON.parse(localStorage.getItem('hpmap-v2')).basemap==='terrain';})()",
                "Terrain selection, persistence or role permissions failed");
        boolean terrainLoaded=false;
        for(int i=0;i<30;i++) {
            if ("true".equals(js("HPMapApp.getBasemap()==='terrain'&&Array.from(document.querySelectorAll('.leaflet-tile-pane img')).some(function(e){return e.complete&&e.naturalWidth>0&&/^https:\\/\\/[abc]\\.tile\\.opentopomap\\.org\\//.test(e.src);})"))) {
                terrainLoaded=true;break;
            }
            SystemClock.sleep(500);
        }
        if(!terrainLoaded)throw new AssertionError("OpenTopoMap terrain did not load a real tile within 15 seconds");
        checkPatrolMarkers();
        if(!admin)screenshot("07-terrain-real-tiles");
        check("(function(){document.getElementById('layers').click();return document.getElementById('basemap-terrain').getAttribute('aria-pressed')==='true'&&document.getElementById('basemap-street').getAttribute('aria-pressed')==='false'&&JSON.parse(localStorage.getItem('hpmap-v2')).basemap==='terrain';})()",
                "Terrain active state was not retained when reopening layer picker");
        if(!admin)screenshot("06-map-layers");
        check("(function(){document.getElementById('basemap-street').click();return HPMapApp.getBasemap()==='street'&&JSON.parse(localStorage.getItem('hpmap-v2')).basemap==='street';})()",
                "Street layer was not restored or persisted");
        checkPatrolMarkers();
        if (!admin) {
            check("(function(){document.getElementById('layers').click();document.getElementById('basemap-satellite').click();var safe=HPMapApp.getBasemap()==='street'&&!document.getElementById('basemap-key')&&!document.getElementById('configure-basemap');document.getElementById('close-modal').click();return safe;})()",
                    "Staff changed unavailable satellite source or accessed credentials form");
        } else {
            check("(function(){document.getElementById('layers').click();document.getElementById('configure-basemap').click();var input=document.getElementById('basemap-key');var exists=!!input&&input.type==='password'&&!!document.getElementById('save-basemap-config');document.getElementById('close-modal').click();return exists;})()",
                    "Administrator map source configuration missing");
        }
    }
    private WebView find(View view) {
        if (view instanceof WebView) return (WebView)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            WebView found=find(((ViewGroup)view).getChildAt(i)); if (found!=null) return found;
        }
        return null;
    }
    private void screenshot(String name) throws Exception {
        SystemClock.sleep(1200);
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        if(bitmap==null)throw new AssertionError("Screenshot failed");
        File dir=new File(getTargetContext().getExternalFilesDir(null),"qa"); dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        bitmap.recycle();
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Intent intent=new Intent(getTargetContext(),MapHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity=startActivitySync(intent);
            runOnMainSync(()->web=find(activity.getWindow().getDecorView()));
            if(web==null)throw new AssertionError("Missing packaged app WebView");
            boolean ready=false;
            for(int i=0;i<40;i++){if("true".equals(js("Boolean(window.HPMapApp)"))){ready=true;break;}SystemClock.sleep(500);}
            if(!ready)throw new AssertionError("Local app JS failed to boot");
            SystemClock.sleep(1000);
            check("location.origin==='https://appassets.androidplatform.net'","App opened server HTML");
            check("!document.getElementById('login').hidden","Signed-out login missing");
            check("document.getElementById('creation-tools').hidden","Signed-out editing exposed");
            screenshot("01-login");
            String fixture="{user:{id:'qa-staff',username:'qa',role:'staff',display_name:'Kiểm thử'},"
                    +"routes:[{key:'qa-route',name:'TUYẾN KIỂM THỬ',connection_type:'DWDM',capacity:'8FO',"
                    +"lines:[[[12.680,108.046],[12.681,108.051],[12.682,108.052],[12.684,108.054]]],markers:["
                    +"{lat:12.680,lon:108.046,kind:'GPS',label:'QA-A',position:'Địa điểm QA-A',display_on_map:true,is_endpoint:true},"
                    +"{lat:12.681,lon:108.051,kind:'MX',label:'MX',position:'Vị trí kiểm thử',display_on_map:true,is_endpoint:false},"
                    +"{lat:12.682,lon:108.052,kind:'GPS',label:'GPS-QA-HIDDEN',position:'Điểm trung gian cần ẩn',display_on_map:false,is_endpoint:false},"
                    +"{lat:12.684,lon:108.054,kind:'GPS',label:'QA-B',position:'Địa điểm QA-B',display_on_map:true,is_endpoint:true}"
                    +"]}],sharedLayers:[{key:'qa-shared',name:'Thư mục kiểm thử',parent_id:'',source_type:'server',team:'',lines:[],"
                    +"markers:[{lat:12.683,lon:108.053,label:'DP',position:'Dự phòng kiểm thử',display_on_map:true}]}]}";
            js("HPMapApp.receiveSession("+fixture+");true");
            check("document.getElementById('login').hidden","Login did not return to map");
            check("document.getElementById('route-list').textContent.includes('TUYẾN KIỂM THỬ')","Server route missing");
            check("document.getElementById('creation-tools').hidden&&document.getElementById('nav-import').hidden&&document.getElementById('edit').hidden","Staff can edit");
            check("HPMapApp.makeKml(true).includes('TUYẾN KIỂM THỬ')","Read-only KMZ export missing route");
            SystemClock.sleep(800);
            checkPatrolMarkers();
            check("(function(){var s=document.querySelector('.leaflet-overlay-pane>svg'),p=s&&s.querySelector('path');if(!s||!p)return false;var r=s.getBoundingClientRect();return r.width>100&&r.height>100&&p.getTotalLength()>10;})()","Cable line is clipped or missing");
            screenshot("02-map-staff-test-data");
            checkDirections(false, true);
            checkLayers(false);
            js("document.getElementById('shared-tab').click();true");
            screenshot("03-shared-folders-test-data");
            js("HPMapApp.receiveSession("+fixture.replace("role:'staff'","role:'admin'")+");document.getElementById('routes-tab').click();true");
            check("!document.getElementById('creation-tools').hidden&&!document.getElementById('nav-import').hidden","Admin tools missing");
            checkPatrolMarkers();
            checkLayers(true);
            js("document.getElementById('open-folders').click();document.getElementById('new-folder').click();true");
            check("!document.getElementById('modal-backdrop').hidden","Create folder form missing");
            screenshot("04-admin-folder");
            js("document.getElementById('close-modal').click();true");
            check("(function(){var point=Array.from(document.querySelectorAll('.leaflet-marker-pane .hp-marker')).find(function(e){return e.textContent.trim()==='MX';});if(!point)return false;point.click();document.getElementById('copy-server-point').click();document.getElementById('feature-name').value='LOCAL-QA';document.getElementById('feature-label').value='Điểm';document.getElementById('save-feature').click();return !!document.querySelector('.leaflet-marker-pane .hp-marker.local');})()",
                    "Administrator cannot copy a selected point to a local folder");
            checkDirections(true, false);
            js("HPMapApp.receiveSession("+fixture+");true");
            check("document.getElementById('modal-backdrop').hidden&&document.getElementById('creation-tools').hidden","Role downgrade left edit form open");
            checkPatrolMarkers();
            result.putString("stream","HP_MAP_SMOKE_PASS: login, packaged map, hidden GPS with retained geometry/KML, MX/DP/endpoints, terrain switching/persistence, directions coordinates, shared folders, admin/read-only roles.\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            result.putString("stream","HP_MAP_SMOKE_FAIL: "+failure.getClass().getSimpleName()+": "+failure.getMessage()+"\n");
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
