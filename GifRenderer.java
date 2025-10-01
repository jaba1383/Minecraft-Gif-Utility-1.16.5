package ru.client.utils.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class GifUtil {
    private static final Minecraft mc = Minecraft.getInstance();

    private static class GifAnimation {
        private List<ResourceLocation> frames = new ArrayList<>();
        private List<Integer> delays = new ArrayList<>();
        private int currentFrame = 0;
        private long lastFrameTime = 0;
        private boolean loaded = false;
        private int width, height;

        public void cleanup() {
            for (ResourceLocation frame : frames) {
                if (mc.getTextureManager() != null) {
                    mc.getTextureManager().deleteTexture(frame);
                }
            }
            frames.clear();
            delays.clear();
        }
    }

    private static final HashMap<ResourceLocation, GifAnimation> gifCache = new HashMap<>();
    private static int textureCounter = 0;

    public static void renderGif(ResourceLocation gifLocation, int x, int y, int width, int height) {
        renderGif(gifLocation, x, y, width, height, -1);
    }

    public static void renderGif(ResourceLocation gifLocation, int x, int y, int width, int height, int color) {
        if (mc.getResourceManager() == null) return;
        GifAnimation animation = gifCache.get(gifLocation);
        if (animation == null) {
            animation = new GifAnimation();
            gifCache.put(gifLocation, animation);
            loadGifFrames(gifLocation, animation);
        }
        if (!animation.loaded || animation.frames.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() - animation.lastFrameTime > animation.delays.get(animation.currentFrame)) {
            animation.lastFrameTime = System.currentTimeMillis();
            animation.currentFrame = (animation.currentFrame + 1) % animation.frames.size();
        }
        ResourceLocation currentFrame = animation.frames.get(animation.currentFrame);
        DisplayUtils.drawImage(currentFrame, x, y, width, height, color);
    }

    private static void loadGifFrames(ResourceLocation gifLocation, GifAnimation animation) {
        try (InputStream inputStream = mc.getResourceManager().getResource(gifLocation).getInputStream()) {
            ImageInputStream imageInput = ImageIO.createImageInputStream(inputStream);

            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) {
                System.err.println("No GIF reader found!");
                return;
            }
            ImageReader reader = readers.next();
            reader.setInput(imageInput, false);

            int frameCount = reader.getNumImages(true);
            animation.width = reader.getWidth(0);
            animation.height = reader.getHeight(0);

            for (int i = 0; i < frameCount; i++) {
                try {
                    BufferedImage frame = reader.read(i);
                    int delay = getGifFrameDelay(reader, i);
                    ResourceLocation frameTexture = createTextureFromBufferedImage(frame, "gif_frame_" + textureCounter++);
                    if (frameTexture != null) {
                        animation.frames.add(frameTexture);
                        animation.delays.add(delay);
                    }

                } catch (Exception e) {
                    System.err.println("Failed to load frame " + i + " of GIF: " + gifLocation);
                    e.printStackTrace();
                }
            }

            reader.dispose();
            imageInput.close();

            animation.loaded = !animation.frames.isEmpty();
            if (animation.loaded) {
                System.out.println("Successfully loaded GIF with " + animation.frames.size() + " frames: " + gifLocation);
            }

        } catch (Exception e) {
            System.err.println("Failed to load GIF: " + gifLocation);
            e.printStackTrace();
            animation.loaded = false;
        }
    }

    private static int getGifFrameDelay(ImageReader reader, int frameIndex) {
        try {
            var metadata = reader.getImageMetadata(frameIndex);
            var tree = metadata.getAsTree("javax_imageio_gif_image_1.0");
            var children = tree.getChildNodes();

            for (int i = 0; i < children.getLength(); i++) {
                var node = children.item(i);
                if ("GraphicControlExtension".equals(node.getNodeName())) {
                    var attributes = node.getAttributes();
                    var delayNode = attributes.getNamedItem("delayTime");
                    if (delayNode != null) {
                        int delay = Integer.parseInt(delayNode.getNodeValue()) * 10;
                        return Math.max(delay, 20);
                    }
                }
            }
        } catch (Exception e) {
        }
        return 100;
    }

    private static ResourceLocation createTextureFromBufferedImage(BufferedImage bufferedImage, String name) {
        try {
            NativeImage nativeImage = bufferedImageToNativeImage(bufferedImage);
            DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);
            return Minecraft.getInstance().getTextureManager().getDynamicTextureLocation(name, dynamicTexture);
        } catch (Exception e) {
            System.err.println("Failed to create texture from BufferedImage: " + e.getMessage());
            return null;
        }
    }

    private static NativeImage bufferedImageToNativeImage(BufferedImage bufferedImage) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        NativeImage nativeImage = new NativeImage(NativeImage.PixelFormat.RGBA, width, height, false);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int argb = bufferedImage.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                nativeImage.setPixelRGBA(x, y, abgr);
            }
        }

        return nativeImage;
    }


    public static boolean preloadGif(ResourceLocation gifLocation) {
        if (gifCache.containsKey(gifLocation)) {
            return true; // Already loaded
        }

        GifAnimation animation = new GifAnimation();
        gifCache.put(gifLocation, animation);
        loadGifFrames(gifLocation, animation);

        return animation.loaded;
    }


    public static boolean isGifLoaded(ResourceLocation gifLocation) {
        GifAnimation animation = gifCache.get(gifLocation);
        return animation != null && animation.loaded && !animation.frames.isEmpty();
    }

    public static int[] getGifDimensions(ResourceLocation gifLocation) {
        GifAnimation animation = gifCache.get(gifLocation);
        if (animation != null && animation.loaded) {
            return new int[]{animation.width, animation.height};
        }
        return new int[]{0, 0};
    }


    public static void cleanupGif(ResourceLocation gifLocation) {
        GifAnimation animation = gifCache.get(gifLocation);
        if (animation != null) {
            animation.cleanup();
            gifCache.remove(gifLocation);
        }
    }

    public static void cleanupAll() {
        for (GifAnimation animation : gifCache.values()) {
            animation.cleanup();
        }
        gifCache.clear();
        textureCounter = 0;
    }

    public static void renderGifWithSpeed(ResourceLocation gifLocation, int x, int y, int width, int height, float speedMultiplier) {
        renderGifWithSpeed(gifLocation, x, y, width, height, -1, speedMultiplier);
    }

    public static void renderGifWithSpeed(ResourceLocation gifLocation, int x, int y, int width, int height, int color, float speedMultiplier) {
        GifAnimation animation = gifCache.get(gifLocation);
        if (animation == null || !animation.loaded || animation.frames.isEmpty()) {
            return;
        }

        // Calculate adjusted delay based on speed multiplier
        int baseDelay = animation.delays.get(animation.currentFrame);
        int adjustedDelay = (int) (baseDelay / speedMultiplier);

        if (System.currentTimeMillis() - animation.lastFrameTime > adjustedDelay) {
            animation.lastFrameTime = System.currentTimeMillis();
            animation.currentFrame = (animation.currentFrame + 1) % animation.frames.size();
        }

        ResourceLocation currentFrame = animation.frames.get(animation.currentFrame);
        DisplayUtils.drawImage(currentFrame, x, y, width, height, color);
    }
}
