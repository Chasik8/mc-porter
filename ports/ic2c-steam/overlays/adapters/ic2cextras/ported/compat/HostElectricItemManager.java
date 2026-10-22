// porter:adapter (IC2C-specific) - the ported 2.x ElectricItem.MANAGER (never assigned by the port: IC2C's main
// class did it) answered by the installed IC2C 1.2.6 electric item manager (double energy -> int).
package ic2cextras.ported.compat;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

import ic2.api.item.IElectricItem;
import ic2cextras.ported.api.items.electric.IElectricItemManager;

public final class HostElectricItemManager implements IElectricItemManager {

    private static ic2.api.item.IElectricItemManager host() {
        return ic2.api.item.ElectricItem.manager;
    }

    private static IElectricItem item(ItemStack s) {
        return s != null && s.getItem() instanceof IElectricItem ? (IElectricItem) s.getItem() : null;
    }

    @Override
    public int charge(ItemStack s, int amount, int tier, boolean ignoreLimit, boolean simulate) {
        return s == null ? 0 : (int) host().charge(s, amount, tier, ignoreLimit, simulate);
    }

    @Override
    public int discharge(ItemStack s, int amount, int tier, boolean ignoreLimit, boolean externally, boolean simulate) {
        return s == null ? 0 : (int) host().discharge(s, amount, tier, ignoreLimit, externally, simulate);
    }

    @Override
    public int getCharge(ItemStack s) {
        return s == null ? 0 : (int) host().getCharge(s);
    }

    @Override
    public int getCapacity(ItemStack s) {
        IElectricItem i = item(s);
        return i == null ? 0 : (int) i.getMaxCharge(s);
    }

    @Override
    public boolean canUse(ItemStack s, int amount) {
        return s != null && host().canUse(s, amount);
    }

    @Override
    public boolean use(ItemStack s, int amount, EntityLivingBase user) {
        return s != null && host().use(s, amount, user);
    }

    @Override
    public void chargeFromArmor(ItemStack s, EntityLivingBase user) {
        host().chargeFromArmor(s, user);
    }

    @Override
    public int getTier(ItemStack s) {
        IElectricItem i = item(s);
        return i == null ? 0 : i.getTier(s);
    }

    @Override
    public int getTransferLimit(ItemStack s) {
        IElectricItem i = item(s);
        return i == null ? 0 : (int) i.getTransferLimit(s);
    }

    @Override
    public IChatComponent getToolTip(ItemStack s) {
        return new ChatComponentText(s == null ? "" : host().getToolTip(s));
    }
}
