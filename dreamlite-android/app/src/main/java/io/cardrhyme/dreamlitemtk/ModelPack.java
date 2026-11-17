package io.cardrhyme.dreamlitemtk;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** A simple, explicit model pack manifest; no model weights are shipped in the APK. */
public final class ModelPack {
    public final File dir;
    public final JSONObject config;

    public ModelPack(File dir) throws Exception {
        this.dir = dir;
        File manifest = new File(dir, "model-pack.json");
        if (!manifest.isFile()) throw new IllegalArgumentException(
            "Missing model-pack.json. The pack must describe its ONNX I/O contract.");
        config = new JSONObject(Files.readString(manifest.toPath(), StandardCharsets.UTF_8));
        if (!"dreamlite-onnx-mobile-v1".equals(config.optString("format")))
            throw new IllegalArgumentException("Unsupported pack format. Expected dreamlite-onnx-mobile-v1");
        for (String name : new String[]{"text_encoder","unet","vae_decoder","tokenizer"}) getFile(name);
    }

    public File getFile(String key) throws Exception {
        String name = config.getString(key);
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Only flat model pack filenames supported: " + key);
        File result = new File(dir, name);
        if (!result.isFile()) throw new IllegalArgumentException("Missing " + key + ": " + name);
        return result;
    }

    public String name(String key, String defaultName) {
        return config.optString(key, defaultName);
    }

    public int number(String key, int defaultValue) {
        return config.optInt(key, defaultValue);
    }
}
