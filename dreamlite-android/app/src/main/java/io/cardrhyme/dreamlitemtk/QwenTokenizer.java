package io.cardrhyme.dreamlitemtk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local byte-level BPE tokenizer for Qwen3 tokenizer.json exports.
 * Recognizes explicit special tokens in templates. No image/video processing.
 */
public final class QwenTokenizer {
    private static final Pattern PIECES = Pattern.compile(
        "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+");
    private final Map<String, Integer> vocab = new HashMap<>();
    private final Map<String, Integer> merges = new HashMap<>();
    private final Map<String, Integer> special = new HashMap<>();
    private final List<String> specialKeys = new ArrayList<>();
    private final String[] byteToUnicode = new String[256];
    private final int unknown;

    public QwenTokenizer(File file) throws Exception {
        JSONObject root = new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        JSONObject model = root.getJSONObject("model");
        JSONObject v = model.getJSONObject("vocab");
        for (String key : v.keySet()) vocab.put(key, v.getInt(key));
        JSONArray mergeList = model.getJSONArray("merges");
        for (int i = 0; i < mergeList.length(); ++i) {
            Object raw = mergeList.get(i);
            String pair;
            if (raw instanceof JSONArray) {
                JSONArray a = (JSONArray) raw;
                pair = a.getString(0) + " " + a.getString(1);
            } else pair = String.valueOf(raw);
            merges.put(pair, i);
        }
        JSONArray added = root.optJSONArray("added_tokens");
        if (added != null) for (int i = 0; i < added.length(); ++i) {
            JSONObject a = added.getJSONObject(i);
            String token = a.getString("content");
            int id = a.getInt("id");
            vocab.put(token, id);
            if (a.optBoolean("special", false)) {
                special.put(token, id);
                specialKeys.add(token);
            }
        }
        specialKeys.sort(Comparator.comparingInt(String::length).reversed());
        unknown = vocab.getOrDefault("<|endoftext|>", 0);
        makeByteMap();
    }

    private void makeByteMap() {
        List<Integer> bs = new ArrayList<>();
        for (int x = 33; x <= 126; x++) bs.add(x);
        for (int x = 161; x <= 172; x++) bs.add(x);
        for (int x = 174; x <= 255; x++) bs.add(x);
        int extra = 0;
        for (int b : bs) byteToUnicode[b] = new String(Character.toChars(b));
        for (int b = 0; b < 256; ++b)
            if (byteToUnicode[b] == null)
                byteToUnicode[b] = new String(Character.toChars(256 + extra++));
    }

    public long[] tokenize(String text, int maxTokens, int padId) {
        List<Integer> out = new ArrayList<>();
        int pos = 0;
        while (pos < text.length() && out.size() < maxTokens) {
            int nearest = text.length();
            String matched = null;
            for (String token : specialKeys) {
                int i = text.indexOf(token, pos);
                if (i >= 0 && i < nearest) { nearest = i; matched = token; }
            }
            appendRegular(text.substring(pos, nearest), out, maxTokens);
            if (matched == null || out.size() >= maxTokens) break;
            out.add(special.get(matched));
            pos = nearest + matched.length();
        }
        long[] result = new long[maxTokens];
        for (int i = 0; i < maxTokens; i++) result[i] = i < out.size() ? out.get(i) : padId;
        return result;
    }

    private void appendRegular(String text, List<Integer> out, int maxTokens) {
        Matcher matcher = PIECES.matcher(text);
        while (matcher.find() && out.size() < maxTokens) {
            byte[] bytes = matcher.group().getBytes(StandardCharsets.UTF_8);
            List<String> words = new ArrayList<>(bytes.length);
            for (byte b : bytes) words.add(byteToUnicode[b & 255]);
            while (words.size() > 1) {
                int best = Integer.MAX_VALUE, at = -1;
                for (int j = 0; j < words.size() - 1; j++) {
                    Integer rank = merges.get(words.get(j) + " " + words.get(j + 1));
                    if (rank != null && rank < best) { best = rank; at = j; }
                }
                if (at < 0) break;
                words.set(at, words.get(at) + words.get(at + 1));
                words.remove(at + 1);
            }
            for (String w : words) {
                out.add(vocab.getOrDefault(w, unknown));
                if (out.size() == maxTokens) break;
            }
        }
    }
}
