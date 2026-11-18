package io.cardrhyme.dreamlitemtk;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native Android view app. No INTERNET permission. Models are selected from
 * user storage and copied to app-private storage to satisfy ONNX external-data paths.
 */
public final class MainActivity extends Activity {
    private static final int PICK_FOLDER = 410;
    private static final int SAVE_PNG = 411;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status, packLabel;
    private EditText prompt, seedInput;
    private Spinner resolution, steps, backend;
    private ImageView image;
    private ProgressBar progress;
    private Bitmap latest;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(16, 18, 23));
        getWindow().setNavigationBarColor(Color.rgb(16, 18, 23));
        getWindow().getDecorView().setSystemUiVisibility(0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(18),dp(18),dp(12));
        root.setBackgroundColor(Color.rgb(18,20,25));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0,0,0,dp(24));
        scroller.addView(body);
        root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);

        TextView title = label("DreamLite MTK", 25, 0xfff4f6f9);
        title.setTypeface(null,1);
        body.addView(title);
        body.addView(label("Local ONNX research build • No model downloads • NNAPI is not proven NPU",12,0xff98a5b4));
        body.addView(space(14));

        packLabel = label("Model pack not imported",14,0xffbbc6d6);
        body.addView(packLabel);
        Button choose = button("Import model folder");
        body.addView(choose);
        choose.setOnClickListener(v -> chooseFolder());
        body.addView(space(12));

        body.addView(label("PROMPT",12,0xff9daec0));
        prompt = new EditText(this);
        prompt.setHint("anime illustration, vibrant colors, detailed eyes...");
        prompt.setHintTextColor(0xff778394);
        prompt.setTextColor(Color.WHITE);
        prompt.setTextSize(15);
        prompt.setMinLines(2);
        prompt.setMaxLines(5);
        prompt.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff5a6e8c));
        body.addView(prompt,new LinearLayout.LayoutParams(-1,-2));
        body.addView(space(6));

        body.addView(label("RESOLUTION",12,0xff9daec0));
        resolution = spinner(new String[]{"512 × 512","1024 × 1024"});
        body.addView(resolution);
        body.addView(label("DENOISING STEPS",12,0xff9daec0));
        steps = spinner(new String[]{"4 (recommended)","2 (experimental)","1 (experimental)"});
        body.addView(steps);
        body.addView(label("EXECUTION BACKEND",12,0xff9daec0));
        backend = spinner(new String[]{
            "NNAPI strict (no ORT CPU fallback)",
            "NNAPI preferred (CPU fallback allowed)",
            "CPU only (diagnostic)"
        });
        body.addView(backend);
        body.addView(label("Note: even strict NNAPI can choose a GPU, not necessarily MediaTek APU.",12,0xff98a5b4));

        body.addView(space(10));
        body.addView(label("SEED",12,0xff9daec0));
        seedInput = new EditText(this);
        seedInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        seedInput.setText("42");
        seedInput.setTextColor(Color.WHITE);
        seedInput.setTextSize(14);
        seedInput.setSingleLine();
        body.addView(seedInput);

        Button generate = button("Generate offline");
        body.addView(generate);
        generate.setOnClickListener(v -> startGeneration());
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        body.addView(progress);

        image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(0xff252a33);
        LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(-1,dp(320));
        imageParams.topMargin=dp(12);
        body.addView(image,imageParams);
        Button save = button("Save PNG");
        body.addView(save);
        save.setOnClickListener(v -> savePng());

        status = label("Choose a local folder with model-pack.json, text encoder, UNet, VAE and tokenizer. " +
            "A compatible ONNX export is required; generic checkpoints are not supported.\n\n" +
            "Phone: " + Build.MANUFACTURER + " " + Build.MODEL + " • " + Build.HARDWARE,12,0xffa6b3c4);
        status.setTextIsSelectable(true);
        body.addView(status);
    }

    private void chooseFolder() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,PICK_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == PICK_FOLDER) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {}
            background(() -> importFolder(uri));
        } else if (requestCode == SAVE_PNG) {
            Bitmap result = latest;
            if (result == null || data.getData() == null) return;
            background(() -> {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null || !result.compress(Bitmap.CompressFormat.PNG,100,out))
                        throw new IllegalStateException("Could not write PNG");
                    show("Saved PNG to selected destination.");
                }
            });
        }
    }

    private void importFolder(Uri tree) throws Exception {
        show("Importing external files to private app storage. Large packs can take a while...");
        File folder = modelFolder();
        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create model directory");
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,treeId);
        String[] columns = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        };
        int copied = 0;
        boolean foundManifest = false;
        try (Cursor cursor = getContentResolver().query(children,columns,null,null,null)) {
            if (cursor == null) throw new IllegalArgumentException("Folder provider returned no documents.");
            while (cursor.moveToNext()) {
                String documentId=cursor.getString(0);
                String name=cursor.getString(1);
                String mime=cursor.getString(2);
                long size=cursor.isNull(3) ? -1 : cursor.getLong(3);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                if (name == null || !name.matches("[A-Za-z0-9._-]{1,128}")) continue;
                if (!(name.endsWith(".onnx") || name.endsWith(".data") ||
                      name.endsWith(".json") || name.endsWith(".bin"))) continue;
                if (name.equals("model-pack.json")) foundManifest=true;
                File target = new File(folder,name);
                if (target.isFile() && size > 0 && target.length() == size) continue;
                show("Importing " + name + " (" + (size > 0 ? (size/1048576) + " MiB" : "size unknown") + ")");
                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(tree,documentId);
                File part = new File(folder,name+".part");
                try (InputStream in = getContentResolver().openInputStream(fileUri);
                     FileOutputStream out = new FileOutputStream(part)) {
                    if (in == null) throw new IllegalArgumentException("Cannot open " + name);
                    byte[] buffer = new byte[1024*1024];
                    int n;
                    while ((n=in.read(buffer)) != -1) out.write(buffer,0,n);
                    out.getFD().sync();
                }
                Files.move(part.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        if (!foundManifest && !new File(folder,"model-pack.json").isFile()) {
            JSONObject config = new JSONObject();
            config.put("format","dreamlite-onnx-mobile-v1");
            config.put("text_encoder","te_fp16.onnx");
            config.put("unet","unet_masked.onnx");
            config.put("vae_decoder","vae_decoder.onnx");
            config.put("tokenizer","tokenizer.json");
            config.put("prefix","[Generate]: ");
            config.put("max_tokens",200);
            config.put("latent_channels",4);
            config.put("latent_downsample",8);
            config.put("vae_range","minus_one_to_one");
            config.put("sample_input","sample");
            config.put("timestep_input","timestep");
            config.put("embedding_input","encoder_hidden_states");
            config.put("ids_input","input_ids");
            config.put("mask_input","attention_mask");
            Files.write(new File(folder,"model-pack.json").toPath(),
                config.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        ModelPack pack = new ModelPack(folder);
        show("Imported " + copied + " files; model contract present.\n" +
            "Models are local. No test inference has been performed yet.");
        runOnUiThread(() -> packLabel.setText("Model pack: " + pack.dir.getAbsolutePath()));
    }

    private void startGeneration() {
        if (busy) return;
        String text = prompt.getText().toString().trim();
        if (text.isEmpty()) { show("Enter a prompt."); return; }
        int side = resolution.getSelectedItemPosition() == 0 ? 512 : 1024;
        int count = new int[]{4,2,1}[steps.getSelectedItemPosition()];
        int mode = backend.getSelectedItemPosition();
        long seed;
        try { seed = Long.parseLong(seedInput.getText().toString()); }
        catch (Exception ex) { show("Invalid integer seed"); return; }
        background(() -> {
            ModelPack pack = new ModelPack(modelFolder());
            DreamLiteEngine engine = new DreamLiteEngine(pack,mode,this::show);
            long t = System.nanoTime();
            DreamLiteEngine.Render result = engine.generate(text,side,count,seed);
            latest = result.image;
            runOnUiThread(() -> image.setImageBitmap(result.image));
            show("Generated " + result.image.getWidth() + "x" + result.image.getHeight() +
                " in " + (System.nanoTime()-t)/1_000_000 + " ms\n\n" + result.diagnostic);
        });
    }

    private void savePng() {
        if (latest == null) { show("Generate an image before saving."); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("image/png");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE,"dreamlite-"+System.currentTimeMillis()+".png");
        startActivityForResult(intent,SAVE_PNG);
    }

    private void background(Worker worker) {
        if (busy) { show("Another operation is running."); return; }
        busy=true;
        progress.setVisibility(View.VISIBLE);
        io.submit(() -> {
            try { worker.run(); }
            catch (Throwable error) {
                show("Error: " + error.getClass().getSimpleName() + ": " + error.getMessage() +
                    "\n\nThis usually means the selected ONNX export does not match the manifest, " +
                    "or the NPU cannot compile its operators. Try CPU mode for diagnosis.");
            } finally {
                busy=false;
                runOnUiThread(() -> progress.setVisibility(View.GONE));
            }
        });
    }
    private interface Worker { void run() throws Exception; }

    private void show(String message) { runOnUiThread(() -> status.setText(message)); }
    private File modelFolder() { return new File(getFilesDir(),"model"); }
    private TextView label(String text,int sp,int color) {
        TextView view=new TextView(this);
        view.setText(text); view.setTextSize(sp); view.setTextColor(color);
        view.setPadding(0,dp(3),0,dp(5));
        return view;
    }
    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text); button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff354c71));
        return button;
    }
    private Spinner spinner(String[] items) {
        Spinner value = new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        value.setAdapter(adapter);
        value.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff7b8da8));
        return value;
    }
    private View space(int height) {
        View view=new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1,dp(height)));
        return view;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density*value); }
    @Override protected void onDestroy() { super.onDestroy(); io.shutdown(); }
}
