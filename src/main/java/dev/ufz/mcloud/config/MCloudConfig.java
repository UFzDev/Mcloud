package dev.ufz.mcloud.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ufz.mcloud.MCloudMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MCloudConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MCloudConfig instance;

    public boolean enabled = true;
    public boolean autoStartCloudflared = true;
    public boolean autoDownloadCloudflared = true;
    public String tunnelMode = "quick"; // "quick" (free trycloudflare.com) or "token" (custom domain)
    public String tunnelToken = "";
    public int localTargetPort = 25565;
    public List<String> customCloudflareDomains = new ArrayList<>();

    public static synchronized MCloudConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcloud.json");
    }

    public static MCloudConfig load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                MCloudConfig loaded = GSON.fromJson(reader, MCloudConfig.class);
                if (loaded != null) {
                    if (loaded.customCloudflareDomains == null) {
                        loaded.customCloudflareDomains = new ArrayList<>();
                    }
                    return loaded;
                }
            } catch (Exception e) {
                MCloudMod.LOGGER.error("[MCloud] Failed to read config/mcloud.json, using defaults", e);
            }
        }
        MCloudConfig defaults = new MCloudConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            MCloudMod.LOGGER.error("[MCloud] Failed to save config/mcloud.json", e);
        }
    }
}
