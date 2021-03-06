package me.desht.pneumaticcraft.common.tileentity;

import com.google.common.collect.ImmutableMap;
import me.desht.pneumaticcraft.api.PneumaticRegistry;
import me.desht.pneumaticcraft.api.crafting.TemperatureRange;
import me.desht.pneumaticcraft.api.crafting.recipe.RefineryRecipe;
import me.desht.pneumaticcraft.api.heat.IHeatExchangerLogic;
import me.desht.pneumaticcraft.client.util.ClientUtils;
import me.desht.pneumaticcraft.common.core.ModTileEntities;
import me.desht.pneumaticcraft.common.inventory.ContainerRefinery;
import me.desht.pneumaticcraft.common.network.DescSynced;
import me.desht.pneumaticcraft.common.network.GuiSynced;
import me.desht.pneumaticcraft.common.recipes.PneumaticCraftRecipeType;
import me.desht.pneumaticcraft.common.util.FluidUtils;
import me.desht.pneumaticcraft.lib.NBTKeys;
import me.desht.pneumaticcraft.lib.PneumaticValues;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.Fluid;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.INamedContainerProvider;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public class TileEntityRefineryController extends TileEntityTickableBase
        implements IRedstoneControlled, IComparatorSupport, ISerializableTanks, INamedContainerProvider {

    @GuiSynced
    @DescSynced
    private final RefineryInputTank inputTank = new RefineryInputTank(PneumaticValues.NORMAL_TANK_CAPACITY);
    private final LazyOptional<IFluidHandler> fluidCap = LazyOptional.of(() -> inputTank);
    @GuiSynced
    public final SmartSyncTank[] outputsSynced = new SmartSyncTank[RefineryRecipe.MAX_OUTPUTS];  // purely for GUI syncing
    @GuiSynced
    private final IHeatExchangerLogic heatExchanger = PneumaticRegistry.getInstance().getHeatRegistry().makeHeatExchangerLogic();
    private final LazyOptional<IHeatExchangerLogic> heatCap = LazyOptional.of(() -> heatExchanger);
    @GuiSynced
    private int redstoneMode;
    @GuiSynced
    private boolean blocked;
    @GuiSynced
    public int minTemp;
    @GuiSynced
    public int maxTemp;

    @DescSynced
    private int outputCount;
    @DescSynced
    private int lastProgress; // indicates to client that refinery is running, for particle spawning

    private List<LazyOptional<IFluidHandler>> outputCache;
    private TemperatureRange operatingTemp = TemperatureRange.invalid();
    private RefineryRecipe currentRecipe;
    private int workTimer = 0;
    private int comparatorValue;
    private int prevOutputCount = -1;
    private boolean searchForRecipe = true;

    public TileEntityRefineryController() {
        super(ModTileEntities.REFINERY.get());

        for (int i = 0; i < RefineryRecipe.MAX_OUTPUTS; i++) {
            outputsSynced[i] = new SmartSyncTank(this, PneumaticValues.NORMAL_TANK_CAPACITY);
        }
    }

    public static boolean isInputFluidValid(World world, Fluid fluid, int size) {
        RefineryRecipe recipe =  PneumaticCraftRecipeType.REFINERY
                .findFirst(world, r -> r.getOutputs().size() <= size && FluidUtils.matchFluid(r.getInput(), fluid, true));
        return recipe != null;
    }

    private RefineryRecipe getRecipeFor(FluidStack fluid) {
        return PneumaticCraftRecipeType.REFINERY.stream(world)
                .filter(r -> r.getOutputs().size() <= outputCount)
                .filter(r -> FluidUtils.matchFluid(r.getInput(), fluid, true))
                .max(Comparator.comparingInt(r2 -> r2.getOutputs().size()))
                .orElse(null);
    }

    @Override
    public void tick() {
        super.tick();

        inputTank.tick();

        if (!getWorld().isRemote) {
            // server
            lastProgress = 0;
            if (outputCache == null) cacheRefineryOutputs();
            outputCount = outputCache.size();
            if (prevOutputCount != outputCount) {
                searchForRecipe = true;
            }
            if (searchForRecipe) {
                currentRecipe = getRecipeFor(inputTank.getFluid());
                operatingTemp = currentRecipe == null ? TemperatureRange.invalid() : currentRecipe.getOperatingTemp();
                minTemp = operatingTemp.getMin();
                maxTemp = operatingTemp.getMax();
                searchForRecipe = false;
            }
            boolean hasWork = false;
            if (currentRecipe != null) {
                if (prevOutputCount != outputCount && outputCount > 1) {
                    redistributeFluids();
                }

                if (outputCount > 1 && redstoneAllows() && doRefiningStep(FluidAction.SIMULATE)) {
                    hasWork = true;
                    if (operatingTemp.inRange(heatExchanger.getTemperature())
                            && inputTank.getFluidAmount() >= currentRecipe.getInput().getAmount()) {
                        // TODO support for cryo-refining (faster as it gets colder, adds heat instead of removing)
                        int progress = Math.max(0, ((int) heatExchanger.getTemperature() - (operatingTemp.getMin() - 30)) / 30);
                        progress = Math.min(5, progress);
                        heatExchanger.addHeat(-progress);
                        workTimer += progress;
                        while (workTimer >= 20 && inputTank.getFluidAmount() >= currentRecipe.getInput().getAmount()) {
                            workTimer -= 20;
                            doRefiningStep(FluidAction.EXECUTE);
                            inputTank.drain(currentRecipe.getInput().getAmount(), FluidAction.EXECUTE);
                        }
                        lastProgress = progress;
                    }
                } else {
                    workTimer = 0;
                }
            }

            IntStream.range(0, outputCount).forEach(i -> outputCache.get(i).ifPresent(h -> {
                outputsSynced[i].setFluid(h.getFluidInTank(0).copy());
                outputsSynced[i].tick();
            }));

            prevOutputCount = outputCount;
            updateComparatorValue(outputCount, hasWork);
        } else {
            // client
            if (lastProgress > 0) {
                TileEntityRefineryOutput teRO = findAdjacentOutput();
                if (teRO != null) {
                    for (int i = 0; i < lastProgress; i++) {
                        ClientUtils.emitParticles(getWorld(), teRO.getPos().offset(Direction.UP, outputCount - 1), ParticleTypes.SMOKE);
                    }
                }
            }
            for (SmartSyncTank smartSyncTank : outputsSynced) {
                smartSyncTank.tick();
            }
        }
    }

    /**
     * Called when the number of refinery outputs in the multiblock changes. Redistribute existing fluids to match the
     * current recipe so the refinery can continue to run.  Of course, it might not be possible to move fluids if
     * there's already something in the new tank, but we'll do our best.
     *
     * When this is called, there will be a valid recipe and at least two outputs present.
     */
    private void redistributeFluids() {
        FluidTank[] tempTanks = new FluidTank[outputCount];
        for (int i = 0; i < outputCount; i++) {
            tempTanks[i] = new FluidTank(PneumaticValues.NORMAL_TANK_CAPACITY);
        }

        // now scan all refineries and ensure each one has the correct output, according to the current recipe
        for (int i = 0; i < outputCount; i++) {
            final FluidStack wantedFluid = currentRecipe.getOutputs().get(i);
            outputCache.get(i).ifPresent(outputHandler -> {
                FluidStack fluid = outputHandler.getFluidInTank(0);
                if (!fluid.isFluidEqual(wantedFluid)) {
                    // this fluid shouldn't be here; find the appropriate output tank to move it to,
                    // using an intermediate temporary tank to allow for possible swapping of fluids
                    for (int j = 0; j < currentRecipe.getOutputs().size(); j++) {
                        if (currentRecipe.getOutputs().get(j).isFluidEqual(fluid)) {
                            tryMoveFluid(outputHandler, tempTanks[j]);
                            break;
                        }
                    }
                }
            });
        }

        // and finally move fluids back to the actual output tanks
        for (int i = 0; i < outputCount; i++) {
            final IFluidHandler tempTank = tempTanks[i];
            outputCache.get(i).ifPresent(outputHandler -> tryMoveFluid(tempTank, outputHandler));
        }
    }

    private void tryMoveFluid(IFluidHandler sourceHandler, IFluidHandler destHandler) {
        FluidStack fluid = sourceHandler.drain(sourceHandler.getTankCapacity(0), FluidAction.SIMULATE);
        if (!fluid.isEmpty()) {
            int moved = destHandler.fill(fluid, FluidAction.EXECUTE);
            if (moved > 0) {
                sourceHandler.drain(moved, FluidAction.EXECUTE);
            }
        }
    }

    public void cacheRefineryOutputs() {
        if (isRemoved()) return;

        List<LazyOptional<IFluidHandler>> cache = new ArrayList<>();

        TileEntityRefineryOutput output = findAdjacentOutput();
        while (output != null) {
            // direction DOWN is important here to get the unwrapped cap
            LazyOptional<IFluidHandler> handler = output.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, Direction.DOWN);
            handler.addListener(l -> cacheRefineryOutputs());
            cache.add(handler);
            TileEntity te = output.getCachedNeighbor(Direction.UP);
            output = te instanceof TileEntityRefineryOutput ? (TileEntityRefineryOutput) te : null;
        }

        outputCache = cache;
    }

    public TileEntityRefineryOutput findAdjacentOutput() {
        for (Direction d : Direction.VALUES) {
            if (d != Direction.DOWN) {
                TileEntity te = getCachedNeighbor(d);
                if (te instanceof TileEntityRefineryOutput) return (TileEntityRefineryOutput) te;
            }
        }
        return null;
    }

    private boolean doRefiningStep(FluidAction action) {
        List<FluidStack> recipeOutputs = currentRecipe.getOutputs();

        for (int i = 0; i < outputCache.size() && i < recipeOutputs.size(); i++) {
        	final FluidStack outFluid = recipeOutputs.get(i);
            int filled = outputCache.get(i).map(h -> h.fill(outFluid, action)).orElse(0);
            if (filled != outFluid.getAmount()) {
            	blocked = true;
            	return false;
            }
        }

        blocked = false;
        return true;
    }

    @Override
    public boolean redstoneAllows() {
        int totalPower = poweredRedstone;

        // power to each refinery output block is also considered
        TileEntityRefineryOutput refineryOutput = findAdjacentOutput();
        if (refineryOutput != null) {
            while (refineryOutput.getCachedNeighbor(Direction.UP) instanceof TileEntityRefineryOutput) {
                totalPower += refineryOutput.poweredRedstone;
                refineryOutput = (TileEntityRefineryOutput) refineryOutput.getCachedNeighbor(Direction.UP);
            }
        }

        switch (getRedstoneMode()) {
            case 0: return true;
            case 1: return totalPower > 0;
            case 2: return totalPower == 0;
        }
        return false;
    }

    @Override
    public IItemHandler getPrimaryInventory() {
        return null;
    }

    public FluidTank getInputTank() {
        return inputTank;
    }

    public boolean isBlocked() {
        return blocked;
    }

    @Override
    public CompoundNBT write(CompoundNBT tag) {
        super.write(tag);

        tag.putByte(NBTKeys.NBT_REDSTONE_MODE, (byte) redstoneMode);

        return tag;
    }

    @Override
    public void read(CompoundNBT tag) {
        super.read(tag);

        redstoneMode = tag.getByte(NBTKeys.NBT_REDSTONE_MODE);
    }

    @Override
    public int getRedstoneMode() {
        return redstoneMode;
    }

    @Override
    public void handleGUIButtonPress(String tag, boolean shiftHeld, PlayerEntity player) {
        if (tag.equals(IGUIButtonSensitive.REDSTONE_TAG)) {
            redstoneMode++;
            if (redstoneMode > 2) redstoneMode = 0;
        }
    }

    private void updateComparatorValue(int outputCount, boolean didWork) {
        int value;
        if (inputTank.getFluidAmount() < 10 || outputCount < 2 || currentRecipe == null || outputCount > currentRecipe.getOutputs().size()) {
            value = 0;
        } else {
            value = didWork ? 15 : 0;
        }
        if (value != comparatorValue) {
            comparatorValue = value;
            getWorld().updateComparatorOutputLevel(getPos(), getBlockState().getBlock());
        }
    }

    @Override
    public int getComparatorValue() {
        return comparatorValue;
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return fluidCap.cast();
        } else {
            return super.getCapability(cap, side);
        }
    }

    @Nonnull
    @Override
    public Map<String, FluidTank> getSerializableTanks() {
        return ImmutableMap.of("OilTank", inputTank);
    }

    @Override
    public ITextComponent getDisplayName() {
        return getDisplayNameInternal();
    }

    @Nullable
    @Override
    public Container createMenu(int i, PlayerInventory playerInventory, PlayerEntity playerEntity) {
        return new ContainerRefinery(i, playerInventory, getPos());
    }

    @Override
    public LazyOptional<IHeatExchangerLogic> getHeatCap(Direction side) {
        return heatCap;
    }

    private class RefineryInputTank extends SmartSyncTank {
        private Fluid prevFluid;

        RefineryInputTank(int capacity) {
            super(TileEntityRefineryController.this, capacity);
        }

        @Override
        public boolean isFluidValid(FluidStack fluid) {
            return getFluid().isFluidEqual(fluid) || isInputFluidValid(world, fluid.getFluid(), 4);
        }

        @Override
        protected void onContentsChanged() {
            super.onContentsChanged();

            Fluid newFluid = getFluid().getFluid();
            if (prevFluid != newFluid) {
                searchForRecipe = true;
                prevFluid = newFluid;
            }
        }
    }
}
