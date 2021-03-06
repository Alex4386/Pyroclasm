package grondag.pyroclasm.world;

import javax.annotation.Nullable;

import grondag.fermion.position.PackedBlockPos;
import grondag.fermion.position.PackedChunkPos;
import grondag.fermion.sc.concurrency.PerformanceCollector;
import grondag.fermion.sc.concurrency.PerformanceCounter;
import grondag.fermion.simulator.Simulator;
import grondag.fermion.varia.NBTDictionary;
import grondag.fermion.varia.Useful;
import grondag.pyroclasm.Configurator;
import grondag.pyroclasm.Pyroclasm;
import grondag.pyroclasm.block.CoolingBasaltBlock;
import it.unimi.dsi.fastutil.longs.Long2IntMap.Entry;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class BasaltTracker {
    private static final String NBT_BASALT_BLOCKS = NBTDictionary.claim("basaltBlocks");
    private static final int BASALT_BLOCKS_NBT_WIDTH = 3;

    /** Basalt blocks that are awaiting cooling */
    private final Long2ObjectOpenHashMap<Long2IntOpenHashMap> basaltBlocks = new Long2ObjectOpenHashMap<>();

    private final PerformanceCounter perfCounter;
    private final ServerWorld world;
    private final ChunkTracker chunkTracker;

    private int size = 0;

    private void coolBlocks(Long2IntOpenHashMap targets) {
        int lastEligibleBasaltCoolingTick = Simulator.currentTick() - Configurator.LAVA.basaltCoolingTicks;
        ObjectIterator<Entry> it = targets.long2IntEntrySet().fastIterator();
        while (it.hasNext()) {
            Entry e = it.next();

            if (e.getIntValue() <= lastEligibleBasaltCoolingTick) {
                BlockPos pos = PackedBlockPos.unpack(e.getLongKey());
                BlockState state = world.getBlockState(pos);
                Block block = state.getBlock();
                if (block instanceof CoolingBasaltBlock) {
                    switch (((CoolingBasaltBlock) block).tryCooling(world, pos, state)) {
                    case PARTIAL:
                        // will be ready to cool again after delay
                        e.setValue(Simulator.currentTick());
                        break;

                    case UNREADY:
                        // do nothing and try again later
                        break;

                    case COMPLETE:
                    case INVALID:
                    default:
                        it.remove();
                        this.size--;
                    }
                } else {
                    it.remove();
                    this.size--;
                }
            }
            ;
        }

    }

    public BasaltTracker(PerformanceCollector perfCollector, ServerWorld world, ChunkTracker chunkTracker) {
        this.world = world;
        this.chunkTracker = chunkTracker;
        this.perfCounter = PerformanceCounter.create(Configurator.DEBUG.enablePerformanceLogging, "Basalt cooling", perfCollector);
    }

    public void doBasaltCooling(long packedChunkPos) {
//        assert FMLCommonHandler.instance().getMinecraftServerInstance().isCallingFromMinecraftThread();

        this.perfCounter.startRun();
        if (!this.basaltBlocks.isEmpty()) {
            Long2IntOpenHashMap targets = this.basaltBlocks.get(packedChunkPos);

            if (targets != null) {
                if (!targets.isEmpty())
                    this.coolBlocks(targets);

                if (targets.isEmpty()) {
                    this.basaltBlocks.remove(packedChunkPos);
                    this.chunkTracker.untrackChunk(this.world, packedChunkPos);
                }
            }
        }
        this.perfCounter.endRun();
    }

    public boolean isTracked(long packedBlockPos) {
        long chunkPos = PackedChunkPos.getPackedChunkPos(packedBlockPos);
        Long2IntOpenHashMap blocks = this.basaltBlocks.get(chunkPos);
        if (blocks == null)
            return false;

        return blocks.containsKey(packedBlockPos);
    }

    /**
     * Call from world thread only - not thread-safe
     */
    public void trackCoolingBlock(long packedBlockPos) {
        this.trackCoolingBlock(packedBlockPos, Simulator.currentTick());
    }

    /**
     * Call from world thread only - not thread-safe
     */
    public void trackCoolingBlock(long packedBlockPos, int tick) {
//        assert FMLCommonHandler.instance().getMinecraftServerInstance().isCallingFromMinecraftThread();

        long chunkPos = PackedChunkPos.getPackedChunkPos(packedBlockPos);
        Long2IntOpenHashMap blocks = this.basaltBlocks.get(chunkPos);

        if (blocks == null) {
            blocks = new Long2IntOpenHashMap();
            this.basaltBlocks.put(chunkPos, blocks);

            this.chunkTracker.trackChunk(this.world, chunkPos);
        }
        if (blocks.put(packedBlockPos, tick) == blocks.defaultReturnValue())
            this.size++;
    }

    public int size() {
        return this.size;
    }

    public void serializeNBT(CompoundTag nbt) {
        if (Configurator.DEBUG.enablePerformanceLogging)
            Pyroclasm.LOG.info("Saving " + this.size + " cooling basalt blocks.");

        int[] saveData = new int[this.size * BASALT_BLOCKS_NBT_WIDTH];
        int i = 0;
        for (Long2IntOpenHashMap blocks : this.basaltBlocks.values()) {
            for (Entry e : blocks.long2IntEntrySet()) {
                saveData[i++] = Useful.longToIntHigh(e.getLongKey());
                saveData[i++] = Useful.longToIntLow(e.getLongKey());
                saveData[i++] = e.getIntValue();
            }
        }
        nbt.putIntArray(NBT_BASALT_BLOCKS, saveData);
    }

    public void deserializeNBT(@Nullable CompoundTag nbt) {
        basaltBlocks.clear();
        if (nbt == null)
            return;

        int[] saveData = nbt.getIntArray(NBT_BASALT_BLOCKS);

        // confirm correct size
        if (saveData.length % BASALT_BLOCKS_NBT_WIDTH != 0) {
            Pyroclasm.LOG.warn("Invalid save data loading lava simulator. Cooling basalt blocks may not be updated properly.");
        } else {
            int i = 0;
            while (i < saveData.length) {
                this.trackCoolingBlock(Useful.longFromInts(saveData[i++], saveData[i++]), saveData[i++]);
            }
            if (Configurator.DEBUG.enablePerformanceLogging)
                Pyroclasm.LOG.info("Loaded " + this.size + " cooling basalt blocks.");
        }
    }
}
