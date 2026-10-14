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
        String backend, boolean flash);

    final String[] KEYS={"model","encoder","decoder"};
    final String[] URLS={
        "https://huggingface.co/vanes430/Anima-Turbo-V1.1-GGUF/resolve/main/anima-turbo-v1.1-Q4_0.gguf?download=true",
        "https://huggingface.co/circlestone-labs/Anima/resolve/main/split_files/text_encoders/qwen_3_06b_base.safetensors?download=true",
        "https://github.com/madebyollin/taehv/raw/refs/heads/main/safetensors/taew2_1.safetensors",
        "https://huggingface.co/circlestone-labs/Anima/resolve/main/split_files/vae/qwen_image_vae.safetensors?download=true"
    };
    SharedPreferences preferences;
    final TextView[] files = new TextView[3];
    TextView status, outputs, devices;
    EditText prompt, seed;
    Spinner decoderType, resolution, steps, backend;
    CheckBox flash;
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
    void link(String s){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(s)));}

    @Override public void onCreate(Bundle b){
        super.onCreate(b);preferences=getSharedPreferences("files",MODE_PRIVATE);
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(bg);scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(20),dp(20),dp(40));scroll.addView(root);setContentView(scroll);
        TextView title=text("ANIMA / POCKET",24,Color.WHITE);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);add(root,title,0);
        add(root,text("Native offline image generation · ARM64 Vulkan",12,hint),0);
        add(root,text("MODEL FILES",17,accent),22);
        add(root,text("Download in your browser, then select local files. Browser Downloads survive app resets. The app caches working copies.",12,hint),2);
        String[] labels={"01   Anima GGUF","02   Qwen3 text encoder","03   TAEW2.1 or full VAE"};
        for(int i=0;i<3;i++){
            final int n=i;add(root,text(labels[i],14,Color.WHITE),12);
            files[i]=text(preferences.getString(KEYS[i]+"_name","No file selected"),12,hint);add(root,files[i],0);
            LinearLayout r=new LinearLayout(this);
            Button pick=button("Choose file",()->pick(n));r.addView(pick,new LinearLayout.LayoutParams(0,dp(46),1));
            Button download=button("Download ↗",()->link(URLS[n==2 && decoderType!=null && decoderType.getSelectedItemPosition()==1?3:n]));
            LinearLayout.LayoutParams dp1=new LinearLayout.LayoutParams(0,dp(46),1);dp1.leftMargin=dp(8);r.addView(download,dp1);add(root,r,4);
        }
        add(root,text("Decoder mode",13,hint),10);
        decoderType=spinner(new String[]{"TAEW2.1 (recommended)","Full Qwen VAE"},0);add(root,decoderType,2);
        add(root,text("GENERATION",17,accent),22);
        prompt=new EditText(this);prompt.setTextColor(Color.WHITE);prompt.setTextSize(15);prompt.setMinLines(3);
        prompt.setGravity(android.view.Gravity.TOP);prompt.setText(preferences.getString("prompt","anime illustration of a city at night"));
        prompt.setPadding(dp(14),dp(14),dp(14),dp(14));prompt.setBackground(shape(panel));add(root,prompt,5);
        add(root,text("Resolution",13,hint),12);
        resolution=spinner(new String[]{"512 × 512","768 × 768","1024 × 1024","512 × 768","768 × 512"},0);add(root,resolution,2);
        add(root,text("Sampling steps",13,hint),12);
        steps=spinner(new String[]{"4","6","8","10","12","16"},2);add(root,steps,2);
        add(root,text("Backend",13,hint),12);
        backend=spinner(new String[]{"Hybrid Vulkan + CPU","Auto","All Vulkan","CPU diagnostic"},0);add(root,backend,2);
        flash=new CheckBox(this);flash.setText("Experimental diffusion Flash Attention");flash.setTextColor(hint);add(root,flash,7);
        add(root,text("Seed (-1=random)",13,hint),7);
        seed=new EditText(this);seed.setTextColor(Color.WHITE);seed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        seed.setText("-1");seed.setSingleLine();seed.setBackground(shape(panel));seed.setPadding(dp(12),dp(10),dp(12),dp(10));add(root,seed,2);
        go=button("Generate",this::start);go.setBackground(shape(accent));add(root,go,16);
        stop=button("Cancel",()->{cancelNative();notifyStatus("Cancel requested…");});stop.setEnabled(false);add(root,stop,7);
        status=text("Ready. Select three files.",13,hint);add(root,status,12);
        devices=text("Detecting backends…",11,hint);add(root,devices,4);
        outputs=text("Result saved to Pictures/AnimaPocket",12,hint);add(root,outputs,12);
        image=new ImageView(this);image.setAdjustViewBounds(true);add(root,image,8);
        try{devices.setText("Backends: "+devices().replace('\t',' '));}catch(Throwable e){devices.setText("Backend detection error: "+e.getMessage());}
    }
    void pick(int n){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,900+n);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||request<900||request>902)return;
        int n=request-900;Uri uri=data.getData();if(uri==null)return;
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(SecurityException e){Toast.makeText(this,"Read grant not persistent",Toast.LENGTH_LONG).show();}
        String name=fileName(uri);
        preferences.edit().putString(KEYS[n],uri.toString()).putString(KEYS[n]+"_name",name).apply();files[n].setText(name);
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
        String[] selected=new String[3];for(int n=0;n<3;n++){selected[n]=preferences.getString(KEYS[n],null);
            if(selected[n]==null){Toast.makeText(this,"Choose all model files",Toast.LENGTH_SHORT).show();return;}}
        String p=prompt.getText().toString().trim();if(p.isEmpty()){Toast.makeText(this,"Enter a prompt",Toast.LENGTH_SHORT).show();return;}
        String[] wh=resolution.getSelectedItem().toString().replace(" ","").split("×");
        int w=Integer.parseInt(wh[0]),h=Integer.parseInt(wh[1]),s=Integer.parseInt(steps.getSelectedItem().toString());
        String[] backends={"diffusion=vulkan0,te=cpu,vae=cpu","","vulkan0","cpu"};
        String target=backends[backend.getSelectedItemPosition()];
        boolean tae=decoderType.getSelectedItemPosition()==0, fa=flash.isChecked();
        long rand;try{rand=Long.parseLong(seed.getText().toString());}catch(NumberFormatException e){rand=-1;}
        if(rand<0)rand=new SecureRandom().nextLong()&Long.MAX_VALUE;
        final long id=rand;preferences.edit().putString("prompt",p).apply();busy(true);
        executor.execute(()->{
            try{
                String[] paths=new String[3];
                for(int n=0;n<3;n++)paths[n]=copyModel(Uri.parse(selected[n]),n);
                long began=System.nanoTime();
                int[] pixels=generateNative(paths[0],paths[1],paths[2],tae,p,w,h,s,id,1f,target,fa);
                if(pixels==null||pixels.length!=w*h)throw new IOException("Invalid native output");
                Bitmap bmp=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
                Uri saved=save(bmp,id);long seconds=(System.nanoTime()-began)/1000000000L;
                runOnUiThread(()->{image.setImageBitmap(bmp);status.setText("Complete in "+seconds+"s · seed "+id);outputs.setText("Saved "+saved);});
            }catch(Throwable e){notifyStatus("Error: "+e.getMessage());}
            finally{busy(false);}
        });
    }
    String copyModel(Uri uri,int index)throws Exception{
        File dir=new File(getFilesDir(),"weights");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create cache");
        long size=-1;try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))size=c.getLong(0);}
        byte[] hash=MessageDigest.getInstance("SHA-256").digest((uri.toString()+":"+size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder key=new StringBuilder();for(int n=0;n<12;n++)key.append(String.format(Locale.US,"%02x",hash[n]));
        File to=new File(dir,"m"+index+"-"+key+(index==0?".gguf":".safetensors"));
        if(to.exists()&&to.length()>0&&(size<0||to.length()==size))return to.getAbsolutePath();
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
        return to.getAbsolutePath();
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
