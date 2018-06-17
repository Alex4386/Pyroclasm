package grondag.big_volcano.init;


import static grondag.exotic_matter.model.texture.TextureRotationType.*;
import static grondag.exotic_matter.world.Rotation.*;

import grondag.big_volcano.BigActiveVolcano;
import grondag.exotic_matter.model.texture.ITexturePalette;
import grondag.exotic_matter.model.texture.TextureGroup;
import grondag.exotic_matter.model.texture.TextureLayout;
import grondag.exotic_matter.model.texture.TexturePaletteRegistry;
import grondag.exotic_matter.model.texture.TexturePaletteSpec;
import grondag.exotic_matter.model.texture.TextureRenderIntent;
import grondag.exotic_matter.model.texture.TextureScale;

public class ModTextures
{
    //======================================================================
    //  VOLCANO
    //======================================================================
    
    public static final ITexturePalette BIGTEX_BASALT_CUT = TexturePaletteRegistry.addTexturePallette("basalt_cut", "basalt_cut", 
            new TexturePaletteSpec(BigActiveVolcano.INSTANCE).withVersionCount(1).withScale(TextureScale.MEDIUM).withLayout(TextureLayout.SIMPLE)
            .withRotation(CONSISTENT.with(ROTATE_NONE)).withRenderIntent(TextureRenderIntent.BASE_ONLY).withGroups(TextureGroup.STATIC_TILES));
    public static final ITexturePalette BIGTEX_BASALT_CUT_ZOOM = TexturePaletteRegistry.addZoomedPallete(BIGTEX_BASALT_CUT);
    public static final ITexturePalette BIGTEX_BASALT_CUT_ZOOM_X2 = TexturePaletteRegistry.addZoomedPallete(BIGTEX_BASALT_CUT_ZOOM);
    
    public static final ITexturePalette BIGTEX_BASALT_COOL = TexturePaletteRegistry.addTexturePallette("basalt_cool", "basalt_cool", new TexturePaletteSpec(BIGTEX_BASALT_CUT));
    public static final ITexturePalette BIGTEX_BASALT_COOL_ZOOM = TexturePaletteRegistry.addZoomedPallete(BIGTEX_BASALT_COOL);
    public static final ITexturePalette BIGTEX_BASALT_COOL_ZOOM_X2 = TexturePaletteRegistry.addZoomedPallete(BIGTEX_BASALT_COOL_ZOOM);

    public static final ITexturePalette BIGTEX_BASALT_COOLING = TexturePaletteRegistry.addTexturePallette("basalt_cooling", "basalt_cooling", 
             new TexturePaletteSpec(BigActiveVolcano.INSTANCE).withVersionCount(1).withScale(TextureScale.LARGE).withLayout(TextureLayout.SIMPLE)
             .withRotation(CONSISTENT.with(ROTATE_NONE)).withRenderIntent(TextureRenderIntent.OVERLAY_ONLY).withGroups(TextureGroup.STATIC_DETAILS));
    public static final ITexturePalette BIGTEX_BASALT_WARM = TexturePaletteRegistry.addTexturePallette("basalt_warm", "basalt_warm",  new TexturePaletteSpec(BIGTEX_BASALT_COOLING));
    public static final ITexturePalette BIGTEX_BASALT_HOT = TexturePaletteRegistry.addTexturePallette("basalt_hot", "basalt_hot", new TexturePaletteSpec(BIGTEX_BASALT_COOLING));
    public static final ITexturePalette BIGTEX_BASALT_VERY_HOT = TexturePaletteRegistry.addTexturePallette("basalt_very_hot", "basalt_very_hot", new TexturePaletteSpec(BIGTEX_BASALT_COOLING));

    public static final ITexturePalette LAVA_QUADRANTS = TexturePaletteRegistry.addTexturePallette("lava_quadrant", "lava_quadrant", 
            new TexturePaletteSpec(BigActiveVolcano.INSTANCE)
            .withVersionCount(16)
            .withScale(TextureScale.SINGLE)
            .withLayout(TextureLayout.LAVA_CONNECTED)
            .withRenderIntent(TextureRenderIntent.OVERLAY_ONLY)
            .withRenderNoBorderAsTile(true)
            .withGroups(TextureGroup.STATIC_BORDERS));
    
    public static final ITexturePalette LAVA_TILE = TexturePaletteRegistry.addTexturePallette("lava", "lava", 
            new TexturePaletteSpec(BigActiveVolcano.INSTANCE)
            .withVersionCount(2)
            .withScale(TextureScale.SINGLE)
            .withLayout(TextureLayout.SIMPLE)
            .withRotation(RANDOM.with(ROTATE_NONE))
            .withRenderIntent(TextureRenderIntent.BASE_OR_OVERLAY_CUTOUT_OKAY)
            .withGroups(TextureGroup.STATIC_TILES));

}
