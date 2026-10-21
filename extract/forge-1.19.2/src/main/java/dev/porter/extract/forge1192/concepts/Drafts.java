package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** Mutable node drafts so that generic extractors and pattern packs can fill fields before the node is frozen. */
public final class Drafts {
    private Drafts() {
    }

    public static final class Block {
        public String id;
        public String translationKey;
        public String logicClass;
        public String material;
        public Double hardness;
        public Double resistance;
        public Integer lightLevel;
        public String soundType;
        public Boolean requiresTool;
        public IrNode.Block.Harvest harvest;
        public String creativeTab;
        public List<IrNode.Block.StateProperty> stateProperties;
        public Map<String, String> textures;
        public IrNode.Block.Model model;
        public String blockEntity;
        public String item;
        public String lootTable;
        public Provenance provenance;
        public Double confidence;
        public final Map<String, JsonNode> extensions = new TreeMap<>();

        public IrNode.Block build() {
            Legacy.put(extensions, id);
            return new IrNode.Block(id, translationKey, logicClass, material, hardness, resistance, lightLevel, soundType,
                    requiresTool, harvest, creativeTab, stateProperties, textures, model, blockEntity, item, lootTable,
                    provenance, confidence, extensions.isEmpty() ? null : extensions);
        }
    }

    public static final class Item {
        public String id;
        public String translationKey;
        public String logicClass;
        public Integer maxStackSize;
        public Integer maxDamage;
        public String rarity;
        public String creativeTab;
        public String placesBlock;
        public String containerItem;
        public Map<String, String> textures;
        public IrNode.Item.Food food;
        public Provenance provenance;
        public Double confidence;
        public final Map<String, JsonNode> extensions = new TreeMap<>();

        public IrNode.Item build() {
            Legacy.put(extensions, id);
            return new IrNode.Item(id, translationKey, logicClass, maxStackSize, maxDamage, rarity, creativeTab, placesBlock,
                    containerItem, textures, food, provenance, confidence, extensions.isEmpty() ? null : extensions);
        }
    }
}
