package me.desht.pneumaticcraft.common.util;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class TileEntityCache {
    private TileEntity te;
    private final World world;
    private final BlockPos pos;

    public TileEntityCache(World world, BlockPos pos) {
        this.world = world;
        this.pos = pos;
        update();
    }

    public void update() {
        te = world.isBlockLoaded(pos) ? world.getTileEntity(pos) : null;
    }

    public TileEntity getTileEntity() {
        if (te != null && te.isInvalid()) te = null;
        return te;
    }

    public static TileEntityCache[] getDefaultCache(World world, BlockPos pos) {
        TileEntityCache[] cache = new TileEntityCache[6];
        for (int i = 0; i < 6; i++) {
            try {
            	EnumFacing d = EnumFacing.byIndex(i);
            	cache[i] = new TileEntityCache(world, pos.offset(d));
            } catch (Exception e) {
            	System.out.println("Supressing crash from PNC: " + e.getMessage());
            	e.printStackTrace();
            }
        }
        return cache;
    }

}
