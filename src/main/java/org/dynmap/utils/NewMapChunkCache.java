package org.dynmap.utils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.block.Biome;
import org.bukkit.entity.Entity;
import org.bukkit.ChunkSnapshot;
import org.dynmap.DynmapChunk;
import org.dynmap.DynmapPlugin;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.MapManager;

/**
 * Container for managing chunks - dependent upon using chunk snapshots, since rendering is off server thread
 */
public class NewMapChunkCache implements MapChunkCache {
    private static boolean init = false;
    private static Method poppreservedchunk = null;
    private static Method getsnapshot2 = null;
    private static Method getemptysnapshot = null;
    
    private World w;
    private List<DynmapChunk> chunks;
    private ListIterator<DynmapChunk> iterator;
    private int x_min, x_max, z_min, z_max;
    private int x_dim;
    private boolean biome, biomeraw, highesty, blockdata;
    private HiddenChunkStyle hidestyle = HiddenChunkStyle.FILL_AIR;
    private List<VisibilityLimit> visible_limits = null;
    private DynmapWorld.AutoGenerateOption generateopt;
    private boolean do_generate = false;
    private boolean do_save = false;
    private boolean isempty = true;

    private ChunkSnapshot[] snaparray; /* Index = (x-x_min) + ((z-z_min)*x_dim) */
    
    /**
     * Iterator for traversing map chunk cache (base is for non-snapshot)
     */
    public class OurMapIterator implements MapIterator {
        private int x, y, z;  
        private ChunkSnapshot snap;

        OurMapIterator(int x0, int y0, int z0) {
            initialize(x0, y0, z0);
        }
        public final void initialize(int x0, int y0, int z0) {
            this.x = x0;
            this.y = y0;
            this.z = z0;
            try {
                snap = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
            } catch (ArrayIndexOutOfBoundsException aioobx) {
                snap = EMPTY;
            }
        }
        public final int getBlockTypeID() {
            return snap.getBlockTypeId(x & 0xF, y, z & 0xF);
        }
        public final int getBlockData() {
            return snap.getBlockData(x & 0xF, y, z & 0xF);
        }
        public final int getHighestBlockYAt() {
            return snap.getHighestBlockYAt(x & 0xF, z & 0xF);
        }
        public final int getBlockSkyLight() {
            return snap.getBlockSkyLight(x & 0xF, y, z & 0xF);
        }
        public final int getBlockEmittedLight() {
            return snap.getBlockEmittedLight(x & 0xF, y, z & 0xF);
        }
        public Biome getBiome() {
            return snap.getBiome(x & 0xF, z & 0xF);
        }
        public double getRawBiomeTemperature() {
            return snap.getRawBiomeTemperature(x & 0xf, z & 0xf);
        }
        public double getRawBiomeRainfall() {
            return snap.getRawBiomeRainfall(x & 0xf, z & 0xf);
        }
        public final void incrementX() {
            x++;
            if((x & 0xF) == 0) {  /* Next chunk? */
                try {
                    snap = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
                } catch (ArrayIndexOutOfBoundsException aioobx) {
                    snap = EMPTY;
                }
            }
        }
        public final void decrementX() {
            x--;
            if((x & 0xF) == 15) {  /* Next chunk? */
                try {
                    snap = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
                } catch (ArrayIndexOutOfBoundsException aioobx) {
                    snap = EMPTY;
                }
            }
        }
        public final void incrementY() {
            y++;
        }
        public final void decrementY() {
            y--;
        }
        public final void incrementZ() {
            z++;
            if((z & 0xF) == 0) {  /* Next chunk? */
                try {
                    snap = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
                } catch (ArrayIndexOutOfBoundsException aioobx) {
                    snap = EMPTY;
                }
            }
        }
        public final void decrementZ() {
            z--;
            if((z & 0xF) == 15) {  /* Next chunk? */
                try {
                    snap = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
                } catch (ArrayIndexOutOfBoundsException aioobx) {
                    snap = EMPTY;
                }
            }
        }
        public final void setY(int y) {
            this.y = y;
        }
        public final int getY() {
            return y;
        }
     }

    /**
     * Chunk cache for representing unloaded chunk (or air)
     */
    private static class EmptyChunk implements ChunkSnapshot {
        /* Need these for interface, but not used */
        public int getX() { return 0; }
        public int getZ() { return 0; }
        public String getWorldName() { return ""; }
        public long getCaptureFullTime() { return 0; }
        
        public final int getBlockTypeId(int x, int y, int z) {
            return 0;
        }
        public final int getBlockData(int x, int y, int z) {
            return 0;
        }
        public final int getBlockSkyLight(int x, int y, int z) {
            return 15;
        }
        public final int getBlockEmittedLight(int x, int y, int z) {
            return 0;
        }
        public final int getHighestBlockYAt(int x, int z) {
            return 0;
        }
        public Biome getBiome(int x, int z) {
            return null;
        }
        public double getRawBiomeTemperature(int x, int z) {
            return 0.0;
        }
        public double getRawBiomeRainfall(int x, int z) {
            return 0.0;
        }
    }

    /**
     * Chunk cache for representing generic stone chunk
     */
    private static class PlainChunk implements ChunkSnapshot {
        private int fillid;
        PlainChunk(int fillid) { this.fillid = fillid; }
        /* Need these for interface, but not used */
        public int getX() { return 0; }
        public int getZ() { return 0; }
        public String getWorldName() { return ""; }
        public Biome getBiome(int x, int z) { return null; }
        public double getRawBiomeTemperature(int x, int z) { return 0.0; }
        public double getRawBiomeRainfall(int x, int z) { return 0.0; }
        public long getCaptureFullTime() { return 0; }
        
        public final int getBlockTypeId(int x, int y, int z) {
            if(y < 64) return fillid;
            return 0;
        }
        public final int getBlockData(int x, int y, int z) {
            return 0;
        }
        public final int getBlockSkyLight(int x, int y, int z) {
            if(y < 64)
                return 0;
            return 15;
        }
        public final int getBlockEmittedLight(int x, int y, int z) {
            return 0;
        }
        public final int getHighestBlockYAt(int x, int z) {
            return 64;
        }
    }

    private static final EmptyChunk EMPTY = new EmptyChunk();
    private static final PlainChunk STONE = new PlainChunk(1);
    private static final PlainChunk OCEAN = new PlainChunk(9);

    /**
     * Construct empty cache
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public NewMapChunkCache() {
        if(!init) {
            /* Get CraftWorld.popPreservedChunk(x,z) - reduces memory bloat from map traversals (optional) */
            try {
                Class c = Class.forName("org.bukkit.craftbukkit.CraftWorld");
                poppreservedchunk = c.getDeclaredMethod("popPreservedChunk", new Class[] { int.class, int.class });
                /* getEmptyChunkSnapshot(int x, int z, boolean includeBiome, boolean includeBiomeTempRain) */
                getemptysnapshot = c.getDeclaredMethod("getEmptyChunkSnapshot", new Class[] { int.class, int.class,
                    boolean.class, boolean.class });
            } catch (ClassNotFoundException cnfx) {
            } catch (NoSuchMethodException nsmx) {
            }
            /* Get CraftChunk.getChunkSnapshot(boolean,boolean,boolean) */
            try {
                Class c = Class.forName("org.bukkit.craftbukkit.CraftChunk");
                getsnapshot2 = c.getDeclaredMethod("getChunkSnapshot", new Class[] { boolean.class, boolean.class, boolean.class });
            } catch (ClassNotFoundException cnfx) {
            } catch (NoSuchMethodException nsmx) {
            }
            if(getsnapshot2 != null)
                Log.info("Biome data support is enabled");
            else
                Log.info("Biome data support is disabled");
            init = true;
        }
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void setChunks(World w, List<DynmapChunk> chunks) {
        this.w = w;
        this.chunks = chunks;
        if(poppreservedchunk == null) {
            /* Get CraftWorld.popPreservedChunk(x,z) - reduces memory bloat from map traversals (optional) */
            try {
                Class c = Class.forName("org.bukkit.craftbukkit.CraftWorld");
                poppreservedchunk = c.getDeclaredMethod("popPreservedChunk", new Class[] { int.class, int.class });
            } catch (ClassNotFoundException cnfx) {
            } catch (NoSuchMethodException nsmx) {
            }
        }
        /* Compute range */
        if(chunks.size() == 0) {
            this.x_min = 0;
            this.x_max = 0;
            this.z_min = 0;
            this.z_max = 0;
            x_dim = 1;            
        }
        else {
            x_min = x_max = chunks.get(0).x;
            z_min = z_max = chunks.get(0).z;
            for(DynmapChunk c : chunks) {
                if(c.x > x_max)
                    x_max = c.x;
                if(c.x < x_min)
                    x_min = c.x;
                if(c.z > z_max)
                    z_max = c.z;
                if(c.z < z_min)
                    z_min = c.z;
            }
            x_dim = x_max - x_min + 1;            
        }
    
        snaparray = new ChunkSnapshot[x_dim * (z_max-z_min+1)];
    }
    
    public int loadChunks(int max_to_load) {
        int cnt = 0;
        if(iterator == null)
            iterator = chunks.listIterator();
        
        DynmapPlugin.setIgnoreChunkLoads(true);
        // Load the required chunks.
        while((cnt < max_to_load) && iterator.hasNext()) {
            DynmapChunk chunk = iterator.next();
            boolean vis = true;
            if(visible_limits != null) {
                vis = false;
                for(VisibilityLimit limit : visible_limits) {
                    if((chunk.x >= limit.x0) && (chunk.x <= limit.x1) && (chunk.z >= limit.z0) && (chunk.z <= limit.z1)) {
                        vis = true;
                        break;
                    }
                }
            }
            boolean wasLoaded = w.isChunkLoaded(chunk.x, chunk.z);
            boolean didload = w.loadChunk(chunk.x, chunk.z, false);
            boolean didgenerate = false;
            /* If we didn't load, and we're supposed to generate, do it */
            if((!didload) && do_generate && vis)
                didgenerate = didload = w.loadChunk(chunk.x, chunk.z, true);
            /* If it did load, make cache of it */
            if(didload) {
                ChunkSnapshot ss = null;
                if(!vis) {
                    if(hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN)
                        ss = STONE;
                    else if(hidestyle == HiddenChunkStyle.FILL_OCEAN)
                        ss = OCEAN;
                    else
                        ss = EMPTY;
                }
                else {
                    Chunk c = w.getChunkAt(chunk.x, chunk.z);
                    if(getsnapshot2 != null) {
                        try {
                            if(blockdata || highesty)
                                ss = (ChunkSnapshot)getsnapshot2.invoke(c, highesty, biome, biomeraw);
                            else
                                ss = (ChunkSnapshot)getemptysnapshot.invoke(w, chunk.x, chunk.z, biome, biomeraw);
                        } catch (InvocationTargetException itx) {
                        } catch (IllegalArgumentException e) {
                        } catch (IllegalAccessException e) {
                        }
                    }
                    else
                        ss = c.getChunkSnapshot();
                }
                snaparray[(chunk.x-x_min) + (chunk.z - z_min)*x_dim] = ss;
            }
            if ((!wasLoaded) && didload) {
                /* It looks like bukkit "leaks" entities - they don't get removed from the world-level table
                 * when chunks are unloaded but not saved - removing them seems to do the trick */
                if(!didgenerate) {
                    Chunk cc = w.getChunkAt(chunk.x, chunk.z);
                    if(cc != null) {
                        for(Entity e: cc.getEntities())
                            e.remove();
                    }
                }
                /* Since we only remember ones we loaded, and we're synchronous, no player has
                 * moved, so it must be safe (also prevent chunk leak, which appears to happen
                 * because isChunkInUse defined "in use" as being within 256 blocks of a player,
                 * while the actual in-use chunk area for a player where the chunks are managed
                 * by the MC base server is 21x21 (or about a 160 block radius).
                 * Also, if we did generate it, need to save it */
                w.unloadChunk(chunk.x, chunk.z, didgenerate && do_save, false);
                /* And pop preserved chunk - this is a bad leak in Bukkit for map traversals like us */
                try {
                    if(poppreservedchunk != null)
                        poppreservedchunk.invoke(w, chunk.x, chunk.z);
                } catch (Exception x) {
                    Log.severe("Cannot pop preserved chunk - " + x.toString());
                }
            }
            cnt++;
        }
        DynmapPlugin.setIgnoreChunkLoads(false);

        if(iterator.hasNext() == false) {   /* If we're done */
            isempty = true;
            /* Fill missing chunks with empty dummy chunk */
            for(int i = 0; i < snaparray.length; i++) {
                if(snaparray[i] == null)
                    snaparray[i] = EMPTY;
                else if(snaparray[i] != EMPTY)
                    isempty = false;
            }
        }
        return cnt;
    }
    /**
     * Test if done loading
     */
    public boolean isDoneLoading() {
        if(iterator != null)
            return !iterator.hasNext();
        return false;
    }
    /**
     * Test if all empty blocks
     */
    public boolean isEmpty() {
        return isempty;
    }
    /**
     * Unload chunks
     */
    public void unloadChunks() {
        if(snaparray != null) {
            for(int i = 0; i < snaparray.length; i++) {
                snaparray[i] = null;
            }
            snaparray = null;
        }
    }
    /**
     * Get block ID at coordinates
     */
    public int getBlockTypeID(int x, int y, int z) {
        ChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getBlockTypeId(x & 0xF, y, z & 0xF);
    }
    /**
     * Get block data at coordiates
     */
    public byte getBlockData(int x, int y, int z) {
        ChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return (byte)ss.getBlockData(x & 0xF, y, z & 0xF);
    }
    /* Get highest block Y
     * 
     */
    public int getHighestBlockYAt(int x, int z) {
        ChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getHighestBlockYAt(x & 0xF, z & 0xF);
    }
    /* Get sky light level
     */
    public int getBlockSkyLight(int x, int y, int z) {
        ChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getBlockSkyLight(x & 0xF, y, z & 0xF);
    }
    /* Get emitted light level
     */
    public int getBlockEmittedLight(int x, int y, int z) {
        ChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getBlockEmittedLight(x & 0xF, y, z & 0xF);
    }
    public Biome getBiome(int x, int z) {
        ChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getBiome(x & 0xF, z & 0xF);
    }
    public double getRawBiomeTemperature(int x, int z) {
        ChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getRawBiomeTemperature(x & 0xF, z & 0xF);
    }
    public double getRawBiomeRainfall(int x, int z) {
        ChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getRawBiomeRainfall(x & 0xF, z & 0xF);
    }

    /**
     * Get cache iterator
     */
    public MapIterator getIterator(int x, int y, int z) {
        return new OurMapIterator(x, y, z);
    }
    /**
     * Set hidden chunk style (default is FILL_AIR)
     */
    public void setHiddenFillStyle(HiddenChunkStyle style) {
        this.hidestyle = style;
    }
    /**
     * Set autogenerate - must be done after at least one visible range has been set
     */
    public void setAutoGenerateVisbileRanges(DynmapWorld.AutoGenerateOption generateopt) {
        if((generateopt != DynmapWorld.AutoGenerateOption.NONE) && ((visible_limits == null) || (visible_limits.size() == 0))) {
            Log.severe("Cannot setAutoGenerateVisibleRanges() without visible ranges defined");
            return;
        }
        this.generateopt = generateopt;
        this.do_generate = (generateopt != DynmapWorld.AutoGenerateOption.NONE);
        this.do_save = (generateopt == DynmapWorld.AutoGenerateOption.PERMANENT);
    }
    /**
     * Add visible area limit - can be called more than once 
     * Needs to be set before chunks are loaded
     * Coordinates are block coordinates
     */
    public void setVisibleRange(VisibilityLimit lim) {
        VisibilityLimit limit = new VisibilityLimit();
        if(lim.x0 > lim.x1) {
            limit.x0 = (lim.x1 >> 4); limit.x1 = ((lim.x0+15) >> 4);
        }
        else {
            limit.x0 = (lim.x0 >> 4); limit.x1 = ((lim.x1+15) >> 4);
        }
        if(lim.z0 > lim.z1) {
            limit.z0 = (lim.z1 >> 4); limit.z1 = ((lim.z0+15) >> 4);
        }
        else {
            limit.z0 = (lim.z0 >> 4); limit.z1 = ((lim.z1+15) >> 4);
        }
        if(visible_limits == null)
            visible_limits = new ArrayList<VisibilityLimit>();
        visible_limits.add(limit);
    }
    @Override
    public boolean setChunkDataTypes(boolean blockdata, boolean biome, boolean highestblocky, boolean rawbiome) {
        if((getsnapshot2 == null) && (biome || rawbiome))
            return false;
        this.biome = biome;
        this.biomeraw = rawbiome;
        this.highesty = highestblocky;
        this.blockdata = blockdata;
        return true;
    }
}
