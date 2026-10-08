package dev.cardrhyme.equirectshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.IntStream;

public final class EquirectStitcher {
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private EquirectStitcher() {}

    public static Path stitch(int[][] faces, int faceSize, int width, int height, Path directory) throws IOException {
        for (int i = 0; i < 6; i++) {
            if (faces[i] == null || faces[i].length != faceSize * faceSize) {
                throw new IllegalArgumentException("Missing or invalid cube face " + i);
            }
        }

        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) output.getRaster().getDataBuffer()).getData();
        double[] sinLon = new double[width];
        double[] cosLon = new double[width];
        for (int x = 0; x < width; x++) {
            double lon = ((x + 0.5) / width - 0.5) * Math.PI * 2.0;
            sinLon[x] = Math.sin(lon);
            cosLon[x] = Math.cos(lon);
        }
        double[] sinLat = new double[height];
        double[] cosLat = new double[height];
        for (int y = 0; y < height; y++) {
            double lat = (0.5 - (y + 0.5) / height) * Math.PI;
            sinLat[y] = Math.sin(lat);
            cosLat[y] = Math.cos(lat);
        }

        IntStream.range(0, height).parallel().forEach(y -> {
            double dy = sinLat[y];
            double cl = cosLat[y];
            int row = y * width;
            for (int x = 0; x < width; x++) {
                double dx = sinLon[x] * cl;
                double dz = cosLon[x] * cl;
                out[row + x] = sampleCube(faces, faceSize, dx, dy, dz);
            }
        });

        Files.createDirectories(directory);
        Path file = uniqueFile(directory);
        if (!ImageIO.write(output, "PNG", file.toFile())) throw new IOException("No PNG writer available");
        output.flush();
        return file;
    }

    private static int sampleCube(int[][] faces, int s, double x, double y, double z) {
        double ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        int face;
        double u, vUp, denom;

        if (ay >= ax && ay >= az) {
            denom = ay;
            if (y >= 0.0) {
                face = 4;
                u = x / denom;
                vUp = -z / denom;
            } else {
                face = 5;
                u = x / denom;
                vUp = z / denom;
            }
        } else if (ax >= az) {
            denom = ax;
            if (x >= 0.0) {
                face = 1;
                u = -z / denom;
                vUp = y / denom;
            } else {
                face = 3;
                u = z / denom;
                vUp = y / denom;
            }
        } else {
            denom = az;
            if (z >= 0.0) {
                face = 0;
                u = x / denom;
                vUp = y / denom;
            } else {
                face = 2;
                u = -x / denom;
                vUp = y / denom;
            }
        }

        double px = (u + 1.0) * 0.5 * (s - 1);
        double py = (1.0 - vUp) * 0.5 * (s - 1);
        return bilinear(faces[face], s, px, py);
    }

    private static int bilinear(int[] pixels, int s, double x, double y) {
        int x0 = clamp((int) Math.floor(x), 0, s - 1);
        int y0 = clamp((int) Math.floor(y), 0, s - 1);
        int x1 = Math.min(x0 + 1, s - 1);
        int y1 = Math.min(y0 + 1, s - 1);
        double tx = x - x0;
        double ty = y - y0;

        int c00 = pixels[y0 * s + x0];
        int c10 = pixels[y0 * s + x1];
        int c01 = pixels[y1 * s + x0];
        int c11 = pixels[y1 * s + x1];

        int r = lerpChannel(c00 >>> 16 & 255, c10 >>> 16 & 255, c01 >>> 16 & 255, c11 >>> 16 & 255, tx, ty);
        int g = lerpChannel(c00 >>> 8 & 255, c10 >>> 8 & 255, c01 >>> 8 & 255, c11 >>> 8 & 255, tx, ty);
        int b = lerpChannel(c00 & 255, c10 & 255, c01 & 255, c11 & 255, tx, ty);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int lerpChannel(int a, int b, int c, int d, double tx, double ty) {
        double top = a + (b - a) * tx;
        double bottom = c + (d - c) * tx;
        return clamp((int) Math.round(top + (bottom - top) * ty), 0, 255);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Path uniqueFile(Path directory) {
        String base = "equirectshot_" + LocalDateTime.now().format(NAME_FORMAT);
        Path candidate = directory.resolve(base + ".png");
        int suffix = 2;
        while (Files.exists(candidate)) candidate = directory.resolve(base + "_" + suffix++ + ".png");
        return candidate;
    }
}
