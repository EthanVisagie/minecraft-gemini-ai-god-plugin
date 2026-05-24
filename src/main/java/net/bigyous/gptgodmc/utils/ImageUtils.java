package net.bigyous.gptgodmc.utils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import dev.jensderuiter.minecraft_imagery.image.ImageCapture;
import dev.jensderuiter.minecraft_imagery.image.ImageCaptureOptions;
import dev.jensderuiter.minecraft_imagery.image.ImageCaptureOptions.ImageCaptureOptionsBuilder;

import com.loohp.imageframe.ImageFrame;
import com.loohp.imageframe.objectholders.DitheringType;
import com.loohp.imageframe.objectholders.ImageMap;

import net.bigyous.gptgodmc.GPTGOD;
import net.bigyous.gptgodmc.Structure;
import net.bigyous.gptgodmc.StructureManager;
import net.bigyous.gptgodmc.GPT.GoogleFile;
import net.bigyous.gptgodmc.GPT.GoogleVision;
import net.bigyous.gptgodmc.image_maps.ImageBufferMap;
import net.bigyous.gptgodmc.interfaces.SimpFunction;

public class ImageUtils {

    // reference to image frame api
    public static final ImageFrame IMAGE_FRAME_PLUGIN = JavaPlugin.getPlugin(ImageFrame.class);

    private static float fov = 1.0f;

    // todo map this per image title
    // and check what the highest index is for that title on the disk already
    private static int fileIdx = 0;

    private static GPTGOD gptGodPlugin = JavaPlugin.getPlugin(GPTGOD.class);

    private static FileConfiguration config = gptGodPlugin.getConfig();

    // flag to turn on image saving to see what kind of output the camera and the ai sees
    private static boolean enableDebugImageSaving = config.getBoolean("enable-debug-image");

    public static Path IMAGE_DATA = gptGodPlugin.getDataFolder().toPath().resolve("ai_image_data");

    public static Path getImageFile(String title, int fileNumber) {

        return IMAGE_DATA.resolve(String.format("%s/%d.png", title, fileNumber));
    }

    public static OutputStream getImageOutputStream(String title, int fileNumber) {
        Path imageFile = getImageFile(title, fileNumber);
        try {
            Files.createDirectories(imageFile.getParent());
            return Files.newOutputStream(imageFile);
        } catch (IOException e) {
            GPTGOD.LOGGER.warn(String.format("An IO Exception occured getting output stream for: %s", title));
            return null;
        }
    }

    // try to give the player a copy of the image on a map
    public static void givePhoto(BufferedImage imageBytes, Player recipient, String builderName, String photographer,
            String subjectName) {
        String pictureName = String.format("PHOTO_OF_%s_%s_BY_%s", builderName, subjectName, photographer);

        try {
            ImageMap map = ImageBufferMap.create(ImageFrame.imageMapManager, pictureName, imageBytes, 1, 1,
                    DitheringType.FLOYD_STEINBERG, recipient.getUniqueId()).get();
            ImageFrame.imageMapManager.addMap(map);
            map.giveMaps(recipient, ImageFrame.mapItemFormat);
        } catch (Exception e) {
            GPTGOD.LOGGER.error("failed to create map for rendered picture", e);
        }
    }

    static class PictureCallbackData {
        BufferedImage imageBytes;
        GoogleFile uploadedFile;

        PictureCallbackData(BufferedImage imageBytes, GoogleFile uploadedFile) {
            this.imageBytes = imageBytes;
            this.uploadedFile = uploadedFile;
        }
    }

    // takes a picture from the given camera location
    public static void takePicture(Location cameraLocation, String pictureName, boolean dayLightCycleAware,
            List<Player> worldPlayers, SimpFunction<PictureCallbackData> resultCallback) {
        ImageCaptureOptionsBuilder builder = ImageCaptureOptions.builder().fov(fov)
                .dayLightCycleAware(dayLightCycleAware).showDepth(true);

        ImageCapture capture;
        if (worldPlayers == null) {
            capture = new ImageCapture(cameraLocation, builder.build());
        } else {
            capture = new ImageCapture(cameraLocation, worldPlayers, builder.build());
        }

        // capture asynchronously as it may run for a while
        new BukkitRunnable() {
            @Override
            public void run() {
                BufferedImage img = capture.render();
                // get bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    ImageIO.write(img, "png", baos);
                    // output to file for testing
                    if(enableDebugImageSaving) ImageIO.write(img, "png", getImageOutputStream(pictureName, fileIdx++));
                } catch (IOException e) {
                    GPTGOD.LOGGER.error("failed to output image bytes on capture", e);
                    return;
                }
                // raw png image bytes
                byte[] bytes = baos.toByteArray();
                GoogleFile upload = new GoogleFile(bytes, "image/png", pictureName);
                if (!GoogleVision.requiresGeminiUpload() || upload.tryUpload()) {
                    resultCallback.run(new PictureCallbackData(img, upload));
                } else {
                    GPTGOD.LOGGER.warn("failed to upload picture of " + pictureName);
                }
            }
        }.runTaskAsynchronously(JavaPlugin.getPlugin(GPTGOD.class));
    }

    public static void takePicture(Location cameraLocation, String pictureName, boolean dayLightCycleAware,
            SimpFunction<PictureCallbackData> resultCallback) {
        takePicture(cameraLocation, pictureName, dayLightCycleAware, null, resultCallback);
    }

    // same as takePicture(Location) but with a default picture name
    public static void takePicture(Location location, List<Player> worldPlayers,
            SimpFunction<PictureCallbackData> resultCallback) {
        takePicture(location, "MINECRAFT_PICTURE", true, worldPlayers, resultCallback);
    }

    // same as takePicture(Location) but with a default picture name
    public static void takePicture(Location location, SimpFunction<PictureCallbackData> resultCallback) {
        takePicture(location, "MINECRAFT_PICTURE", true, resultCallback);
    }

    // takes a picture through the eyes of the provided player
    public static void takePicture(Player player) {
        // get players to include in the render
        List<Player> worldPlayers = player.getWorld().getPlayers();
        // make sure to remove the player that's taking the picture
        // when you take a picture from the player's perspective
        worldPlayers.remove(player);

        takePicture(player.getEyeLocation(), worldPlayers, (PictureCallbackData result) -> {

            // try and get some info about the photo

            // gets either the ray hit or timeout position
            Vector hitpos = player.rayTraceBlocks(256).getHitPosition();
            Location pictureCenter = new Location(player.getWorld(), hitpos.getX(), hitpos.getY(), hitpos.getZ());
            Structure closestStructure = StructureManager.getClosestStructureToLocation(pictureCenter);

            // try to give the player a copy of the photo
            String subjectName = closestStructure == null ? "SCENERY" : closestStructure.getName();
            String creatorName = closestStructure == null ? "UNKNOWN" : closestStructure.getBuilder().getName();
            givePhoto(result.imageBytes, player, creatorName, player.getName(), subjectName);

            // call the configured vision provider with our user generated photography
            GoogleVision.lookAtPhoto(player.getName(),
                    StructureManager.getStructureDescription(closestStructure, pictureCenter), result.uploadedFile);
        });
    }

    // takes a picture of the given structure
    public static void takePicture(Structure structure, String structureName, Vector cameraAngle) {
        Location structureCenter = structure.getLocation();
        double cameraDistance = calculateCameraDistance(structure);
        Location cameraLocation = lookAt(structureCenter, cameraAngle, cameraDistance);
        takePicture(cameraLocation, structureName, false, (PictureCallbackData result) -> {
            GoogleVision.lookAtStructure("God", structureName, result.uploadedFile);
        });
    }

    // takes a picture of the given structure at a default camera angle
    public static void takePicture(Structure structure, String structureName) {
        takePicture(structure, structureName, new Vector(1.0, 1.0, 1.0).normalize());
    }

    // calculates how far the camera has to be from a structure at a specified fov
    // for all of it to fit in the view
    public static double calculateCameraDistance(Structure structure) {
        // Location structureCenter = structure.getLocation();
        Location[] bounds = structure.getBounds();

        // the corner of lowest x, y, and z
        Location bottomBounds = bounds[0];
        // the corner of highest x, y, and z
        Location topBounds = bounds[1];

        double width = Math.abs(topBounds.getX() - bottomBounds.getX());
        double height = Math.abs(topBounds.getY() - bottomBounds.getY());
        double depth = Math.abs(topBounds.getZ() - bottomBounds.getZ());

        // get the biggest width on any axis
        double maxDimension = Math.max(width, Math.max(height, depth));
        return (maxDimension / 2) / Math.tan(fov / 2);
    }

    // takes in the location for the camera to look at
    // a normalized directional vector for the axis to move the camera along
    // compared to the target
    // and how far (magnitude) the camera should move along this axis from the look
    // target
    // returns the computed Location for our camera with the included look direction
    public static Location lookAt(Location target, Vector cameraDirection, double cameraDistance) {
        // translate along axis of cameraDirection (normal vec)
        // force the cameraDirection normal vector into normal length
        cameraDirection.normalize();

        double dx = cameraDirection.getX() * cameraDistance;
        double dy = cameraDirection.getY() * cameraDistance;
        double dz = cameraDirection.getZ() * cameraDistance;
        double cameraX = target.getX() + dx;
        double cameraY = target.getY() + dy;
        double cameraZ = target.getZ() + dz;

        // get camera angle pointing at target

        // Calculate the direction vector from camera to target (reverse the camera move
        // direction)
        double reverseX = target.getX() - cameraX;
        double reverseZ = target.getZ() - cameraZ;

        // Calculate yaw
        // float yaw = (float) Math.toDegrees(Math.atan2(-dx, -dz));
        float yaw = (float) Math.toDegrees(Math.atan2(reverseZ, reverseX)) - 90; // adjust by 90 degrees for orientation
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) Math.toDegrees(Math.atan2(dy, horizontalDistance));

        return new Location(target.getWorld(), cameraX, cameraY, cameraZ, yaw, pitch);
    }
}
