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
            String fixture="{user:{id:'qa-staff',username:'qa',role:'staff',display_name:'Kiểm thử'},routes:[{key:'qa-route',name:'TUYẾN KIỂM THỬ',connection_type:'DWDM',capacity:'8FO',lines:[[[12.680,108.046],[12.681,108.051],[12.684,108.054]]],markers:[{lat:12.681,lon:108.051,label:'MX',position:'Vị trí kiểm thử'}]}],sharedLayers:[{key:'qa-shared',name:'Thư mục kiểm thử',parent_id:'',source_type:'server',team:'',lines:[],markers:[{lat:12.683,lon:108.053,label:'DP',position:'Dự phòng kiểm thử'}]}]}";
            js("HPMapApp.receiveSession("+fixture+");true");
            check("document.getElementById('login').hidden","Login did not return to map");
            check("document.getElementById('route-list').textContent.includes('TUYẾN KIỂM THỬ')","Server route missing");
            check("document.getElementById('creation-tools').hidden&&document.getElementById('nav-import').hidden&&document.getElementById('edit').hidden","Staff can edit");
            check("HPMapApp.makeKml(true).includes('TUYẾN KIỂM THỬ')","Read-only KMZ export missing route");
            screenshot("02-map-staff-test-data");
            js("document.getElementById('shared-tab').click();true");
            screenshot("03-shared-folders-test-data");
            js("HPMapApp.receiveSession("+fixture.replace("role:'staff'","role:'admin'")+");document.getElementById('routes-tab').click();true");
            check("!document.getElementById('creation-tools').hidden&&!document.getElementById('nav-import').hidden","Admin tools missing");
            js("document.getElementById('open-folders').click();document.getElementById('new-folder').click();true");
            check("!document.getElementById('modal-backdrop').hidden","Create folder form missing");
            screenshot("04-admin-folder");
            js("HPMapApp.receiveSession("+fixture+");true");
            check("document.getElementById('modal-backdrop').hidden&&document.getElementById('creation-tools').hidden","Role downgrade left edit form open");
            result.putString("stream","HP_MAP_SMOKE_PASS: login, packaged map, server route fixtures, shared folders, KMZ, admin/read-only roles.\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            result.putString("stream","HP_MAP_SMOKE_FAIL: "+failure.getClass().getSimpleName()+": "+failure.getMessage()+"\n");
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
