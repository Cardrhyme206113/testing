package com.example.blockhost;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class ProcessIo {
    private ProcessIo() {}

    public static String consume(Process process, Consumer<String> lineConsumer) throws IOException {
        StringBuilder all = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
                if (lineConsumer != null) lineConsumer.accept(line);
            }
        }
        return all.toString();
    }
}
