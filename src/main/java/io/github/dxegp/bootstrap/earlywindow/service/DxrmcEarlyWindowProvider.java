package io.github.dxegp.bootstrap.earlywindow.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.CancellationException;
import javax.imageio.ImageIO;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.ProgramArgs;
import net.neoforged.fml.earlydisplay.render.backend.ELSRenderBackend;
import net.neoforged.fml.earlydisplay.render.backend.ELSTexture;
import net.neoforged.fml.earlydisplay.render.backend.TextureFormat;
import net.neoforged.fml.earlydisplay.theme.ThemeColor;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDLRender;
import org.lwjgl.sdl.SDLSurface;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 建 Minecraft 随后接管的那个 SDL 窗口，并把加载屏画进去。
 *
 * <p>加载屏用 AWT 合成、经 surface → texture 上屏，不自带字体/图像编解码依赖。
 * 必须待在独立的 early-service jar 里。见 docs/misc/mixins-and-earlywindow.md §5</p>
 */
public final class DxrmcEarlyWindowProvider implements ImmediateWindowProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("dxrmc/earlywindow");

    private static final int INITIAL_WIDTH = 854;
    private static final int INITIAL_HEIGHT = 480;

    private static final String LOGO_RESOURCE = "/assets/dxrmc/logo_flat_dark.jpg";
    /** 原版默认字形表（Mojangles）：16×16 格、每格 8×8，覆盖 U+0000..U+00FF。 */
    private static final String MOJANGLES_RESOURCE = "/assets/dxrmc/mojangles_ascii.png";
    private static final String CAPTION = "Initializing DirectX 12 backend...";

    /** 进度条填充色。 */
    private static final Color BAR_FILL = new Color(0xC4, 0x6B, 0xFF);
    private static final Color BAR_TRACK = new Color(0x2E, 0x32, 0x38);
    private static final Color BACKGROUND = new Color(0x00, 0x00, 0x00);
    private static final Color CAPTION_COLOR = new Color(0xD8, 0xDC, 0xE2);

    private EarlyWindowLease lease;
    private ELSRenderBackend gameBackend;
    private ELSTexture gameTexture;
    private boolean stopped;
    private boolean closed;
    private boolean handedOver;
    private Thread gameRenderThread;
    private Thread earlyWindowThread;

    private long window;
    private long renderer;
    private BufferedImage logo;
    private BufferedImage mojangles;
    private boolean textureFailureLogged;
    private int progressTicks;

    @Override
    public String name() {
        return "dxrmc";
    }

    @Override
    public synchronized void initialize(ProgramArgs args) {
        if (lease != null || closed) {
            throw new IllegalStateException("Early window provider is already initialized or closed");
        }
        earlyWindowThread = Thread.currentThread();
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) {
            LOGGER.error("SDL_Init failed: {}", SDLError.SDL_GetError());
            return;
        }
        window = SDLVideo.SDL_CreateWindow("Minecraft* NeoForge", INITIAL_WIDTH, INITIAL_HEIGHT,
                SDLVideo.SDL_WINDOW_RESIZABLE);
        if (window == 0L) {
            LOGGER.error("SDL_CreateWindow failed: {}", SDLError.SDL_GetError());
            return;
        }
        lease = new EarlyWindowLease(window);
        try {
        SDLVideo.SDL_SetWindowPosition(window, SDLVideo.SDL_WINDOWPOS_CENTERED, SDLVideo.SDL_WINDOWPOS_CENTERED);
        renderer = SDLRender.SDL_CreateRenderer(window, "software");
        if (renderer == 0L) {
            LOGGER.warn("software renderer unavailable ({}); loading image disabled",
                    SDLError.SDL_GetError());
        }
        SDLVideo.SDL_ShowWindow(window);
        logo = loadLogo();
        mojangles = loadImage(MOJANGLES_RESOURCE);
        LOGGER.info("early window created handle={} renderer={} (single-window mode)", window, renderer);
        LOGGER.info("loading screen: logo={} bar=purple caption='{}' (Mojangles glyphs={})", logo != null ? logo.getWidth() + "x" + logo.getHeight() : "missing", CAPTION, mojangles != null ? mojangles.getWidth() + "x" + mojangles.getHeight() : "missing");
        redraw();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private BufferedImage loadLogo() {
        try (InputStream in = DxrmcEarlyWindowProvider.class.getResourceAsStream(LOGO_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("logo resource {} is missing from the early-window jar", LOGO_RESOURCE);
                return null;
            }
            return ImageIO.read(in);
        } catch (Throwable t) {
            LOGGER.warn("cannot decode the loading screen logo: {}", t.toString());
            return null;
        }
    }

    private void redraw() {
        if ((renderer == 0L && gameBackend == null) || stopped) {
            return;
        }
        WindowState state = snapshot();
        int width = Math.max(1, state.width());
        int height = Math.max(1, state.height());

        BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, width, height);

            int margin = Math.round(width * 0.10f);
            int contentWidth = width - margin * 2;

            if (logo != null) {
                float maxLogoWidth = contentWidth;
                float maxLogoHeight = height * 0.34f;
                float scale = Math.min(maxLogoWidth / logo.getWidth(), maxLogoHeight / logo.getHeight());
                int logoWidth = Math.round(logo.getWidth() * scale);
                int logoHeight = Math.round(logo.getHeight() * scale);
                g.drawImage(logo, (width - logoWidth) / 2, Math.round(height * 0.08f), logoWidth, logoHeight,
                        null);
            }

            int barHeight = Math.max(8, Math.round(height * 0.022f));
            int barY = Math.round(height * 0.62f);
            g.setColor(BAR_TRACK);
            g.fillRoundRect(margin, barY, contentWidth, barHeight, barHeight, barHeight);

            float fraction = Math.min(1.0f, 0.05f + progressTicks * 0.02f);
            int filled = Math.max(barHeight, Math.round(contentWidth * fraction));
            g.setColor(BAR_FILL);
            g.fillRoundRect(margin, barY, filled, barHeight, barHeight, barHeight);

            // 说明文字用原版字形表而不是系统字体，观感才和游戏内一致。
            int captionScale = Math.max(2, Math.round(height * 0.036f) / 8);
            drawMojangles(g, CAPTION, width, barY + barHeight + Math.round(18 * captionScale / 2f), captionScale);
        } finally {
            g.dispose();
        }

        presentFrame(frame);
    }

    private BufferedImage loadImage(String resource) {
        try (InputStream in = DxrmcEarlyWindowProvider.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("{} is missing from the early-window jar", resource);
                return null;
            }
            return ImageIO.read(in);
        } catch (Throwable t) {
            LOGGER.warn("cannot decode {}: {}", resource, t.toString());
            return null;
        }
    }

    /** 用原版 Mojangles 字形表画说明文字；字宽取最后一个非空列 +1。 */
    private void drawMojangles(Graphics2D g, String text, int canvasWidth, int baselineY, int scale) {
        if (mojangles == null) {
            return;
        }
        int totalWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            totalWidth += glyphAdvance(text.charAt(i)) * scale;
        }
        int x = (canvasWidth - totalWidth) / 2;
        int top = baselineY - 8 * scale;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int advance = glyphAdvance(c);
            drawGlyph(g, c, x, top, scale);
            x += advance * scale;
        }
        g.setColor(CAPTION_COLOR);
    }

    private int glyphAdvance(char c) {
        int cellX = (c & 0x0F) * 8;
        int cellY = ((c >> 4) & 0x0F) * 8;
        int lastColumn = 1;
        for (int column = 0; column < 8; column++) {
            for (int row = 0; row < 8; row++) {
                if ((mojangles.getRGB(cellX + column, cellY + row) >>> 24) != 0) {
                    lastColumn = Math.max(lastColumn, column + 1);
                }
            }
        }
        return lastColumn + 1;
    }

    private void drawGlyph(Graphics2D g, char c, int x, int top, int scale) {
        int cellX = (c & 0x0F) * 8;
        int cellY = ((c >> 4) & 0x0F) * 8;
        g.setColor(new Color(0x3F, 0x3F, 0x3F));
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                if ((mojangles.getRGB(cellX + column, cellY + row) >>> 24) != 0) {
                    g.fillRect(x + column * scale + scale, top + row * scale + scale, scale, scale);
                }
            }
        }
        g.setColor(CAPTION_COLOR);
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                if ((mojangles.getRGB(cellX + column, cellY + row) >>> 24) != 0) {
                    g.fillRect(x + column * scale, top + row * scale, scale, scale);
                }
            }
        }
    }
    /** 必须走 surface → texture：软件 renderer 会**静默忽略**流式纹理上传（不报错、也不显示）。 */
    private void presentFrame(BufferedImage frame) {
        if (gameBackend != null) {
            presentGameFrame(frame);
            return;
        }
        int width = frame.getWidth();
        int height = frame.getHeight();
        int[] argb = frame.getRGB(0, 0, width, height, null, 0, width);
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            pixels.asIntBuffer().put(argb);
            long surface = SDLSurface.nSDL_CreateSurfaceFrom(width, height, SDLPixels.SDL_PIXELFORMAT_ARGB8888,
                    MemoryUtil.memAddress(pixels), width * 4);
            if (surface == 0L) {
                logTextureFailure("SDL_CreateSurfaceFrom");
                return;
            }
            long texture = SDLRender.nSDL_CreateTextureFromSurface(renderer, surface);
            if (texture == 0L) {
                logTextureFailure("SDL_CreateTextureFromSurface");
                SDLSurface.nSDL_DestroySurface(surface);
                return;
            }
            SDLRender.nSDL_RenderTexture(renderer, texture, 0L, 0L);
            SDLRender.nSDL_DestroyTexture(texture);
            SDLSurface.nSDL_DestroySurface(surface);
        } finally {
            MemoryUtil.memFree(pixels);
        }
        SDLRender.SDL_RenderPresent(renderer);
    }

    private void logTextureFailure(String what) {
        if (!textureFailureLogged) {
            textureFailureLogged = true;
            LOGGER.warn("{} failed: {}", what, SDLError.SDL_GetError());
        }
    }

    @Override
    public void setMinecraftVersion(String version) {
        LOGGER.info("minecraftVersion={}", version);
    }

    @Override
    public void setNeoForgeVersion(String version) {
        LOGGER.info("neoForgeVersion={}", version);
    }

    @Override
    public void crash(String message) {
        LOGGER.error("crash: {}", message);
    }

    @Override
    public void displayFatalErrorAndExit(List<ModLoadingIssue> issues, @Nullable Path modsFolder,
            @Nullable Path logFile, @Nullable Path crashReportFile) {
        LOGGER.error("fatal loading error, {} issue(s)", issues.size());
        Runtime.getRuntime().halt(1);
    }

    @Override
    public synchronized void stopEventPolling() {
        stopped = true;
    }

    public synchronized long claimWindow() {
        if (lease != null && lease.isClaimed()) {
            throw new IllegalStateException("Early window has already been claimed by Minecraft");
        }
        if (closed || lease == null) {
            return 0L;
        }
        stopped = true;
        releaseRenderer();
        return lease.claim();
    }

    @Override
    public synchronized WindowState handOverToMinecraft(Supplier<Object> renderBackend) {
        if (closed || handedOver) {
            throw new IllegalStateException("Early window handoff is already complete or closed");
        }
        WindowState state = snapshot();
        handedOver = true;
        if (lease == null || !lease.isClaimed()) {
            // 非 D3D12：原版自己建了窗口，我们这扇不能当第二个窗口留在屏幕上。
            LOGGER.info("graphics backend is not D3D12 - dropping the early window, vanilla creates its own");
            close();
            return state;
        }
        LOGGER.info("handing the early window over to Minecraft (same window, no recreate)");
        releaseRenderer();
        try {
            gameBackend = (ELSRenderBackend) renderBackend.get();
            gameRenderThread = Thread.currentThread();
            if (gameBackend.getWindowHandle() != window) {
                throw new IllegalStateException("Loading backend changed the adopted SDL window");
            }
            stopped = false;
            redraw();
            return state;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Override
    public synchronized void periodicTick() {
        if (stopped) {
            return;
        }
        if (gameBackend != null && Thread.currentThread() != gameRenderThread) {
            return;
        }
        if (!handedOver && window != 0L) {
            if (Thread.currentThread() != earlyWindowThread) {
                return;
            }
            pollEarlyEvents();
        }
        progressTicks++;
        if (progressTicks % 4 == 0) {
            redraw();
        }
    }

    @Override
    public void updateProgress(String label) {
        // 说明文字固定：它描述的是正在初始化的后端。
    }

    @Override
    public synchronized void completeProgress() {
        progressTicks = 50;
        if (gameBackend == null ? Thread.currentThread() == earlyWindowThread
                : Thread.currentThread() == gameRenderThread) {
            redraw();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        stopped = true;
        if (gameBackend != null) {
            ELSRenderBackend backend = gameBackend;
            ELSTexture texture = gameTexture;
            gameBackend = null;
            gameTexture = null;
            backend.guardResourceCleanup(() -> {
                try {
                    if (texture != null) {
                        texture.close();
                    }
                } finally {
                    backend.close();
                }
            });
        }
        releaseRenderer();
        if (lease != null) {
            lease.closeUnclaimed();
        }
        window = 0L;
    }

    private void releaseRenderer() {
        if (renderer != 0L) {
            SDLRender.SDL_SetRenderDrawColor(renderer, (byte) 0, (byte) 0, (byte) 0, (byte) 255);
            SDLRender.SDL_RenderClear(renderer);
            SDLRender.SDL_RenderPresent(renderer);
            SDLRender.SDL_DestroyRenderer(renderer);
            renderer = 0L;
        }
    }

    private void pollEarlyEvents() {
        try (SDL_Event event = SDL_Event.malloc()) {
            while (SDLEvents.SDL_PollEvent(event)) {
                if (event.type() == SDLEvents.SDL_EVENT_QUIT
                        || event.type() == SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED
                        && SDLEvents.SDL_GetWindowFromEvent(event) == window) {
                    close();
                    throw new CancellationException("Early loading window was closed");
                }
            }
        }
    }

    private WindowState snapshot() {
        if (window == 0L) {
            return new WindowState(0, 0, INITIAL_WIDTH, INITIAL_HEIGHT, false, false, false, false);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.callocInt(1);
            IntBuffer y = stack.callocInt(1);
            IntBuffer width = stack.ints(INITIAL_WIDTH);
            IntBuffer height = stack.ints(INITIAL_HEIGHT);
            boolean position = SDLVideo.SDL_GetWindowPosition(window, x, y);
            SDLVideo.SDL_GetWindowSize(window, width, height);
            long flags = SDLVideo.SDL_GetWindowFlags(window);
            return new WindowState(x.get(0), y.get(0), width.get(0), height.get(0), position,
                    (flags & SDLVideo.SDL_WINDOW_MINIMIZED) != 0,
                    (flags & SDLVideo.SDL_WINDOW_MAXIMIZED) != 0,
                    (flags & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0);
        }
    }

    private void presentGameFrame(BufferedImage frame) {
        if (!gameBackend.startFrame((width, height) -> {})) {
            return;
        }
        if (gameTexture != null && (gameTexture.width() != frame.getWidth() || gameTexture.height() != frame.getHeight())) {
            gameTexture.close();
            gameTexture = null;
        }
        if (gameTexture == null) {
            gameTexture = gameBackend.createTexture("Illusion loading screen", frame.getWidth(), frame.getHeight(),
                    TextureFormat.RGBA8_UNORM, true);
        }
        ByteBuffer pixels = MemoryUtil.memAlloc(frame.getWidth() * frame.getHeight() * 4);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int pixel : frame.getRGB(0, 0, frame.getWidth(), frame.getHeight(), null, 0, frame.getWidth())) {
                pixels.put((byte) (pixel >>> 16)).put((byte) (pixel >>> 8)).put((byte) pixel).put((byte) (pixel >>> 24));
            }
            pixels.flip();
            gameBackend.writeToTexture(gameTexture, pixels);
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            SDLVideo.SDL_GetWindowSizeInPixels(window, width, height);
            gameBackend.presentTexture(gameTexture, ThemeColor.ofRgb(0), width.get(0), height.get(0));
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
