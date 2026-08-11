package com.slopeconnector.surface;

import com.slopeconnector.connected.ConnectedArcMod;
import com.slopeconnector.model.ModelBlockEntity;
import com.slopeconnector.model.ModelSystemMod;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connection-state bridge for vanilla and modded connected blocks.
 *
 * <p>The important rule in 0.9.25 is: when a skinned ModelBlock visually represents another block,
 * neighboring blocks are allowed to run <em>their own native neighbour-update method</em> against
 * that represented state. This is what vanilla Fence/Pane/Wall and Conquest Reforged's Pane,
 * FenceLayered, WallNew/WallOld etc. expect. Property forcing remains only as a fallback for simple
 * data-driven states.</p>
 */
public final class ConnectionStateHelper {
    private static volatile Method supportedMethod;
    private static final Map<Block, Boolean> SUPPORTED_CACHE = new ConcurrentHashMap<>();

    private ConnectionStateHelper() {}

    public static void forceWorldConnection(ServerWorld world, BlockPos pos, Direction direction) {
        if (direction.getAxis().isVertical()) return;
        BlockState state = world.getBlockState(pos);
        BlockState connected = forceConnection(state, direction);
        if (!connected.equals(state)) world.setBlockState(pos, connected, 3);
    }

    public static BlockState forceConnection(BlockState state, Direction direction) {
        if (state == null || direction.getAxis().isVertical() || !isSupported(state)) return state;
        String name = direction.getName();
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equalsIgnoreCase(name)) continue;
            return setConnected(state, property);
        }
        // Facing/axis are orientation properties, not connection arms.  Conquest Railings and
        // Balustrade use them to select their model direction; treating them as a N/E/S/W arm made
        // the endpoint rotate every time an external railing was added.  Endpoint alignment is
        // handled once by ModelStateResolver after native neighbour updates have run.
        return state;
    }

    public static boolean shouldForce(BlockState current, BlockState neighbor) {
        if (!isSupported(current)) return false;
        if (neighbor.getBlock() == ConnectedArcMod.CONNECTED_ARC) return true;
        if (!isSupported(neighbor)) return false;
        String a = family(current);
        String b = family(neighbor);
        return a.equals(b) || current.getBlock() == neighbor.getBlock();
    }

    public static boolean isSupported(BlockState state) {
        if (state == null || state.isAir()) return false;
        Block block = state.getBlock();
        Boolean cached = SUPPORTED_CACHE.get(block);
        if (cached != null) return cached;
        boolean result;
        if (block instanceof FenceBlock || block instanceof PaneBlock || block instanceof WallBlock) {
            result = true;
        } else {
            String id = Registries.BLOCK.getId(block).toString().toLowerCase(Locale.ROOT);
            String className = block.getClass().getName().toLowerCase(Locale.ROOT);
            boolean keyword = containsAny(id + " " + className,
                    "fence", "railing", "railings", "balustrade", "baluster",
                    "bars", "pane", "wall", "guardrail", "lattice");
            if (!keyword) {
                result = false;
            } else if (hasConnectionOrOrientationProperty(state)) {
                // Conquest Balustrade is a PillarBlock (axis), Railings is horizontal-facing,
                // WallNew uses custom directional wall-shape enums, and Pane/FenceLayered inherit
                // native connection booleans. All of them are valid model-connection families.
                result = true;
            } else {
                try {
                    Method method = supportedMethod;
                    if (method == null) {
                        Class<?> type = Class.forName("com.slopeconnector.connected.ConnectedBlockClassifier");
                        method = type.getDeclaredMethod("isSupported", BlockState.class);
                        method.setAccessible(true);
                        supportedMethod = method;
                    }
                    result = (boolean) method.invoke(null, state);
                } catch (ReflectiveOperationException ignored) {
                    result = true;
                }
            }
        }
        SUPPORTED_CACHE.put(block, result);
        return result;
    }

    public static String family(BlockState state) {
        String id = Registries.BLOCK.getId(state.getBlock()).toString().toLowerCase(Locale.ROOT);
        String className = state.getBlock().getClass().getName().toLowerCase(Locale.ROOT);
        String text = id + " " + className;
        if (state.getBlock() instanceof FenceBlock || containsAny(text, "fence", "railing", "railings", "guardrail")) return "fence";
        if (containsAny(text, "balustrade", "baluster")) return "balustrade";
        if (state.getBlock() instanceof PaneBlock || containsAny(text, "pane", "bars", "lattice")) return "pane";
        if (state.getBlock() instanceof WallBlock || text.contains("wall")) return "wall";
        return id;
    }

    /** Returns true when two states belong to the same connection family. */
    public static boolean sameFamily(BlockState first, BlockState second) {
        if (!isSupported(first) || !isSupported(second)) return false;
        return first.getBlock() == second.getBlock() || family(first).equals(family(second));
    }

    /**
     * Creates a deterministic X-running source module for deformation. Native N/E/S/W families get
     * east+west, while axis/facing-only Conquest models are aligned to the same X longitudinal axis.
     */
    public static BlockState straightState(BlockState state) {
        if (!isSupported(state)) return state;
        BlockState result = state;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            switch (name) {
                case "east", "west" -> result = setConnected(result, property);
                case "north", "south" -> result = setDisconnected(result, property);
                case "up", "post" -> result = setDisconnected(result, property);
                case "axis", "horizontal_axis" -> result = setParsedOrSame(result, property, "x");
                case "facing", "horizontal_facing" -> result = setParsedOrSame(result, property, "east");
                default -> { }
            }
        }
        return result;
    }

    /**
     * Builds an endpoint state using the represented block's own neighbour-update implementation.
     * This is the critical compatibility path for Conquest Reforged: the code in its actual Fence,
     * Pane/FenceLayered, WallNew/WallOld classes gets to decide its own connection shape.
     */
    public static BlockState endpointState(BlockState captured, Direction arcDirection,
                                           BlockView world, BlockPos pos) {
        if (!isSupported(captured)) return captured;
        BlockState result = captured;
        if (world instanceof WorldAccess access) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos neighborPos = pos.offset(direction);
                BlockState neighbor;
                if (arcDirection != null && direction == arcDirection) {
                    // The custom middle arc is not a real instance of the captured block. Present a
                    // compatible straight source state to native canConnect/shouldConnect logic.
                    neighbor = straightState(captured);
                } else {
                    neighbor = representedNeighbor(world, neighborPos);
                }
                try {
                    result = result.getStateForNeighborUpdate(direction, neighbor, access, pos, neighborPos);
                } catch (RuntimeException ignored) {
                    // Data-driven fallback below still handles straightforward boolean/enum states.
                }
            }
        }

        if (arcDirection != null && !arcDirection.getAxis().isVertical()) {
            result = forceConnection(result, arcDirection);
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (arcDirection != null && direction == arcDirection) continue;
            BlockState neighbor = representedNeighbor(world, pos.offset(direction));
            if (sameFamily(captured, neighbor)) result = forceConnection(result, direction);
        }
        return result;
    }

    /** State an ordinary block should see when its neighbour position is a skinned ModelBlock. */
    public static BlockState representedNeighbor(BlockView world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() != ModelSystemMod.MODEL_ENDPOINT_BLOCK) return state;
        if (world.getBlockEntity(pos) instanceof ModelBlockEntity model && model.isSkinned()) {
            return model.getDisplayState();
        }
        return state;
    }

    /**
     * Re-runs the current block's native update with the endpoint's represented state substituted for
     * the ModelBlock holder. Called by the neighbour Mixin after vanilla/modded code saw the holder.
     */
    public static BlockState nativeUpdateWithRepresentedNeighbor(BlockState current, Direction direction,
                                                                  WorldAccess world, BlockPos pos,
                                                                  BlockPos neighborPos,
                                                                  BlockState representedNeighbor) {
        try {
            return current.getStateForNeighborUpdate(direction, representedNeighbor, world, pos, neighborPos);
        } catch (RuntimeException ignored) {
            return current;
        }
    }

    /** Clears only horizontal connection properties while preserving material/facing variants. */
    public static BlockState disconnectedState(BlockState state) {
        if (!isSupported(state)) return state;
        BlockState result = state;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("north") || name.equals("south") || name.equals("east") || name.equals("west")) {
                result = setDisconnected(result, property);
            }
        }
        return result;
    }

    public static BlockState alignAxisOrFacing(BlockState state, Direction longitudinal) {
        if (state == null || longitudinal == null || longitudinal.getAxis().isVertical()) return state;
        BlockState result = state;
        for (Property<?> property : state.getProperties()) {
            String name=property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("axis") || name.equals("horizontal_axis")) {
                result=setParsedOrSame(result,property,longitudinal.getAxis()==Direction.Axis.X?"x":"z");
            } else if (name.equals("facing") || name.equals("horizontal_facing")) {
                result=setParsedOrSame(result,property,longitudinal.getName());
            }
        }
        return result;
    }

    /** True for Fence/Pane/Wall style N/E/S/W arm states. */
    public static boolean hasHorizontalConnectionArms(BlockState state) {
        if (state == null) return false;
        int count = 0;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("north") || name.equals("south") || name.equals("east") || name.equals("west")) count++;
        }
        return count >= 2;
    }

    /**
     * Conquest Balustrade is axis-only and Railings is facing/open.  They do not expose connection
     * arms at all; visual continuation is obtained by aligning their longitudinal orientation to
     * the neighbouring endpoint direction rather than inventing boolean arms.
     */
    public static boolean orientationOnlyConnectedProfile(BlockState state) {
        if (!isSupported(state) || hasHorizontalConnectionArms(state)) return false;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("axis") || name.equals("horizontal_axis")
                    || name.equals("facing") || name.equals("horizontal_facing")) return true;
        }
        return false;
    }

    private static boolean hasConnectionOrOrientationProperty(BlockState state) {
        for(Property<?> property:state.getProperties()) {
            String name=property.getName().toLowerCase(Locale.ROOT);
            if(name.equals("north")||name.equals("south")||name.equals("east")||name.equals("west")
                    ||name.equals("axis")||name.equals("horizontal_axis")
                    ||name.equals("facing")||name.equals("horizontal_facing")) return true;
        }
        return false;
    }

    private static BlockState setDisconnected(BlockState state, Property<?> property) {
        if (property instanceof BooleanProperty booleanProperty) return state.with(booleanProperty, false);
        for (String candidate : new String[]{"none", "false", "empty"}) {
            BlockState parsed = setParsed(state, property, candidate);
            if (parsed != null) return parsed;
        }
        return state;
    }

    private static boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private static BlockState setConnected(BlockState state, Property<?> property) {
        if (property instanceof BooleanProperty booleanProperty) return state.with(booleanProperty, true);
        for (String candidate : new String[]{"low", "side", "tall", "connected", "true", "wall"}) {
            BlockState parsed = setParsed(state, property, candidate);
            if (parsed != null) return parsed;
        }
        for (Comparable<?> value : property.getValues()) {
            String named = propertyValueName(property, value).toLowerCase(Locale.ROOT);
            if (!named.equals("none") && !named.equals("false") && !named.equals("empty")) {
                return withRaw(state, property, value);
            }
        }
        return state;
    }

    private static BlockState setParsedOrSame(BlockState state,Property<?> property,String value) {
        BlockState parsed=setParsed(state,property,value);return parsed==null?state:parsed;
    }

    private static BlockState setParsed(BlockState state, Property<?> property, String value) {
        Optional<?> parsed = property.parse(value);
        if (parsed.isEmpty()) return null;
        return withRaw(state, property, (Comparable<?>) parsed.get());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withRaw(BlockState state, Property property, Comparable value) {
        return property.getValues().contains(value) ? state.with(property, value) : state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(Property property, Comparable value) { return property.name(value); }
}
