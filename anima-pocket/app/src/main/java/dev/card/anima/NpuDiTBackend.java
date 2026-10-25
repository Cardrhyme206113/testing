package dev.card.anima;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.os.Environment;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import org.pytorch.executorch.EValue;
import org.pytorch.executorch.Module;
import org.pytorch.executorch.Tensor;

/**
 * Android-side half of the Anima native/Neuron hybrid.
 * Input vectors are the actual ggml graph-cut outputs from the existing Anima
 * conditioner. Only the 28 genuine A16W4 diffusion transformer PTEs run here.
 * All other model operations remain in stable-diffusion.cpp.
 *
 * This interface is fixed at 512x512 / 1024 spatial tokens. On any mismatch
 * it fails closed; it never silently substitutes a synthetic conditioning.
 */
final class NpuDiTBackend implements AutoCloseable {
    static final int BLOCKS = 28;
    private static final int TOKENS = 1024, WIDTH = 2048, CTX_TOKENS = 512, CTX_WIDTH = 1024;
    private static final long MAX_PTE_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_OPEN_MODULES = 4;
    private final File[] files = new File[BLOCKS];
    private final Module[] opened = new Module[BLOCKS];
    private final ArrayDeque<Integer> lru = new ArrayDeque<>();
    private final float[] ropeCos = new float[TOKENS * 128];
    private final float[] ropeSin = new float[TOKENS * 128];
    private Context appContext;
    private String calibrationCapture = "not captured";
    private boolean ready;
    private int forwarded;

    NpuDiTBackend() {
        makeSpatialRope();
    }

    String getCalibrationCapture() { return calibrationCapture; }

    private static void appendF32(ZipOutputStream zip, String filename, float[] values)
            throws IOException {
        zip.putNextEntry(new ZipEntry(filename));
        ByteBuffer buffer = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            if (buffer.remaining() < Float.BYTES) {
                zip.write(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
            buffer.putFloat(value);
        }
        if (buffer.position() > 0) zip.write(buffer.array(), 0, buffer.position());
        zip.closeEntry();
    }

    /** Real block-zero inputs, captured before the first Neuron call.
     *  Calibrate a replacement PTE with these, retaining all existing PTE files.
     */
    private String saveCalibration(float[] x, float[] context, float[] step, float[] temb)
            throws IOException {
        if (appContext == null) throw new IOException("Android context not initialized");
        ContentResolver resolver = appContext.getContentResolver();
        String name = "anima-real-block00-" + System.currentTimeMillis() + ".zip";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(MediaStore.Downloads.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS + "/AnimaPocket");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri saved = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (saved == null) throw new IOException("Cannot create a Downloads file");
        boolean complete = false;
        try {
            try (OutputStream raw = resolver.openOutputStream(saved)) {
                if (raw == null) throw new IOException("Cannot open calibration archive");
                try (ZipOutputStream zip = new ZipOutputStream(
                        new BufferedOutputStream(raw, 65536))) {
                    zip.setLevel(1);
                    zip.putNextEntry(new ZipEntry("metadata.txt"));
                    byte[] meta = ("Anima Turbo v1.1: first real conditioned block-0 inputs\n"
                        + "Little-endian float32; file shapes as listed below\n"
                        + "x.f32 [1,1024,2048]\ncontext.f32 [1,512,1024]\n"
                        + "step.f32 [1,2048]\ntemb.f32 [1,6144]\n"
                        + "cos.f32 [1024,128]\nsin.f32 [1024,128]\n"
                        + "Model PTE filenames are unchanged; only replace block00 after validation.\n"
                        ).getBytes(StandardCharsets.UTF_8);
                    zip.write(meta);
                    zip.closeEntry();
                    appendF32(zip, "x.f32", x);
                    appendF32(zip, "context.f32", context);
                    appendF32(zip, "step.f32", step);
                    appendF32(zip, "temb.f32", temb);
                    appendF32(zip, "cos.f32", ropeCos);
                    appendF32(zip, "sin.f32", ropeSin);
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(saved, done, null, null);
            complete = true;
            return Environment.DIRECTORY_DOWNLOADS + "/AnimaPocket/" + name;
        } finally {
            if (!complete) resolver.delete(saved, null, null);
        }
    }

    private void makeSpatialRope() {
        // ComfyUI Anima / Cosmos-Predict2 3-axis rotary encoding.
        // 128 head dims = 44 temporal + 42 height + 42 width.
        final int[] halves = {22, 21, 21};
        final double[] theta = {
            10000.0,
            10000.0 * Math.pow(4.0, 42.0 / 40.0),
            10000.0 * Math.pow(4.0, 42.0 / 40.0)
        };
        for (int pos = 0; pos < TOKENS; pos++) {
            int y = pos / 32, x = pos % 32, axisIndex = 0;
            int[] coordinates = {0, y, x};
            for (int axis = 0; axis < 3; axis++) {
                for (int n = 0; n < halves[axis]; n++) {
                    double angle = coordinates[axis] * Math.pow(theta[axis],
                        -((double)n) / halves[axis]);
                    float c = (float)Math.cos(angle), s = (float)Math.sin(angle);
                    // PyTorch rotate_half uses [first 64][second 64].
                    ropeCos[pos * 128 + axisIndex] = c;
                    ropeCos[pos * 128 + axisIndex + 64] = c;
                    ropeSin[pos * 128 + axisIndex] = s;
                    ropeSin[pos * 128 + axisIndex + 64] = s;
                    axisIndex++;
                }
            }
        }
    }

    static String filename(int i) {
        return String.format(Locale.US, "anima_block_%02d_1024_mt6989_a16w4.pte", i);
    }

    void prepare(Context context, Uri folder, java.util.function.Consumer<String> onStatus)
            throws IOException {
        if (ready) throw new IllegalStateException("NPU model directory already prepared");
        appContext = context.getApplicationContext();
        if (folder == null) throw new IOException("Select the directory containing all 28 A16W4 PTE files.");
        ContentResolver resolver = context.getContentResolver();
        String treeId = DocumentsContract.getTreeDocumentId(folder);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folder, treeId);
        Map<String, Uri> sources = new HashMap<>();
        Map<String, Long> sourceSizes = new HashMap<>();
        try (Cursor cursor = resolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor == null) throw new IOException("Android documents provider cannot list the selected folder.");
            while (cursor.moveToNext()) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) continue;
                String name = cursor.getString(1);
                if (name == null) continue;
                sources.put(name, DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0)));
                sourceSizes.put(name, cursor.isNull(3) ? -1L : cursor.getLong(3));
            }
        }
        File cache = new File(context.getFilesDir(), "npu-dit-512");
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot create NPU staging directory");
        long neededBytes = 0;
        for (int i = 0; i < BLOCKS; i++) {
            String name = filename(i);
            Long length = sourceSizes.get(name);
            if (!sources.containsKey(name)) throw new IOException("Missing " + name
                + ". Select the unzipped out-512 directory containing all 28 transformer files.");
            if (length != null && length >= 0 && (length < 10000 || length > MAX_PTE_BYTES)) {
                throw new IOException("Invalid PTE size for " + name + ": " + length);
            }
            File target = new File(cache, name);
            if (!target.isFile() || (length != null && length >= 0 && target.length() != length)) {
                neededBytes += length != null && length >= 0 ? length : MAX_PTE_BYTES;
            }
        }
        if (cache.getUsableSpace() < neededBytes + 128L * 1024L * 1024L) {
            throw new IOException("Not enough storage to cache all 28 models: need at least "
                + ((neededBytes / 1048576L) + 128) + " MiB free.");
        }
        for (int i = 0; i < BLOCKS; i++) {
            String name = filename(i);
            long length = sourceSizes.get(name);
            File target = new File(cache, name);
            if (!target.isFile() || (length >= 0 && target.length() != length)) {
                onStatus.accept("Staging NPU model " + (i+1) + "/28 · " + name);
                File partial = new File(cache, name + ".part");
                long copied = 0;
                try (InputStream input = resolver.openInputStream(sources.get(name));
                     FileOutputStream output = new FileOutputStream(partial)) {
                    if (input == null) throw new IOException("Cannot read " + name);
                    byte[] buffer = new byte[256 * 1024];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (n == 0) continue;
                        copied += n;
                        if (copied > MAX_PTE_BYTES) throw new IOException("Oversized PTE " + name);
                        output.write(buffer, 0, n);
                    }
                    output.getFD().sync();
                }
                if (copied < 10000 || (length >= 0 && copied != length)) {
                    throw new IOException("Incomplete PTE " + name + " (" + copied + "/" + length + ")");
                }
                if (target.exists() && !target.delete()) throw new IOException("Cannot replace stale cache " + name);
                if (!partial.renameTo(target)) throw new IOException("Cannot finalize PTE cache " + name);
            }
            files[i] = target;
        }
        // The already-tested v0.6.8 instrumented backend is bundled with the APK.
        System.loadLibrary("neuron_backend");
        ready = true;
        onStatus.accept("All 28 A16W4 models staged; hybrid NPU path ready.");
    }

    synchronized float[] forward(int index, float[] x, float[] context, float[] step, float[] temb) {
        if (!ready || index < 0 || index >= BLOCKS) {
            throw new IllegalStateException("NPU graph attempted to execute before staging models");
        }
        if (x.length != TOKENS * WIDTH || context.length != CTX_TOKENS * CTX_WIDTH
                || step.length != WIDTH || temb.length != WIDTH * 3) {
            throw new IllegalArgumentException("Incorrect real conditioner shape: x=" + x.length
                + " context=" + context.length + " step=" + step.length + " temb=" + temb.length);
        }
        long start = SystemClock.elapsedRealtime();
        if (index == 0 && forwarded == 0) {
            try {
                calibrationCapture = "SAVED " + saveCalibration(x, context, step, temb);
            } catch (Exception error) {
                calibrationCapture = "FAILED " + error.getMessage();
                Log.e("AnimaHybridNpu", "Calibration capture failed", error);
            }
        }
        Module module = opened[index];
        if (module == null) {
            if (lru.size() == MAX_OPEN_MODULES) {
                int oldest = lru.removeFirst();
                opened[oldest].close();
                opened[oldest] = null;
            }
            module = Module.load(files[index].getAbsolutePath(), Module.LOAD_MODE_MMAP, 1);
            opened[index] = module;
        } else {
            lru.remove(Integer.valueOf(index));
        }
        lru.addLast(index);
        EValue[] results = module.forward(
            EValue.from(Tensor.fromBlob(x, new long[]{1, TOKENS, WIDTH})),
            EValue.from(Tensor.fromBlob(context, new long[]{1, CTX_TOKENS, CTX_WIDTH})),
            EValue.from(Tensor.fromBlob(step, new long[]{1, WIDTH})),
            EValue.from(Tensor.fromBlob(temb, new long[]{1, WIDTH * 3})),
            EValue.from(Tensor.fromBlob(ropeCos, new long[]{TOKENS, 128})),
            EValue.from(Tensor.fromBlob(ropeSin, new long[]{TOKENS, 128}))
        );
        if (results == null || results.length != 1 || !results[0].isTensor()) {
            throw new IllegalStateException("Unexpected NPU response for block " + index);
        }
        float[] output = results[0].toTensor().getDataAsFloatArray();
        if (output.length != x.length) throw new IllegalStateException("NPU output shape mismatch");
        for (float value : output) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite NPU result at block " + index);
        }
        forwarded++;
        Log.i("AnimaHybridNpu", "NPU_BLOCK_OK index=" + index
            + " time_ms=" + (SystemClock.elapsedRealtime() - start)
            + " total_blocks_forwarded=" + forwarded);
        return output;
    }

    int getForwardedCount() { return forwarded; }

    @Override public synchronized void close() {
        for (int i=0; i<BLOCKS; i++) {
            if (opened[i] != null) {
                opened[i].close();
                opened[i] = null;
            }
        }
        lru.clear();
        ready = false;
    }
}
