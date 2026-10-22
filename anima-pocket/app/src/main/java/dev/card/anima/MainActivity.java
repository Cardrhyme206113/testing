package dev.card.anima;

import android.app.Activity;
import android.os.Bundle;
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
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static { System.loadLibrary("animabridge"); }
    public native String devices();
    public native void cancelNative();
    public native int[] generateNative(String model, String encoder, String decoder, boolean tae,
        String prompt, int width, int height, int steps, long seed, float cfg,
        String backend, boolean flash, boolean diskEncoder);

    final String[] KEYS={"model","encoder","decoder"};
    final String[] URLS={
        "https://huggingface.co/vanes430/Anima-Turbo-V1.1-GGUF/resolve/main/anima-turbo-v1.1-Q4_0.gguf?download=true",
        "https://huggingface.co/mradermacher/Qwen3-0.6B-Base-GGUF",
        "https://github.com/madebyollin/taehv/raw/refs/heads/main/safetensors/taew2_1.safetensors",
        "https://huggingface.co/circlestone-labs/Anima/resolve/main/split_files/vae/qwen_image_vae.safetensors?download=true"
    };
    SharedPreferences preferences;
    final TextView[] files = new TextView[3];
    TextView status, outputs, devices, logView;
    final StringBuilder diagnostics=new StringBuilder();
    long lastLogRefresh=0;
    boolean logDirty=false;
    EditText prompt, seed;
    Spinner decoderType, resolution, steps, backend;
    CheckBox flash, lowRamEncoder;
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
    void notifyStatus(String s){runOnUiThread(()->status.setText(s));}
    @SuppressWarnings("unused") public void onNativeStatus(String s){notifyStatus(s);}
    @SuppressWarnings("unused") public void onNativeLog(int level,String message){
        runOnUiThread(()->appendDiagnostic("ENGINE/"+level+"  "+message));
    }
    void appendDiagnostic(String message){
        if(!message.endsWith("\n"))message+="\n";
        diagnostics.append(message);
        if(diagnostics.length()>220000)diagnostics.delete(0,diagnostics.length()-160000);
        if(logView!=null){
            long now=android.os.SystemClock.uptimeMillis();
            if(now-lastLogRefresh>350 || message.startsWith("ENGINE/4")){
                lastLogRefresh=now;
                String recent=diagnostics.toString();
                if(recent.length()>16000)recent=recent.substring(recent.length()-16000);
                logView.setText(recent);
            }
        }
    }
    void showDiagnostics(){
        if(logView!=null){String lines=diagnostics.toString();
            if(lines.length()>16000)lines=lines.substring(lines.length()-16000);
            logView.setText(lines.isEmpty()?"No logs captured yet.":lines);
        }
    }
    void copyDiagnostics(){
        showDiagnostics();
        android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(android.content.ClipData.newPlainText("Anima Pocket engine log",diagnostics.toString()));
        Toast.makeText(this,"Diagnostic log copied",Toast.LENGTH_SHORT).show();
    }
    void shareDiagnostics(){
        showDiagnostics();
        Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT,diagnostics.toString());
        startActivity(Intent.createChooser(i,"Share Anima Pocket diagnostics"));
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
        resolution=spinner(new String[]{"384 × 384","512 × 512","512 × 384","384 × 512"},0);add(root,resolution,2);
        add(root,text("Sampling steps",13,hint),12);
        steps=spinner(new String[]{"4","6","8","10"},0);add(root,steps,2);
        add(root,text("Backend",13,hint),12);
        backend=spinner(new String[]{"CPU only (no GPU inference)","Vulkan cautious (1.0 GiB)","Vulkan experimental (1.5 GiB)"},0);
        add(root,text("WARNING: Vulkan budget is not a hard driver cap. The previous reboot's cause is unconfirmed. Start with CPU.",12,0xffffc278),6);add(root,backend,2);
        lowRamEncoder=new CheckBox(this);
        lowRamEncoder.setText("Low RAM: stream Qwen encoder weights from disk (slower)");
        lowRamEncoder.setChecked(preferences.getBoolean("encoder_disk",true));
        lowRamEncoder.setTextColor(hint);
        add(root,lowRamEncoder,6);
        flash=new CheckBox(this);flash.setText("Experimental diffusion Flash Attention");flash.setTextColor(hint);add(root,flash,7);
        add(root,text("Seed (-1=random)",13,hint),7);
        seed=new EditText(this);seed.setTextColor(Color.WHITE);seed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        seed.setText("-1");seed.setSingleLine();seed.setBackground(shape(panel));seed.setPadding(dp(12),dp(10),dp(12),dp(10));add(root,seed,2);
        go=button("Generate",this::start);go.setBackground(shape(accent));add(root,go,16);
        stop=button("Cancel",()->{cancelNative();notifyStatus("Cancel requested…");});stop.setEnabled(false);add(root,stop,7);
        status=text("Ready. Select three files.",13,hint);add(root,status,12);
        add(root,text("ENGINE DIAGNOSTICS · no ADB required",15,accent),16);
        LinearLayout diagnosticActions=new LinearLayout(this);
        diagnosticActions.addView(button("Copy log",this::copyDiagnostics),new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams shareParams=new LinearLayout.LayoutParams(0,dp(44),1);shareParams.leftMargin=dp(8);
        diagnosticActions.addView(button("Share log",this::shareDiagnostics),shareParams);
        add(root,diagnosticActions,4);
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

    }
    void pick(int n){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,900+n);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||request<900||request>902)return;
        int n=request-900;Uri uri=data.getData();if(uri==null)return;
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
            if(b)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }
    void start(){
        if(running)return;
        if(backend.getSelectedItemPosition()>0){
            new android.app.AlertDialog.Builder(this)
                .setTitle("Warning: Vulkan previously rebooted this phone")
                .setMessage("This device suffered a complete restart while loading the model. A managed Vulkan budget is not a hard physical memory limit. Continue at your own risk, or use CPU-only diagnostics.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Proceed anyway",(dialog,which)->startUnchecked())
                .show();
        }else startUnchecked();
    }
    void startUnchecked(){
        if(running)return;
        String[] selected=new String[3];for(int n=0;n<3;n++){selected[n]=preferences.getString(KEYS[n],null);
            if(selected[n]==null){Toast.makeText(this,"Choose all model files",Toast.LENGTH_SHORT).show();return;}}
        String p=prompt.getText().toString().trim();if(p.isEmpty()){Toast.makeText(this,"Enter a prompt",Toast.LENGTH_SHORT).show();return;}
        String[] wh=resolution.getSelectedItem().toString().replace(" ","").split("×");
        int w=Integer.parseInt(wh[0]),h=Integer.parseInt(wh[1]),s=Integer.parseInt(steps.getSelectedItem().toString());
        String[] backends={"cpu","vulkan-1.0","vulkan-1.5"};
        String target=backends[backend.getSelectedItemPosition()];
        android.app.ActivityManager am=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi=new android.app.ActivityManager.MemoryInfo();
        if(am!=null){
            am.getMemoryInfo(mi);
            long minFree=target.equals("cpu") ? 3L*1024*1024*1024 : 5L*1024*1024*1024;
            if(mi.lowMemory || mi.availMem<minFree){
                status.setText("Not starting: "+(mi.availMem/1048576)+" MiB available; profile needs "+
                    (minFree/1048576)+" MiB free. Close other apps or select CPU.");
                return;
            }
        }
        boolean tae=decoderType.getSelectedItemPosition()==0, fa=flash.isChecked();
        boolean diskEncoder=lowRamEncoder.isChecked();
        preferences.edit().putBoolean("encoder_disk",diskEncoder).apply();
        long rand;try{rand=Long.parseLong(seed.getText().toString());}catch(NumberFormatException e){rand=-1;}
        if(rand<0)rand=new SecureRandom().nextLong()&Long.MAX_VALUE;
        final long id=rand;preferences.edit().putString("prompt",p).apply();
        diagnostics.setLength(0);
        appendDiagnostic("Anima Pocket diagnostics, native init");
        appendDiagnostic("Backend "+target+"; resolution "+w+"x"+h+"; steps "+s+"; tae "+tae+"; flash "+fa+"; encoderDisk "+diskEncoder);
        if(am!=null)appendDiagnostic("Free system RAM "+(mi.availMem/1048576)+" MiB; lowMemory="+mi.lowMemory);
        for(int n=0;n<3;n++)appendDiagnostic("Selected file "+n+": "+preferences.getString(KEYS[n]+"_name","?"));
        busy(true);
        startMemoryWatcher();
        executor.execute(()->{
            try{
                String[] paths=new String[3];
                for(int n=0;n<3;n++)paths[n]=copyModel(Uri.parse(selected[n]),n);
                long began=System.nanoTime();
                int[] pixels=generateNative(paths[0],paths[1],paths[2],tae,p,w,h,s,id,1f,target,fa,diskEncoder);
                if(pixels==null||pixels.length!=w*h)throw new IOException("Invalid native output");
                Bitmap bmp=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
                Uri saved=save(bmp,id);long seconds=(System.nanoTime()-began)/1000000000L;
                runOnUiThread(()->{image.setImageBitmap(bmp);status.setText("Complete in "+seconds+"s · seed "+id);outputs.setText("Saved "+saved);});
            }catch(Throwable e){
                runOnUiThread(()->{
                    appendDiagnostic("ERROR "+e.toString());
                    showDiagnostics();
                    status.setText("Error: "+e.getMessage()+" — tap Copy log below.");
                });
            }
            finally{
                ScheduledFuture<?> task=memoryTask;
                if(task!=null)task.cancel(false);
                memoryTask=null;
                busy(false);
            }
        });
    }
    void startMemoryWatcher(){
        ScheduledFuture<?> old=memoryTask;
        if(old!=null)old.cancel(false);
        memoryTask=memoryWatcher.scheduleAtFixedRate(()->{
            if(!running)return;
            try{
                android.app.ActivityManager am=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
                if(am==null)return;
                android.app.ActivityManager.MemoryInfo info=new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                long processPss=android.os.Debug.getPss()/1024L;
                logOnUi("MEM free="+(info.availMem/1048576L)+"MiB processPSS="+processPss+
                    "MiB lowMemory="+info.lowMemory+" threshold="+(info.threshold/1048576L)+"MiB");
                // Best effort only. The loader is not cancellable until a
                // context exists, and driver/kernel OOM may bypass this guard.
                if(info.lowMemory || info.availMem<1024L*1048576L){
                    logOnUi("CRITICAL memory pressure; requesting inference cancellation");
                    cancelNative();
                }
            }catch(Throwable err){logOnUi("Memory telemetry: "+err);}
        },5,5,TimeUnit.SECONDS);
    }
    @Override public void onTrimMemory(int level){
        super.onTrimMemory(level);
        if(running){
            logOnUi("Android onTrimMemory level="+level);
            if(level>=TRIM_MEMORY_RUNNING_LOW)cancelNative();
        }
    }
    @Override protected void onDestroy(){
        ScheduledFuture<?> task=memoryTask;
        if(task!=null)task.cancel(false);
        memoryWatcher.shutdownNow();
        if(running)cancelNative();
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
    void logOnUi(String value){runOnUiThread(()->appendDiagnostic(value));}
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
