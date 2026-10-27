package cam72cam.immersiverailroading.render;

import cam72cam.immersiverailroading.ImmersiveRailroading;
import cam72cam.mod.render.opengl.Texture;
import cam72cam.mod.resource.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decodes a GIF and uploads the frame that matches the stock's age.
 * Faces that display it are remapped to the full 0..1 UV range, so the clip replaces that group's atlas tile.
 */
public final class GifClip {
    private static final Map<String, GifClip> CACHE = new ConcurrentHashMap<>();

    private final BufferedImage[] frames;
    private final int[] delays;
    private final int width;
    private final int height;
    private int textureId;
    private int uploaded = -1;

    private GifClip(BufferedImage[] frames, int[] delays) {
        this.frames = frames;
        this.delays = delays;
        this.width = frames[0].getWidth();
        this.height = frames[0].getHeight();
    }

    public static GifClip get(Identifier location) {
        if (location == null || !location.canLoad()) {
            return null;
        }
        String key = location.toString();
        GifClip cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try (InputStream stream = location.getResourceStream()) {
            GifClip decoded = decode(stream);
            if (decoded == null) {
                return null;
            }
            CACHE.put(key, decoded);
            return decoded;
        } catch (IOException ex) {
            ImmersiveRailroading.error("Unable to read GIF %s: %s", key, ex.getMessage());
            return null;
        }
    }

    public Texture frame(int tick) {
        int index = GifTimeline.frameAt(tick, delays);
        if (textureId == 0) {
            textureId = GL11.glGenTextures();
        }
        if (uploaded != index) {
            upload(frames[index]);
            uploaded = index;
        }
        return Texture.wrap(textureId);
    }

    private void upload(BufferedImage image) {
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
        ByteBuffer buffer = ByteBuffer.allocateDirect(pixels.length * 4).order(ByteOrder.nativeOrder());
        for (int pixel : pixels) {
            buffer.put((byte) ((pixel >> 16) & 0xFF));
            buffer.put((byte) ((pixel >> 8) & 0xFF));
            buffer.put((byte) (pixel & 0xFF));
            buffer.put((byte) ((pixel >> 24) & 0xFF));
        }
        buffer.flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
    }

    static GifClip decode(InputStream stream) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(stream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int count = reader.getNumImages(true);
                if (count <= 0) {
                    return null;
                }
                List<BufferedImage> images = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    images.add(reader.read(i));
                }
                int[] delays = new int[images.size()];
                for (int i = 0; i < delays.length; i++) {
                    delays[i] = 2;
                }
                return new GifClip(images.toArray(new BufferedImage[0]), delays);
            } finally {
                reader.dispose();
            }
        }
    }
}
