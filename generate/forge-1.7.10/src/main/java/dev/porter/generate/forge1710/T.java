package dev.porter.generate.forge1710;

import com.squareup.javapoet.ClassName;

/**
 * Minecraft 1.7.10 / Forge 10.13.4.1614 types used by the generated code (MCP stable_12 names). Every name here was
 * checked against the RFG-decompiled sources of the target (net/minecraft, cpw/mods/fml, net/minecraftforge).
 */
final class T {
    private T() {
    }

    // net.minecraft
    static final ClassName BLOCK = ClassName.get("net.minecraft.block", "Block");
    static final ClassName SOUND_TYPE = ClassName.get("net.minecraft.block", "Block", "SoundType");
    static final ClassName MATERIAL = ClassName.get("net.minecraft.block.material", "Material");
    static final ClassName ICON = ClassName.get("net.minecraft.util", "IIcon");
    static final ClassName ICON_REGISTER = ClassName.get("net.minecraft.client.renderer.texture", "IIconRegister");
    static final ClassName BLOCK_ACCESS = ClassName.get("net.minecraft.world", "IBlockAccess");
    static final ClassName WORLD = ClassName.get("net.minecraft.world", "World");
    static final ClassName TILE = ClassName.get("net.minecraft.tileentity", "TileEntity");
    static final ClassName PLAYER = ClassName.get("net.minecraft.entity.player", "EntityPlayer");
    static final ClassName PLAYER_INVENTORY = ClassName.get("net.minecraft.entity.player", "InventoryPlayer");
    static final ClassName LIVING = ClassName.get("net.minecraft.entity", "EntityLivingBase");
    static final ClassName ITEM_STACK = ClassName.get("net.minecraft.item", "ItemStack");
    static final ClassName ITEM = ClassName.get("net.minecraft.item", "Item");
    static final ClassName ITEM_BLOCK = ClassName.get("net.minecraft.item", "ItemBlock");
    static final ClassName ITEM_FOOD = ClassName.get("net.minecraft.item", "ItemFood");
    static final ClassName ITEM_BUCKET = ClassName.get("net.minecraft.item", "ItemBucket");
    static final ClassName ENUM_RARITY = ClassName.get("net.minecraft.item", "EnumRarity");
    static final ClassName ENUM_ACTION = ClassName.get("net.minecraft.item", "EnumAction");
    static final ClassName CREATIVE_TABS = ClassName.get("net.minecraft.creativetab", "CreativeTabs");
    static final ClassName ITEMS = ClassName.get("net.minecraft.init", "Items");
    static final ClassName NBT = ClassName.get("net.minecraft.nbt", "NBTTagCompound");
    static final ClassName NBT_LIST = ClassName.get("net.minecraft.nbt", "NBTTagList");
    static final ClassName INVENTORY = ClassName.get("net.minecraft.inventory", "IInventory");
    static final ClassName INVENTORY_BASIC = ClassName.get("net.minecraft.inventory", "InventoryBasic");
    static final ClassName CONTAINER = ClassName.get("net.minecraft.inventory", "Container");
    static final ClassName SLOT = ClassName.get("net.minecraft.inventory", "Slot");
    static final ClassName CRAFTING = ClassName.get("net.minecraft.inventory", "ICrafting");
    static final ClassName GUI_CONTAINER = ClassName.get("net.minecraft.client.gui.inventory", "GuiContainer");
    static final ClassName GUI = ClassName.get("net.minecraft.client.gui", "Gui");
    static final ClassName GUI_BUTTON = ClassName.get("net.minecraft.client.gui", "GuiButton");
    static final ClassName RESOURCE_LOCATION = ClassName.get("net.minecraft.util", "ResourceLocation");
    static final ClassName STAT_COLLECTOR = ClassName.get("net.minecraft.util", "StatCollector");
    static final ClassName MATH_HELPER = ClassName.get("net.minecraft.util", "MathHelper");
    static final ClassName PACKET = ClassName.get("net.minecraft.network", "Packet");
    static final ClassName NETWORK_MANAGER = ClassName.get("net.minecraft.network", "NetworkManager");
    static final ClassName S35 = ClassName.get("net.minecraft.network.play.server", "S35PacketUpdateTileEntity");
    static final ClassName POTION = ClassName.get("net.minecraft.potion", "Potion");
    static final ClassName POTION_EFFECT = ClassName.get("net.minecraft.potion", "PotionEffect");
    static final ClassName MONSTER_ATTRIBUTES = ClassName.get("net.minecraft.entity", "SharedMonsterAttributes");
    static final ClassName GL11 = ClassName.get("org.lwjgl.opengl", "GL11");

    // FML
    static final ClassName MOD = ClassName.get("cpw.mods.fml.common", "Mod");
    static final ClassName MOD_EVENT_HANDLER = ClassName.get("cpw.mods.fml.common", "Mod", "EventHandler");
    static final ClassName MOD_INSTANCE = ClassName.get("cpw.mods.fml.common", "Mod", "Instance");
    static final ClassName PRE_INIT = ClassName.get("cpw.mods.fml.common.event", "FMLPreInitializationEvent");
    static final ClassName INIT = ClassName.get("cpw.mods.fml.common.event", "FMLInitializationEvent");
    static final ClassName POST_INIT = ClassName.get("cpw.mods.fml.common.event", "FMLPostInitializationEvent");
    static final ClassName GAME_REGISTRY = ClassName.get("cpw.mods.fml.common.registry", "GameRegistry");
    static final ClassName GUI_HANDLER = ClassName.get("cpw.mods.fml.common.network", "IGuiHandler");
    static final ClassName NETWORK_REGISTRY = ClassName.get("cpw.mods.fml.common.network", "NetworkRegistry");
    static final ClassName BYTE_BUF_UTILS = ClassName.get("cpw.mods.fml.common.network", "ByteBufUtils");
    static final ClassName SIMPLE_WRAPPER = ClassName.get("cpw.mods.fml.common.network.simpleimpl", "SimpleNetworkWrapper");
    static final ClassName MESSAGE = ClassName.get("cpw.mods.fml.common.network.simpleimpl", "IMessage");
    static final ClassName MESSAGE_HANDLER = ClassName.get("cpw.mods.fml.common.network.simpleimpl", "IMessageHandler");
    static final ClassName MESSAGE_CONTEXT = ClassName.get("cpw.mods.fml.common.network.simpleimpl", "MessageContext");
    static final ClassName SIDE = ClassName.get("cpw.mods.fml.relauncher", "Side");
    static final ClassName SIDE_ONLY = ClassName.get("cpw.mods.fml.relauncher", "SideOnly");
    static final ClassName SUBSCRIBE = ClassName.get("cpw.mods.fml.common.eventhandler", "SubscribeEvent");

    // Forge
    static final ClassName FORGE = ClassName.get("net.minecraftforge.common", "MinecraftForge");
    static final ClassName FORGE_DIRECTION = ClassName.get("net.minecraftforge.common.util", "ForgeDirection");
    static final ClassName ORE_DICT = ClassName.get("net.minecraftforge.oredict", "OreDictionary");
    static final ClassName SHAPED_ORE = ClassName.get("net.minecraftforge.oredict", "ShapedOreRecipe");
    static final ClassName SHAPELESS_ORE = ClassName.get("net.minecraftforge.oredict", "ShapelessOreRecipe");
    static final ClassName FLUID = ClassName.get("net.minecraftforge.fluids", "Fluid");
    static final ClassName FLUID_REGISTRY = ClassName.get("net.minecraftforge.fluids", "FluidRegistry");
    static final ClassName FLUID_STACK = ClassName.get("net.minecraftforge.fluids", "FluidStack");
    static final ClassName FLUID_CONTAINERS = ClassName.get("net.minecraftforge.fluids", "FluidContainerRegistry");
    static final ClassName BLOCK_FLUID_CLASSIC = ClassName.get("net.minecraftforge.fluids", "BlockFluidClassic");
    static final ClassName TEXTURE_STITCH_PRE = ClassName.get("net.minecraftforge.client.event", "TextureStitchEvent", "Pre");

    // libraries shipped with 1.7.10
    static final ClassName BYTE_BUF = ClassName.get("io.netty.buffer", "ByteBuf");
    static final ClassName LOGGER = ClassName.get("org.apache.logging.log4j", "Logger");
    static final ClassName LOG_MANAGER = ClassName.get("org.apache.logging.log4j", "LogManager");
}
