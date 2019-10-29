package icbm.classic.content.machines.emptower;

import li.cil.oc.api.Network;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.*;

import icbm.classic.api.tile.multiblock.IMultiTile;
import icbm.classic.api.tile.multiblock.IMultiTileHost;
import icbm.classic.client.ICBMSounds;
import icbm.classic.content.explosive.blast.BlastEMP;
import icbm.classic.content.multiblock.MultiBlockHelper;
import icbm.classic.lib.IGuiTile;
import icbm.classic.lib.network.IPacket;
import icbm.classic.lib.network.IPacketIDReceiver;
import icbm.classic.prefab.inventory.ExternalInventory;
import icbm.classic.prefab.inventory.IInventoryProvider;
import icbm.classic.prefab.item.TilePoweredMachine;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class TileEMPTower extends TilePoweredMachine implements IMultiTileHost, IPacketIDReceiver, IGuiTile, IInventoryProvider<ExternalInventory>, ManagedEnvironment
{
    // The maximum possible radius for the EMP to strike
    public static final int MAX_RADIUS = 150;

    public static List<BlockPos> tileMapCache = new ArrayList();

    static
    {
        tileMapCache.add(new BlockPos(0, 1, 0));
    }

    public float rotation = 0;
    private float rotationDelta;

    // The EMP mode. 0 = All, 1 = Missiles Only, 2 = Electricity Only
    public byte empMode = 0; //TODO move to enum

    private int cooldownTicks = 0;

    // The EMP explosion radius
    public int empRadius = 60;

    private boolean _destroyingStructure = false;

    private ExternalInventory inventory;


    public ComponentConnector node;

    public TileEMPTower() {
        super();

        node = Network.newNode(this, Visibility.Network).withComponent("emp_tower").withConnector(32).create();
    }

    @Override
    public ExternalInventory getInventory()
    {
        if (inventory == null)
        {
            inventory = new ExternalInventory(this, 2);
        }
        return inventory;
    }

    @Override
    public void update()
    {
        super.update();
        if (isServer())
        {
            if (!isReady())
            {
                cooldownTicks--;
            }
            else
            {
                if (ticks % 20 == 0 && getEnergy() > 0)
                {
                    ICBMSounds.MACHINE_HUM.play(world, xi(), yi(), zi(), 0.5F, 0.85F * getChargePercentage(), true);
                    sendDescPacket();
                }
                if (world.getRedstonePowerFromNeighbors(getPos()) > 0)
                {
                    fire();
                }
            }
        }
        else
        {
            rotationDelta = (float) (Math.pow(getChargePercentage(), 2) * 0.5);
            rotation += rotationDelta;
            if (rotation > 360)
            {
                rotation = 0;
            }
        }

        if (node() != null && node().network() == null) {
            Network.joinOrCreateNetwork(this);
        }
    }

    @Override
    public boolean canUpdate() {
        return true;
    }

    @Override
    public void onMessage(Message arg0) {

    }

    @Override
    public void onConnect(Node arg0) {}

    @Override
    public Node node() {
        return node;
    }

    @Override
    public void onDisconnect(Node arg0) {}

    public float getChargePercentage()
    {
        return Math.min(1, getEnergy() / (float) getEnergyConsumption());
    }

    @Override
    public boolean read(ByteBuf data, int id, EntityPlayer player, IPacket type)
    {
        if (!super.read(data, id, player, type))
        {
            switch (id)
            {
                case 1: //TODO constant
                {
                    empRadius = data.readInt();
                    return true;
                }
                case 2://TODO constant
                {
                    empMode = data.readByte();
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public void writeDescPacket(ByteBuf buf)
    {
        super.writeDescPacket(buf);
        buf.writeInt(empRadius);
        buf.writeByte(empMode);
    }

    @Override
    public void readDescPacket(ByteBuf buf)
    {
        super.readDescPacket(buf);
        empRadius = buf.readInt();
        empMode = buf.readByte();
    }

    @Override
    public int getEnergyBufferSize()
    {
        return Math.max(3000000 * (this.empRadius / MAX_RADIUS), 1000000);
    }

    /** Reads a tile entity from NBT. */
    @Override
    public void readFromNBT(NBTTagCompound par1NBTTagCompound)
    {
        super.readFromNBT(par1NBTTagCompound);

        this.empRadius = par1NBTTagCompound.getInteger("empRadius");
        this.empMode = par1NBTTagCompound.getByte("empMode");

        if (node() != null && node().host() == this) {
            node().load(par1NBTTagCompound.getCompoundTag("oc:node"));
        }
    }

    @Override
    public void load(NBTTagCompound v) {
        this.readFromNBT(v);
    }

    /** Writes a tile entity to NBT. */
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound par1NBTTagCompound)
    {
        par1NBTTagCompound.setInteger("empRadius", this.empRadius);
        par1NBTTagCompound.setByte("empMode", this.empMode);

        if (node() != null && node().host() == this) {
            final NBTTagCompound nodeNbt = new NBTTagCompound();
            node.save(nodeNbt);
            par1NBTTagCompound.setTag("oc:node", nodeNbt);
        }

        return super.writeToNBT(par1NBTTagCompound);
    }

    @Override
    public void save(NBTTagCompound v) {
        this.writeToNBT(v);
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (node != null) {
            node.remove();
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (node != null) {
            node.remove();
        }
    }

    //@Callback(limit = 1)
    public boolean fire()
    {
        if (this.checkExtract())
        {
            if (isReady())
            {
                switch (this.empMode)
                {
                    default:
                        new BlastEMP(world, null, this.xi() + 0.5, this.yi() + 1.2, this.zi() + 0.5, this.empRadius).setEffectBlocks().setEffectEntities().runBlast();
                        break;
                    case 1:
                        new BlastEMP(world, null, this.xi() + 0.5, this.yi() + 1.2, this.zi() + 0.5, this.empRadius).setEffectEntities().runBlast();
                        break;
                    case 2:
                        new BlastEMP(world, null, this.xi() + 0.5, this.yi() + 1.2, this.zi() + 0.5, this.empRadius).setEffectBlocks().runBlast();
                        break;
                }
                this.extractEnergy();
                this.cooldownTicks = getMaxCooldown();
                return true;
            }
        }
        return false;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox()
    {
        return INFINITE_EXTENT_AABB;
    }

    //@Callback
    public boolean isReady()
    {
        return getCooldown() <= 0;
    }

    //@Callback
    public int getCooldown()
    {
        return cooldownTicks;
    }

    //@Callback
    public int getMaxCooldown()
    {
        return 120;
    }

    //==========================================
    //==== Multi-Block code
    //=========================================

    @Override
    public void onMultiTileAdded(IMultiTile tileMulti)
    {
        if (tileMulti instanceof TileEntity)
        {
            if (getLayoutOfMultiBlock().contains(getPos().subtract(((TileEntity) tileMulti).getPos())))
            {
                tileMulti.setHost(this);
            }
        }
    }

    @Override
    public boolean onMultiTileBroken(IMultiTile tileMulti, Object source, boolean harvest)
    {
        if (!_destroyingStructure && tileMulti instanceof TileEntity)
        {
            if (getLayoutOfMultiBlock().contains(getPos().subtract(((TileEntity) tileMulti).getPos())))
            {
                MultiBlockHelper.destroyMultiBlockStructure(this, harvest, true, true);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onTileInvalidate(IMultiTile tileMulti)
    {

    }

    @Override
    public boolean onMultiTileActivated(IMultiTile tile, EntityPlayer player, EnumHand hand, EnumFacing side, float xHit, float yHit, float zHit)
    {
        if (isServer())
        {
            openGui(player, 0);
        }
        return true;
    }

    @Override
    public void onMultiTileClicked(IMultiTile tile, EntityPlayer player)
    {

    }

    @Override
    public List<BlockPos> getLayoutOfMultiBlock()
    {
        return tileMapCache;
    }

    @Override
    public Object getServerGuiElement(int ID, EntityPlayer player)
    {
        return new ContainerEMPTower(player, this);
    }

    @Override
    public Object getClientGuiElement(int ID, EntityPlayer player)
    {
        return new GuiEMPTower(player, this);
    }

    @Callback(doc = "function():boolean; Fires the EMP Tower", direct = true)
    public Object[] fire(Context context, Arguments args) {
        return new Object[] { this.fire() };
    }

    @Callback(doc = "function():boolean; Checks if the EMP can fire", direct = true)
    public Object[] isReady(Context context, Arguments args) {
        return new Object[] { this.isReady() };
    }

    @Callback(doc = "function():integer; Returns the stored energy", direct = true)
    public Object[] getStoredEnergy(Context context, Arguments args) {
        return new Object[] { this.getEnergy() };
    }

    @Callback(doc = "function():integer; Returns the maximum stored energy", direct = true)
    public Object[] getMaxEnergy(Context context, Arguments args) {
        return new Object[] { this.getEnergyBufferSize() };
    }

    @Callback(doc = "function(mode:string):boolean; Sets the fire mode (valid for mode: 'all', 'missile', 'electricity')", direct = true)
    public Object[] setFireMode(Context context, Arguments args) {
        if (args.count() == 0) {
            return new Object[] { false, "missing args" };
        }

        String mode = args.checkString(0);

        if (mode == "all") {
            this.empMode = 0;
        } else if (mode == "missile") {
            this.empMode = 1;
        } else if (mode == "electricity") {
            this.empMode = 2;
        } else {
            return new Object[] { false, "invalid argument" };
        }

        return new Object[] { true };
    }

    @Callback(doc = "function():string; returns the current fire mode ('all', 'missile', 'electricity')", direct = true)
    public Object[] getFireMode(Context context, Arguments args) {
        switch (this.empMode) {
            case 0:
                return new Object[] { "all" };
            case 1:
                return new Object[] { "missile" };
            case 2:
                return new Object[] { "electricity" };
            default:
                return new Object[] { "Invalid" };
        }
    }

    @Callback(doc = "function(radius:integer):boolean; Sets the EMP effect radius", direct = true)
    public Object[] setRadius(Context context, Arguments args) {
        int newRadius = args.checkInteger(0);

        if ((newRadius < 0) || (newRadius > MAX_RADIUS)) {
            return new Object[] { false, "Invalid radius (has to be between [0 - " + MAX_RADIUS + "])" };
        }

        this.empRadius = newRadius;

        return new Object[] { true };
    }

    @Callback(doc = "function():integer; returns the current EMP effect radius", direct = true)
    public Object[] getRadius(Context context, Arguments args) {
        return new Object[] { this.empRadius };
    }
}
