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
    private void checkLocalTransfers() throws Exception {
        check("(function(){document.getElementById('open-folders').click();document.getElementById('new-folder').click();document.getElementById('folder-name').value='DEST-QA';document.getElementById('folder-parent').value='';document.getElementById('save-folder').click();var s=JSON.parse(localStorage.getItem('hpmap-v2')),p=s.features.find(function(f){return f.name==='LOCAL-QA';}),d=s.folders.find(function(f){return f.name==='DEST-QA';});if(!p||!d)return false;window.__qaLocalOriginal=p.id;window.__qaLocalSource=p.folder;window.__qaLocalDest=d.id;return true;})()",
                "Cannot create local transfer destination");
        check("(function(){document.getElementById('nav-map').click();var p=document.querySelector('.leaflet-marker-pane .hp-marker.local');if(!p)return false;p.click();document.getElementById('select-feature').click();return !document.getElementById('selection-bar').hidden&&document.getElementById('selection-count').textContent.startsWith('1 ');})()",
                "Local point could not enter selection mode");
        screenshot("08-selected-point");
        check("(function(){document.getElementById('selection-copy').click();document.getElementById('transfer-target').value=window.__qaLocalDest;return !document.getElementById('modal-backdrop').hidden&&document.getElementById('transfer-confirm').textContent==='Sao chép';})()",
                "Local copy target dialog missing");
        screenshot("09-transfer-destination");
        check("(function(){document.getElementById('transfer-confirm').click();var s=JSON.parse(localStorage.getItem('hpmap-v2')),a=s.features.find(function(f){return f.id===window.__qaLocalOriginal;}),b=s.features.find(function(f){return f.folder===window.__qaLocalDest;});return s.features.length===2&&a&&b&&a.id!==b.id&&a.folder===window.__qaLocalSource&&JSON.stringify(a.coords)===JSON.stringify(b.coords)&&document.getElementById('selection-bar').hidden;})()",
                "Local copy did not retain source and create a distinct point with identical coordinates");
        check("(function(){document.getElementById('nav-map').click();var p=document.querySelector('.leaflet-marker-pane .hp-marker.local');if(!p)return false;p.click();document.getElementById('select-feature').click();var s=document.querySelector('.feature-choice input[data-feature-id=\"'+window.__qaLocalOriginal+'\"]');return !!s&&s.checked;})()",
                "Cannot select original local point for move");
        check("(function(){document.getElementById('selection-move').click();document.getElementById('transfer-target').value=window.__qaLocalDest;document.getElementById('transfer-confirm').click();var s=JSON.parse(localStorage.getItem('hpmap-v2'));return s.features.length===2&&new Set(s.features.map(function(f){return f.id;})).size===2&&s.features.every(function(f){return f.folder===window.__qaLocalDest;})&&s.features.some(function(f){return f.id===window.__qaLocalOriginal;})&&JSON.stringify(s.features[0].coords)===JSON.stringify(s.features[1].coords)&&document.getElementById('selection-bar').hidden;})()",
                "Local move did not preserve IDs or remove original from source folder");
    }
    private void checkSharedTransfers(String fixture) throws Exception {
        js("window.__qaFixture=" + fixture.replace("role:'staff'", "role:'admin'") + ";HPMapApp.receiveSession(window.__qaFixture);document.getElementById('nav-routes').click();document.getElementById('shared-tab').click();true");
        check("(function(){var select=document.querySelector('.select-shared-features');if(!select)return false;select.click();var checks=Array.from(document.querySelectorAll('.feature-choice input[data-source=shared]'));checks.forEach(function(c){c.click();});return checks.length===2&&document.getElementById('selection-count').textContent.startsWith('2 ');})()",
                "Shared point and line selection controls missing");
        check("(function(){document.getElementById('selection-copy').click();document.getElementById('transfer-target').value='qa-dest';var original=window.HPMap,spy={request:function(id,action,args){if(action!=='transferSharedFeatures')return original.request(id,action,args);window.__qaTransfer=JSON.parse(args);var updated=JSON.parse(JSON.stringify(window.__qaFixture)),source=updated.sharedLayers[0],dest=updated.sharedLayers[1];updated.sharedLayers.forEach(function(l){l.revision=4;});dest.markers=[Object.assign({},source.markers[0],{feature_id:'qa-copy-point'})];dest.lines=JSON.parse(JSON.stringify(source.lines));dest.lineFeatures=[Object.assign({},source.lineFeatures[0],{feature_id:'qa-copy-line'})];updated.transfer={saved:true,mode:'copy',count:2};window.HPMapResult(id,updated);}};try{window.HPMap=spy;if(window.HPMap!==spy)return false;document.getElementById('transfer-confirm').click();var t=window.__qaTransfer;return t&&t.team==='QA'&&t.revision===3&&t.mode==='copy'&&t.targetFolderId==='qa-dest'&&t.featureIds.length===2&&t.featureIds.includes('qa-point')&&t.featureIds.includes('qa-line');}finally{window.HPMap=original;}})()",
                "Shared transfer did not use raw feature/folder IDs, selected scope and revision");
        check("(function(){var rows=Array.from(document.querySelectorAll('.shared-row'));return document.getElementById('selection-bar').hidden&&document.getElementById('modal-backdrop').hidden&&rows.some(function(r){return r.textContent.includes('Đích quản trị kiểm thử')&&r.textContent.includes('1 điểm hiển thị')&&r.textContent.includes('1 đường');})()",
                "Shared transfer response did not clear selection or refresh destination data");
        screenshot("10-shared-transfer-complete");
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
                    +"]}],sharedLayers:[{key:'qa-shared',folder_id:'qa-source',revision:3,name:'Thư mục kiểm thử',parent_id:'',source_type:'server',team:'QA',"
                    +"lines:[[[12.683,108.053],[12.6835,108.0535]]],lineFeatures:[{feature_id:'qa-line',name:'Đường quản trị kiểm thử',note:'Ghi chú đường',color:'#008080',coords:[[12.683,108.053],[12.6835,108.0535]]}],"
                    +"markers:[{feature_id:'qa-point',lat:12.683,lon:108.053,label:'DP',position:'Dự phòng kiểm thử',display_on_map:true}]},"
                    +"{key:'qa-dest-layer',folder_id:'qa-dest',revision:3,name:'Đích quản trị kiểm thử',parent_id:'',source_type:'server',team:'QA',lines:[],lineFeatures:[],markers:[]}]}";
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
            check("document.querySelectorAll('.select-shared-features').length===0", "Staff can select shared features for editing");
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
            checkLocalTransfers();
            checkSharedTransfers(fixture);
            js("HPMapApp.receiveSession("+fixture+");true");
            check("document.getElementById('modal-backdrop').hidden&&document.getElementById('creation-tools').hidden","Role downgrade left edit form open");
            checkPatrolMarkers();
            result.putString("stream","HP_MAP_SMOKE_PASS: login, packaged map, hidden GPS with retained geometry/KML, MX/DP/endpoints, terrain switching/persistence, directions coordinates, local point copy/move, shared point+line selection/transfer/revision refresh, admin/read-only roles.\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            result.putString("stream","HP_MAP_SMOKE_FAIL: "+failure.getClass().getSimpleName()+": "+failure.getMessage()+"\n");
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
