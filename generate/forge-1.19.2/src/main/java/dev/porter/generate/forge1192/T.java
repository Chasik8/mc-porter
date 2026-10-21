package dev.porter.generate.forge1192;

import com.squareup.javapoet.ClassName;

/** Minecraft 1.19.2 (official mappings) / Forge 43.x types used by the generated code. */
final class T {
    private T() {
    }

    static ClassName mc(String pkg, String name, String... nested) {
        return ClassName.get("net.minecraft." + pkg, name, nested);
    }

    static ClassName forge(String pkg, String name, String... nested) {
        return ClassName.get("net.minecraftforge." + pkg, name, nested);
    }

    // core
    static final ClassName RL = mc("resources", "ResourceLocation");
    static final ClassName COMPONENT = mc("network.chat", "Component");
    static final ClassName BLOCK_POS = mc("core", "BlockPos");
    static final ClassName DIRECTION = mc("core", "Direction");
    static final ClassName NON_NULL_LIST = mc("core", "NonNullList");
    static final ClassName FRIENDLY_BUF = mc("network", "FriendlyByteBuf");
    static final ClassName COMPOUND = mc("nbt", "CompoundTag");
    static final ClassName STRING_REPRESENTABLE = mc("util", "StringRepresentable");
    static final ClassName SOUND_EVENT = mc("sounds", "SoundEvent");
    static final ClassName PACKET = mc("network.protocol", "Packet");
    static final ClassName CLIENT_GAME_PACKET_LISTENER = mc("network.protocol.game", "ClientGamePacketListener");
    static final ClassName BE_DATA_PACKET = mc("network.protocol.game", "ClientboundBlockEntityDataPacket");

    // world
    static final ClassName BLOCK = mc("world.level.block", "Block");
    static final ClassName BLOCKS = mc("world.level.block", "Blocks");
    static final ClassName BLOCK_PROPERTIES = mc("world.level.block.state", "BlockBehaviour", "Properties");
    static final ClassName BLOCK_STATE = mc("world.level.block.state", "BlockState");
    static final ClassName STATE_DEFINITION_BUILDER = mc("world.level.block.state", "StateDefinition", "Builder");
    static final ClassName BOOLEAN_PROPERTY = mc("world.level.block.state.properties", "BooleanProperty");
    static final ClassName INTEGER_PROPERTY = mc("world.level.block.state.properties", "IntegerProperty");
    static final ClassName DIRECTION_PROPERTY = mc("world.level.block.state.properties", "DirectionProperty");
    static final ClassName ENUM_PROPERTY = mc("world.level.block.state.properties", "EnumProperty");
    static final ClassName MATERIAL = mc("world.level.material", "Material");
    static final ClassName SOUND_TYPE = mc("world.level.block", "SoundType");
    static final ClassName ENTITY_BLOCK = mc("world.level.block", "EntityBlock");
    static final ClassName LIQUID_BLOCK = mc("world.level.block", "LiquidBlock");
    static final ClassName BLOCK_ENTITY = mc("world.level.block.entity", "BlockEntity");
    static final ClassName BLOCK_ENTITY_TYPE = mc("world.level.block.entity", "BlockEntityType");
    static final ClassName BLOCK_ENTITY_TICKER = mc("world.level.block.entity", "BlockEntityTicker");
    static final ClassName LEVEL = mc("world.level", "Level");
    static final ClassName BLOCK_GETTER = mc("world.level", "BlockGetter");
    static final ClassName PLAYER = mc("world.entity.player", "Player");
    static final ClassName INVENTORY = mc("world.entity.player", "Inventory");
    static final ClassName SERVER_PLAYER = mc("server.level", "ServerPlayer");
    static final ClassName INTERACTION_HAND = mc("world", "InteractionHand");
    static final ClassName INTERACTION_RESULT = mc("world", "InteractionResult");
    static final ClassName BLOCK_HIT_RESULT = mc("world.phys", "BlockHitResult");
    static final ClassName CONTAINER = mc("world", "Container");
    static final ClassName CONTAINER_HELPER = mc("world", "ContainerHelper");
    static final ClassName SIMPLE_CONTAINER = mc("world", "SimpleContainer");
    static final ClassName MENU_PROVIDER = mc("world", "MenuProvider");
    static final ClassName ITEM = mc("world.item", "Item");
    static final ClassName ITEM_PROPERTIES = mc("world.item", "Item", "Properties");
    static final ClassName ITEM_STACK = mc("world.item", "ItemStack");
    static final ClassName BLOCK_ITEM = mc("world.item", "BlockItem");
    static final ClassName BUCKET_ITEM = mc("world.item", "BucketItem");
    static final ClassName ITEMS = mc("world.item", "Items");
    static final ClassName RARITY = mc("world.item", "Rarity");
    static final ClassName USE_ANIM = mc("world.item", "UseAnim");
    static final ClassName CREATIVE_TAB = mc("world.item", "CreativeModeTab");
    static final ClassName FOOD_PROPERTIES = mc("world.food", "FoodProperties");
    static final ClassName FOOD_BUILDER = mc("world.food", "FoodProperties", "Builder");
    static final ClassName MOB_EFFECT = mc("world.effect", "MobEffect");
    static final ClassName MOB_EFFECT_CATEGORY = mc("world.effect", "MobEffectCategory");
    static final ClassName MOB_EFFECT_INSTANCE = mc("world.effect", "MobEffectInstance");
    static final ClassName ATTRIBUTE_OPERATION = mc("world.entity.ai.attributes", "AttributeModifier", "Operation");
    static final ClassName ATTRIBUTES = mc("world.entity.ai.attributes", "Attributes");
    static final ClassName MENU = mc("world.inventory", "AbstractContainerMenu");
    static final ClassName MENU_TYPE = mc("world.inventory", "MenuType");
    static final ClassName SLOT = mc("world.inventory", "Slot");
    static final ClassName CONTAINER_DATA = mc("world.inventory", "ContainerData");
    static final ClassName SIMPLE_CONTAINER_DATA = mc("world.inventory", "SimpleContainerData");
    static final ClassName FLUID = mc("world.level.material", "Fluid");
    static final ClassName FLOWING_FLUID = mc("world.level.material", "FlowingFluid");
    static final ClassName TAG_KEY = mc("tags", "TagKey");
    static final ClassName REGISTRY = mc("core", "Registry");
    static final ClassName ITEM_TAGS = mc("tags", "ItemTags");

    // client
    static final ClassName SCREEN = mc("client.gui.screens.inventory", "AbstractContainerScreen");
    static final ClassName MENU_SCREENS = mc("client.gui.screens", "MenuScreens");
    static final ClassName BUTTON = mc("client.gui.components", "Button");
    static final ClassName POSE_STACK = ClassName.get("com.mojang.blaze3d.vertex", "PoseStack");
    static final ClassName RENDER_SYSTEM = ClassName.get("com.mojang.blaze3d.systems", "RenderSystem");
    static final ClassName GAME_RENDERER = mc("client.renderer", "GameRenderer");

    // forge
    static final ClassName MOD = forge("fml.common", "Mod");
    static final ClassName MOD_LOADING_CONTEXT = forge("fml.javafmlmod", "FMLJavaModLoadingContext");
    static final ClassName EVENT_BUS = forge("eventbus.api", "IEventBus");
    static final ClassName COMMON_SETUP = forge("fml.event.lifecycle", "FMLCommonSetupEvent");
    static final ClassName CLIENT_SETUP = forge("fml.event.lifecycle", "FMLClientSetupEvent");
    static final ClassName DIST = forge("api.distmarker", "Dist");
    static final ClassName DIST_EXECUTOR = forge("fml", "DistExecutor");
    static final ClassName FML_ENV = forge("fml.loading", "FMLEnvironment");
    static final ClassName DEFERRED_REGISTER = forge("registries", "DeferredRegister");
    static final ClassName REGISTRY_OBJECT = forge("registries", "RegistryObject");
    static final ClassName FORGE_REGISTRIES = forge("registries", "ForgeRegistries");
    static final ClassName FORGE_REGISTRY_KEYS = forge("registries", "ForgeRegistries", "Keys");
    static final ClassName FORGE_MENU_TYPE = forge("common.extensions", "IForgeMenuType");
    static final ClassName NETWORK_HOOKS = forge("network", "NetworkHooks");
    static final ClassName NETWORK_REGISTRY = forge("network", "NetworkRegistry");
    static final ClassName SIMPLE_CHANNEL = forge("network.simple", "SimpleChannel");
    static final ClassName NETWORK_DIRECTION = forge("network", "NetworkDirection");
    static final ClassName NETWORK_EVENT_CONTEXT = forge("network", "NetworkEvent", "Context");
    static final ClassName FLUID_TYPE = forge("fluids", "FluidType");
    static final ClassName FLUID_TYPE_PROPERTIES = forge("fluids", "FluidType", "Properties");
    static final ClassName FLUID_STACK = forge("fluids", "FluidStack");
    static final ClassName CLIENT_FLUID_EXT = forge("client.extensions.common", "IClientFluidTypeExtensions");
    static final ClassName FORGE_FLOWING_FLUID = forge("fluids", "ForgeFlowingFluid");
    static final ClassName FORGE_FLOWING_PROPERTIES = forge("fluids", "ForgeFlowingFluid", "Properties");
    static final ClassName FORGE_FLOWING_SOURCE = forge("fluids", "ForgeFlowingFluid", "Source");
    static final ClassName FORGE_FLOWING_FLOWING = forge("fluids", "ForgeFlowingFluid", "Flowing");
}
