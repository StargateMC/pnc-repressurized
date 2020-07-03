package me.desht.pneumaticcraft.common.event;

import me.desht.pneumaticcraft.api.client.pneumaticHelmet.IHackableBlock;
import me.desht.pneumaticcraft.api.client.pneumaticHelmet.IHackableEntity;
import me.desht.pneumaticcraft.api.hacking.CapabilityHacking;
import me.desht.pneumaticcraft.api.hacking.IHacking;
import me.desht.pneumaticcraft.client.render.pneumatic_armor.PneumaticHelmetRegistry;
import me.desht.pneumaticcraft.common.util.WorldAndCoord;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class HackTickHandler {
    private final Map<WorldAndCoord, IHackableBlock> hackedBlocks = new HashMap<>();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Iterator<Map.Entry<WorldAndCoord, IHackableBlock>> blockIterator = hackedBlocks.entrySet().iterator();
            while (blockIterator.hasNext()) {
                Map.Entry<WorldAndCoord, IHackableBlock> entry = blockIterator.next();
                IHackableBlock hackableBlock = entry.getValue();
                WorldAndCoord hackedBlock = entry.getKey();

                boolean found = false;
                for (Map.Entry<Block, Class<? extends IHackableBlock>> registeredEntry : PneumaticHelmetRegistry.getInstance().hackableBlocks.entrySet()) {
                    if (hackableBlock.getClass() == registeredEntry.getValue()) {
                        if (hackedBlock.getBlock() == registeredEntry.getKey()) {
                            if (!hackableBlock.afterHackTick((World) hackedBlock.world, hackedBlock.pos)) {
                                blockIterator.remove();
                            }
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) blockIterator.remove();
            }
        }
    }

    @SubscribeEvent
    public void worldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (event.world.loadedEntityList.size() > 5000) {
                //System.out.println("Not ticking dimension: " + event.world.provider.getDimension() + " for hacking updates, as the entity list is currently: " + event.world.loadedEntityList.size() + " in size.");
                return;
            } else {
                try {
                    for (Entity entity : event.world.loadedEntityList) {
                        if (entity.hasCapability(CapabilityHacking.HACKING_CAPABILITY, null)) {
                            IHacking hack = entity.getCapability(CapabilityHacking.HACKING_CAPABILITY, null);
                            if (!hack.getCurrentHacks().isEmpty()) {
                                hack.update(entity);
                            }
                        }
                    }
                } catch (Throwable e) {
                    // Catching a CME which I have no clue on what might cause it.
                }
            }
        }
    }

    public void trackBlock(WorldAndCoord coord, IHackableBlock iHackable) {
        hackedBlocks.put(coord, iHackable);
    }

    public void trackEntity(Entity entity, IHackableEntity iHackable) {
        if (iHackable.getId() != null && entity.hasCapability(CapabilityHacking.HACKING_CAPABILITY, null)) {
            IHacking hack = entity.getCapability(CapabilityHacking.HACKING_CAPABILITY, null);
            hack.addHackable(iHackable);
        }
    }
}
