package dev.porter.port.upfront;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The 1.7.10 sound-name table for {@code SoundEvents}: the 1.19.2 sound ids ({@code entity.zombie.hurt}) of the
 * flattening table become the 1.7.10 sound names ({@code mob.zombie.hurt}, a string, as the hand rules already do) when a
 * name of the same shape exists in the user's own 1.7.10 {@code sounds.json}. The 1.7.10 name set is read from that file
 * (its keys); nothing is shipped. A 1.19.2 id gets a target only if a candidate is in the set, never a guess.
 */
public final class SoundNames {
    private SoundNames() {
    }

    /** The sound names (keys) of a 1.7.10 {@code sounds.json}. */
    public static Set<String> load(Path soundsJson) throws IOException {
        JsonNode root = Json.json().readTree(Files.readString(soundsJson, StandardCharsets.UTF_8));
        Set<String> out = new TreeSet<>();
        root.properties().forEach(e -> out.add(e.getKey()));
        return out;
    }

    /** Event words of 1.19.2 and what 1.7.10 called them. */
    private static final Map<String, List<String>> EVENT = Map.ofEntries(
            Map.entry("ambient", List.of("say", "idle", "breathe")), Map.entry("hurt", List.of("hurt", "hit")),
            Map.entry("death", List.of("death")), Map.entry("step", List.of("step", "walk")), Map.entry("hit", List.of("hit", "hurt")),
            Map.entry("attack", List.of("attack", "hit")), Map.entry("shoot", List.of("bow", "shoot")),
            Map.entry("fly", List.of("fly", "wings")), Map.entry("angry", List.of("angry", "hit")));
    /** Block material words of 1.19.2 and the 1.7.10 dig/step families. */
    private static final Map<String, String> MATERIAL = Map.of("wool", "cloth", "gravel", "gravel", "grass", "grass", "stone", "stone",
            "wood", "wood", "sand", "sand", "snow", "snow", "glass", "glass", "ladder", "ladder");
    /** Whole-id aliases (1.19.2 id to the 1.7.10 name); vocabulary only, checked against the file. */
    private static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("entity.experience_orb.pickup", "random.orb"), Map.entry("entity.player.levelup", "random.levelup"),
            Map.entry("entity.item.pickup", "random.pop"), Map.entry("entity.generic.explode", "random.explode"),
            Map.entry("ui.button.click", "random.click"), Map.entry("entity.generic.eat", "random.eat"),
            Map.entry("entity.generic.drink", "random.drink"), Map.entry("entity.generic.burn", "random.fizz"),
            Map.entry("entity.arrow.hit_player", "random.successful_hit"), Map.entry("entity.arrow.shoot", "random.bow"),
            Map.entry("block.fire.ambient", "fire.fire"), Map.entry("block.lava.ambient", "liquid.lava"),
            Map.entry("block.water.ambient", "liquid.water"), Map.entry("block.lava.pop", "liquid.lavapop"),
            Map.entry("block.chest.open", "random.chestopen"), Map.entry("block.chest.close", "random.chestclosed"),
            Map.entry("block.anvil.land", "random.anvil_land"), Map.entry("block.anvil.use", "random.anvil_use"),
            Map.entry("block.anvil.break", "random.anvil_break"), Map.entry("block.lever.click", "random.click"),
            Map.entry("block.stone_button.click_on", "random.click"), Map.entry("block.wooden_door.open", "random.door_open"),
            Map.entry("block.wooden_door.close", "random.door_close"), Map.entry("block.note_block.harp", "note.harp"),
            Map.entry("block.note_block.bass", "note.bassattack"), Map.entry("block.note_block.hat", "note.hat"),
            Map.entry("block.note_block.snare", "note.snare"), Map.entry("block.note_block.basedrum", "note.bd"),
            Map.entry("block.portal.ambient", "portal.portal"), Map.entry("block.portal.trigger", "portal.trigger"),
            Map.entry("block.portal.travel", "portal.travel"), Map.entry("entity.lightning_bolt.thunder", "ambient.weather.thunder"),
            Map.entry("entity.lightning_bolt.impact", "random.explode"), Map.entry("entity.firework_rocket.launch", "fireworks.launch"),
            Map.entry("entity.firework_rocket.blast", "fireworks.blast"), Map.entry("entity.tnt.primed", "game.tnt.primed"),
            Map.entry("entity.splash_potion.break", "game.potion.smash"), Map.entry("entity.egg.throw", "random.bow"),
            Map.entry("entity.snowball.throw", "random.bow"), Map.entry("entity.ender_pearl.throw", "random.bow"),
            Map.entry("entity.zombie.ambient", "mob.zombie.say"), Map.entry("entity.zombie.hurt", "mob.zombie.hurt"),
            Map.entry("entity.zombie.death", "mob.zombie.death"), Map.entry("entity.zombie.step", "mob.zombie.step"));

    /** Candidate 1.7.10 names for a 1.19.2 sound id, best first. */
    static List<String> candidates(String id) {
        Set<String> c = new LinkedHashSet<>();
        String a = ALIAS.get(id);
        if (a != null) {
            c.add(a);
        }
        String[] p = id.split("\\.");
        if (p.length == 3 && p[0].equals("entity")) {
            String mob = p[1];
            for (String mobName : List.of(mob, mob.replace("_", ""), mob + "s", mob.replace("_", "") + "s")) {
                for (String ev : EVENT.getOrDefault(p[2], List.of(p[2]))) {
                    c.add("mob." + mobName + "." + ev);
                }
                c.add("mob." + mobName + "." + p[2]);
            }
        } else if (p.length == 3 && p[0].equals("block")) {
            String mat = MATERIAL.getOrDefault(p[1], p[1]);
            switch (p[2]) {
                case "break", "place" -> c.add("dig." + mat);
                case "step", "hit", "fall" -> c.add("step." + mat);
                default -> {
                }
            }
            c.add(p[1] + "." + p[2]);
        }
        c.add(id);
        return new ArrayList<>(c);
    }

    /** The 1.7.10 name of a 1.19.2 sound id, or null. */
    public static String find(String id, Set<String> names) {
        for (String cand : candidates(id.toLowerCase(Locale.ROOT))) {
            if (names.contains(cand)) {
                return cand;
            }
        }
        return null;
    }

    /** Fills the TODO SoundEvents rows that have a 1.7.10 name; returns the rows and sets {@code found[0]}. */
    public static List<Flattening.Row> apply(List<Flattening.Row> rows, Set<String> names, int[] found) {
        List<Flattening.Row> out = new ArrayList<>(rows.size());
        for (Flattening.Row r : rows) {
            boolean sound = r.registry().equals("SoundEvents") && r.kind().equals("TODO");
            String n = sound ? find(r.id(), names) : null;
            if (sound && n == null) {
                out.add(new Flattening.Row(r.registry(), r.id(), r.field(), "ABSENT", "", "", 0, "no 1.7.10 sound name for " + r.id()));
            } else if (n == null) {
                out.add(r);
            } else {
                found[0]++;
                out.add(new Flattening.Row(r.registry(), r.id(), r.field(), "SOUND", "\"" + n + "\"", "", 0.6, "sound-name"));
            }
        }
        return out;
    }
}
