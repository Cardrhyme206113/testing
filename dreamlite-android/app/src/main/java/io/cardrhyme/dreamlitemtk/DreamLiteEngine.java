package io.cardrhyme.dreamlitemtk;

import android.graphics.Bitmap;

import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import ai.onnxruntime.providers.NNAPIFlags;

/**
 * Experimental ONNX DreamLite-Mobile flow-matching pipeline.
 * IMPORTANT: This is not an automatically working adapter to arbitrary upstream
 * DreamLite checkpoints. Models must be exported to the manifest I/O contract.
 * NNAPI can choose NPU/GPU and can fall back; never report NNAPI as proven NPU.
 */
public final class DreamLiteEngine {
    public interface Progress { void update(String message); }
    public static final class Render {
        public final Bitmap image;
        public final String diagnostic;
        Render(Bitmap image, String diagnostic) { this.image=image; this.diagnostic=diagnostic; }
    }
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final ModelPack pack;
    private final int backend; // 0 = NNAPI strict; 1 = NNAPI with ORT CPU fallback; 2 = CPU
    private final Progress progress;
    private final StringBuilder diagnostic = new StringBuilder();

    public DreamLiteEngine(ModelPack pack, int backend, Progress progress) {
        this.pack = pack;
        this.backend = backend;
        this.progress = progress;
    }

    public Render generate(String prompt, int side, int steps, long seed) throws Exception {
        if (prompt.trim().isEmpty()) throw new IllegalArgumentException("Type a prompt first.");
        if (side != 512 && side != 1024) throw new IllegalArgumentException("Unsupported resolution");
        if (steps < 1 || steps > 4) throw new IllegalArgumentException("Steps must be 1–4");
        int maxTokens = pack.number("max_tokens", 200);
        if (maxTokens < 8 || maxTokens > 2048) throw new IllegalArgumentException("Invalid max_tokens");
        String template = pack.name("prompt_template", pack.name("prefix", "[Generate]: ") + "{prompt}");
        String fullPrompt = template.replace("{prompt}", prompt);
        long[] ids = new QwenTokenizer(pack.getFile("tokenizer")).tokenize(
            fullPrompt, maxTokens, pack.number("pad_token_id", 151643));
        long[] mask = new long[ids.length];
        int pad = pack.number("pad_token_id", 151643);
        for (int i = 0; i < mask.length; i++) mask[i] = ids[i] == pad ? 0 : 1;

        progress.update("Encoding custom prompt...");
        long before = System.nanoTime();
        float[] embeddings;
        long[] embShape;
        try (OrtSession session = open("text_encoder")) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            try {
                for (Map.Entry<String, NodeInfo> entry : session.getInputInfo().entrySet()) {
                    String n = entry.getKey();
                    TensorInfo info = type(entry.getValue());
                    long[] dims = info.getShape().clone();
                    if (n.equals(pack.name("ids_input", "input_ids"))) {
                        dims = replaceShape(dims, new long[]{1, ids.length});
                        inputs.put(n, integral(ids, dims, info));
                    } else if (n.equals(pack.name("mask_input", "attention_mask"))) {
                        dims = replaceShape(dims, new long[]{1, mask.length});
                        inputs.put(n, integral(mask, dims, info));
                    } else if (n.toLowerCase().contains("position_ids")) {
                        long[] positions = new long[ids.length];
                        for (int i = 0; i < positions.length; i++) positions[i] = i;
                        dims = replaceShape(dims, new long[]{1, positions.length});
                        inputs.put(n, integral(positions, dims, info));
                    } else throw new IllegalArgumentException("Unsupported text encoder input: " + n
                        + ". Adjust ONNX export; this app does not invent encoder tensors.");
                }
                try (OrtSession.Result result = session.run(inputs)) {
                    int index = outputIndex(session, pack.name("embeddings_output", ""));
                    OnnxTensor tensor = (OnnxTensor) result.get(index);
                    embShape = ((TensorInfo) tensor.getInfo()).getShape();
                    embeddings = floats(tensor);
                }
            } finally { closeAll(inputs); }
        }
        diagnostic.append("Text encoder: ").append(msSince(before)).append(" ms\n");
        if (embShape.length != 3) throw new IllegalArgumentException(
            "Text encoder must output [batch, tokens, hidden], got " + Arrays.toString(embShape));

        int channels = pack.number("latent_channels", 4);
        int downsample = pack.number("latent_downsample", 8);
        int h = side / downsample, w = side / downsample;
        float[] latents = new float[channels * h * w];
        Random random = new Random(seed);
        for (int i = 0; i < latents.length; i++) latents[i] = (float) random.nextGaussian();
        progress.update("Denoising (0/" + steps + ")...");
        before = System.nanoTime();

        try (OrtSession unet = open("unet")) {
            for (int step = 0; step < steps; step++) {
                double sigma = (steps - step) / (double) steps;
                double nextSigma = (steps - step - 1) / (double) steps;
                Map<String, OnnxTensor> inputs = new HashMap<>();
                try {
                    for (Map.Entry<String, NodeInfo> entry : unet.getInputInfo().entrySet()) {
                        String name = entry.getKey();
                        TensorInfo info = type(entry.getValue());
                        if (name.equals(pack.name("sample_input", "sample"))) {
                            long[] shape = shapeForSample(info.getShape(), channels, h, w);
                            float[] payload = fillSample(latents, shape, channels, h, w);
                            inputs.put(name, floating(payload, shape, info));
                        } else if (name.equals(pack.name("timestep_input", "timestep"))) {
                            long[] shape = replaceShape(info.getShape(), new long[]{1});
                            float value = (float) (sigma * pack.number("timestep_multiplier", 1000));
                            float[] repeated = new float[count(shape)];
                            Arrays.fill(repeated, value);
                            inputs.put(name, floating(repeated, shape, info));
                        } else if (name.equals(pack.name("embedding_input", "encoder_hidden_states"))) {
                            long[] shape = replaceShape(info.getShape(), embShape);
                            inputs.put(name, floating(fit(embeddings, count(shape)), shape, info));
                        } else if (name.equals(pack.name("mask_input", "attention_mask"))) {
                            long[] shape = replaceShape(info.getShape(), new long[]{1, mask.length});
                            inputs.put(name, integral(fitLong(mask, count(shape)), shape, info));
                        } else throw new IllegalArgumentException(
                            "Unsupported UNet input: " + name + ". Supply a matching export/manifest.");
                    }
                    try (OrtSession.Result result = unet.run(inputs)) {
                        OnnxTensor pred = (OnnxTensor) result.get(outputIndex(
                            unet, pack.name("unet_output", "")));
                        float[] velocity = floats(pred);
                        if (velocity.length < latents.length) throw new IllegalArgumentException(
                            "UNet returned too few elements: " + velocity.length);
                        float dt = (float) (nextSigma - sigma);
                        for (int i = 0; i < latents.length; i++) latents[i] += dt * velocity[i];
                    }
                } finally { closeAll(inputs); }
                progress.update("Denoising (" + (step + 1) + "/" + steps + ")...");
            }
        }
        diagnostic.append("Denoising: ").append(msSince(before)).append(" ms\n");

        progress.update("Decoding RGB image...");
        before = System.nanoTime();
        Bitmap image;
        try (OrtSession vae = open("vae_decoder")) {
            Map<String, OnnxTensor> input = new HashMap<>();
            try {
                if (vae.getInputInfo().size() != 1) throw new IllegalArgumentException(
                    "VAE decoder export must have exactly one input");
                Map.Entry<String, NodeInfo> entry = vae.getInputInfo().entrySet().iterator().next();
                String expectedName = pack.name("vae_input", entry.getKey());
                if (!entry.getKey().equals(expectedName)) throw new IllegalArgumentException(
                    "VAE input mismatch: model=" + entry.getKey() + ", manifest=" + expectedName);
                long[] shape = replaceShape(type(entry.getValue()).getShape(), new long[]{1,channels,h,w});
                input.put(entry.getKey(), floating(fit(latents, count(shape)), shape, type(entry.getValue())));
                try (OrtSession.Result result = vae.run(input)) {
                    OnnxTensor output = (OnnxTensor) result.get(outputIndex(vae, pack.name("vae_output", "")));
                    long[] outputShape = ((TensorInfo) output.getInfo()).getShape();
                    image = toBitmap(floats(output), outputShape,
                        pack.name("vae_range", "minus_one_to_one"));
                }
            } finally { closeAll(input); }
        }
        diagnostic.append("VAE: ").append(msSince(before)).append(" ms\n");
        diagnostic.append("Runtime: ").append(backend == 2 ? "CPU" :
            "ONNX Runtime NNAPI (actual NPU partitioning NOT verified)").append("\n");
        diagnostic.append("Model contract: experimental; compare output with Python baseline.\n");
        return new Render(image, diagnostic.toString());
    }

    private OrtSession open(String model) throws Exception {
        OrtSession.SessionOptions opt = new OrtSession.SessionOptions();
        try {
            if (backend == 0 || backend == 1) {
                opt.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED, NNAPIFlags.USE_FP16));
                if (backend == 0) opt.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            }
            return env.createSession(pack.getFile(model).getAbsolutePath(), opt);
        } finally { opt.close(); }
    }

    private static TensorInfo type(NodeInfo node) {
        if (!(node.getInfo() instanceof TensorInfo)) throw new IllegalArgumentException("Non-tensor input");
        return (TensorInfo) node.getInfo();
    }

    private static int outputIndex(OrtSession session, String preferred) throws Exception {
        if (preferred.isEmpty()) return 0;
        int index = 0;
        for (String name : session.getOutputNames()) {
            if (name.equals(preferred)) return index;
            index++;
        }
        throw new IllegalArgumentException("Output not found: " + preferred);
    }

    private static long[] shapeForSample(long[] original, int c, int h, int w) {
        return replaceShape(original, new long[]{1,c,h,w});
    }

    private static float[] fillSample(float[] latents, long[] shape, int c, int h, int w) {
        int size = count(shape);
        if (size == latents.length) return latents;
        // Some DreamLite ONNX exports concatenate noise + zero-condition along W.
        if (shape.length == 4 && shape[0] == 1 && shape[1] == c &&
                shape[2] == h && shape[3] == w * 2) {
            float[] combined = new float[size];
            for (int ch = 0; ch < c; ch++) for (int y = 0; y < h; y++)
                System.arraycopy(latents, (ch*h+y)*w, combined, (ch*h+y)*w*2, w);
            return combined;
        }
        throw new IllegalArgumentException("UNet sample dimensions " + Arrays.toString(shape)
            + " incompatible with latent " + c + "x" + h + "x" + w);
    }

    private static long[] replaceShape(long[] source, long[] defaults) {
        if (source.length != defaults.length) throw new IllegalArgumentException(
            "Expected rank " + defaults.length + " but model requires " + source.length);
        long[] result = source.clone();
        for (int i=0; i<result.length; i++) if (result[i] <= 0) result[i] = defaults[i];
        return result;
    }
    private static int count(long[] shape) {
        long n=1;
        for (long x : shape) { n *= x; if(n > Integer.MAX_VALUE) throw new IllegalArgumentException("Tensor too large"); }
        return (int)n;
    }
    private static float[] fit(float[] src, int length) {
        if (length == src.length) return src;
        float[] result = new float[length];
        System.arraycopy(src, 0, result, 0, Math.min(length, src.length));
        return result;
    }
    private static long[] fitLong(long[] src, int length) {
        if (length == src.length) return src;
        long[] result = new long[length];
        System.arraycopy(src, 0, result, 0, Math.min(length, src.length));
        return result;
    }
    private OnnxTensor integral(long[] values, long[] shape, TensorInfo info) throws Exception {
        if (values.length != count(shape)) throw new IllegalArgumentException("Integer shape mismatch");
        if (info.type == OnnxJavaType.INT64) {
            LongBuffer b = ByteBuffer.allocateDirect(values.length*8).order(ByteOrder.nativeOrder()).asLongBuffer();
            b.put(values).rewind();
            return OnnxTensor.createTensor(env, b, shape);
        }
        if (info.type == OnnxJavaType.INT32) {
            IntBuffer b = ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asIntBuffer();
            for (long v:values) b.put((int) v);
            b.rewind();
            return OnnxTensor.createTensor(env, b, shape);
        }
        float[] f = new float[values.length];
        for (int i=0; i<values.length; ++i) f[i] = values[i];
        return floating(f, shape, info);
    }
    private OnnxTensor floating(float[] values, long[] shape, TensorInfo info) throws Exception {
        if (values.length != count(shape)) throw new IllegalArgumentException(
            "Float shape mismatch " + values.length + " != " + count(shape));
        if (info.type == OnnxJavaType.FLOAT) {
            FloatBuffer b = ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            b.put(values).rewind();
            return OnnxTensor.createTensor(env, b, shape);
        }
        if (info.type == OnnxJavaType.FLOAT16) {
            ShortBuffer b = ByteBuffer.allocateDirect(values.length*2).order(ByteOrder.nativeOrder()).asShortBuffer();
            for (float f : values) b.put(fp16(f));
            b.rewind();
            return OnnxTensor.createTensor(env, b, shape, OnnxJavaType.FLOAT16);
        }
        throw new IllegalArgumentException("Unsupported ONNX float input type: " + info.type);
    }

    private static short fp16(float x) {
        int f = Float.floatToIntBits(x), sign=(f >>> 16) & 0x8000;
        int exp=((f >>> 23)&255)-127+15;
        int frac=f & 0x7fffff;
        if (exp <= 0) {
            if (exp < -10) return (short) sign;
            return (short) (sign | ((frac | 0x800000) >> (14-exp)));
        }
        if (exp >= 31) return (short) (sign | 0x7c00 | (frac == 0 ? 0 : 0x200));
        return (short) (sign | (exp << 10) | ((frac + 0x1000) >> 13));
    }
    private static float[] floats(OnnxTensor tensor) {
        TensorInfo info = (TensorInfo) tensor.getInfo();
        if (info.type == OnnxJavaType.FLOAT16) {
            ShortBuffer buffer = tensor.getShortBuffer();
            if (buffer == null) throw new IllegalArgumentException("Could not read FP16 ONNX output");
            float[] out = new float[buffer.remaining()];
            for (int i = 0; i < out.length; i++) out[i] = fp16ToFloat(buffer.get());
            return out;
        }
        FloatBuffer buffer = tensor.getFloatBuffer();
        if (buffer == null) throw new IllegalArgumentException("ONNX output is not floating point: " + info.type);
        float[] out = new float[buffer.remaining()];
        buffer.get(out);
        return out;
    }
    private static float fp16ToFloat(short value) {
        int bits = value & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 31;
        int mantissa = bits & 1023;
        if (exponent == 0) {
            if (mantissa == 0) return Float.intBitsToFloat(sign);
            float subnormal = (float)(mantissa * Math.scalb(1.0, -24));
            return (bits & 0x8000) == 0 ? subnormal : -subnormal;
        }
        if (exponent == 31) return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
        return Float.intBitsToFloat(sign | ((exponent + 112) << 23) | (mantissa << 13));
    }
    private static Bitmap toBitmap(float[] data, long[] shape, String range) {
        if (shape.length != 4 || shape[0] != 1 || shape[1] < 3)
            throw new IllegalArgumentException("VAE output must be NCHW RGB, got " + Arrays.toString(shape));
        int h = (int)shape[2], w = (int)shape[3], area=h*w;
        if (w <= 0 || h <= 0 || area*3 > data.length) throw new IllegalArgumentException("Bad VAE dimensions");
        int[] rgba = new int[area];
        boolean normalized = "minus_one_to_one".equals(range);
        for (int i = 0; i < area; i++) {
            int r = quantize(data[i], normalized);
            int g = quantize(data[area+i], normalized);
            int b = quantize(data[area*2+i], normalized);
            rgba[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        return Bitmap.createBitmap(rgba, w, h, Bitmap.Config.ARGB_8888);
    }
    private static int quantize(float x, boolean normalized) {
        double v = normalized ? (x+1)*127.5 : x*255;
        return (int)Math.min(255,Math.max(0,Math.round(v)));
    }
    private static void closeAll(Map<String, OnnxTensor> tensors) {
        for (OnnxTensor t : tensors.values()) t.close();
    }
    private static long msSince(long timestamp) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-timestamp);
    }
}
