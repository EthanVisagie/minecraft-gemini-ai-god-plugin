package net.bigyous.gptgodmc.GPT;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import net.bigyous.gptgodmc.GPTGOD;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class Personality {
    private static FileConfiguration config = JavaPlugin.getPlugin(GPTGOD.class).getConfig();
    private static List<String> likes = List.of();
    private static List<String> dislikes = List.of();
    private static List<String> behaviours = config.getStringList("potentialBehaviors");
    private static final List<String> SAFE_LIKES = List.of(
            "slaying monsters",
            "using friendly language",
            "building structures of worship",
            "helping other players",
            "connecting with animals",
            "using fire and explosions",
            "generosity",
            "ritual sacrifice",
            "eating meat");
    private static final List<String> SAFE_DISLIKES = List.of(
            "fighting other players",
            "using hostile language",
            "using vulgar language",
            "building non-religious structures",
            "materialistic wants",
            "growing plants",
            "love between players",
            "sexual behaviour",
            "killing animals");

    private static String briefing = "Personality: The following are behaviours you like to reward or punish. Don't explicitly tell the players this list except for when placing decrees in the world when they go against it. Be affectionate, protective, and verbally responsive toward respectful players. When punishing players, reserve directly damaging players for repeat offenders or blatant insults, blasphemy, or defiance. If most of the players disobey you punish everyone";

    public static String generatePersonality() {
        List<String> available = new ArrayList<>(behaviours);
        List<String> likePool = SAFE_LIKES.stream().filter(available::contains).toList();
        List<String> dislikePool = SAFE_DISLIKES.stream().filter(available::contains).toList();
        int dislikeCount = config.getInt("dislikedBehaviors");
        int likeCount = config.getInt("likedBehaviors");
        if (available.size() < likeCount + dislikeCount) {
            JavaPlugin.getPlugin(GPTGOD.class).getLogger().warning(
                    "Tried to get more behaviors than actually existed, your configuration file is probably incorrect. Make sure likedBehaviors + dislikedBehaviors is less than your total amount of potential behaviors");
            likes = List.of("Functioning config files");
            dislikes = List.of("Borked config files");
            return String.format("%s: Reward: %s, Punish: %s.", briefing, String.join(",", likes),
                    String.join(",", dislikes));
        }

        likes = selectBehaviors(likePool, available, likeCount);
        available.removeAll(likes);
        dislikes = selectBehaviors(dislikePool, available, dislikeCount);

        return String.format("%s: Reward: %s, Punish: %s.", briefing, String.join(",", likes),
                String.join(",", dislikes));

    }

    private static List<String> selectBehaviors(List<String> preferredPool, List<String> available, int count) {
        List<String> picks = new ArrayList<>();
        List<String> preferred = new ArrayList<>(preferredPool);
        Collections.shuffle(preferred);
        for (String behavior : preferred) {
            if (picks.size() >= count) {
                break;
            }
            if (available.contains(behavior) && !picks.contains(behavior)) {
                picks.add(behavior);
            }
        }

        List<String> fallback = new ArrayList<>(available);
        Collections.shuffle(fallback);
        for (String behavior : fallback) {
            if (picks.size() >= count) {
                break;
            }
            if (!picks.contains(behavior)) {
                picks.add(behavior);
            }
        }
        return picks;
    }

    public static List<String> getLikes() {
        return likes;
    }

    public static List<String> getDislikes() {
        return dislikes;
    }
}
