package com.slopeconnector.model;

import com.slopeconnector.MaterialStateCodec;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

public final class ModelBlockEntity extends BlockEntity {
    private BlockState capturedState = Blocks.WHITE_CONCRETE.getDefaultState();
    private BlockState displayState = Blocks.WHITE_CONCRETE.getDefaultState();
    private Direction arcDirection = Direction.NORTH;
    /** Existing arc-wand face setting, interpreted by the model renderer as the inner-arc side. */
    private Direction innerArcDirection = Direction.UP;
    private boolean skinned;
    /** True only for the terminal endpoint after the ordered arc; persisted to avoid render-time scans. */
    private boolean terminalEnd;
    /** Logical arc group shared by every widened/thickened layer of one generated arc. */
    private long arcGroupId;
    private Vec3d endpointWidthAxis = new Vec3d(0, 0, 1);
    private Vec3d endpointRadialAxis = new Vec3d(0, 1, 0);
    private double endpointWidthSpan = 1.0;
    private double endpointRadialSpan = 1.0;
    /** Group-wide mapping chosen once from the arc middle; endpoints must never resolve it again. */
    private boolean endpointLateralUsesWidth = true;
    private double endpointLateralSign = 1.0;
    private double endpointVerticalSign = 1.0;

    public ModelBlockEntity(BlockPos pos, BlockState state) {
        super(ModelSystemMod.MODEL_BLOCK_ENTITY, pos, state);
    }

    public boolean isSkinned() { return skinned; }
    public BlockState getCapturedState() { return capturedState; }
    public BlockState getDisplayState() { return displayState; }
    public Direction getArcDirection() { return arcDirection; }
    public Direction getInnerArcDirection() { return innerArcDirection; }
    public boolean isTerminalEnd() { return terminalEnd; }
    public long getArcGroupId() { return arcGroupId; }
    public Vec3d getEndpointWidthAxis() { return endpointWidthAxis; }
    public Vec3d getEndpointRadialAxis() { return endpointRadialAxis; }
    public double getEndpointWidthSpan() { return endpointWidthSpan; }
    public double getEndpointRadialSpan() { return endpointRadialSpan; }
    public boolean endpointLateralUsesWidth() { return endpointLateralUsesWidth; }
    public double getEndpointLateralSign() { return endpointLateralSign; }
    public double getEndpointVerticalSign() { return endpointVerticalSign; }
    public Vec3d getEndpointLateralAxis() {
        Vec3d axis = endpointLateralUsesWidth ? endpointWidthAxis : endpointRadialAxis;
        return axis.lengthSquared() < 1.0E-10 ? new Vec3d(0,0,1) : axis.normalize().multiply(endpointLateralSign);
    }
    public Vec3d getEndpointVerticalAxis() {
        Vec3d axis = endpointLateralUsesWidth ? endpointRadialAxis : endpointWidthAxis;
        return axis.lengthSquared() < 1.0E-10 ? new Vec3d(0,1,0) : axis.normalize().multiply(endpointVerticalSign);
    }
    public double getEndpointLateralSpan() { return endpointLateralUsesWidth ? endpointWidthSpan : endpointRadialSpan; }
    public double getEndpointVerticalSpan() { return endpointLateralUsesWidth ? endpointRadialSpan : endpointWidthSpan; }

    public void setEndpointFrame(ArcStationFrames.Station station) {
        ArcCrossSectionMapping.Mapping mapping = station == null ? null : ArcCrossSectionMapping.resolve(
                station.width(), station.radial(), station.widthSpan(), station.radialSpan(), innerArcDirection);
        setEndpointFrame(station, mapping);
    }

    public void setEndpointFrame(ArcStationFrames.Station station, ArcCrossSectionMapping.Mapping mapping) {
        if (station == null) return;
        this.endpointWidthAxis = station.width();
        this.endpointRadialAxis = station.radial();
        this.endpointWidthSpan = Math.max(1.0, station.widthSpan());
        this.endpointRadialSpan = Math.max(1.0, station.radialSpan());
        if (mapping != null) {
            this.endpointLateralUsesWidth = mapping.lateralUsesWidth();
            this.endpointLateralSign = mapping.lateralSign();
            this.endpointVerticalSign = mapping.verticalSign();
        }
        markDirty();
        if (world != null) world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
    }

    public void setTerminalEnd(boolean terminalEnd) {
        this.terminalEnd = terminalEnd;
        if (skinned) refreshDisplayState();
        markDirty();
    }

    public void setArcGroupId(long arcGroupId) {
        this.arcGroupId = arcGroupId;
        markDirty();
    }


    /** Stores arc orientation even while the block is still the pure-white unskinned endpoint. */
    public void setArcMetadata(Direction arcDirection, Direction innerArcDirection) {
        this.arcDirection = arcDirection == null ? Direction.NORTH : arcDirection;
        this.innerArcDirection = innerArcDirection == null ? Direction.UP : innerArcDirection;
        if (skinned) refreshDisplayState();
        markDirty();
        if (world != null) world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
    }

    public void setSkin(BlockState captured, Direction arcDirection) {
        setSkin(captured, arcDirection, innerArcDirection);
    }

    public void setSkin(BlockState captured, Direction arcDirection, Direction innerArcDirection) {
        this.capturedState = sanitize(captured);
        this.arcDirection = arcDirection == null ? Direction.NORTH : arcDirection;
        this.innerArcDirection = innerArcDirection == null ? Direction.UP : innerArcDirection;
        this.skinned = true;
        refreshDisplayState();
        if (world != null) world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
        markDirty();
    }

    public void clearSkin() {
        this.skinned = false;
        this.terminalEnd = false;
        this.capturedState = Blocks.WHITE_CONCRETE.getDefaultState();
        this.displayState = this.capturedState;
        if (world != null) world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
        markDirty();
    }

    public void refreshDisplayState() {
        if (!skinned) {
            displayState = Blocks.WHITE_CONCRETE.getDefaultState();
            return;
        }
        if (world != null) {
            displayState = ModelStateResolver.endpointState(capturedState, arcDirection, terminalEnd, world, pos);
        } else {
            displayState = capturedState;
        }
        markDirty();
    }

    public void onNeighborChanged() {
        BlockState before = displayState;
        refreshDisplayState();
        if (world != null && !before.equals(displayState)) {
            world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
        }
    }

    private static BlockState sanitize(BlockState state) {
        if (state == null || state.isAir() || ModelSystemMod.isModelHolder(state)) {
            return Blocks.WHITE_CONCRETE.getDefaultState();
        }
        return state;
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putBoolean("Skinned", skinned);
        nbt.putBoolean("TerminalEnd", terminalEnd);
        nbt.putLong("ArcGroupId", arcGroupId);
        nbt.put("CapturedState", MaterialStateCodec.write(capturedState));
        nbt.put("DisplayState", MaterialStateCodec.write(displayState));
        nbt.putString("ArcDirection", arcDirection.getName());
        nbt.putString("InnerArcDirection", innerArcDirection.getName());
        nbt.putDouble("EndpointWidthX", endpointWidthAxis.x);
        nbt.putDouble("EndpointWidthY", endpointWidthAxis.y);
        nbt.putDouble("EndpointWidthZ", endpointWidthAxis.z);
        nbt.putDouble("EndpointRadialX", endpointRadialAxis.x);
        nbt.putDouble("EndpointRadialY", endpointRadialAxis.y);
        nbt.putDouble("EndpointRadialZ", endpointRadialAxis.z);
        nbt.putDouble("EndpointWidthSpan", endpointWidthSpan);
        nbt.putDouble("EndpointRadialSpan", endpointRadialSpan);
        nbt.putBoolean("EndpointLateralUsesWidth", endpointLateralUsesWidth);
        nbt.putDouble("EndpointLateralSign", endpointLateralSign);
        nbt.putDouble("EndpointVerticalSign", endpointVerticalSign);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        skinned = nbt.getBoolean("Skinned");
        terminalEnd = nbt.getBoolean("TerminalEnd");
        arcGroupId = nbt.contains("ArcGroupId") ? nbt.getLong("ArcGroupId") : 0L;
        capturedState = nbt.contains("CapturedState")
                ? sanitize(MaterialStateCodec.read(nbt.getCompound("CapturedState")))
                : Blocks.WHITE_CONCRETE.getDefaultState();
        displayState = nbt.contains("DisplayState")
                ? sanitize(MaterialStateCodec.read(nbt.getCompound("DisplayState"))) : capturedState;
        Direction parsed = Direction.byName(nbt.getString("ArcDirection"));
        arcDirection = parsed == null ? Direction.NORTH : parsed;
        Direction inner = Direction.byName(nbt.getString("InnerArcDirection"));
        innerArcDirection = inner == null ? Direction.UP : inner;
        if (nbt.contains("EndpointWidthX")) {
            endpointWidthAxis = new Vec3d(nbt.getDouble("EndpointWidthX"), nbt.getDouble("EndpointWidthY"), nbt.getDouble("EndpointWidthZ"));
            endpointRadialAxis = new Vec3d(nbt.getDouble("EndpointRadialX"), nbt.getDouble("EndpointRadialY"), nbt.getDouble("EndpointRadialZ"));
            endpointWidthSpan = Math.max(1.0, nbt.getDouble("EndpointWidthSpan"));
            endpointRadialSpan = Math.max(1.0, nbt.getDouble("EndpointRadialSpan"));
        }
        if (nbt.contains("EndpointLateralUsesWidth")) {
            endpointLateralUsesWidth = nbt.getBoolean("EndpointLateralUsesWidth");
            endpointLateralSign = nbt.contains("EndpointLateralSign") ? nbt.getDouble("EndpointLateralSign") : 1.0;
            endpointVerticalSign = nbt.contains("EndpointVerticalSign") ? nbt.getDouble("EndpointVerticalSign") : 1.0;
            if (Math.abs(endpointLateralSign) < 0.5) endpointLateralSign = 1.0;
            if (Math.abs(endpointVerticalSign) < 0.5) endpointVerticalSign = 1.0;
        }
    }

    @Override public NbtCompound toInitialChunkDataNbt() { return createNbt(); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }
}
