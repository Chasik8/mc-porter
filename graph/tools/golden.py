#!/usr/bin/env python3
"""Builds graph/golden.json: 100 hand-picked 1.19.2 -> 1.7.10 correspondences, each checked against the real
mapping files (not the graph):

* the 1.19.2 symbol must exist in Mojang's 1.19.2 client mappings (its JVM descriptor is taken from there);
* the 1.7.10 symbol must exist in MCP 1.7.10 (joined.srg + stable_12 methods/fields.csv; descriptor from joined.srg);
* `chain` records how the correspondence itself was verified:
    - "srg-chain": the member has the same name in Mojang 1.16.5 (and 1.19.2) and its MCPConfig 1.16.5 SRG id equals
      the SRG id of the 1.7.10 symbol -- i.e. MCP itself linked them across versions;
    - "class-chain": class whose 1.16.5 MCP name (via MCPConfig) equals the 1.7.10 MCP class name, or whose 1.16.5
      Mojang name maps to the same obf class as the MCP 1.16.5 class of the 1.7.10 name;
    - "manual": the SRG id changed on the way (method re-identified by MCP after 1.8/1.13) or the symbol has no
      counterpart; the pair is a well-known port correspondence and both ends were checked to exist.
  Entries with `to: null` expect status removed.

Usage: python -I golden.py <cache-dir> <gradle-user-home> <out golden.json>
"""
import csv
import io
import json
import re
import sys
import zipfile
from pathlib import Path

# (category, kind, owner 1.19.2 (mojmap), member, param hint, owner 1.7.10 (mcp) or None, member, param hint)
# param hint: list of simple parameter type names (disambiguates overloads) or None (unique name).
E = [
    # --- World / Level -------------------------------------------------------------------------------------
    ("world", "class", "net.minecraft.world.level.Level", None, None, "net.minecraft.world.World", None, None),
    ("world", "method", "net.minecraft.world.level.Level", "getBlockEntity", ["BlockPos"], "net.minecraft.world.World", "getTileEntity", None),
    ("world", "field", "net.minecraft.world.level.Level", "isClientSide", None, "net.minecraft.world.World", "isRemote", None),
    ("world", "field", "net.minecraft.world.level.Level", "random", None, "net.minecraft.world.World", "rand", None),
    ("world", "method", "net.minecraft.world.level.Level", "getGameTime", None, "net.minecraft.world.World", "getTotalWorldTime", None),
    ("world", "method", "net.minecraft.world.level.Level", "isDay", None, "net.minecraft.world.World", "isDaytime", None),
    ("world", "method", "net.minecraft.world.level.Level", "isRaining", None, "net.minecraft.world.World", "isRaining", None),
    ("world", "method", "net.minecraft.world.level.Level", "removeBlockEntity", ["BlockPos"], "net.minecraft.world.World", "removeTileEntity", None),
    ("world", "method", "net.minecraft.world.level.Level", "setBlockEntity", ["BlockEntity"], "net.minecraft.world.World", "setTileEntity", None),
    ("world", "method", "net.minecraft.world.level.LevelWriter", "addFreshEntity", ["Entity"], "net.minecraft.world.World", "spawnEntityInWorld", None),
    ("world", "method", "net.minecraft.world.level.Level", "hasNeighborSignal", ["BlockPos"], "net.minecraft.world.World", "isBlockIndirectlyGettingPowered", None),
    ("world", "method", "net.minecraft.world.level.LevelReader", "isEmptyBlock", ["BlockPos"], "net.minecraft.world.World", "isAirBlock", None),
    ("world", "method", "net.minecraft.world.level.Level", "updateNeighborsAt", ["BlockPos", "Block"], "net.minecraft.world.World", "notifyBlocksOfNeighborChange", ["int", "int", "int", "Block"]),
    ("world", "method", "net.minecraft.world.level.Level", "sendBlockUpdated", None, "net.minecraft.world.World", "markBlockForUpdate", None),
    ("world", "method", "net.minecraft.world.level.LevelWriter", "destroyBlock", ["BlockPos", "boolean"], "net.minecraft.world.World", "breakBlock", None),
    ("world", "method", "net.minecraft.world.level.CommonLevelAccessor", "registryAccess", None, None, None, None),
    # --- Block -----------------------------------------------------------------------------------------------
    ("block", "class", "net.minecraft.world.level.block.Block", None, None, "net.minecraft.block.Block", None, None),
    ("block", "method", "net.minecraft.world.level.block.Block", "getDescriptionId", None, "net.minecraft.block.Block", "getUnlocalizedName", None),
    ("block", "method", "net.minecraft.world.level.block.Block", "setPlacedBy", None, "net.minecraft.block.Block", "onBlockPlacedBy", None),
    ("block", "method", "net.minecraft.world.level.block.Block", "playerWillDestroy", None, "net.minecraft.block.Block", "onBlockHarvested", None),
    ("block", "method", "net.minecraft.world.level.block.Block", "animateTick", None, "net.minecraft.block.Block", "randomDisplayTick", None),
    ("block", "method", "net.minecraft.world.level.block.state.BlockBehaviour", "use", None, "net.minecraft.block.Block", "onBlockActivated", None),
    ("block", "method", "net.minecraft.world.level.block.state.BlockBehaviour", "onRemove", None, "net.minecraft.block.Block", "breakBlock", None),
    ("block", "method", "net.minecraft.world.level.block.state.BlockBehaviour", "neighborChanged", None, "net.minecraft.block.Block", "onNeighborBlockChange", None),
    ("block", "method", "net.minecraft.world.level.block.state.BlockBehaviour", "tick", None, "net.minecraft.block.Block", "updateTick", None),
    ("block", "method", "net.minecraft.world.level.block.state.BlockBehaviour", "isSignalSource", None, "net.minecraft.block.Block", "canProvidePower", None),
    ("block", "method", "net.minecraft.world.level.block.state.BlockBehaviour", "getDestroyProgress", None, "net.minecraft.block.Block", "getPlayerRelativeBlockHardness", None),
    ("block", "class", "net.minecraft.world.level.block.EntityBlock", None, None, "net.minecraft.block.ITileEntityProvider", None, None),
    ("block", "method", "net.minecraft.world.level.block.EntityBlock", "newBlockEntity", None, "net.minecraft.block.ITileEntityProvider", "createNewTileEntity", None),
    ("block", "class", "net.minecraft.world.level.block.Blocks", None, None, "net.minecraft.init.Blocks", None, None),
    ("block", "field", "net.minecraft.world.level.block.Blocks", "STONE", None, "net.minecraft.init.Blocks", "stone", None),
    ("block", "method", "net.minecraft.world.level.block.Block", "defaultBlockState", None, None, None, None),
    # --- BlockEntity / TileEntity --------------------------------------------------------------------------------
    ("blockentity", "class", "net.minecraft.world.level.block.entity.BlockEntity", None, None, "net.minecraft.tileentity.TileEntity", None, None),
    ("blockentity", "method", "net.minecraft.world.level.block.entity.BlockEntity", "getLevel", None, "net.minecraft.tileentity.TileEntity", "getWorld", None),
    ("blockentity", "field", "net.minecraft.world.level.block.entity.BlockEntity", "level", None, "net.minecraft.tileentity.TileEntity", "worldObj", None),
    ("blockentity", "method", "net.minecraft.world.level.block.entity.BlockEntity", "setChanged", [], "net.minecraft.tileentity.TileEntity", "markDirty", None),
    ("blockentity", "method", "net.minecraft.world.level.block.entity.BlockEntity", "load", None, "net.minecraft.tileentity.TileEntity", "readFromNBT", None),
    ("blockentity", "method", "net.minecraft.world.level.block.entity.BlockEntity", "saveAdditional", None, "net.minecraft.tileentity.TileEntity", "writeToNBT", None),
    ("blockentity", "method", "net.minecraft.world.level.block.entity.BlockEntity", "setRemoved", None, "net.minecraft.tileentity.TileEntity", "invalidate", None),
    ("blockentity", "method", "net.minecraft.world.level.block.entity.BlockEntity", "isRemoved", None, "net.minecraft.tileentity.TileEntity", "isInvalid", None),
    ("blockentity", "method", "net.minecraft.world.level.block.entity.BlockEntity", "clearRemoved", None, "net.minecraft.tileentity.TileEntity", "validate", None),
    ("blockentity", "method", "net.minecraft.world.level.block.entity.BlockEntity", "getUpdatePacket", None, "net.minecraft.tileentity.TileEntity", "getDescriptionPacket", None),
    ("blockentity", "class", "net.minecraft.world.level.block.entity.ChestBlockEntity", None, None, "net.minecraft.tileentity.TileEntityChest", None, None),
    ("blockentity", "class", "net.minecraft.world.level.block.entity.HopperBlockEntity", None, None, "net.minecraft.tileentity.TileEntityHopper", None, None),
    ("blockentity", "class", "net.minecraft.world.level.block.entity.BlockEntityType", None, None, None, None, None),
    # --- ItemStack / Item ------------------------------------------------------------------------------------------
    ("itemstack", "class", "net.minecraft.world.item.ItemStack", None, None, "net.minecraft.item.ItemStack", None, None),
    ("itemstack", "method", "net.minecraft.world.item.ItemStack", "getItem", None, "net.minecraft.item.ItemStack", "getItem", None),
    ("itemstack", "method", "net.minecraft.world.item.ItemStack", "getTag", None, "net.minecraft.item.ItemStack", "getTagCompound", None),
    ("itemstack", "method", "net.minecraft.world.item.ItemStack", "setTag", None, "net.minecraft.item.ItemStack", "setTagCompound", None),
    ("itemstack", "method", "net.minecraft.world.item.ItemStack", "copy", None, "net.minecraft.item.ItemStack", "copy", None),
    ("itemstack", "method", "net.minecraft.world.item.ItemStack", "getMaxStackSize", None, "net.minecraft.item.ItemStack", "getMaxStackSize", None),
    ("itemstack", "method", "net.minecraft.world.item.ItemStack", "getDamageValue", None, "net.minecraft.item.ItemStack", "getCurrentDurability", None),
    ("itemstack", "method", "net.minecraft.world.item.ItemStack", "save", None, "net.minecraft.item.ItemStack", "writeToNBT", None),
    ("itemstack", "method", "net.minecraft.world.item.ItemStack", "of", None, "net.minecraft.item.ItemStack", "loadItemStackFromNBT", None),
    ("itemstack", "method", "net.minecraft.world.item.ItemStack", "getHoverName", None, "net.minecraft.item.ItemStack", "getDisplayName", None),
    ("itemstack", "method", "net.minecraft.world.item.ItemStack", "isEmpty", None, None, None, None),
    ("itemstack", "class", "net.minecraft.world.item.Item", None, None, "net.minecraft.item.Item", None, None),
    ("itemstack", "method", "net.minecraft.world.item.Item", "getDescriptionId", [], "net.minecraft.item.Item", "getUnlocalizedName", []),
    # --- NBT -------------------------------------------------------------------------------------------------------
    ("nbt", "class", "net.minecraft.nbt.CompoundTag", None, None, "net.minecraft.nbt.NBTTagCompound", None, None),
    ("nbt", "method", "net.minecraft.nbt.CompoundTag", "putInt", None, "net.minecraft.nbt.NBTTagCompound", "setInteger", None),
    ("nbt", "method", "net.minecraft.nbt.CompoundTag", "getInt", None, "net.minecraft.nbt.NBTTagCompound", "getInteger", None),
    ("nbt", "method", "net.minecraft.nbt.CompoundTag", "putString", None, "net.minecraft.nbt.NBTTagCompound", "setString", None),
    ("nbt", "method", "net.minecraft.nbt.CompoundTag", "getString", None, "net.minecraft.nbt.NBTTagCompound", "getString", None),
    ("nbt", "method", "net.minecraft.nbt.CompoundTag", "put", None, "net.minecraft.nbt.NBTTagCompound", "setTag", None),
    ("nbt", "method", "net.minecraft.nbt.CompoundTag", "getCompound", None, "net.minecraft.nbt.NBTTagCompound", "getCompoundTag", None),
    ("nbt", "method", "net.minecraft.nbt.CompoundTag", "contains", ["String"], "net.minecraft.nbt.NBTTagCompound", "hasKey", ["String"]),
    ("nbt", "method", "net.minecraft.nbt.CompoundTag", "getList", None, "net.minecraft.nbt.NBTTagCompound", "getTagList", None),
    ("nbt", "class", "net.minecraft.nbt.ListTag", None, None, "net.minecraft.nbt.NBTTagList", None, None),
    ("nbt", "method", "net.minecraft.nbt.ListTag", "getCompound", None, "net.minecraft.nbt.NBTTagList", "getCompoundTagAt", None),
    ("nbt", "class", "net.minecraft.nbt.Tag", None, None, "net.minecraft.nbt.NBTBase", None, None),
    # --- Container / Menu -------------------------------------------------------------------------------------------
    ("menu", "class", "net.minecraft.world.inventory.AbstractContainerMenu", None, None, "net.minecraft.inventory.Container", None, None),
    ("menu", "method", "net.minecraft.world.inventory.AbstractContainerMenu", "addSlot", None, "net.minecraft.inventory.Container", "addSlotToContainer", None),
    ("menu", "method", "net.minecraft.world.inventory.AbstractContainerMenu", "quickMoveStack", None, "net.minecraft.inventory.Container", "transferStackInSlot", None),
    ("menu", "method", "net.minecraft.world.inventory.AbstractContainerMenu", "stillValid", ["Player"], "net.minecraft.inventory.Container", "canInteractWith", None),
    ("menu", "method", "net.minecraft.world.inventory.AbstractContainerMenu", "broadcastChanges", None, "net.minecraft.inventory.Container", "detectAndSendChanges", None),
    ("menu", "method", "net.minecraft.world.inventory.AbstractContainerMenu", "moveItemStackTo", None, "net.minecraft.inventory.Container", "mergeItemStack", None),
    ("menu", "method", "net.minecraft.world.inventory.AbstractContainerMenu", "removed", None, "net.minecraft.inventory.Container", "onContainerClosed", None),
    ("menu", "class", "net.minecraft.world.inventory.Slot", None, None, "net.minecraft.inventory.Slot", None, None),
    ("menu", "method", "net.minecraft.world.inventory.Slot", "mayPlace", None, "net.minecraft.inventory.Slot", "isItemValid", None),
    ("menu", "method", "net.minecraft.world.inventory.Slot", "getItem", None, "net.minecraft.inventory.Slot", "getStack", None),
    ("menu", "class", "net.minecraft.world.Container", None, None, "net.minecraft.inventory.IInventory", None, None),
    ("menu", "method", "net.minecraft.world.Container", "getContainerSize", None, "net.minecraft.inventory.IInventory", "getSizeInventory", None),
    ("menu", "class", "net.minecraft.world.inventory.MenuType", None, None, None, None, None),
    # --- GUI ---------------------------------------------------------------------------------------------------------
    ("gui", "class", "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen", None, None, "net.minecraft.client.gui.inventory.GuiContainer", None, None),
    ("gui", "field", "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen", "leftPos", None, "net.minecraft.client.gui.inventory.GuiContainer", "guiLeft", None),
    ("gui", "field", "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen", "imageWidth", None, "net.minecraft.client.gui.inventory.GuiContainer", "xSize", None),
    ("gui", "class", "net.minecraft.client.gui.screens.Screen", None, None, "net.minecraft.client.gui.GuiScreen", None, None),
    ("gui", "method", "net.minecraft.client.gui.screens.Screen", "isPauseScreen", None, "net.minecraft.client.gui.GuiScreen", "doesGuiPauseGame", None),
    ("gui", "class", "net.minecraft.client.gui.Font", None, None, "net.minecraft.client.gui.FontRenderer", None, None),
    ("gui", "method", "net.minecraft.client.Minecraft", "getInstance", None, "net.minecraft.client.Minecraft", "getMinecraft", None),
    # --- Network -------------------------------------------------------------------------------------------------------
    ("network", "class", "net.minecraft.network.FriendlyByteBuf", None, None, "net.minecraft.network.PacketBuffer", None, None),
    ("network", "method", "net.minecraft.network.FriendlyByteBuf", "writeVarInt", None, "net.minecraft.network.PacketBuffer", "writeVarIntToBuffer", None),
    ("network", "method", "net.minecraft.network.FriendlyByteBuf", "readVarInt", None, "net.minecraft.network.PacketBuffer", "readVarIntFromBuffer", None),
    ("network", "method", "net.minecraft.network.FriendlyByteBuf", "writeNbt", None, "net.minecraft.network.PacketBuffer", "writeNBTTagCompoundToBuffer", None),
    ("network", "method", "net.minecraft.network.FriendlyByteBuf", "readNbt", [], "net.minecraft.network.PacketBuffer", "readNBTTagCompoundFromBuffer", None),
    ("network", "method", "net.minecraft.network.FriendlyByteBuf", "writeItem", None, "net.minecraft.network.PacketBuffer", "writeItemStackToBuffer", None),
    ("network", "class", "net.minecraft.network.Connection", None, None, "net.minecraft.network.NetworkManager", None, None),
    ("network", "class", "net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket", None, None, "net.minecraft.network.play.server.S35PacketUpdateTileEntity", None, None),
    ("network", "class", "net.minecraft.server.network.ServerGamePacketListenerImpl", None, None, "net.minecraft.network.NetHandlerPlayServer", None, None),
    ("network", "field", "net.minecraft.server.level.ServerPlayer", "connection", None, "net.minecraft.entity.player.EntityPlayerMP", "playerNetServerHandler", None),
]

PRIM = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I", "long": "J", "float": "F", "double": "D"}


def jtype(t):
    dims = 0
    while t.endswith("[]"):
        dims += 1
        t = t[:-2]
    d = PRIM.get(t) or "L" + t.replace(".", "/") + ";"
    return "[" * dims + d


def simple(t):
    t = t.rstrip("[]")
    return t.split(".")[-1].split("$")[-1]


def read_proguard(text):
    classes, members = {}, {}
    cur = None
    for line in text.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        if not line.startswith(" "):
            name, obf = line.rstrip(":").split(" -> ")
            cur = name
            classes[name] = obf
            members[name] = []
            continue
        left, obf = line.strip().split(" -> ")
        left = re.sub(r"^\d+:\d+:", "", left)
        typ, rest = left.split(" ", 1)
        if "(" in rest:
            name, params = rest.split("(", 1)
            params = [p for p in params.rstrip(")").split(",") if p]
            members[cur].append(("method", name, params, typ, obf))
        else:
            members[cur].append(("field", rest, None, typ, obf))
    return classes, members


def find(members, owner, kind, name, hint):
    c = [m for m in members.get(owner, []) if m[0] == kind and m[1] == name]
    if hint is not None:
        c = [m for m in c if [simple(p) for p in m[2]] == hint]
    return c


def moj_desc(m):
    return "(" + "".join(jtype(p) for p in m[2]) + ")" + jtype(m[3])


def main():
    cache, gradle, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    dl = cache / "dl"
    _, m192 = read_proguard((dl / "mc-1.19.2-client-mappings/client.txt").read_text("utf-8"))
    c165, m165 = read_proguard((dl / "mc-1.16.5-client-mappings/client.txt").read_text("utf-8"))
    # MCPConfig 1.16.5: obf -> srg (class names are MCP names)
    tsrg = zipfile.ZipFile(dl / "mcp_config-1.16.5/mcp_config-1.16.5-20210115.111550.zip").read("config/joined.tsrg").decode()
    cls165, mem165 = {}, {}
    cur = None
    for line in tsrg.splitlines():
        if not line.startswith("\t"):
            obf, srg = line.split(" ")
            cur = obf
            cls165[obf] = srg
        else:
            parts = line.strip().split(" ")
            mem165[(cur, parts[0], parts[1] if len(parts) == 3 else None)] = parts[-1]
    # MCP 1.7.10
    srg = zipfile.ZipFile(dl / "mcp-1.7.10-srg/mcp-1.7.10-srg.zip").read("joined.srg").decode()
    st = zipfile.ZipFile(dl / "mcp_stable-12-1.7.10/mcp_stable-12-1.7.10.zip")
    names = {}
    for f in ("methods.csv", "fields.csv"):
        for row in csv.DictReader(io.StringIO(st.read(f).decode())):
            names[row["searge"]] = row["name"]
    cls1710, mem1710 = set(), []
    for line in srg.splitlines():
        p = line.split(" ")
        if p[0] == "CL:":
            cls1710.add(p[2].replace("/", "."))
        elif p[0] == "FD:":
            owner, s = p[2].rsplit("/", 1)
            mem1710.append(("field", owner.replace("/", "."), names.get(s, s), s, None))
        elif p[0] == "MD:":
            owner, s = p[3].rsplit("/", 1)
            mem1710.append(("method", owner.replace("/", "."), names.get(s, s), s, p[4]))

    def srg_of_165(owner, kind, name, hint):
        """SRG id of a 1.16.5 Mojang member (via the shared obf names)."""
        c = find(m165, owner, kind, name, hint)
        if len(c) != 1 or owner not in c165:
            return None
        m = c[0]
        if kind == "field":
            return mem165.get((c165[owner], m[4], None))
        # obf descriptor of the 1.16.5 member
        inv = {v: k for k, v in c165.items()}
        def obf_t(t):
            dims = len(t) - len(t.rstrip("[]"))
            dims //= 2
            base = t.rstrip("[]")
            d = PRIM.get(base) or "L" + c165.get(base, base).replace(".", "/") + ";"
            return "[" * dims + d
        od = "(" + "".join(obf_t(p) for p in m[2]) + ")" + obf_t(m[3])
        return mem165.get((c165[owner], m[4], od))

    golden, problems = [], []
    for i, (cat, kind, o19, n19, h19, o17, n17, h17) in enumerate(E):
        frm = {"version": "1.19.2", "kind": kind}
        if kind == "class":
            if o19 not in m192:
                problems.append(f"{o19}: not in 1.19.2 mojmap")
                continue
            frm["name"] = o19
        else:
            c = find(m192, o19, kind, n19, h19)
            if len(c) != 1:
                problems.append(f"{o19}#{n19}{h19}: {len(c)} candidates in 1.19.2 mojmap {[x[2] for x in c]}")
                continue
            frm.update(owner=o19, name=n19)
            if kind == "method":
                frm["desc"] = moj_desc(c[0])
        entry = {"id": f"g{i + 1:03d}", "category": cat, "from": frm}
        chain = "manual"
        if o17 is None:
            entry["to"] = None
        elif kind == "class":
            if o17 not in cls1710:
                problems.append(f"{o17}: not in MCP 1.7.10")
                continue
            entry["to"] = {"version": "1.7.10", "kind": "class", "name": o17}
            obf165 = c165.get(o19)
            if obf165 and cls165.get(obf165, "").replace("/", ".") == o17:
                chain = "class-chain"
        else:
            c = [m for m in mem1710 if m[0] == kind and m[1] == o17 and m[2] == n17]
            if h17 is not None and kind == "method":
                def params(desc):
                    inner = desc[1:desc.index(")")]
                    out, j = [], 0
                    while j < len(inner):
                        k = j
                        while inner[k] == "[":
                            k += 1
                        if inner[k] == "L":
                            e = inner.index(";", k)
                            out.append(inner[k + 1:e].split("/")[-1])
                            j = e + 1
                        else:
                            out.append({v: k2 for k2, v in PRIM.items()}[inner[k]])
                            j = k + 1
                    return out
                c = [m for m in c if params(m[4]) == h17]
            if len(c) != 1:
                problems.append(f"{o17}#{n17}: {len(c)} candidates in MCP 1.7.10 {[x[4] for x in c]}")
                continue
            to = {"version": "1.7.10", "kind": kind, "owner": o17, "name": n17}
            if kind == "method":
                to["desc"] = c[0][4]
            entry["to"] = to
            s165 = srg_of_165(o19, kind, n19, h19) if o19 in m165 else None
            if s165 is not None and s165 == c[0][3]:
                chain = "srg-chain"
            entry["srg1710"] = c[0][3]
            if s165:
                entry["srg1165"] = s165
        entry["chain"] = chain
        golden.append(entry)
    for p in problems:
        print("PROBLEM:", p, file=sys.stderr)
    doc = {
        "description": "Hand-picked 1.19.2 (mojmap) -> 1.7.10 (MCP stable_12) correspondences, checked against the real "
                       "mapping files by graph/tools/golden.py. to=null: no counterpart (expected status removed). "
                       "chain: srg-chain/class-chain = MCP's own cross-version ids agree; manual = SRG id changed or no counterpart.",
        "entries": golden,
    }
    out.write_text(json.dumps(doc, indent=1) + "\n", encoding="utf-8", newline="\n")
    by = {}
    for g in golden:
        by[g["chain"]] = by.get(g["chain"], 0) + 1
    print(len(golden), "entries", by, len(problems), "problems", file=sys.stderr)


if __name__ == "__main__":
    main()
