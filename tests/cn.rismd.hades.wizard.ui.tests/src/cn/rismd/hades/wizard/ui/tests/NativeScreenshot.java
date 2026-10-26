package cn.rismd.hades.wizard.ui.tests;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

final class NativeScreenshot {
    private NativeScreenshot() {
    }

    static void capture(String windowTitle, Path destination) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            BufferedImage image = new java.awt.Robot()
                    .createScreenCapture(new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize()));
            Files.createDirectories(destination.getParent());
            ImageIO.write(image, "png", destination.toFile());
            return;
        }
        String tree = command("xwininfo", "-root", "-tree");
        String id = null;
        var matcher = Pattern.compile("(0x[0-9a-fA-F]+)\\s+\\\"\\Q" + windowTitle + "\\E").matcher(tree);
        while (matcher.find()) {
            id = matcher.group(1);
        }
        if (id == null) {
            throw new AssertionError("Native window not found for screenshot: " + windowTitle + "\n" + tree);
        }
        byte[] xwd = commandBytes("xwd", "-silent", "-id", id);
        BufferedImage image = decode(xwd);
        Files.createDirectories(destination.getParent());
        ImageIO.write(image, "png", destination.toFile());
    }

    private static String command(String... command) throws Exception {
        return new String(commandBytes(command), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] commandBytes(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Screenshot command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException("Screenshot command failed: " + String.join(" ", command) + "\n"
                    + new String(output, java.nio.charset.StandardCharsets.UTF_8));
        }
        return output;
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        var input = new ByteArrayInputStream(bytes);
        int[] header = new int[25];
        for (int i = 0; i < header.length; i++) {
            header[i] = readInt(input);
        }
        int headerSize = header[0];
        int width = header[4];
        int height = header[5];
        int byteOrder = header[7];
        int bitsPerPixel = header[11];
        int bytesPerLine = header[12];
        int redMask = header[14];
        int greenMask = header[15];
        int blueMask = header[16];
        int colors = header[19];
        long offset = headerSize + (long) colors * 12;
        input.skipNBytes(offset - 100);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int bytesPerPixel = (bitsPerPixel + 7) / 8;
        byte[] row = new byte[bytesPerLine];
        for (int y = 0; y < height; y++) {
            input.readNBytes(row, 0, row.length);
            for (int x = 0; x < width; x++) {
                int pixel = 0;
                int start = x * bytesPerPixel;
                for (int b = 0; b < bytesPerPixel; b++) {
                    int value = row[start + b] & 0xff;
                    pixel |= byteOrder == 0 ? value << (8 * b) : value << (8 * (bytesPerPixel - 1 - b));
                }
                int red = scale(pixel, redMask);
                int green = scale(pixel, greenMask);
                int blue = scale(pixel, blueMask);
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private static int readInt(ByteArrayInputStream input) throws IOException {
        byte[] bytes = input.readNBytes(4);
        if (bytes.length != 4) {
            throw new IOException("Truncated XWD header");
        }
        return ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16) | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
    }

    private static int scale(int pixel, int mask) {
        if (mask == 0) {
            return 0;
        }
        int shift = Integer.numberOfTrailingZeros(mask);
        long maximum = mask >>> shift;
        return (int) (((pixel & mask) >>> shift) * 255L / maximum);
    }
}
