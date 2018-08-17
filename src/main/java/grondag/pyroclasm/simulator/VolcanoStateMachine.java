package grondag.pyroclasm.simulator;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import grondag.exotic_matter.simulator.ISimulationTickable;
import grondag.exotic_matter.simulator.Simulator;
import grondag.exotic_matter.terrain.TerrainBlockHelper;
import grondag.exotic_matter.terrain.TerrainState;
import grondag.exotic_matter.varia.Useful;
import grondag.pyroclasm.Pyroclasm;
import grondag.pyroclasm.Configurator;
import grondag.pyroclasm.init.ModBlocks;
import grondag.pyroclasm.lava.EntityLavaBlob;
import grondag.pyroclasm.lava.LavaTerrainHelper;
import net.minecraft.block.material.EnumPushReaction;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;

/**
 * Encapsulates all the logic and state related to clearing the bore of
 * a volcano.  Meant to be called each tick when volcano is in clearing mode.<p>
 * 
 * Internal state is not persisted.  Simply restarts from bottom when world
 * is reloaded. <p>
 * 
 * Find spaces right above bedrock.
 * If spaces contain stone, push the stone up to
 * create at least a one-block space.<p>
 * 
 * Add 1 block of lava to each cell and wait for cells
 * to form.  Make sure each cell is marked as a bore cell.<p>
 * 
 * Find the max height of any bore cell.<p>
 * 
 * If lava level is below the current max,
 * add lava until we reach the max AND cells
 * hold pressure.  Periodically reconfirm the max.
 * The rate of lava addition is subject  to configured
 * flow constraints and cooling periods when load peaks.<p>
 * 
 * As lava rises in the bore, remove (melt) any
 * blocks inside the bore.  Note this may cause
 * the ceiling to rise or allow lava to flow out,
 * so need to periodically refresh the max ceiling
 * and wait until pressure can build before pushing
 * up more blocks or exploding.<p>
 * 
 * If the max ceiling is less than sky height (no blockage)
 * then it  is possible lava flow can become blocked.  If
 * all cells are filled to max (which is less than sky height)
 * and all cells are holding pressure then will either  build
 * mound by pushing up blocks or have an explosion.<p>
 * 
 * Explosion or pushing blocks depends on the volcano structure.
 * If there is a weak point anywhere along the bore, the bore
 * will explode outward, with preference given to the top of the bore.
 * If no weak points are found, will push blocks up around the bore<p>
 * 
 * If max ceiling is sky height, will simply continue to add lava until goes
 * dormant, lava level reaches sky height (which will force dormancy),
 * or blockage does happen somehow.  
 * 
 */
public class VolcanoStateMachine implements ISimulationTickable
{
    private static enum Operation
    {
        /**
         * Removes vanilla lava in bore and converts nearby vanilla lava to obsian. 
         * Mostly for aesthetic reasons.
         * Transitions to {@link #SETUP_CLEAR_AND_FILL}.
         */
        SETUP_CONVERT_LAVA,
        
        /**
         * Ensures bottom of bore is lava.
         * Transitions to {@link #SETUP_WAIT_FOR_CELLS_0}.
         */
        SETUP_CLEAR_AND_FILL,
        
        
        /**
         * After initial lava placement, wait a couple ticks for simulator to catch up and generate cells
         */
        SETUP_WAIT_FOR_CELLS_0,
        SETUP_WAIT_FOR_CELLS_1,
        
        /**
         * Populates the list of bore cells and ensures they are all
         * set to non-cooling. When complete, followed by {@link #UPDATE_BORE_LIMITS}.
         */
        SETUP_FIND_CELLS,
        
        /**
         * Iterate through bore cells and determine the current
         * min/max ceilings of bore cells.  Transitions to {@link #FLOW} when complete.
         */
        UPDATE_BORE_LIMITS,
        
        /**
         * Add lava to bore cells, at the configure rate of max flow.
         * Will  continue to flow until one of two things happens..
         * 
         * 1) Lava rises to the level of min ceiling and remains there, 
         * in which case will switch to {@link #MELT_CHECK}.
         * 
         * 2) All cells remain full through a complete pass and no room
         * remains for more lava, in which case it transition to {@link #FIND_WEAKNESS}.
         */
        FLOW,
        
        /**
         * Looks for any solid bore cells above the current lava level
         * but within the current max and turns them into lava.  
         * Transitions to {@link #UPDATE_BORE_LIMITS} when done.
         */
        MELT_CHECK,
        
        
        /**
         * Happens after bore found to be holding pressure, looks
         * for any weak points along the bore.  If found, transitions
         * to {@link #EXPLODE}, otherwise transitions to {@link #PUSH_BLOCKS}.
         */
        FIND_WEAKNESS,
        
        /**
         * Orchestrates an explosion at the weak point found during
         * {@link #FIND_WEAKNESS} and then transitions to {@link #UPDATE_BORE_LIMITS}.
         */
        EXPLODE,
        
        /**
         * Pushes all bore cells up one block and then transitions to
         * {@link #UPDATE_BORE_LIMITS}.
         */
        PUSH_BLOCKS
    }
    
    private static final int BORE_RADIUS = 5;
    
    private static final int MAX_BORE_OFFSET = Useful.getLastDistanceSortedOffsetIndex(BORE_RADIUS);
    
    private static final int MAX_CONVERSION_OFFSET = Useful.getLastDistanceSortedOffsetIndex(BORE_RADIUS + 6);
    
    private static final int MAX_CONVERSION_Y = 70;
    
    @SuppressWarnings("unused")
    private final VolcanoNode volcano;
    
    private final LavaSimulator lavaSim;
    
    private int lavaRemainingThisPass = 0;
    
    private final World world;
    
    private final BlockPos center;
    
    private Operation operation = Operation.SETUP_CONVERT_LAVA;
    
    private double blobChance = 0;
    
    private final Random myRandom = new Random();
    
    /**
     * List of bore cells at volcano floor, from center out. Will be
     * empty until {@link #setupFind()} has fully finished.
     */
    private LavaCell[] boreCells = new LavaCell[MAX_BORE_OFFSET];
    
    /** 
     * Position within {@link Useful#DISTANCE_SORTED_CIRCULAR_OFFSETS} list for operations
     * that iterate that collection over more than one tick.
     */
    private int  offsetIndex = 0;
    
    /**
     * For operations that must retain a y level as part of state, the current y level.
     */
    private int y  = 0;

    /**
     * Max ceiling level of any bore cell.
     * Essentially used to know if chamber is closed and full of lava.
     * Any blocks within the bore below this level 
     * will be melted instead of pushed or exploded out.
     */
    private int maxCeilingLevel;
    
    /**
     * For use only in operation methods, which do not call into each other and are not re-entrant.
     */
    private final MutableBlockPos operationPos = new MutableBlockPos();
    
    @SuppressWarnings("null")
    public VolcanoStateMachine(VolcanoNode volcano)
    {
        this.volcano = volcano;
        this.world = volcano.volcanoManager.world;
        this.lavaSim = Simulator.instance().getNode(LavaSimulator.class);
        this.center = volcano.blockPos();
    }

    //TODO: make confiurable
    private final static int OPERATIONS_PER_TICK = 64;
    
    @Override
    public void doOnTick()
    {
        this.lavaRemainingThisPass = Configurator.VOLCANO.lavaBlocksPerSecond * LavaSimulator.FLUID_UNITS_PER_BLOCK / 20;
        
        for(int i = 0; i < OPERATIONS_PER_TICK; i++)
        {
            switch(this.operation)
            {
                case SETUP_CONVERT_LAVA:
                    this.operation = convertLava();
                    break;
                    
                case SETUP_CLEAR_AND_FILL:
                    this.operation = setupClear();
                    if(this.operation == Operation.SETUP_WAIT_FOR_CELLS_0) return;
                    break;
                    
                case SETUP_WAIT_FOR_CELLS_0:
                    this.operation = Operation.SETUP_WAIT_FOR_CELLS_1;
                    return;
                    
                case SETUP_WAIT_FOR_CELLS_1:
                    this.operation = Operation.SETUP_FIND_CELLS;
                    return;
                    
                case SETUP_FIND_CELLS:
                    this.operation = setupFind();
                    break;
                
                case UPDATE_BORE_LIMITS:
                    this.operation = updateBoreLimits();
                    break;

                case FLOW:
                    this.operation = flow();
                    break;

                case MELT_CHECK:
                    this.operation = meltCheck();
                    break;
                    
                case FIND_WEAKNESS:
                    this.operation = findWeakness();
                    break;
                    
                case EXPLODE:
                    this.operation = explode();
                    break;

                case PUSH_BLOCKS:
                    this.operation = pushBlocks();
                    break;
                    
                default:
                    assert false : "Invalid volcano state";
                    break;
            }
        }
    }

    private MutableBlockPos setBorePosForLevel(MutableBlockPos pos, int index, int yLevel)
    {
        Vec3i offset = Useful.DISTANCE_SORTED_CIRCULAR_OFFSETS[index];
        pos.setPos(offset.getX() + this.center.getX(), yLevel, offset.getZ() + this.center.getZ());
        return pos;
    }
    
    /**
     * Call each tick (on-tick, not  off-tick.)
     * Does some work to clear the bore.  If bore is clear
     * and lava should begin flowing returns true. If more
     * work remains and clearing should continue next tick returns false.
     */
    private Operation setupClear()
    {
        
        // handle any kind of improper clean up or initialization
        if(this.offsetIndex >= MAX_BORE_OFFSET) 
        {
            assert false : "Improper initialization or cleanup in volcano state machine. Restarting.";
            offsetIndex = 0;
        }
      
        final MutableBlockPos pos = this.setBorePosForLevel(operationPos, offsetIndex++, 0);
        
        if(world.getBlockState(pos).getBlock() == ModBlocks.lava_dynamic_height)
        {
            @Nullable LavaCell cell = lavaSim.cells.getCellIfExists(pos);
            if(cell == null)
            {
                lavaSim.registerPlacedLava(world, pos, world.getBlockState(pos));
            }
        }
        else
        {
            world.setBlockState(pos.toImmutable(), TerrainBlockHelper.stateWithDiscreteFlowHeight(ModBlocks.lava_dynamic_height.getDefaultState(), TerrainState.BLOCK_LEVELS_INT));
        }
         
        if(this.offsetIndex >= MAX_BORE_OFFSET)
        {
            // if we've gone past the last offset, can go to next stage
            offsetIndex = 0;
            return Operation.SETUP_WAIT_FOR_CELLS_0;
            
        }
        else
        {
            return Operation.SETUP_CLEAR_AND_FILL;
        }
                
    }
    
    /**
     * Used only in {@link #getBoreCell(int)}
     */
    private MutableBlockPos getBoreCellPos = new MutableBlockPos();
    
    private @Nullable LavaCell getBoreCell(int index)
    {
        LavaCell result = this.boreCells[index];
        
        if(result == null || result.isDeleted())
        {
            final MutableBlockPos pos = this.setBorePosForLevel(this.getBoreCellPos, index, 0);
            result = lavaSim.cells.getCellIfExists(pos);
            if(result != null) result.setCoolingDisabled(true);
            this.boreCells[index] = result;
        }
        
        return result;
    }
    
    private Operation setupFind()
    {
        
        if(this.offsetIndex >= MAX_BORE_OFFSET) 
        {
            assert false : "Improper initialization or cleanup in volcano state machine. Restarting.";
            offsetIndex = 0;
        }
      
        @Nullable LavaCell cell = getBoreCell(offsetIndex++);

        if(cell == null)
        {
            Pyroclasm.INSTANCE.warn("Unable to find lava cell for volcano bore when expected.  Reverting to initial setup.");
            this.offsetIndex = 0;
            return Operation.SETUP_CLEAR_AND_FILL;
        }
        
        
        if(this.offsetIndex >= MAX_BORE_OFFSET)
        {
            // if we've gone past the last offset, can go to next stage
            offsetIndex = 0;

            return Operation.UPDATE_BORE_LIMITS;
        }
        else
        {
            return Operation.SETUP_FIND_CELLS;
        }
        
    }
    
    private Operation updateBoreLimits()
    {
        if(this.offsetIndex >= MAX_BORE_OFFSET) 
        {
            assert false : "Improper initialization or cleanup in volcano state machine. Restarting.";
            offsetIndex = 0;
        }
        
        LavaCell cell = this.getBoreCell(offsetIndex);
        
        if(cell == null)
        {
            Pyroclasm.INSTANCE.warn("Volcano bore cell missing, Returning to setup");
            this.offsetIndex = 0;
            return Operation.SETUP_CLEAR_AND_FILL;
        }
        
        int l = cell.ceilingLevel();
        if(offsetIndex++ == 0)
        {
            this.maxCeilingLevel = l;
            this.blobChance = cell.isOpenToSky() ? 1.0 : 0;
        }
        else 
        {
            if(l > this.maxCeilingLevel) this.maxCeilingLevel = l;
        }
      
        if(this.offsetIndex >= MAX_BORE_OFFSET)
        {
            // if we've gone past the last offset, can go to next stage
            offsetIndex = 0;
            
            Pyroclasm.INSTANCE.info("Switching from %s to %s", this.operation.toString(), Operation.FLOW.toString());

            return Operation.FLOW;
        }
        else
        {
            return Operation.UPDATE_BORE_LIMITS;
        }        
    }

    private Operation flow()
    {
        if(this.offsetIndex >= MAX_BORE_OFFSET) 
        {
            assert false : "Improper initialization or cleanup in volcano state machine. Restarting.";
            offsetIndex = 0;
        }

        
        if(lavaRemainingThisPass > 0)
            doBlobs();
        
        LavaCell cell = this.getBoreCell(offsetIndex++);
        
        if(cell == null)
        {
            Pyroclasm.INSTANCE.warn("Volcano bore cell missing, Returning to setup");
            this.offsetIndex = 0;
            return Operation.SETUP_CLEAR_AND_FILL;
        }
        
        if(cell.worldSurfaceLevel() < cell.ceilingLevel())
        {
            // cell has room, add lava if available
            if(lavaRemainingThisPass > 0)
            {
                final int amount = Math.min(LavaSimulator.FLUID_UNITS_PER_LEVEL, lavaRemainingThisPass);
                this.lavaRemainingThisPass -= amount;
                cell.addLava(amount);
            }
        }
        else if(cell.ceilingLevel() < this.maxCeilingLevel)
        {
            // check for melting
            // if cell is full and ceiling is less than the max of the
            // current chamber then should check for block melting to
            // open up the chamber
            
            // confirm barrier actually exists and mark cell for revalidation if not
            IBlockState blockState = lavaSim.world.getBlockState(this.operationPos.setPos(cell.x(), cell.ceilingY() + 1, cell.z()));
            if(LavaTerrainHelper.canLavaDisplace(blockState))
            {
                cell.setValidationNeeded(true);
                
                //FIXME: remove
                Pyroclasm.INSTANCE.info("found block %s in bore @ %d, %d, %d that wasn't part of cell.  Cells not getting updated when bore is cleared.", 
                        blockState.toString(), cell.x(), cell.ceilingY() + 1, cell.z() );
            }
            else
            {
                offsetIndex = 0;
                return Operation.MELT_CHECK;
            }
        }
        
      
        if(this.offsetIndex >= MAX_BORE_OFFSET)
        {
            // if we've gone past the last offset, can go to next stage
            offsetIndex = 0;

            // if used up all the lava, continue flowing, otherwise too constrained - mound or explode
            return this.lavaRemainingThisPass == 0 ? Operation.FLOW : Operation.FIND_WEAKNESS;
        }
        else
        {
            return Operation.FLOW;
        }   
    }
    
    private void doBlobs()
    {
        final LavaCell center = getBoreCell(0);
        if(center == null || !center.isOpenToSky() || center.worldSurfaceY() < 64)
        {
            return;
        }
        
        final Random r = myRandom;
        final int blobCount = r.nextInt(4) + r.nextInt(4) + r.nextInt(4);
        if(EntityLavaBlob.getLiveParticleCount() + blobCount <= Configurator.VOLCANO.maxLavaEntities && Math.abs(r.nextDouble()) < blobChance)
        {
            for(int i = 0; i < blobCount; i++)
            {
                final double dx = (r.nextDouble() - 0.5) * 2;
                final double dz = (r.nextDouble() - 0.5) * 2;
                final int units = Math.max(LavaSimulator.FLUID_UNITS_PER_HALF_BLOCK, 
                        r.nextInt(LavaSimulator.FLUID_UNITS_PER_BLOCK) 
                        + r.nextInt(LavaSimulator.FLUID_UNITS_PER_BLOCK) 
                        + r.nextInt(LavaSimulator.FLUID_UNITS_PER_BLOCK));
                EntityLavaBlob blob = new EntityLavaBlob(
                        this.world, 
                        units, 
                        center.x(), 
                        center.worldSurfaceY() + 1,
                        center.z(), 
                        dx,
                        Math.max(.75, r.nextGaussian() * 0.1 + 1),
                        dz);
                this.world.spawnEntity(blob);
                this.lavaRemainingThisPass -= units;
            }
            blobChance = 0;
        }
        else
            blobChance  += 0.01;
    }
  
    private Operation meltCheck()
    {
        if(this.offsetIndex >= MAX_BORE_OFFSET) 
        {
            assert false : "Improper initialization or cleanup in volcano state machine. Restarting.";
            offsetIndex = 0;
        }

        LavaCell cell = this.getBoreCell(offsetIndex++);
        
        if(cell == null)
        {
            Pyroclasm.INSTANCE.warn("Volcano bore cell missing, Returning to setup");
            this.offsetIndex = 0;
            return Operation.SETUP_CLEAR_AND_FILL;
        }
        
        if(cell.ceilingLevel() < this.maxCeilingLevel && cell.worldSurfaceLevel() == cell.ceilingLevel())
        {
            MutableBlockPos pos = this.operationPos.setPos(cell.x(), cell.ceilingY() + 1, cell.z());
            IBlockState priorState = this.lavaSim.world.getBlockState(pos);
            if(!LavaTerrainHelper.canLavaDisplace(priorState))
            {
                if(this.maxCeilingLevel < LavaSimulator.LEVELS_PER_BLOCK * 255)
                {
                    this.pushBlock(pos);
                }
                else
                {
                    this.lavaSim.world.setBlockState(pos.toImmutable(), Blocks.AIR.getDefaultState());
                }
            }
            cell.setValidationNeeded(true);
        }
        
      
        if(this.offsetIndex >= MAX_BORE_OFFSET)
        {
            // Update bore limits because block that was melted might
            // have opened up cell to a height above the current max.
            offsetIndex = 0;
            
            //FIXME: remove
            Pyroclasm.INSTANCE.info("Switching from %s to %s", this.operation.toString(), Operation.UPDATE_BORE_LIMITS.toString());
            
            return Operation.UPDATE_BORE_LIMITS;
        }
        else
        {
            return Operation.MELT_CHECK;
        }   
    }


    private Operation findWeakness()
    {
        return Operation.PUSH_BLOCKS;
    }

    private Operation explode()
    {
        return Operation.EXPLODE;
    }

    private Operation pushBlocks()
    {
        if(this.offsetIndex >= MAX_BORE_OFFSET) 
        {
            assert false : "Improper initialization or cleanup in volcano state machine. Restarting.";
            offsetIndex = 0;
        }

        LavaCell cell = this.getBoreCell(offsetIndex++);
        
        if(cell == null)
        {
            Pyroclasm.INSTANCE.warn("Volcano bore cell missing, Returning to setup");
            this.offsetIndex = 0;
            return Operation.SETUP_CLEAR_AND_FILL;
        }
        
        if(this.pushBlock(this.operationPos.setPos(cell.x(), cell.ceilingY() + 1, cell.z())))
            cell.setValidationNeeded(true);
      
        if(this.offsetIndex >= MAX_BORE_OFFSET)
        {
            offsetIndex = 0;
            
            Pyroclasm.INSTANCE.info("Switching from %s to %s", this.operation.toString(), Operation.UPDATE_BORE_LIMITS.toString());
            
            return Operation.UPDATE_BORE_LIMITS;
        }
        else
        {
            return Operation.PUSH_BLOCKS;
        }   
    }
    
    /**
     * If block at location is not lava, pushes it out of bore.
     * Assumes given position is in the bore!
     * Return true if a push happened and cell should be revalidated.
     */
    private boolean pushBlock(MutableBlockPos fromPos)
    {
        IBlockState fromState = this.world.getBlockState(fromPos);
        
        // nothing to do
        if(fromState.getBlock() == ModBlocks.lava_dynamic_height || fromState.getBlock() == ModBlocks.lava_dynamic_filler) return false;
        
        IBlockState toState = null;
        
        if(LavaTerrainHelper.canLavaDisplace(fromState))
        {
            ;
        }
        else if(fromState.getBlock().hasTileEntity(fromState) )
        {
            ;
        }
        else if(fromState.getBlock() == Blocks.BEDROCK)
        {
            toState = Blocks.STONE.getDefaultState();
        }
        else if(fromState.getBlockHardness(world, fromPos) == -1.0F)
        {
            ;
        }
        else if(fromState.getMobilityFlag() == EnumPushReaction.NORMAL || fromState.getMobilityFlag() == EnumPushReaction.PUSH_ONLY)
        {
            toState = fromState;
        }
        
        this.world.setBlockState(fromPos.toImmutable(), Blocks.AIR.getDefaultState());
        
        if(toState != null)
        {
            this.lavaSim.world.setBlockState(findMoundSpot(), toState);
        }
        
        return true;
    }

    /**
     * For use only in {@link #findMoundSpot()}
     */
    private final MutableBlockPos findPos = new MutableBlockPos();
    
    private BlockPos findMoundSpot()
    {
        int lowest = 255;
        
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // should give us the distance from origin for a sample from a bivariate normal distribution
        // probably a more elegant way to do it, but whatever
        double dx = r.nextGaussian() * Configurator.VOLCANO.moundRadius;
        double dz = r.nextGaussian() * Configurator.VOLCANO.moundRadius;
        int distance = (int) Math.sqrt(dx * dx + dz * dz);
        
        int bestX = 0;
        int bestZ = 0;
        
        final World world = this.lavaSim.world;
        
        // find lowest point at the given distance
        // intended to fill in low areas before high areas but still keep normal mound shape
        for(int i = 0; i <= 20; i++)
        {
            double angle = 2 * Math.PI * r.nextDouble();
            int x = (int) Math.round(this.center.getX() + distance * Math.cos(angle));
            int z = (int) Math.round(this.center.getZ() + distance * Math.sin(angle));
            int y = this.world.getHeight(x, z);
            
            while(y > 0 && LavaTerrainHelper.canLavaDisplace(world.getBlockState(findPos.setPos(x, y - 1, z))))
            {
                y--;
            }
            
            if(y < lowest)
            {
               lowest = y;
               bestX = x;
               bestZ = z;
            }
        }
        
        MutableBlockPos best = new MutableBlockPos(bestX, lowest, bestZ);
        
        // found the general location, now nudge to directly nearby blocks if any are lower
        for(int i = 1; i < 9; i++)
        {
            int x = best.getX() + Useful.getDistanceSortedCircularOffset(i).getX();
            int z = best.getZ() + Useful.getDistanceSortedCircularOffset(i).getZ();
            int y = this.world.getHeight(x, z);
            
            while(y > 0 && LavaTerrainHelper.canLavaDisplace(world.getBlockState(findPos.setPos(x, y - 1, z))))
            {
                y--;
            }
            
            if(y < best.getY())
            {
                best.setPos(x, y, z);
            }
        }
        
        return best.toImmutable();
    }
  
    /**
     * Removes vanilla lava inside bore and converts lava around it to obsidian.
     */
    private Operation convertLava()
    {
        // handle any kind of improper clean up or initialization
        if(this.offsetIndex >= MAX_CONVERSION_OFFSET) 
        {
            assert false : "Improper initialization or cleanup in volcano state machine. Restarting.";
            offsetIndex = 0;
        }
        
        if(this.y >= MAX_CONVERSION_Y) 
        {
            assert false : "Improper initialization or cleanup in volcano state machine. Restarting.";
            y = 0;
        }
      
        final MutableBlockPos pos = this.setBorePosForLevel(this.operationPos, offsetIndex, y);
        
        if(world.getBlockState(pos).getMaterial() == Material.LAVA)
        {
            if(offsetIndex < MAX_BORE_OFFSET)
                world.setBlockToAir(pos.toImmutable());
            else
                world.setBlockState(pos.toImmutable(), Blocks.OBSIDIAN.getDefaultState());
        }
         
        
        if(++offsetIndex >= MAX_CONVERSION_OFFSET)
        {
            // if we've gone past the last offset, can go to next stage
            offsetIndex = 0;
            
            if(++y < MAX_CONVERSION_Y)
            {
                return Operation.SETUP_CONVERT_LAVA;
            }
            else
            {
                this.y = 0;
                return Operation.SETUP_CLEAR_AND_FILL;
            }
        }
        else
        {
            return Operation.SETUP_CONVERT_LAVA;
        }
    }
}