package net.bigyous.gptgodmc;

import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.File;
import java.io.IOException;

public class LocalGameMap {
    private final File sourceWorldFolder;
    private File activeWorldFolder;
    private String worldName;
    private World bukkitWorld;

    public LocalGameMap(File worldFolder, String worldName, boolean loadOnInit) {
        this.sourceWorldFolder = new File(worldFolder, worldName);
        if (loadOnInit)
            load();
        this.worldName = worldName;
    }

    public boolean load() {
        if (isLoaded())
            return true;
        this.activeWorldFolder = new File(Bukkit.getWorldContainer(),
                sourceWorldFolder.getName() + "_active_" + System.currentTimeMillis());
        try {
            FileUtils.copyDirectory(sourceWorldFolder, activeWorldFolder);
        } catch (IOException e) {
            GPTGOD.LOGGER.error("Loading GameMap Failed", e);
            return false;
        }
        this.bukkitWorld = Bukkit.createWorld(new WorldCreator(activeWorldFolder.getName()));

        if (bukkitWorld != null) {
            this.bukkitWorld.setAutoSave(false);
        }
        return isLoaded();
    }

    public void unload() {
        if (bukkitWorld != null)
            Bukkit.unloadWorld(bukkitWorld, false);
        if (activeWorldFolder != null) {
            try {
                FileUtils.deleteDirectory(activeWorldFolder);
            } catch (IOException e) {
                GPTGOD.LOGGER.error("Unloading World failed", e);
                return;
            }
        }
        this.bukkitWorld = null;
        this.activeWorldFolder = null;
    }

    public boolean saveActiveToSource() {
        if (activeWorldFolder == null || !activeWorldFolder.exists()) {
            return false;
        }
        try {
            if (sourceWorldFolder.exists()) {
                FileUtils.deleteDirectory(sourceWorldFolder);
            }
            FileUtils.copyDirectory(activeWorldFolder, sourceWorldFolder);
            return true;
        } catch (IOException e) {
            GPTGOD.LOGGER.error("Saving active world back to source failed", e);
            return false;
        }
    }

    public boolean restoreFromSource() {
        unload();
        return load();
    }

    public boolean isLoaded() {
        return bukkitWorld != null;
    }

    public World getWorld() {
        return bukkitWorld;
    }

    public String getWorldName() {
        return worldName;
    }
}
