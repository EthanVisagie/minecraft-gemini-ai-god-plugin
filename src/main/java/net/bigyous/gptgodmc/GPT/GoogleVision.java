package net.bigyous.gptgodmc.GPT;

import java.util.Arrays;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.bigyous.gptgodmc.EventLogger;
import net.bigyous.gptgodmc.GPTGOD;
import net.bigyous.gptgodmc.StructureManager;
import net.bigyous.gptgodmc.GPT.Json.FunctionDeclaration;
import net.bigyous.gptgodmc.GPT.Json.Schema;
import net.bigyous.gptgodmc.GPT.Json.Tool;
import net.bigyous.gptgodmc.enums.GptGameMode;
import net.bigyous.gptgodmc.interfaces.SimpFunction;
import net.bigyous.gptgodmc.loggables.CameraEventLoggable;
import net.bigyous.gptgodmc.loggables.Loggable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

// Let the AI god see the structures we build,
// so that when we build a phalic monument they are wise to it,
// and we may be smited >:D
public class GoogleVision {
    private static Gson gson = new Gson();

    // the parameters that the vision model returns
    class DescribeStructureParams {
        protected String originalName;
        private String newName;
        private String description;
        private String photographer;
        private boolean isItUgly;
        // just in case the ai decides to be dumb af
        private boolean is_it_ugly;

        public String getOriginalName() {
            return originalName;
        }

        public String getNewName() {
            return newName;
        }

        public String getDescription() {
            return description;
        }

        public String getPhotographer() {
            return photographer;
        }

        public boolean getIsItUgly() {
            return isItUgly || is_it_ugly;
        }
    }

    class CritiquePhotoParams {
        String subject;
        String photographer;
        String description;
        boolean isItUgly;
        boolean is_it_ugly;

        public boolean getIsUgly() {
            return isItUgly || is_it_ugly;
        }
    }

    // // for the task queue
    // class DescribeStructureRequest {
    // // the name that the structure currently has (the ai might choose to rename
    // it)
    // String currentStructureName;
    // }

    // this would run sequentially 🤔
    // not sure if I should prefer this approach (for rate limit purposes)
    // or the async callback approach so that multiple requests may run
    // private static TaskQueue<DescribeStructureRequest> describeStructureQueue =
    // new TaskQueue<>((DescribeStructureRequest buffer) -> {});

    // handles the results when the configured vision provider finishes processing
    private static SimpFunction<JsonObject> describeStructure = (JsonObject args) -> {
        GPTGOD.LOGGER.info("received vision response: " + args.toString());
        DescribeStructureParams params = gson.fromJson(args, DescribeStructureParams.class);

        if (params == null) {
            GPTGOD.LOGGER.error("describeStructure parameters is null");
            return;
        }

        if (params.originalName == null || params.originalName.length() < 1 || params.newName == null
                || params.newName.length() < 1 || params.description == null || params.description.length() < 1) {
            GPTGOD.LOGGER.error("describeStructure response had invalid args " + args.toString());
            return;
        }

        StructureManager.updateStructureDetails(params.originalName, params.newName, params.description,
                params.getIsItUgly());
        EventLogger.addLoggable(new CameraEventLoggable(params.getNewName(), params.getDescription(),
                params.getIsItUgly(), params.getPhotographer()));
    };

    private static SimpFunction<JsonObject> critiquePhoto = (JsonObject args) -> {
        GPTGOD.LOGGER.info("received vision response: " + args.toString());
        CritiquePhotoParams params = gson.fromJson(args, CritiquePhotoParams.class);

        if(params == null) {
            GPTGOD.LOGGER.error("describeStructure parameters is null");
            return;
        }

        if (params.subject == null || params.subject.length() < 1 || params.photographer == null
                || params.photographer.length() < 1 || params.description == null || params.description.length() < 1) {
            GPTGOD.LOGGER.error("critiquePhoto response had invalid args " + args.toString());
            return;
        }

        Loggable logItem = new CameraEventLoggable(params.subject, params.description, params.getIsUgly(), params.photographer);

        // tell gpt about
        EventLogger.addLoggable(logItem);

        // tell server what god thinks of this offering
        GPTGOD.SERVER.broadcast(Component.text(logItem.getLog()).decorate(TextDecoration.BOLD));
    };

    private static Map<String, FunctionDeclaration> functionMap = Map.of("describeStructure", new FunctionDeclaration(
            "describeStructure", "input the description and opinions of the structure pictured in the received images",
            new Schema(Map.of("originalName", new Schema(Schema.Type.STRING,
                    "the current name that this structure is called based on the the users prompt before we rename it."),
                    "newName",
                    new Schema(Schema.Type.STRING,
                            "the new name for the structure based on what it looks like. Such as: STONE_CHURCH or SAND_PILLAR_3"),
                    "photographer",
                    new Schema(Schema.Type.STRING,
                            "the name of whoever took the photo (either God, or one of the players)"),
                    "description",
                    new Schema(Schema.Type.STRING,
                            "description of what the structure and aesthetics are like, including key points you like and dislike about the design."),
                    "isItUgly",
                    new Schema(Schema.Type.BOOLEAN,
                            "The executive decision on wether or not this structure is ugly or not in it's current state. Return a value of true to indicate that you think it is ugly, or false if it is pretty."))),
            describeStructure), "critiquePhoto",
            new FunctionDeclaration("critiquePhoto",
                    "input the description and opinions of the structure pictured in the received images",
                    new Schema(Map.of("subject",
                            new Schema(Schema.Type.STRING, "A name for what the subject of the photo is."),
                            "photographer",
                            new Schema(Schema.Type.STRING,
                                    "the name of whoever took the photo (either God, or one of the players)"),
                            "description",
                            new Schema(Schema.Type.STRING,
                                    "description of what the structure and aesthetics are like, including key points you like and dislike about the design."),
                            "isItUgly",
                            new Schema(Schema.Type.BOOLEAN,
                                    "The executive decision on wether or not this structure is ugly or not in it's current state. Return a value of true to indicate that you think it is ugly, or false if it is pretty."))),
                    critiquePhoto));
    private static Tool tools = GptActions.wrapFunctions(functionMap);
    private static ModelProvider provider = GPTModels.getVisionProvider();
    private static GptAPI gpt = new GptAPI(GPTModels.getVisionModel(provider), provider, tools).setSystemContext("""
            You are a helpful assistant that will generate opinions and descriptions about minecraft structures.
            Using the provided renderings of Minecraft structures,
            please answer the below questions succinctly excluding
            extraneous detail about thegame itself for each set of images you receive:
            What does the main subject of the photo look like? (i.e. what will you make it's new name?).
            Describe what the structure and aesthetics are like.
            Do you believe the subject of this photo to be subjectively ugly or pretty,
            based solely on its construction, without regard to the photo quality itself?
            You MUST choose one or the other and explain. Do not base this decision on chance.
            Only use a tool call in one json response, other responses will be ignored.
            """).setTools(tools).setToolChoice("describeStructure");

    public static boolean requiresGeminiUpload() {
        return provider == ModelProvider.GEMINI;
    }

    // have the ai model rate a structure with multiple different camera angles
    // available to it
    public static void lookAtPhoto(String photographer, String photoSubject, GoogleFile[] imageFiles,
            boolean critiqueOnlyMode) {
        GPTGOD.LOGGER.info("generating vision request for: " + photoSubject);
        String allStructures = StructureManager.getDisplayString();

        String teams = String.join(",", GPTGOD.SCOREBOARD.getTeams().stream().map(team -> {
            return team.getName();
        }).toList());
        if (GPTGOD.gameMode.equals(GptGameMode.DEATHMATCH)) {
            gpt.addContext(String.format("Teams: %s", teams), "teams");
        }

        String imageSlashS = imageFiles.length > 1 ? "these images" : "this image";

        // select either the photo ciritique only mode or the update structure details
        // mode
        if (critiqueOnlyMode) {
            gpt.setToolChoice("critiquePhoto");
        } else {
            gpt.setToolChoice("describeStructure");
        }

        gpt.addContext(
                String.format("Current Players: %s",
                        Arrays.toString(
                                GPTGOD.SERVER.getOnlinePlayers().stream().map(player -> player.getName()).toArray())),
                "PlayerNames").addContext(String.format("All Structures: %s", allStructures), "structures")
                .addFilesWithContext(
                        String.format("describe and critique photo of %s depictied in %s photographed by %s.",
                                photoSubject, imageSlashS, photographer),
                        imageFiles)
                .send(functionMap);
    }

    public static void lookAtPhoto(String photographer, String photoSubject, GoogleFile imageFile) {
        lookAtPhoto(photographer, photoSubject, new GoogleFile[] { imageFile }, true);
    }

    public static void lookAtStructure(String photographer, String structureName, GoogleFile[] imageFiles) {
        GPTGOD.LOGGER.info("generating vision request for structure: " + structureName);
        // send a vision request in update structure mode
        lookAtPhoto(photographer, String.format("the minecraft structure currently named %s", structureName),
                imageFiles, false);
    }

    // send off an image of a structure to be rated by the secondary ai model
    public static void lookAtStructure(String photographer, String structureName, GoogleFile imageFile) {
        lookAtStructure(photographer, structureName, new GoogleFile[] { imageFile });
    }
}
