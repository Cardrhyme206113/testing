package dev.card.anima;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.SharedPreferences;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

final class PocketUi {
    final MainActivity host;
    final WebView webView;
    private boolean ready=false;
    private boolean showLive=true;
    private long lastBlockUi=0;
    PocketUi(MainActivity activity){
        host=activity;
        webView=new WebView(activity);
        webView.setBackgroundColor(0xff0d0e10);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setDomStorageEnabled(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){ready=true;}
        });
        webView.addJavascriptInterface(new Api(),"PocketAndroid");
        webView.loadUrl("file:///android_asset/anima-pocket.html");
    }
    void js(String call){
        host.runOnUiThread(()->{if(ready)webView.evaluateJavascript(call,null);});
    }
    void event(String name,String args){js("window.PocketNative&&window.PocketNative."+name+"("+args+")");}
    void status(String value){
        // Avoid flooding the WebView for every model-loading tensor.
        if(value.startsWith("Sampling ") || value.startsWith("Preparing ") ||
           value.startsWith("Generating") || value.startsWith("Returning"))
            event("status",JSONObject.quote(value));
    }
    void busy(boolean value){event("busy",String.valueOf(value));}
    void begin(int steps,long seed){
        lastBlockUi=0;
        event("begin",steps+","+JSONObject.quote(Long.toString(seed)));
    }
    void blockProgress(int count,int steps){
        long now=android.os.SystemClock.uptimeMillis();
        if(count%28!=0 && now-lastBlockUi<100)return;
        lastBlockUi=now;
        event("progress",Math.min(steps*28,count)+","+(steps*28));
    }
    private String png(Bitmap bitmap){
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG,90,output);
        return "data:image/png;base64,"+Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP);
    }
    void preview(Bitmap bitmap,int step,int total){
        if(!showLive)return;
        // All decoding happens on the native worker; no UI-thread image conversion.
        String data=png(bitmap);
        event("preview",JSONObject.quote(data)+","+step+","+total);
    }
    void finished(Bitmap bitmap,long seed,Uri saved,String prompt){
        String image=png(bitmap);
        saveHistory(saved.toString());
        event("finished",JSONObject.quote(image)+","+
            JSONObject.quote(Long.toString(seed))+","+JSONObject.quote(prompt));
    }
    void error(String message){event("error",JSONObject.quote(message==null?"Generation failed":message));}
    void modelSelected(int index,String name){event("file",index+","+JSONObject.quote(name));}
    private void saveHistory(String uri){
        SharedPreferences pref=host.preferences;
        String old=pref.getString("pocket_history","");
        ArrayList<String> list=new ArrayList<>();
        list.add(uri);
        for(String item:old.split("\n")){
            if(item.startsWith("content://")&&!item.equals(uri)&&list.size()<12)list.add(item);
        }
        pref.edit().putString("pocket_history",android.text.TextUtils.join("\n",list)).apply();
    }
    final class Api {
        @JavascriptInterface public String initial(){
            JSONObject out=new JSONObject();
            try{
                SharedPreferences p=host.preferences;
                for(int i=0;i<3;i++)out.put("file"+i,p.getString(host.KEYS[i]+"_name","Select model"));
                out.put("file3",p.getString("npu_dit_folder_name","Choose NPU folder"));
                out.put("prompt",p.getString("prompt",""));
                out.put("npu",p.getBoolean("use_npu_dit",true));
                out.put("flash",p.getBoolean("flash_enabled",true));
                out.put("qwen",p.getBoolean("qwen_vulkan",false));
                out.put("steps",p.getInt("ui_steps",3));
                out.put("res",p.getString("ui_resolution","512 × 512"));
                out.put("backend",p.getString("ui_backend","NPU + CPU"));
                out.put("mode",p.getString("ui_mode","latent"));
                out.put("seedRandom",p.getBoolean("ui_random",true));
                out.put("seed",p.getString("ui_seed","42"));
            }catch(Exception ignored){}
            return out.toString();
        }
        @JavascriptInterface public void file(int n){
            host.runOnUiThread(()->{if(n==3)host.chooseNpuDiTFolder();else if(n>=0&&n<3)host.pick(n);});
        }
        @JavascriptInterface public void generate(String json){
            host.runOnUiThread(()->{
                try{
                    if(host.running){host.cancelNative();status("Stopping...");return;}
                    JSONObject state=new JSONObject(json);
                    int count=Math.max(1,Math.min(12,state.optInt("steps",3)));
                    String res=state.optString("resolution","512 × 512");
                    String backend=state.optString("backend","NPU + CPU");
                    String mode=state.optString("preview","latent");
                    boolean npu=backend.equals("NPU + CPU");
                    // This specific A16W4 compile supports 512x512 only.
                    if(npu&&!res.equals("512 × 512")){
                        Toast.makeText(host,"NPU models require 512 × 512",Toast.LENGTH_LONG).show();return;
                    }
                    host.prompt.setText(state.optString("prompt",""));
                    host.resolution.setSelection(res.equals("512 × 512")?1:res.equals("384 × 384")?0:1);
                    host.steps.setSelection(count-1);
                    host.decoderType.setSelection(mode.equals("taesd")?0:2);
                    showLive=!mode.equals("off");
                    host.useNpuDiT.setChecked(npu);
                    host.backend.setSelection(backend.equals("CPU")?2:0);
                    host.qwenVulkan.setChecked(state.optBoolean("qwenVulkan",false));
                    host.flash.setChecked(state.optBoolean("flash",true));
                    host.lowRamEncoder.setChecked(false);
                    host.weightsMode.setSelection(0);
                    host.q4Fp16.setChecked(false);
                    host.cacheMode.setSelection(0);
                    host.cpuThreads.setSelection(2);
                    host.profileLayers.setChecked(false);
                    host.seed.setText(state.optBoolean("random",true)?"-1":state.optString("seed","42"));
                    host.preferences.edit().putInt("ui_steps",count)
                        .putString("ui_resolution",res).putString("ui_backend",backend)
                        .putString("ui_mode",mode).putBoolean("ui_random",state.optBoolean("random",true))
                        .putString("ui_seed",state.optString("seed","42")).apply();
                    host.start();
                }catch(Exception e){error("Settings error: "+e.getMessage());}
            });
        }
        @JavascriptInterface public void cancel(){
            host.runOnUiThread(()->{if(host.running)host.cancelNative();});
        }
        @JavascriptInterface public void history(){
            host.executor.execute(()->{
                String raw=host.preferences.getString("pocket_history","");
                for(String value:raw.split("\n")){
                    if(!value.startsWith("content://"))continue;
                    try(InputStream stream=host.getContentResolver().openInputStream(Uri.parse(value))){
                        if(stream==null)continue;
                        Bitmap bitmap=BitmapFactory.decodeStream(stream);
                        if(bitmap!=null){
                            Bitmap small=Bitmap.createScaledBitmap(bitmap,256,256,true);
                            event("historyItem",JSONObject.quote(png(small))+","+JSONObject.quote(value));
                            if(small!=bitmap)small.recycle();
                            bitmap.recycle();
                        }
                    }catch(Exception ignored){}
                }
                event("historyDone","");
            });
        }
        @JavascriptInterface public void diagnostic(String what){
            host.runOnUiThread(()->{
                if("copy".equals(what))host.copyDiagnostics();
                if("share".equals(what))host.shareDiagnostics();
                if("saved".equals(what))host.copySavedLog(false);
            });
        }
    }
}
