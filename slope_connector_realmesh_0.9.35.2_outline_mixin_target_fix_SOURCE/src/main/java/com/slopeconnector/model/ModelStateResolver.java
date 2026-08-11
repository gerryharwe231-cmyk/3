package com.slopeconnector.model;

import com.slopeconnector.surface.ConnectionStateHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.StairShape;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;

import java.util.Locale;
import java.util.Optional;

/** Resolves the captured state into a deterministic one-block source module before deformation. */
public final class ModelStateResolver {
    private ModelStateResolver() {}

    /**
     * Connected-profile models are normalized to one straight X-running module.  This matters for
     * Conquest/Reforged-style blocks that use axis/facing instead of vanilla north/east booleans.
     * Non-connected blocks preserve the exact captured state.
     */
    public static BlockState middleState(BlockState captured) {
        if (captured == null) return captured;
        BlockState normalized = canonicalSourceState(captured);
        // Stairs/slabs have the highest model-orientation priority.  Keep their real facing/half,
        // but freeze neighbour-driven stair corner shapes to STRAIGHT so the captured source module
        // does not inherit unrelated vanilla corner joins.
        if (isStairOrSlab(normalized)) return normalized;
        if (!ConnectionStateHelper.isSupported(normalized)) return normalized;
        BlockState state = ConnectionStateHelper.straightState(normalized);
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("axis") || name.equals("horizontal_axis")) {
                state = setNamed(state, property, "x");
            } else if (name.equals("facing") || name.equals("horizontal_facing")) {
                state = setNamed(state, property, "east");
            }
        }
        return state;
    }

    public static boolean isStairOrSlab(BlockState state) {
        return state != null && (state.getBlock() instanceof StairsBlock || state.getBlock() instanceof SlabBlock);
    }

    /**
     * Only ordinary full-cube-like models may safely drop a whole face between two cross-section
     * tiles.  Stairs/slabs/connected profiles often occupy only PART of that boundary; culling the
     * entire BakedQuad creates the transparent side holes seen when widening stairs.
     */
    public static boolean canCullInternalTileFaces(BlockState state) {
        return state != null && !isStairOrSlab(state) && !ConnectionStateHelper.isSupported(state);
    }

    public static BlockState canonicalSourceState(BlockState state) {
        if (state == null) return null;
        if (state.getBlock() instanceof StairsBlock && state.contains(StairsBlock.SHAPE)
                && state.get(StairsBlock.SHAPE) != StairShape.STRAIGHT) {
            return state.with(StairsBlock.SHAPE, StairShape.STRAIGHT);
        }
        return state;
    }

    /** Captured horizontal source-forward direction.  Used to preserve stairs' real facing. */
    public static Direction sourceForward(BlockState state) {
        if (state == null) return Direction.EAST;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (!name.equals("facing") && !name.equals("horizontal_facing")) continue;
            Direction direction = Direction.byName(valueName(state, property));
            if (direction != null && !direction.getAxis().isVertical()) return direction;
        }
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (!name.equals("axis") && !name.equals("horizontal_axis")) continue;
            String value = valueName(state, property);
            if (value.equals("z")) return Direction.SOUTH;
            if (value.equals("x")) return Direction.EAST;
        }
        return Direction.EAST;
    }



    /**
     * Endpoint form with two deliberately different directions:
     * - connectionDirection points from the endpoint into the arc and is used for Fence/Pane/Wall
     *   neighbor connectivity;
     * - seamDirection is the ordered model/texture direction.  At the terminal endpoint it points
     *   OUT of the arc, so endpoint texture continuity always wins over vanilla placement facing.
     */
    public static BlockState endpointState(BlockState captured, Direction connectionDirection,
                                           boolean terminalEnd,
                                           net.minecraft.world.BlockView world, net.minecraft.util.math.BlockPos pos) {
        if (captured == null) return captured;
        Direction seamDirection = connectionDirection == null ? Direction.EAST
                : (terminalEnd ? connectionDirection.getOpposite() : connectionDirection);

        BlockState normalized = canonicalSourceState(captured);

        // Priority 1: stairs/slabs keep the captured facing and top/bottom placement, but stair
        // shape is frozen to STRAIGHT so a corner stair captured beside neighbours will not project
        // a bogus T/arrow footprint into the curved endpoint.
        if (isStairOrSlab(normalized)) return normalized;

        // Priority 2: connected profiles outrank generic seam/texture continuity.  Their endpoint
        // state is driven by the REAL neighbour connection direction, never by terminal seamDirection.
        // This is essential for Fence/Pane/Wall and Conquest railing/balustrade variants.
        if (ConnectionStateHelper.isSupported(normalized)) {
            BlockState state = ConnectionStateHelper.endpointState(normalized, connectionDirection, world, pos);
            if (ConnectionStateHelper.orientationOnlyConnectedProfile(state) && connectionDirection != null
                    && !connectionDirection.getAxis().isVertical()) {
                state = ConnectionStateHelper.alignAxisOrFacing(state, connectionDirection);
            }
            return state;
        }

        // Priority 3: ordinary texture/model continuity.  Only then do we override vanilla facing.
        BlockState state = normalized;
        if (connectionDirection == null || connectionDirection.getAxis().isVertical()) return state;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("axis") || name.equals("horizontal_axis")) {
                state = setNamed(state, property, seamDirection.getAxis() == Direction.Axis.X ? "x" : "z");
            } else if (name.equals("facing") || name.equals("horizontal_facing")) {
                state = setNamed(state, property, seamDirection.getName());
            }
        }
        return state;
    }


    public static BlockState endpointState(BlockState captured, Direction connectionDirection,
                                           net.minecraft.world.BlockView world, net.minecraft.util.math.BlockPos pos) {
        return endpointState(captured, connectionDirection, false, world, pos);
    }

    /**
     * True when the captured state already has an explicit horizontal model orientation.  Such
     * states (stairs, logs, connected fences/panes/walls, Conquest railings...) are resolved by
     * endpointState itself and must not receive a second geometric rotation in the endpoint BER.
     */
    public static boolean hasExplicitOrientation(BlockState state) {
        if (state == null) return false;
        if (ConnectionStateHelper.isSupported(state)) return true;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName().toLowerCase(Locale.ROOT);
            if (name.equals("axis") || name.equals("horizontal_axis")
                    || name.equals("facing") || name.equals("horizontal_facing")) return true;
        }
        return false;
    }

    /**
     * Longitudinal source-model axis before deformation.
     *
     * Stairs/slabs are deliberately NOT normalized from their captured facing: the baked model
     * already contains that exact orientation.  Repeating the unchanged model along local +X keeps
     * all four stair facings distinct, while HALF/SHAPE/TOP/BOTTOM remain untouched.
     */
    public static Direction.Axis longitudinalAxis(BlockState state) {
        if (isStairOrSlab(state)) return Direction.Axis.X;
        return sourceForward(state).getAxis();
    }

    public static boolean reverseLongitudinal(BlockState state) {
        if (isStairOrSlab(state)) return false;
        Direction forward = sourceForward(state);
        return forward == Direction.WEST || forward == Direction.NORTH;
    }


    private static BlockState setNamed(BlockState state, Property<?> property, String value) {
        Optional<?> parsed = property.parse(value);
        if (parsed.isEmpty()) return state;
        return withRaw(state, property, (Comparable<?>) parsed.get());
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static BlockState withRaw(BlockState state, Property property, Comparable value) {
        return property.getValues().contains(value) ? state.with(property, value) : state;
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static String valueName(BlockState state, Property property) {
        Comparable value=state.get(property);return property.name(value).toLowerCase(Locale.ROOT);
    }
}
