package dev.card.anima;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.PowerManager;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.content.Intent;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    static { System.loadLibrary("animabridge"); }
    public native String devices();
    public native void cancelNative();
    public native int[] generateNative(String model, String encoder, String decoder, boolean tae,
        String prompt, int width, int height, int steps, long seed, float cfg,
        String backend, boolean flash, boolean diskEncoder, boolean profileLayers,
        boolean residentWeights, boolean q4Fp16Compute, int cacheProfile, int cpuThreads,
        boolean useNpuDiT);
    public native String benchmarkMatmulNative();
    public native String probeNpuNative();

    final String[] KEYS={"model","encoder","decoder"};
    final String[] URLS={
        "https://huggingface.co/vanes430/Anima-Turbo-V1.1-GGUF/resolve/main/anima-turbo-v1.1-Q4_0.gguf?download=true",
        "https://huggingface.co/mradermacher/Qwen3-0.6B-Base-GGUF",
        "https://github.com/madebyollin/taehv/raw/refs/heads/main/safetensors/taew2_1.safetensors",
        "https://huggingface.co/circlestone-labs/Anima/resolve/main/split_files/vae/qwen_image_vae.safetensors?download=true"
    };
    SharedPreferences preferences;
    final TextView[] files = new TextView[3];
    TextView status, outputs, devices, logView, liveMetrics;
    final StringBuilder diagnostics=new StringBuilder();
    final Object logLock=new Object();
    final AtomicBoolean pendingLogUi=new AtomicBoolean(false);
    BufferedWriter sessionWriter=null;
    long lastLogFlushMs=0, lastLogRefresh=0;
    volatile long generationStartMs=0, stageStartMs=0, lastNativeEventMs=0;
    volatile String stageName="Idle";
    volatile int completedSteps=0, desiredSteps=4, workerTid=0;
    volatile boolean profilingLayers=false;
    long lastNativeCpuTicks=-1, lastPswpIn=-1, lastPswpOut=-1, lastMajFault=-1;
    EditText prompt, seed;
    Spinner decoderType, resolution, steps, backend, weightsMode, cacheMode, cpuThreads;
    CheckBox flash, lowRamEncoder, profileLayers, q4Fp16, useNpuDiT;
    TextView npuDiTFolderLabel;
    volatile NpuDiTBackend npuDiTBackend;
    Button benchmarkButton, matmulButton, npuButton, compiledNpuButton;
    TextView compiledNpuFile;
    volatile String lastNpuReport="";
    volatile boolean npuTestActive=false, npuServiceBound=false, npuDeathPending=false;
    String npuCurrentMode="none";
    long npuWorkerStartEpochMs=0;
    android.content.ServiceConnection npuServiceConnection;
    final android.os.Handler npuUiHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    Runnable npuTimeout;

    volatile boolean benchmarkRun=false;
    volatile double[] lastStepSeconds=new double[33];
    volatile String runBackend="?", runWeights="?", runResolution="?";
    volatile boolean runFlash=false, runDiskEncoder=false, runQ4Fp16=false;
    volatile int runCache=0, runThreads=2, runSteps=4;
    final ScheduledExecutorService memoryWatcher=Executors.newSingleThreadScheduledExecutor();
    volatile ScheduledFuture<?> memoryTask;
    ImageView image;
    Button go, stop;
    volatile boolean running=false;
    final ExecutorService executor=Executors.newSingleThreadExecutor();
    final int bg=0xff101521, panel=0xff222b3b, hint=0xffb0bed4, accent=0xff819cf8;
    int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    GradientDrawable shape(int col){GradientDrawable d=new GradientDrawable();d.setColor(col);d.setCornerRadius(dp(11));return d;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    void add(LinearLayout parent,View view,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(view,p);}
    Button button(String label,Runnable click){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(13);b.setBackground(shape(panel));b.setOnClickListener(v->click.run());return b;}
    Spinner spinner(String[] values,int index){Spinner s=new Spinner(this);ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);s.setAdapter(a);s.setSelection(index);s.setBackground(shape(panel));s.setPadding(dp(12),dp(10),dp(8),dp(10));return s;}
    void notifyStatus(String s){runOnUiThread(()->{if(status!=null)status.setText(s);});}
    void setStage(String s){
        if(!s.equals(stageName)){
            stageName=s;
            stageStartMs=SystemClock.elapsedRealtime();
        }
    }
    @SuppressWarnings("unused") public void onNativeStatus(String s){
        lastNativeEventMs=SystemClock.elapsedRealtime();
        if(s.startsWith("Sampling ")){
            try{
                int slash=s.indexOf('/');
                int step=Integer.parseInt(s.substring("Sampling ".length(),slash));
                int tail=slash+1;
                while(tail<s.length()&&Character.isDigit(s.charAt(tail)))tail++;
                int total=Integer.parseInt(s.substring(slash+1,tail));
                if(total==desiredSteps){
                    if(step>completedSteps){
                        completedSteps=step;
                        try{
                            int start=s.indexOf(" · ");
                            int end=s.indexOf(" s/step",start);
                            if(start>=0&&end>start)lastStepSeconds[step]=Double.parseDouble(s.substring(start+3,end));
                        }catch(Exception ignored){}
                        appendDiagnostic("STEP_DONE "+step+"/"+total+" at "+(lastNativeEventMs-generationStartMs)+"ms; "+s);
                    }
                    setStage(step>=total?"Sampling finished; awaiting decoder":
                        "Diffusion step "+(step+1)+"/"+total+" (last completed "+step+")");
                }
            }catch(Exception ignored){setStage(s);}
        }else if(s.startsWith("Loading weights ")){
            setStage("Preparing "+s.substring(0,Math.min(52,s.length())));
        }else if(s.startsWith("Preparing ")||s.startsWith("Initializing")||s.startsWith("Generating")){
            setStage(s);
        }else if(s.startsWith("Returning")){
            setStage(s);
        }
        // Never force a synchronous status update for each weight-loading callback.
        scheduleLogUiRefresh();
    }
    @SuppressWarnings("unused") public void onNativeLog(int level,String message){
        lastNativeEventMs=SystemClock.elapsedRealtime();
        if(message.contains("ANIMA_DIAG_BEGIN")){
            int layer=message.indexOf("layer=");
            String component=message.contains("model=taehv")?"Decoding image with TAE":
                message.contains("model=qwen3")?"Encoding text with Qwen":
                "Diffusion step "+Math.min(desiredSteps,completedSteps+1)+"/"+desiredSteps;
            setStage(component+" · "+(layer>=0?message.substring(layer,Math.min(message.length(),layer+50)).split(" ---")[0]:"graph"));
        }else if(message.contains("anima executing segment ")){
            setStage("Diffusion step "+Math.min(desiredSteps,completedSteps+1)+"/"+desiredSteps+" · GPU graph running");
        }else if(message.contains("qwen3 executing segment ")){
            setStage("Encoding text prompt with Qwen");
        }else if(message.contains("loading 685/685 tensors")){
            setStage("Loading Anima diffusion weights");
        }else if(message.contains("decoding ") && message.contains("latents")){
            setStage("Decoding generated image");
        }else if(message.contains("saving")){
            setStage("Saving generated image");
        }
        appendDiagnostic("ENGINE/"+level+"  "+message);
    }
    void scheduleLogUiRefresh(){
        long now=SystemClock.uptimeMillis();
        if(logView==null || now-lastLogRefresh<400 || !pendingLogUi.compareAndSet(false,true))return;
        lastLogRefresh=now;
        runOnUiThread(()->{pendingLogUi.set(false);showDiagnostics();});
    }
    void appendDiagnostic(String message){
        if(message==null)return;
        if(!message.endsWith("\n"))message+="\n";
        final String written=message;
        synchronized(logLock){
            diagnostics.append(written);
            if(diagnostics.length()>260000)diagnostics.delete(0,diagnostics.length()-210000);
            if(sessionWriter!=null){
                try{
                    sessionWriter.write(written);
                    long now=SystemClock.elapsedRealtime();
                    if(now-lastLogFlushMs>=1800 || written.contains("ANIMA_DIAG_") ||
                       written.contains("STEP_DONE") || written.contains("ERROR ")){
                        sessionWriter.flush();
                        lastLogFlushMs=now;
                    }
                }catch(IOException err){android.util.Log.e("AnimaPocket","Persistent log failed",err);}
            }
        }
        scheduleLogUiRefresh();
    }
    void showDiagnostics(){
        if(logView==null)return;
        String lines;
        synchronized(logLock){
            lines=diagnostics.toString();
        }
        if(lines.length()>24000)lines=lines.substring(lines.length()-24000);
        logView.setText(lines.isEmpty()?"No logs captured yet.":lines);
    }
    void beginSessionLog(){
        synchronized(logLock){
            closeSessionLog();
            File dir=new File(getFilesDir(),"diagnostics");
            if(!dir.exists()&&!dir.mkdirs())return;
            File newest=new File(dir,"last-run.txt");
            File previous=new File(dir,"previous-run.txt");
            if(newest.exists()){
                if(previous.exists())previous.delete();
                if(!newest.renameTo(previous))android.util.Log.w("AnimaPocket","Couldn't rotate old log");
            }
            try{
                sessionWriter=new BufferedWriter(new FileWriter(newest,false),16384);
                lastLogFlushMs=0;
            }catch(IOException err){android.util.Log.e("AnimaPocket","Cannot create persistent log",err);}
        }
    }
    void closeSessionLog(){
        synchronized(logLock){
            if(sessionWriter!=null){
                try{sessionWriter.flush();sessionWriter.close();}catch(IOException ignored){}
                sessionWriter=null;
            }
        }
    }
    File savedLog(boolean previous){
        return new File(new File(getFilesDir(),"diagnostics"),previous?"previous-run.txt":"last-run.txt");
    }
    String loadSavedLog(boolean previous)throws IOException{
        File f=savedLog(previous);
        if(!f.isFile())throw new IOException("No saved "+(previous?"previous":"latest")+" run yet");
        if(f.length()>1024*1024)throw new IOException("Saved log exceeds 1 MB; use a later export method");
        StringBuilder out=new StringBuilder();
        try(BufferedReader in=new BufferedReader(new FileReader(f))){
            String line;while((line=in.readLine())!=null)out.append(line).append('\n');
        }
        return out.toString();
    }
    void copyText(String title,String value){
        android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(android.content.ClipData.newPlainText(title,value));
        Toast.makeText(this,title+" copied",Toast.LENGTH_SHORT).show();
    }
    void copyDiagnostics(){
        String s; synchronized(logLock){s=diagnostics.toString();}
        copyText("Current Anima log",s);
    }
    void copySavedLog(boolean previous){
        try{
            // Always flush the ongoing session before exposing its last recorded state.
            synchronized(logLock){if(sessionWriter!=null)sessionWriter.flush();}
            copyText(previous?"Previous Anima log":"Saved Anima log",loadSavedLog(previous));
        }catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    void shareDiagnostics(){
        String s; synchronized(logLock){s=diagnostics.toString();}
        Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT,s);
        startActivity(Intent.createChooser(i,"Share Anima Pocket diagnostics"));
    }
    String previousProcessExit(){
        if(Build.VERSION.SDK_INT<30)return "Process-exit history unavailable";
        try{
            ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
            java.util.List<ApplicationExitInfo> entries=
                am.getHistoricalProcessExitReasons(getPackageName(),0,3);
            if(entries.isEmpty())return "No previous process-exit record";
            ApplicationExitInfo last=entries.get(0);
            return "Last process exit: reason="+last.getReason()+" (LMK="+
                ApplicationExitInfo.REASON_LOW_MEMORY+", ANR="+ApplicationExitInfo.REASON_ANR+
                ", nativeCrash="+ApplicationExitInfo.REASON_CRASH_NATIVE+
                "), RSS="+last.getRss()+"KiB PSS="+last.getPss()+"KiB at "+
                new java.util.Date(last.getTimestamp())+"; "+last.getDescription();
        }catch(Exception e){return "Process-exit history error: "+e;}
    }
    void link(String s){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(s)));}

    @Override public void onCreate(Bundle b){
        super.onCreate(b);preferences=getSharedPreferences("files",MODE_PRIVATE);
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(bg);scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(20),dp(20),dp(40));scroll.addView(root);setContentView(scroll);
        TextView title=text("ANIMA / POCKET",24,Color.WHITE);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);add(root,title,0);
        add(root,text("ARM64 · Qwen3-0.6B-Base GGUF · streamed low-RAM profiles",12,hint),0);
        add(root,text("MODEL FILES",17,accent),22);
        add(root,text("Download in your browser, then select local files. Browser Downloads survive app resets. The app caches working copies.",12,hint),2);
        String[] labels={"01   Anima GGUF","02   Qwen3-0.6B-Base GGUF / safetensors","03   TAEW2.1 or full VAE"};
        for(int i=0;i<3;i++){
            final int n=i;add(root,text(labels[i],14,Color.WHITE),12);
            files[i]=text(preferences.getString(KEYS[i]+"_name","No file selected"),12,hint);add(root,files[i],0);
            LinearLayout r=new LinearLayout(this);
            Button pick=button("Choose file",()->pick(n));r.addView(pick,new LinearLayout.LayoutParams(0,dp(46),1));
            Button download=button(n==1?"Browse GGUF ↗":"Download ↗",()->link(URLS[n==2 && decoderType!=null && decoderType.getSelectedItemPosition()==1?3:n]));
            LinearLayout.LayoutParams dp1=new LinearLayout.LayoutParams(0,dp(46),1);dp1.leftMargin=dp(8);r.addView(download,dp1);add(root,r,4);
        }
        add(root,text("Decoder mode",13,hint),10);
        decoderType=spinner(new String[]{"TAEW2.1 (recommended)","Full Qwen VAE"},0);add(root,decoderType,2);
        add(root,button("Inspect model headers (no ADB)",this::inspectModelFiles),12);
        add(root,text("GENERATION",17,accent),22);
        prompt=new EditText(this);prompt.setTextColor(Color.WHITE);prompt.setTextSize(15);prompt.setMinLines(3);
        prompt.setGravity(android.view.Gravity.TOP);prompt.setText(preferences.getString("prompt","anime illustration of a city at night"));
        prompt.setPadding(dp(14),dp(14),dp(14),dp(14));prompt.setBackground(shape(panel));add(root,prompt,5);
        add(root,text("Resolution",13,hint),12);
        resolution=spinner(new String[]{"384 × 384","512 × 512","512 × 384","384 × 512",
            "256 × 256 (performance test)","320 × 320 (performance test)"},0);add(root,resolution,2);
        add(root,text("Sampling steps",13,hint),12);
        steps=spinner(new String[]{"4","2","3","6","8","10"},0);add(root,steps,2);
        add(root,text("2 or 3 steps are faster but can reduce output quality. Original 4 steps remain default.",12,hint),2);
        add(root,text("Backend",13,hint),12);
        backend=spinner(new String[]{"Vulkan auto (no explicit VRAM limit)",
            "Vulkan 1.5 GiB managed budget","CPU only (slow diagnostic)",
            "Vulkan 2.5 GiB managed budget (experiment)"},
            Math.min(3,Math.max(0,preferences.getInt("backend_choice",0))));
        add(root,text("Vulkan uses shared RAM. Auto removes the app's Vulkan budget, but can still freeze or restart this phone. No 5 GiB free-RAM requirement.",12,0xffffc278),6);add(root,backend,2);
        add(root,text("NPU HYBRID · 28 REAL INT4 DIT BLOCKS",15,accent),14);
        useNpuDiT=new CheckBox(this);
        useNpuDiT.setText("Enable real A16W4 MediaTek NPU diffusion (512×512 only)");
        useNpuDiT.setTextColor(0xff94e1a8);
        useNpuDiT.setChecked(preferences.getBoolean("use_npu_dit",false));
        add(root,useNpuDiT,4);
        npuDiTFolderLabel=text(preferences.getString("npu_dit_folder_name",
            "No 28-block model directory selected"),12,hint);
        add(root,npuDiTFolderLabel,2);
        add(root,button("Choose unzipped out-512 folder (all 28 .pte)",this::chooseNpuDiTFolder),4);
        add(root,text("NPU mode keeps existing Qwen/T5 prompt conditioning, Euler sampling and TAE decoding. The 28 diffusion blocks run on the phone's Neuron delegate; other operations stay CPU. Requires ~1.3 GiB extra local storage for cached PTEs. Experimental; may terminate the app under memory pressure.",12,0xffffc278),4);
        add(root,text("Anima weight residency",13,hint),10);
        weightsMode=spinner(new String[]{"Disk-backed (original, more memory-flexible)",
            "GPU-resident weights (experimental; more memory)"},0);
        weightsMode.setSelection(Math.min(1,Math.max(0,preferences.getInt("weights_mode",0))));
        add(root,weightsMode,3);
        add(root,text("Resident mode avoids disk-backed eviction but may exceed 1.5 GiB managed budget. Try with 2.5 GiB or auto only after testing the original profile. Increased risk of driver stalls/reboots.",12,0xffffc278),5);
        lowRamEncoder=new CheckBox(this);
        lowRamEncoder.setText("Low RAM: stream Qwen encoder weights from disk (slower)");
        lowRamEncoder.setChecked(preferences.getBoolean("encoder_disk",false));
        lowRamEncoder.setTextColor(hint);
        add(root,lowRamEncoder,6);
        flash=new CheckBox(this);flash.setText("Experimental diffusion Flash Attention");
        flash.setChecked(preferences.getBoolean("flash_enabled",false));
        flash.setTextColor(hint);add(root,flash,7);
        q4Fp16=new CheckBox(this);
        q4Fp16.setText("EXPERIMENTAL: Q4_0 -> temporary FP16 Vulkan matrix compute");
        q4Fp16.setChecked(preferences.getBoolean("q4_fp16_compute",false));
        q4Fp16.setTextColor(0xffffca85);add(root,q4Fp16,8);
        add(root,text("Stores compact Q4_0 weights; converts only the current matrix into a reusable FP16 GPU scratch buffer. May be faster or slower and use more transient memory.",12,0xffffc278),2);
        add(root,text("Approximate diffusion-output caching",13,hint),10);
        cacheMode=spinner(new String[]{"Off (original)", "EasyCache conservative (0.10)", "EasyCache balanced (0.20)"},
            Math.min(2,Math.max(0,preferences.getInt("cache_choice",0))));
        add(root,cacheMode,4);
        add(root,text("Can skip entire transformer passes when successive steps are similar. Quality may change; four-step models may not benefit. Engine logs report skips.",12,0xffffc278),4);
        add(root,text("CPU threads (Qwen / TAE decoder)",13,hint),10);
        cpuThreads=spinner(new String[]{"2 original","4","6"},
            Math.min(2,Math.max(0,preferences.getInt("cpu_threads_choice",0))));
        add(root,cpuThreads,4);
        profileLayers=new CheckBox(this);
        profileLayers.setText("DIAGNOSTIC: profile individual Anima transformer layers (slower; changes GPU execution)");
        profileLayers.setTextColor(0xffffc278);add(root,profileLayers,7);
        add(root,text("Seed (-1=random)",13,hint),7);
        seed=new EditText(this);seed.setTextColor(Color.WHITE);seed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        seed.setText("-1");seed.setSingleLine();seed.setBackground(shape(panel));seed.setPadding(dp(12),dp(10),dp(12),dp(10));add(root,seed,2);
        go=button("Generate",this::start);go.setBackground(shape(accent));add(root,go,16);
        benchmarkButton=button("Benchmark selected settings (2 diffusion steps)",()->startWithMode(true));
        add(root,benchmarkButton,7);
        add(root,text("Runs 2 steps at selected settings, records first and warm-step times. No automatic multi-profile stress test; change one option at a time.",12,hint),2);
        matmulButton=button("Benchmark Q4_0 vs temp FP16 vs native FP16 (no model needed)",this::runMatmulBenchmark);
        add(root,matmulButton,9);
        add(root,text("NPU PROOF OF CONCEPT",15,accent),15);
        npuButton=button("Detect NPU and run hardware-only FP16 matrix benchmark",this::runNpuProbe);
        add(root,npuButton,4);
        add(root,text("Strict NNAPI hardware-device selection: CPU/GPU fallback disabled. No models required; does not yet run Anima on the NPU. NNAPI may be unavailable even if LiteRT can access NeuroPilot.",12,hint),4);
        add(root,button("Copy latest NPU test report",this::copyNpuProbe),5);
        add(root,button("Share latest NPU native crash (.pb)",this::shareNpuTombstone),4);
        add(root,text("NEUROPILOT / PUBLIC NEURON INFERENCE · ISOLATED",15,accent),16);
        add(root,text("Each test runs in a fresh child process to protect Anima Pocket. Run F for a real MDLA FP16 matrix operation, not just a library check. No CPU/GPU fallback.",12,hint),4);
        add(root,button("A · Phone vendor Neuron runtime (no model)",()->runIsolatedLoaderTest("vendor_runtime")),4);
        add(root,button("B · Bundled Neuron allocator (no model)",()->runIsolatedLoaderTest("allocator_direct")),4);
        add(root,button("C · ExecuTorch lazy backend discovery (no model)",()->runIsolatedLoaderTest("et_lazy")),4);
        add(root,button("D · MediaTek backend direct load (no model)",()->runIsolatedLoaderTest("backend_direct")),4);
        add(root,button("E · Inspect public Neuron Adapter ABI (no model)",()->runIsolatedLoaderTest("vendor_symbols")),4);
        add(root,button("F · Run real MediaTek MDLA FP16 matrix (no CPU fallback)",()->runIsolatedLoaderTest("vendor_fp16")),4);
        add(root,text("F checks native FP16 inference with generated weights; it does not yet accelerate full Anima image generation. Experimental ExecuTorch INT4 .pte below may still crash on this firmware.",12,hint),4);
        add(root,text("NEUROPILOT / ANIMA A16W4 PTE BLOCK TEST",15,accent),16);
        add(root,text("INSTRUMENTED REAL-TIME TRACE: Select the 45.6 MiB anima_block_00_1024_mt6989_a16w4.pte. Performs three passes and counts actual vendor NeuronExecution_compute calls inside the MediaTek delegate. Isolated worker; no synthetic inference.",12,hint),3);
        compiledNpuFile=text(preferences.getString("npu_pte_name","No compiled .pte selected"),12,hint);
        add(root,compiledNpuFile,4);
        add(root,button("Select compiled .pte model",()->pick(3)),5);
        compiledNpuButton=button("Trace INT4 .pte · 3 runs · lazy route",this::runCompiledNpuTest);
        add(root,compiledNpuButton,5);
        add(root,button("Trace INT4 .pte · 3 runs · backend-first",()->runCompiledNpuTest("pte_backend")),5);
        add(root,button("Copy performance history (CSV)",this::copyBenchmarkHistory),6);
        stop=button("Cancel",()->{cancelNative();notifyStatus("Cancel requested…");});stop.setEnabled(false);add(root,stop,7);
        status=text("Ready. Select three files.",13,hint);add(root,status,12);
        liveMetrics=text("Live diagnostic heartbeat appears here during generation.",12,0xff9ac4ee);
        add(root,liveMetrics,8);
        add(root,text("ENGINE DIAGNOSTICS · persistent / no ADB required",15,accent),16);
        LinearLayout diagnosticActions=new LinearLayout(this);
        diagnosticActions.addView(button("Copy log",this::copyDiagnostics),new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams shareParams=new LinearLayout.LayoutParams(0,dp(44),1);shareParams.leftMargin=dp(8);
        diagnosticActions.addView(button("Share log",this::shareDiagnostics),shareParams);
        add(root,diagnosticActions,4);
        LinearLayout savedActions=new LinearLayout(this);
        savedActions.addView(button("Copy saved run",()->copySavedLog(false)),new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams previousParams=new LinearLayout.LayoutParams(0,dp(44),1);
        previousParams.leftMargin=dp(8);
        savedActions.addView(button("Copy previous run",()->copySavedLog(true)),previousParams);
        add(root,savedActions,5);
        add(root,text("Saved logs survive app crashes/reboots, but not uninstalling the app. After a freeze, reopen this version and use Copy saved run BEFORE starting another run.",12,hint),5);
        logView=text("No logs captured yet.",11,hint);
        logView.setTextIsSelectable(true);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(12),dp(10),dp(12),dp(10));
        logView.setBackground(shape(panel));
        add(root,logView,6);
        devices=text("Vulkan probing skipped at launch.",11,hint);add(root,devices,9);
        add(root,button("Probe Vulkan devices (optional)",()->executor.execute(()->{
            try{String detected=devices();runOnUiThread(()->devices.setText("Backends: "+detected.replace('\t',' ')));}
            catch(Throwable e){runOnUiThread(()->devices.setText("Device probe error: "+e.getMessage()));}
        })),5);
        outputs=text("Result saved to Pictures/AnimaPocket",12,hint);add(root,outputs,12);
        image=new ImageView(this);image.setAdjustViewBounds(true);add(root,image,8);
        appendDiagnostic("ANIMA_NPU v0.6.9 real 28-block hybrid NPU path; "+previousProcessExit());
        recoverInterruptedNpuTest();
        if(savedLog(false).isFile()){
            appendDiagnostic("Recovered prior log: "+savedLog(false).length()+" bytes; tap Copy saved run");
        }
    }
    void chooseNpuDiTFolder(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,904);
    }
    void pick(int n){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,900+n);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||request<900||request>904)return;
        Uri uri=data.getData();if(uri==null)return;
        if(request==904){
            try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch(SecurityException e){Toast.makeText(this,"Could not persist folder permission",Toast.LENGTH_LONG).show();return;}
            String title=uri.getLastPathSegment();
            if(title==null)title="Selected model directory";
            preferences.edit().putString("npu_dit_folder",uri.toString())
                .putString("npu_dit_folder_name",title).apply();
            npuDiTFolderLabel.setText(title+" · NPU directory selected");
            appendDiagnostic("ANIMA_NPU_DIT_FOLDER_SELECTED "+title);
            return;
        }
        int n=request-900;
        if(n==3){
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(SecurityException ignored){}
            String name=fileName(uri);
            if(!name.toLowerCase(Locale.ROOT).endsWith(".pte")){
                Toast.makeText(this,"Expected a compiled ExecuTorch .pte model",Toast.LENGTH_LONG).show();
                return;
            }
            preferences.edit().putString("npu_pte",uri.toString()).putString("npu_pte_name",name).apply();
            compiledNpuFile.setText(name+" · selected; not yet executed");
            appendDiagnostic("ANIMA_PTE_SELECTED "+name+" (hardware execution unverified)");
            return;
        }
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(SecurityException e){Toast.makeText(this,"Read grant not persistent",Toast.LENGTH_LONG).show();}
        String name=fileName(uri);
        preferences.edit().putString(KEYS[n],uri.toString()).putString(KEYS[n]+"_name",name).apply();
        files[n].setText(name+" · checking header...");
        final String selectedUri=uri.toString();
        executor.execute(()->{
            String warning=null;
            String fmt=null;
            try{
                byte[] h=sourcePrefix(uri,n);
                validateSourceFormat(h,n,name);
                fmt=headerFormat(h,Long.MAX_VALUE);
                logOnUi("Selected "+n+": "+fmt+" header verified (tensor compatibility not yet checked)");
            }catch(Exception e){
                warning=e.getMessage();
                logOnUi("Selected "+n+" invalid: "+warning);
            }
            final String error=warning, validFormat=fmt;
            runOnUiThread(()->{
                if(!selectedUri.equals(preferences.getString(KEYS[n],"")))return;
                if(error==null){
                    files[n].setText(name+" · "+validFormat+" header OK");
                    files[n].setTextColor(0xff9ce6af);
                }else{
                    files[n].setText(name+" · INVALID FORMAT");
                    files[n].setTextColor(0xffffa0a0);
                    if(status!=null)status.setText(error);
                }
            });
        });
    }
    String fileName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}
        return String.valueOf(uri.getLastPathSegment());
    }
    void busy(boolean b){
        running=b;runOnUiThread(()->{go.setEnabled(!b);stop.setEnabled(b);
            if(benchmarkButton!=null)benchmarkButton.setEnabled(!b);
            if(matmulButton!=null)matmulButton.setEnabled(!b);
            if(npuButton!=null)npuButton.setEnabled(!b);
            if(compiledNpuButton!=null)compiledNpuButton.setEnabled(!b);
            if(b)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }
    @SuppressWarnings("unused") public float[] onNpuTransformerBlock(
        int index,float[] x,float[] context,float[] step,float[] temb){
        NpuDiTBackend active=npuDiTBackend;
        if(active==null)throw new IllegalStateException("NPU hybrid bridge was not prepared");
        long tick=SystemClock.elapsedRealtime();
        float[] result=active.forward(index,x,context,step,temb);
        appendDiagnostic("ANIMA_NPU_DIT_BLOCK_OK index="+index+" ms="+
            (SystemClock.elapsedRealtime()-tick)+" count="+active.getForwardedCount());
        return result;
    }
    void start(){startWithMode(false);}
    void startWithMode(boolean benchmark){
        if(running)return;
        if(useNpuDiT!=null && useNpuDiT.isChecked()){
            new android.app.AlertDialog.Builder(this)
                .setTitle("Experimental 28-block NeuroPilot generation")
                .setMessage("The unzipped A16W4 models will be staged into app storage (~1.3 GiB), and native NPU modules will execute in the generating process. A vendor driver failure can close the app. Test while the phone is cool and well charged.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Proceed",(dialog,which)->startUnchecked(benchmark))
                .show();
        }else if(backend.getSelectedItemPosition()!=2){
            new android.app.AlertDialog.Builder(this)
                .setTitle(benchmark?"Benchmark GPU memory warning":"Vulkan memory and driver warning")
                .setMessage("GPU allocations use shared RAM. GPU-resident weights and auto memory mode are experimental; either can freeze or restart this phone. Never run a performance benchmark unattended. Keep layer profiling OFF for valid timing.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Proceed",(dialog,which)->startUnchecked(benchmark))
                .show();
        }else startUnchecked(benchmark);
    }
    void startUnchecked(boolean benchmark){
        if(running)return;
        String[] selected=new String[3];for(int n=0;n<3;n++){selected[n]=preferences.getString(KEYS[n],null);
            if(selected[n]==null){Toast.makeText(this,"Choose all model files",Toast.LENGTH_SHORT).show();return;}}
        String p=prompt.getText().toString().trim();if(p.isEmpty()){Toast.makeText(this,"Enter a prompt",Toast.LENGTH_SHORT).show();return;}
        String[] wh=resolution.getSelectedItem().toString().replace(" ","").split("×");
        int w=Integer.parseInt(wh[0].replaceAll("[^0-9]","")),h=Integer.parseInt(wh[1].replaceAll("[^0-9]",""));
        int s=benchmark?2:Integer.parseInt(steps.getSelectedItem().toString());
        final boolean enableNpu=useNpuDiT!=null && useNpuDiT.isChecked();
        final String npuFolder=preferences.getString("npu_dit_folder",null);
        if(enableNpu && (w!=512 || h!=512)){
            Toast.makeText(this,"These PTEs require 512×512. Set resolution to 512 × 512.",Toast.LENGTH_LONG).show();return;
        }
        if(enableNpu && npuFolder==null){
            Toast.makeText(this,"Choose the out-512 folder containing all 28 .pte files.",Toast.LENGTH_LONG).show();return;
        }
        String[] backends={"vulkan-auto","vulkan-1.5","cpu","vulkan-2.5"};
        String target=enableNpu?"cpu":backends[backend.getSelectedItemPosition()];
        boolean residentWeights=!enableNpu && weightsMode.getSelectedItemPosition()==1 && backend.getSelectedItemPosition()!=2;
        if(benchmark && profileLayers.isChecked()){
            Toast.makeText(this,"Turn OFF transformer layer profiling for a valid speed benchmark",Toast.LENGTH_LONG).show();
            return;
        }
        android.app.ActivityManager am=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi=new android.app.ActivityManager.MemoryInfo();
        if(am!=null){
            am.getMemoryInfo(mi);
            // A 5 GiB gate blocked the GPU even when Android said lowMemory=false.
            // Respect Android's actual low-memory signal instead of a static cap.
            if(mi.lowMemory){
                status.setText("Android reports critical low system memory ("+
                    (mi.availMem/1048576)+" MiB free). Close other apps before inference.");
                return;
            }
        }
        boolean tae=decoderType.getSelectedItemPosition()==0, fa=flash.isChecked();
        boolean diskEncoder=lowRamEncoder.isChecked();
        boolean q4Fast=!enableNpu && q4Fp16.isChecked() && backend.getSelectedItemPosition()!=2;
        int cacheProfile=cacheMode.getSelectedItemPosition();
        int threads=new int[]{2,4,6}[cpuThreads.getSelectedItemPosition()];
        preferences.edit().putInt("cache_choice",cacheProfile)
            .putInt("cpu_threads_choice",cpuThreads.getSelectedItemPosition())
            .putBoolean("q4_fp16_compute",q4Fp16.isChecked())
            .putBoolean("encoder_disk",diskEncoder)
            .putBoolean("flash_enabled",fa)
            .putInt("weights_mode",weightsMode.getSelectedItemPosition())
            .putInt("backend_choice",backend.getSelectedItemPosition())
            .putBoolean("use_npu_dit",enableNpu).apply();
        long rand;try{rand=Long.parseLong(seed.getText().toString());}catch(NumberFormatException e){rand=-1;}
        if(rand<0)rand=new SecureRandom().nextLong()&Long.MAX_VALUE;
        final long id=rand;preferences.edit().putString("prompt",p).apply();
        synchronized(logLock){diagnostics.setLength(0);}
        generationStartMs=SystemClock.elapsedRealtime();
        lastNativeEventMs=generationStartMs;
        stageStartMs=generationStartMs;
        completedSteps=0; desiredSteps=s; workerTid=0;
        benchmarkRun=benchmark;
        lastStepSeconds=new double[33];
        runBackend=target; runWeights=residentWeights?"resident":"disk";
        runResolution=w+"x"+h; runFlash=fa; runDiskEncoder=diskEncoder; runQ4Fp16=q4Fast;
        runCache=cacheProfile; runThreads=threads; runSteps=s;
        profilingLayers=!enableNpu && profileLayers.isChecked();
        lastNativeCpuTicks=lastPswpIn=lastPswpOut=lastMajFault=-1;
        setStage("Preparing model files");
        beginSessionLog();
        appendDiagnostic("Anima Pocket v0.4.2 fast sampling, optimized native build; benchmark="+benchmark);
        appendDiagnostic("ANIMA_HYBRID_NPU_ENABLED "+enableNpu+"; native_backend="+target);
        appendDiagnostic("Backend "+target+"; resolution "+w+"x"+h+"; steps "+s+"; tae "+tae+"; flash "+fa+"; encoderDisk "+diskEncoder+"; residentWeights "+residentWeights+"; q4Fp16Compute "+q4Fast+"; cacheProfile "+cacheProfile+
            "; cpuThreads "+threads+"; profileLayers "+profilingLayers);
        if(am!=null)appendDiagnostic("Free system RAM "+(mi.availMem/1048576)+" MiB; lowMemory="+mi.lowMemory);
        for(int n=0;n<3;n++)appendDiagnostic("Selected file "+n+": "+preferences.getString(KEYS[n]+"_name","?"));
        busy(true);
        startMemoryWatcher();
        executor.execute(()->{
            NpuDiTBackend preparedNpu=null;
            try{
                if(enableNpu){
                    preparedNpu=new NpuDiTBackend();
                    preparedNpu.prepare(this,Uri.parse(npuFolder),message->{
                        setStage(message);
                        appendDiagnostic("ANIMA_NPU_DIT_PREPARE "+message);
                    });
                    npuDiTBackend=preparedNpu;
                }
                String[] paths=new String[3];
                for(int n=0;n<3;n++)paths[n]=copyModel(Uri.parse(selected[n]),n);
                long began=System.nanoTime();
                workerTid=android.os.Process.myTid();
                appendDiagnostic("ANIMA_DIAG_NATIVE_THREAD tid="+workerTid);
                int[] pixels=generateNative(paths[0],paths[1],paths[2],tae,p,w,h,s,id,1f,target,fa,diskEncoder,profilingLayers,residentWeights,q4Fast,cacheProfile,threads,enableNpu);
                if(enableNpu && (npuDiTBackend==null || npuDiTBackend.getForwardedCount()<28))
                    throw new IOException("Hybrid NPU blocks were not actually dispatched; refusing CPU-only result");
                if(pixels==null||pixels.length!=w*h)throw new IOException("Invalid native output");
                Bitmap bmp=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
                setStage("Saving finished image");
                Uri saved=save(bmp,id);long seconds=(System.nanoTime()-began)/1000000000L;
                appendDiagnostic("ANIMA_DIAG_SUCCESS total_ms="+(SystemClock.elapsedRealtime()-generationStartMs)+" saved="+saved);
                if(benchmark)recordBenchmarkResult(true,"OK");
                runOnUiThread(()->{image.setImageBitmap(bmp);
                    status.setText((benchmark?"Benchmark complete":"Complete")+" in "+seconds+"s · seed "+id);
                    outputs.setText("Saved "+saved);});
            }catch(Throwable e){
                runOnUiThread(()->{
                    setStage("Generation error");
                    appendDiagnostic("ERROR "+e.toString());
                    if(benchmark)recordBenchmarkResult(false,e.getClass().getSimpleName());
                    showDiagnostics();
                    status.setText("Error: "+e.getMessage()+" — tap Copy log below.");
                });
            }
            finally{
                npuDiTBackend=null;
                if(preparedNpu!=null)preparedNpu.close();
                ScheduledFuture<?> task=memoryTask;
                if(task!=null)task.cancel(false);
                memoryTask=null;
                appendDiagnostic("ANIMA_DIAG_SESSION_END elapsed_ms="+(SystemClock.elapsedRealtime()-generationStartMs));
                closeSessionLog();
                workerTid=0;
                busy(false);
            }
        });
    }

    File perfHistoryFile(){
        return new File(getFilesDir(),"performance-benchmarks.csv");
    }
    void saveBenchRow(String line){
        try(BufferedWriter writer=new BufferedWriter(new FileWriter(perfHistoryFile(),true))){
            writer.write(line);writer.newLine();
        }catch(IOException e){appendDiagnostic("BENCH_SAVE_ERROR "+e);}
    }
    void recordBenchmarkResult(boolean ok,String outcome){
        String row=System.currentTimeMillis()+","+runBackend+","+runWeights+","+runResolution+","+
            (runFlash?"flash":"standard")+","+(runDiskEncoder?"qwenDisk":"qwenRAM")+","+
            (profilingLayers?"layerProfile":"normal")+","+
            String.format(Locale.US,"%.3f",lastStepSeconds[1])+","+
            String.format(Locale.US,"%.3f",lastStepSeconds[2])+","+
            (SystemClock.elapsedRealtime()-generationStartMs)+","+
            (ok?"OK":outcome)+","+(runQ4Fp16?"q4ToFp16":"nativeQ4")+","+
            runCache+","+runThreads+","+runSteps;
        appendDiagnostic("PERF_RESULT CSV="+row);
        saveBenchRow(row);
    }
    void copyBenchmarkHistory(){
        File file=perfHistoryFile();
        if(!file.isFile()){
            Toast.makeText(this,"No benchmark results yet",Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder csv=new StringBuilder("timeEpochMs,backend,residency,resolution,attention,encoder,profiling,step1Seconds,step2Seconds,totalMs,result,q4ComputeMode,cacheProfile,cpuThreads,steps\n");
        try(BufferedReader reader=new BufferedReader(new FileReader(file))){
            String line;
            while((line=reader.readLine())!=null)csv.append(line).append('\n');
            copyText("Anima performance history",csv.toString());
        }catch(IOException e){Toast.makeText(this,"Could not load benchmark history: "+e,Toast.LENGTH_LONG).show();}
    }
    void copyNpuProbe(){
        if(lastNpuReport.isEmpty()){
            Toast.makeText(this,"Run NPU probe first",Toast.LENGTH_SHORT).show();
            return;
        }
        copyText("Anima NPU report",lastNpuReport);
    }
    void shareNpuTombstone(){
        File file=new File(new File(getFilesDir(),"diagnostics"),"npu-tombstone.pb");
        if(!file.isFile()){
            Toast.makeText(this,"No native worker tombstone available yet. Copy the NPU report instead.",Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri=Uri.parse("content://"+NpuCrashProvider.AUTHORITY+"/npu-tombstone.pb");
        Intent send=new Intent(Intent.ACTION_SEND);
        send.setType("application/octet-stream");
        send.putExtra(Intent.EXTRA_STREAM,uri);
        send.setClipData(android.content.ClipData.newUri(getContentResolver(),"Anima NPU native crash",uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send,"Share NPU native crash report"));
    }

    String isolatedNpuExitReport(long sinceEpochMs){
        if(Build.VERSION.SDK_INT<30)return "ApplicationExitInfo unavailable on this Android version";
        try{
            ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
            if(am==null)return "ActivityManager unavailable";
            java.util.List<ApplicationExitInfo> exits=am.getHistoricalProcessExitReasons(null,0,20);
            for(ApplicationExitInfo exit:exits){
                String process=exit.getProcessName();
                if(process==null || !process.endsWith(":npu_probe"))continue;
                if(sinceEpochMs>0 && exit.getTimestamp()<sinceEpochMs-5000)continue;
                StringBuilder result=new StringBuilder("ISOLATED_NPU_EXIT process=")
                    .append(process).append(" reason=").append(exit.getReason())
                    .append(" nativeCrash=").append(ApplicationExitInfo.REASON_CRASH_NATIVE)
                    .append(" signalOrStatus=").append(exit.getStatus())
                    .append(" rssKiB=").append(exit.getRss())
                    .append(" at=").append(new java.util.Date(exit.getTimestamp()))
                    .append("\\nDescription: ").append(exit.getDescription());
                try(InputStream trace=exit.getTraceInputStream()){
                    if(trace!=null){
                        File dir=new File(getFilesDir(),"diagnostics");
                        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create diagnostics directory");
                        File dump=new File(dir,"npu-tombstone.pb");
                        long size=0;
                        try(FileOutputStream output=new FileOutputStream(dump)){
                            byte[] buf=new byte[16384];int n;
                            while((n=trace.read(buf))!=-1){
                                if(size+n>4L*1048576L)break;
                                output.write(buf,0,n);size+=n;
                            }
                            output.getFD().sync();
                        }
                        result.append("\\nAndroid native tombstone captured: ").append(size)
                            .append(" bytes protobuf. Tap Share latest NPU native crash (.pb).");
                    }else result.append("\\nAndroid did not retain a native tombstone stream.");
                }catch(Exception error){result.append("\\nTombstone extraction error: ").append(error);}
                return result.toString();
            }
            return "No isolated NPU worker exit record yet. Android may still be processing the crash.";
        }catch(Exception error){return "Process-exit inspection failed: "+error;}
    }

    void recoverInterruptedNpuTest(){
        if(!preferences.getBoolean("npu_worker_pending",false))return;
        long began=preferences.getLong("npu_worker_start",0);
        executor.execute(()->{
            String report="Previous isolated NPU test interrupted.\\n"+isolatedNpuExitReport(began);
            preferences.edit().putBoolean("npu_worker_pending",false).apply();
            appendDiagnostic(report);
            lastNpuReport=report;
            runOnUiThread(()->{
                if(liveMetrics!=null)liveMetrics.setText(report);
                if(status!=null)status.setText("Recovered last isolated NPU test. Copy NPU report for details.");
                showDiagnostics();
            });
        });
    }

    void finishIsolatedNpu(String report,boolean success){
        if(!npuTestActive)return;
        npuTestActive=false;
        npuDeathPending=false;
        if(npuTimeout!=null)npuUiHandler.removeCallbacks(npuTimeout);
        npuTimeout=null;
        if(npuServiceBound && npuServiceConnection!=null){
            try{unbindService(npuServiceConnection);}catch(Exception ignored){}
            npuServiceBound=false;
        }
        npuServiceConnection=null;
        preferences.edit().putBoolean("npu_worker_pending",false).apply();
        lastNpuReport=report;
        appendDiagnostic((success?"ANIMA_NPU_TEST_RESULT ":"ANIMA_NPU_TEST_ERROR ")+npuCurrentMode+"\n"+report);
        appendDiagnostic("ANIMA_NPU_TEST_END mode="+npuCurrentMode);
        if(status!=null)status.setText(success?"Isolated NPU test complete. Copy NPU report.":"NPU worker failed; app survived. Copy NPU report.");
        if(liveMetrics!=null)liveMetrics.setText(report);
        showDiagnostics();
        busy(false);
    }

    void isolatedNpuWorkerDied(String why){
        if(!npuTestActive || npuDeathPending)return;
        npuDeathPending=true;
        // Don't let BIND_AUTO_CREATE repeatedly restart a crashing native worker.
        if(npuServiceBound && npuServiceConnection!=null){
            try{unbindService(npuServiceConnection);}catch(Exception ignored){}
            npuServiceBound=false;
        }
        npuUiHandler.postDelayed(()->executor.execute(()->{
            String report="NPU WORKER CRASHED OR DISCONNECTED\\n"+why+
                "\\nThe main Anima Pocket process survived.\\n"+
                isolatedNpuExitReport(npuWorkerStartEpochMs);
            runOnUiThread(()->finishIsolatedNpu(report,false));
        }),1500);
    }

    void bindIsolatedNpuWorker(File model,String mode){
        npuWorkerStartEpochMs=System.currentTimeMillis();
        preferences.edit().putBoolean("npu_worker_pending",true)
            .putLong("npu_worker_start",npuWorkerStartEpochMs).apply();
        appendDiagnostic("ISOLATED_NPU_WORKER_START mode="+mode+" model="+
            (model==null?"none":model.getAbsolutePath())+
            " fingerprint="+Build.FINGERPRINT+" abi="+java.util.Arrays.toString(Build.SUPPORTED_ABIS));
        final android.os.Messenger receiver=new android.os.Messenger(
            new android.os.Handler(android.os.Looper.getMainLooper()){
                @Override public void handleMessage(android.os.Message message){
                    if(message.what!=NpuPteService.MSG_RESULT || !npuTestActive)return;
                    Bundle data=message.getData();
                    String report=data==null?"Missing NPU service response":data.getString("report","Missing NPU service response");
                    finishIsolatedNpu(report,!report.startsWith("ISOLATED_NPU_JAVA_ERROR"));
                }
            });
        npuServiceConnection=new android.content.ServiceConnection(){
            @Override public void onServiceConnected(android.content.ComponentName name,android.os.IBinder binder){
                if(!npuTestActive)return;
                try{
                    android.os.Message message=android.os.Message.obtain(null,NpuPteService.MSG_RUN);
                    Bundle data=new Bundle();data.putString("mode",mode);
                    if(model!=null)data.putString("model_path",model.getAbsolutePath());
                    message.setData(data);message.replyTo=receiver;
                    new android.os.Messenger(binder).send(message);
                    appendDiagnostic("ISOLATED_NPU_WORKER_BOUND; mode="+mode);
                }catch(Exception e){isolatedNpuWorkerDied("Could not message worker: "+e);}
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name){
                isolatedNpuWorkerDied("Service process disconnected during native initialization");
            }
            @Override public void onBindingDied(android.content.ComponentName name){
                isolatedNpuWorkerDied("Android reported worker binding died");
            }
            @Override public void onNullBinding(android.content.ComponentName name){
                isolatedNpuWorkerDied("NPU service returned a null binder");
            }
        };
        try{
            npuServiceBound=bindService(new Intent(this,NpuPteService.class),npuServiceConnection,BIND_AUTO_CREATE);
            if(!npuServiceBound){isolatedNpuWorkerDied("Android refused to bind the NPU worker");return;}
            npuTimeout=()->isolatedNpuWorkerDied("No NPU worker response after 120 seconds");
            npuUiHandler.postDelayed(npuTimeout,180000);
        }catch(Exception e){isolatedNpuWorkerDied("Could not start isolated process: "+e);}
    }

    void runIsolatedLoaderTest(String mode){
        if(running || npuTestActive)return;
        npuTestActive=true;
        npuDeathPending=false;
        npuCurrentMode=mode;
        busy(true);
        setStage("Testing NPU loader: "+mode);
        appendDiagnostic("ANIMA_NPU_LOADER_TEST_BEGIN mode="+mode);
        bindIsolatedNpuWorker(null,mode);
    }

    void runCompiledNpuTest(){ runCompiledNpuTest("pte_lazy"); }

    void runCompiledNpuTest(String mode){
        if(running || npuTestActive)return;
        String source=preferences.getString("npu_pte",null);
        if(source==null){Toast.makeText(this,"Select one compiled Anima projection or DiT block .pte",Toast.LENGTH_LONG).show();return;}
        // Projection or single real DiT block. Native execution remains isolated in :npu_probe.
        npuTestActive=true;
        npuDeathPending=false;
        npuCurrentMode=mode;
        busy(true);
        setStage("Preparing isolated Anima NPU .pte test: "+mode);
        appendDiagnostic("ANIMA_NPU_ISOLATED_PTE_TEST_BEGIN mode="+mode+" model="+preferences.getString("npu_pte_name","?"));
        executor.execute(()->{
            try{
                File dir=new File(getFilesDir(),"npu");
                if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create NPU cache directory");
                File target=new File(dir,"anima-projection.pte");
                Uri uri=Uri.parse(source);
                long size=-1;
                try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){
                    if(c!=null&&c.moveToFirst()&&!c.isNull(0))size=c.getLong(0);
                }
                if(size>100L*1048576L)throw new IOException("Select one .pte under 100 MiB, not a ZIP. File is "+(size/1048576L)+" MiB.");
                if(size>0 && dir.getUsableSpace()<size+32L*1048576L)throw new IOException("Insufficient free storage for .pte cache");
                File temporary=new File(dir,"anima-projection.part");
                long copied=0;
                try(InputStream input=getContentResolver().openInputStream(uri);
                    FileOutputStream output=new FileOutputStream(temporary)){
                    if(input==null)throw new IOException("Android did not provide the selected .pte");
                    byte[] buffer=new byte[256*1024];
                    int n;
                    while((n=input.read(buffer))>=0){
                        copied+=n;
                        if(copied>100L*1048576L)throw new IOException("Unexpectedly large .pte file");
                        output.write(buffer,0,n);
                    }
                    output.getFD().sync();
                }
                if(copied<10000 || (size>=0 && copied!=size))throw new IOException("Compiled .pte length mismatch: "+copied+" vs provider="+size);
                if(target.exists()&&!target.delete())throw new IOException("Cannot replace cached .pte");
                if(!temporary.renameTo(target))throw new IOException("Cannot finalize cached .pte");
                appendDiagnostic("ANIMA_NPU_PTE_STAGED bytes="+copied+" path="+target.getAbsolutePath());
                runOnUiThread(()->bindIsolatedNpuWorker(target,mode));
            }catch(Throwable error){
                String detail=android.util.Log.getStackTraceString(error);
                runOnUiThread(()->finishIsolatedNpu("PREPARE_FAILED\\n"+detail,false));
            }
        });
    }

    void runNpuProbe(){
        if(running)return;
        busy(true);
        setStage("Probing dedicated NPU driver");
        if(status!=null)status.setText("Checking NPU driver and compiling a hardware-only FP16 test...");
        executor.execute(()->{
            try{
                String report=probeNpuNative();
                lastNpuReport=report;
                appendDiagnostic("ANIMA_NPU_PROBE_BEGIN\n"+report+"\nANIMA_NPU_PROBE_END");
                saveBenchRow("# NPU_PROBE "+System.currentTimeMillis()+" "+report.replace('\n',';').replace(',','|'));
                runOnUiThread(()->{
                    status.setText("NPU probe finished. Tap Copy NPU probe report.");
                    liveMetrics.setText(report);showDiagnostics();
                });
            }catch(Throwable e){
                lastNpuReport="NPU probe failed: "+e;
                appendDiagnostic("ANIMA_NPU_PROBE_ERROR "+e);
                runOnUiThread(()->status.setText("NPU probe error: "+e));
            }finally{busy(false);}
        });
    }
    void runMatmulBenchmark(){
        if(running)return;
        busy(true);
        setStage("Running Q4_0 vs FP16 Mali benchmark");
        runOnUiThread(()->{if(status!=null)status.setText("Benchmarking isolated GPU matrix multiplication...");});
        executor.execute(()->{
            try{
                String report=benchmarkMatmulNative();
                appendDiagnostic("PERF_MATMUL_BENCH\n"+report);
                saveBenchRow("# MATRIX_KERNEL "+new java.util.Date()+": "+report.replace('\n',';'));
                runOnUiThread(()->{
                    if(status!=null)status.setText("Matrix benchmark completed. Copy performance history or log.");
                    if(liveMetrics!=null)liveMetrics.setText(report);
                    showDiagnostics();
                });
            }catch(Throwable e){
                appendDiagnostic("PERF_MATMUL_ERROR "+e);
                runOnUiThread(()->{if(status!=null)status.setText("Matrix benchmark error: "+e);});
            }finally{busy(false);}
        });
    }

    Map<String,Long> readKernelNumbers(String path,boolean colon){
        Map<String,Long> m=new HashMap<>();
        try(BufferedReader r=new BufferedReader(new FileReader(path))){
            for(int n=0;n<300;n++){
                String line=r.readLine();if(line==null)break;
                String[] pieces=colon?line.split(":",2):line.trim().split("\\s+",2);
                if(pieces.length<2)continue;
                String[] val=pieces[1].trim().split("\\s+");
                try{m.put(pieces[0].trim(),Long.parseLong(val[0]));}
                catch(NumberFormatException ignored){}
            }
        }catch(Exception ignored){}
        return m;
    }
    long count(Map<String,Long> map,String key){
        Long n=map.get(key);return n==null?-1:n;
    }
    String readFirstLine(String path){
        try(BufferedReader r=new BufferedReader(new FileReader(path))){
            String s=r.readLine();return s==null?"":s;
        }catch(Exception ignored){return "";}
    }
    String currentWorkerState(){
        if(workerTid<=0)return "none";
        String stat=readFirstLine("/proc/self/task/"+workerTid+"/stat");
        int i=stat.lastIndexOf(") ");
        if(i<0)return "unavailable";
        try{
            String[] fields=stat.substring(i+2).split("\\s+");
            long ticks=Long.parseLong(fields[11])+Long.parseLong(fields[12]);
            long delta=lastNativeCpuTicks<0?-1:ticks-lastNativeCpuTicks;
            lastNativeCpuTicks=ticks;
            return "state="+fields[0]+" cpuTicksDelta="+delta;
        }catch(Exception e){return "parse-error";}
    }
    String zramStats(){
        String line=readFirstLine("/sys/block/zram0/mm_stat");
        if(line.isEmpty())return "n/a";
        String[] parts=line.trim().split("\\s+");
        if(parts.length<3)return "n/a";
        try{return "original="+(Long.parseLong(parts[0])/1048576L)+
            "MiB compressed="+(Long.parseLong(parts[1])/1048576L)+
            "MiB allocated="+(Long.parseLong(parts[2])/1048576L)+"MiB";}
        catch(NumberFormatException e){return "n/a";}
    }
    void startMemoryWatcher(){
        ScheduledFuture<?> old=memoryTask;
        if(old!=null)old.cancel(false);
        memoryTask=memoryWatcher.scheduleAtFixedRate(()->{
            if(!running)return;
            try{
                long now=SystemClock.elapsedRealtime();
                ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
                if(am!=null)am.getMemoryInfo(info);
                android.os.Debug.MemoryInfo process=new android.os.Debug.MemoryInfo();
                android.os.Debug.getMemoryInfo(process);
                Map<String,Long> mem=readKernelNumbers("/proc/meminfo",true);
                Map<String,Long> self=readKernelNumbers("/proc/self/status",true);
                Map<String,Long> vm=readKernelNumbers("/proc/vmstat",false);
                long in=count(vm,"pswpin"),out=count(vm,"pswpout"),maj=count(vm,"pgmajfault");
                String swapDelta="in="+(lastPswpIn<0?"n/a":String.valueOf(in-lastPswpIn))+
                    " out="+(lastPswpOut<0?"n/a":String.valueOf(out-lastPswpOut))+
                    " majfault="+(lastMajFault<0?"n/a":String.valueOf(maj-lastMajFault));
                if(in>=0)lastPswpIn=in;
                if(out>=0)lastPswpOut=out;
                if(maj>=0)lastMajFault=maj;
                PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
                int therm=pm==null?-1:pm.getCurrentThermalStatus();
                String live="t="+((now-generationStartMs)/1000)+"s phase="+stageName+
                    " phaseFor="+((now-stageStartMs)/1000)+"s noEngineLog="+((now-lastNativeEventMs)/1000)+"s"+
                    " | MemAvailable="+(count(mem,"MemAvailable")/1024)+"MiB"+
                    " availMemAPI="+(info.availMem/1048576)+"MiB lowMemory="+info.lowMemory+
                    " | PSS="+(process.getTotalPss()/1024)+"MiB swappedTotalKB="+process.getMemoryStat("summary.total-swap")+"kB"+
                    " VmRSS="+(count(self,"VmRSS")/1024)+"MiB VmSwap="+(count(self,"VmSwap")/1024)+"MiB"+
                    " | SwapTotal="+(count(mem,"SwapTotal")/1024)+"MiB SwapFree="+(count(mem,"SwapFree")/1024)+"MiB"+
                    " | zram "+zramStats()+" | swapPagesDelta "+swapDelta+
                    " | nativeThread "+currentWorkerState()+" | thermal="+therm;
                appendDiagnostic("HEARTBEAT "+live);
                runOnUiThread(()->{
                    if(liveMetrics!=null)liveMetrics.setText(live);
                    if(status!=null)status.setText(stageName+" · elapsed "+((SystemClock.elapsedRealtime()-generationStartMs)/1000)+"s");
                });
                // The native Vulkan device can stall without triggering an Android lowMemory signal.
                // Continue to preserve the original best-effort critical memory cancellation only.
                if(info.lowMemory || info.availMem<1024L*1048576L){
                    appendDiagnostic("CRITICAL MEMORY: Android lowMemory="+info.lowMemory+
                        " availMiB="+(info.availMem/1048576L)+"; requesting cancellation");
                    cancelNative();
                }
            }catch(Throwable err){appendDiagnostic("HEARTBEAT ERROR "+err);}
        },1,3,TimeUnit.SECONDS);
    }
    @Override public void onTrimMemory(int level){
        super.onTrimMemory(level);
        if(running){
            logOnUi("Android onTrimMemory level="+level);
            // 20 = TRIM_MEMORY_UI_HIDDEN, not critically low memory. Do not
            // cancel work just because the user briefly switches apps.
            if(level==TRIM_MEMORY_RUNNING_CRITICAL || level==TRIM_MEMORY_COMPLETE){
                logOnUi("CRITICAL trim event; cancelling inference");
                cancelNative();
            }
        }
    }
    @Override protected void onDestroy(){
        ScheduledFuture<?> task=memoryTask;
        if(task!=null)task.cancel(false);
        memoryWatcher.shutdownNow();
        if(npuTimeout!=null)npuUiHandler.removeCallbacks(npuTimeout);
        if(npuServiceBound && npuServiceConnection!=null){
            try{unbindService(npuServiceConnection);}catch(Exception ignored){}
            npuServiceBound=false;
        }
        if(npuTestActive)preferences.edit().putBoolean("npu_worker_pending",false).apply();
        if(running && !npuTestActive)cancelNative();
        closeSessionLog();
        super.onDestroy();
    }
    String copyModel(Uri uri,int index)throws Exception{
        byte[] originalHeader=sourcePrefix(uri,index);
        // Reject wrong/mislabelled model files before copying hundreds of MB.
        validateSourceFormat(originalHeader,index,fileName(uri));
        File dir=new File(getFilesDir(),"weights");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create cache");
        long size=-1;try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))size=c.getLong(0);}
        if(index==0 && size>0 && size<200L*1048576L)
            throw new IOException("Android provides only "+(size/1048576)+" MiB for the main Anima model. Expected about 1.24 GB.");
        byte[] hash=MessageDigest.getInstance("SHA-256").digest((uri.toString()+":"+size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder key=new StringBuilder();for(int n=0;n<12;n++)key.append(String.format(Locale.US,"%02x",hash[n]));
        String suffix=fileName(uri).toLowerCase(Locale.ROOT).endsWith(".gguf")?".gguf":".safetensors";
        File to=new File(dir,"m"+index+"-"+key+suffix);
        if(to.exists()){
            if(to.length()>0&&(size<0||to.length()==size)){
                byte[] cachedHeader;
                try(FileInputStream input=new FileInputStream(to)){cachedHeader=prefix(input);}
                if(java.util.Arrays.equals(cachedHeader,originalHeader)){
                    try{
                        validateWeights(to,index);
                        return to.getAbsolutePath();
                    }catch(IOException corrupt){
                        logOnUi("Removing bad cached model "+index+": "+corrupt.getMessage());
                    }
                }
            }
            if(!to.delete())throw new IOException("Failed to remove stale cached model "+index);
        }
        if(size>0&&dir.getUsableSpace()<size+100*1048576L)throw new IOException("Not enough disk for local model cache");
        File tmp=new File(dir,to.getName()+".part");long bytes=0;
        notifyStatus("Importing "+fileName(uri)+"…");
        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp),2097152)){
            if(in==null)throw new IOException("Cannot read file");
            byte[] buf=new byte[1048576];int got;long mark=0;
            while((got=in.read(buf))!=-1){out.write(buf,0,got);bytes+=got;if(bytes-mark>33554432){mark=bytes;notifyStatus("Importing "+fileName(uri)+" · "+(bytes/1048576)+" MiB");}}
        }catch(Exception e){tmp.delete();throw e;}
        if(size>=0&&bytes!=size){tmp.delete();throw new IOException("Incomplete model copy");}
        if(!tmp.renameTo(to)){tmp.delete();throw new IOException("Could not finish cache");}
        try{validateWeights(to,index);}
        catch(IOException invalid){to.delete();throw invalid;}
        return to.getAbsolutePath();
    }
    // Read only 32 bytes: inspecting a 1.24 GB model must be immediate.
    byte[] prefix(InputStream stream)throws IOException{
        byte[] b=new byte[32];
        int pos=0;
        while(pos<b.length){
            int n=stream.read(b,pos,b.length-pos);
            if(n<0)break;
            if(n==0)break;
            pos+=n;
        }
        return java.util.Arrays.copyOf(b,pos);
    }
    String bytesInfo(byte[] b){
        StringBuilder h=new StringBuilder(),ascii=new StringBuilder();
        for(byte value:b){
            int v=value&0xff;
            h.append(String.format(Locale.US,"%02X ",v));
            ascii.append(v>=32&&v<127?(char)v:'.');
        }
        return "hex=["+h.toString().trim()+"] ascii=["+ascii+"]";
    }
    // A filename is not proof of GGUF. Some downloads have a .gguf extension
    // but actually contain serialized PyTorch data or an HTML error page.
    String headerFormat(byte[] hdr,long size){
        if(hdr.length>=8&&hdr[0]=='G'&&hdr[1]=='G'&&hdr[2]=='U'&&hdr[3]=='F'){
            long version=((long)hdr[4]&255)|(((long)hdr[5]&255)<<8)|
                (((long)hdr[6]&255)<<16)|(((long)hdr[7]&255)<<24);
            if(version==2||version==3)return "GGUF";
            return "unsupported GGUF version "+version;
        }
        long jsonSize=0;
        if(hdr.length>=9)for(int n=0;n<8;n++)jsonSize|=(long)(hdr[n]&255)<<(8*n);
        if(hdr.length>=9&&jsonSize>2&&jsonSize<100000000L&&
            (size==Long.MAX_VALUE||jsonSize+8<size)&&hdr[8]=='{')
            return "safetensors";
        if(hdr.length>=2&&hdr[0]=='P'&&hdr[1]=='K')return "ZIP (not model weights)";
        if(hdr.length>=2&&(hdr[0]&255)==0x80&&(hdr[1]&255)>=2)
            return "Python pickle (not GGUF)";
        if(hdr.length>=5&&hdr[0]=='<'&&hdr[1]=='!')return "HTML response (not model weights)";
        if(hdr.length>=5&&hdr[0]=='<'&&hdr[1]=='h')return "HTML response (not model weights)";
        if(hdr.length>=7&&hdr[0]=='v'&&hdr[1]=='e'&&hdr[2]=='r'&&hdr[3]=='s')
            return "Git LFS pointer (not weights)";
        return "unrecognized binary";
    }
    boolean isValidHeader(byte[] hdr,long size){
        String fmt=headerFormat(hdr,size);
        return fmt.equals("GGUF")||fmt.equals("safetensors");
    }
    void validateSourceFormat(byte[] hdr,int index,String name)throws IOException{
        String fmt=headerFormat(hdr,Long.MAX_VALUE);
        String lower=name.toLowerCase(Locale.ROOT);
        boolean gguf=fmt.equals("GGUF");
        boolean safetensors=fmt.equals("safetensors");
        if(index==0&&!gguf)
            throw new IOException("Anima diffusion model must be a GGUF file; actual header: "+fmt+". Select the correct Anima model.");
        if(index==2&&!safetensors)
            throw new IOException("TAE / VAE decoder must be safetensors; actual header: "+fmt+".");
        if(index==1){
            if(!gguf&&!safetensors)
                throw new IOException("Qwen text encoder is not GGUF or safetensors (actual: "+fmt+"). "+
                    "A .gguf extension does not convert the bytes. Download Qwen3-0.6B-Base Q4_K_M "+
                    "from the recommended GGUF repository, then select that new file.");
            if(lower.endsWith(".gguf")&&!gguf)
                throw new IOException("Qwen filename says GGUF but contains "+fmt+
                    ". Select a genuine Qwen3-0.6B-Base GGUF or a correctly named safetensors file.");
            if(lower.endsWith(".safetensors")&&!safetensors)
                throw new IOException("Qwen filename says safetensors but contains "+fmt+".");
        }
    }
    void logOnUi(String value){appendDiagnostic(value);}
    byte[] sourcePrefix(Uri uri,int index)throws IOException{
        long declared=-1;
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){
            if(c!=null&&c.moveToFirst()&&!c.isNull(0))declared=c.getLong(0);
        }catch(Exception e){logOnUi("Source size unavailable: "+e);}
        byte[] hdr;
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("Android file picker could not open file "+index);
            hdr=prefix(in);
        }
        logOnUi("Source "+index+" · "+fileName(uri)+" · provider size="+declared+" bytes · "+bytesInfo(hdr));
        return hdr;
    }
    void inspectModelFiles(){
        if(running)return;
        for(int i=0;i<3;i++)if(preferences.getString(KEYS[i],null)==null){
            Toast.makeText(this,"Select all three files first",Toast.LENGTH_LONG).show();return;
        }
        diagnostics.setLength(0);
        appendDiagnostic("Model header inspection (no copying and no inference)");
        executor.execute(()->{
            for(int i=0;i<3;i++){
                try{
                    Uri uri=Uri.parse(preferences.getString(KEYS[i],""));
                    byte[] header=sourcePrefix(uri,i);
                    validateSourceFormat(header,i,fileName(uri));
                    logOnUi("Source "+i+" format verified: "+headerFormat(header,Long.MAX_VALUE));
                }catch(Exception e){logOnUi("Source "+i+" ERROR: "+e);}
            }
            runOnUiThread(this::showDiagnostics);
        });
    }
    void validateWeights(File file,int index)throws IOException{
        byte[] hdr;
        try(FileInputStream f=new FileInputStream(file)){hdr=prefix(f);}
        logOnUi("Cached "+index+" · "+file.length()+" bytes · "+bytesInfo(hdr));
        validateSourceFormat(hdr,index,file.getName());
        if(!isValidHeader(hdr,file.length())){
            throw new IOException("File "+index+" has an invalid GGUF/safetensors header: "+bytesInfo(hdr));
        }
        if(index==1&&file.length()<100L*1048576L)
            throw new IOException("Qwen3-0.6B encoder is unexpectedly small; select the complete GGUF or safetensors model");
        logOnUi("Validated "+index+" ("+
            headerFormat(hdr,file.length())+") · "+(file.length()/1048576)+" MiB");
    }
    Uri save(Bitmap bmp,long id)throws IOException{
        ContentValues cv=new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME,"Anima_"+System.currentTimeMillis()+"_"+id+".png");
        cv.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/AnimaPocket");
        cv.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri out=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);if(out==null)throw new IOException("Cannot create gallery item");
        try(OutputStream stream=getContentResolver().openOutputStream(out)){
            if(stream==null||!bmp.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("PNG write failed");
            cv.clear();cv.put(MediaStore.Images.Media.IS_PENDING,0);getContentResolver().update(out,cv,null,null);return out;
        }catch(IOException e){getContentResolver().delete(out,null,null);throw e;}
    }
}
