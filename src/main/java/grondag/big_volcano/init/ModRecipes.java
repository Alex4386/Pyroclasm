package grondag.big_volcano.init;

import grondag.big_volcano.BigActiveVolcano;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

public class ModRecipes
{
    public static void init(FMLInitializationEvent event) 
    {
        // smelt cobble to smooth basalt
        GameRegistry.addSmelting(ModBlocks.basalt_cobble, new ItemStack(ModItems.basalt_cut, 1, 0), 0.1F);
        
        BigActiveVolcano.INSTANCE.addRecipe(new ItemStack(ModItems.basalt_cobble), 0, "AAAAAAAAA", "basalt_rubble");
    }
}
