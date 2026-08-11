package com.slopeconnector.model;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;

/** Reserved prism metadata used only by this outer patch. */
public final class ArcPrismTags {
    public static final byte COLLISION_PROXY_HINT = 120;
    public static final byte GROUP_MARKER_HINT = 121;
    public static final byte RENDER_LEADER_HINT = 122;
    private ArcPrismTags() {}

    public static boolean isCollisionProxy(ArcRibbonBlockEntity.Prism prism) {
        return prism != null && prism.materialHint() == COLLISION_PROXY_HINT && prism.faceMask() == 0;
    }

    public static boolean isGroupMarker(ArcRibbonBlockEntity.Prism prism) {
        return prism != null && prism.materialHint() == GROUP_MARKER_HINT && prism.faceMask() == 0
                && !prism.collidable();
    }

    public static boolean isRenderLeader(ArcRibbonBlockEntity.Prism prism) {
        return prism != null && prism.materialHint() == RENDER_LEADER_HINT && prism.faceMask() == 0
                && !prism.collidable();
    }

    public static boolean isMetadata(ArcRibbonBlockEntity.Prism prism) {
        return isCollisionProxy(prism) || isGroupMarker(prism) || isRenderLeader(prism);
    }

    public static boolean isProxyOnly(ArcRibbonBlockEntity entity) {
        if (entity == null || entity.getPrisms().isEmpty()) return false;
        boolean sawCollision = false;
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            if (isGroupMarker(prism) || isRenderLeader(prism)) continue;
            if (!isCollisionProxy(prism)) return false;
            sawCollision = true;
        }
        return sawCollision && entity.getSurfaces().isEmpty();
    }

    public static ArcRibbonBlockEntity.Prism collisionProxy(float[] xyz) {
        return new ArcRibbonBlockEntity.Prism(xyz, 0, 0, 0, 0, 0, 0,
                (byte)0, COLLISION_PROXY_HINT, true);
    }

    /**
     * Persists a logical arc-group id inside the existing ArcRibbon prism NBT format.  Four exact
     * 16-bit chunks are stored as ordinary finite floats, avoiding NaN/raw-bit canonicalisation.
     */
    public static ArcRibbonBlockEntity.Prism groupMarker(long groupId, double widthSpan,
                                                        double radialSpan, net.minecraft.util.math.Direction innerFace) {
        float[] xyz = new float[24];
        xyz[0] = (float) ((groupId >>> 48) & 0xffffL);
        xyz[1] = (float) ((groupId >>> 32) & 0xffffL);
        xyz[2] = (float) ((groupId >>> 16) & 0xffffL);
        xyz[3] = (float) (groupId & 0xffffL);
        xyz[4] = (float) Math.max(1.0, widthSpan);
        xyz[5] = (float) Math.max(1.0, radialSpan);
        xyz[6] = (float) ((innerFace == null ? net.minecraft.util.math.Direction.UP : innerFace).ordinal() + 1);
        xyz[7] = 931.0f; // marker schema, numeric/finitely serializable
        return new ArcRibbonBlockEntity.Prism(xyz, 0, 0, 0, 0, 0, 0,
                (byte)0, GROUP_MARKER_HINT, false);
    }

    public static ArcRibbonBlockEntity.Prism renderLeader(double radius) {
        float[] xyz = new float[24];
        xyz[0] = (float) Math.max(16.0, radius);
        xyz[1] = 931.0f;
        return new ArcRibbonBlockEntity.Prism(xyz, 0, 0, 0, 0, 0, 0,
                (byte)0, RENDER_LEADER_HINT, false);
    }

    public static boolean isRenderLeader(ArcRibbonBlockEntity entity) {
        if (entity == null) return false;
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) if (isRenderLeader(prism)) return true;
        return false;
    }

    public static double renderLeaderRadius(ArcRibbonBlockEntity entity) {
        if (entity == null) return 64.0;
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            if (!isRenderLeader(prism) || prism.xyz() == null || prism.xyz().length == 0) continue;
            return Math.max(16.0, prism.xyz()[0]);
        }
        return 64.0;
    }

    public static double groupWidthSpan(ArcRibbonBlockEntity entity) {
        ArcRibbonBlockEntity.Prism marker = groupMarker(entity);
        if (marker == null || marker.xyz().length < 6) return Double.NaN;
        return Math.max(1.0, marker.xyz()[4]);
    }

    public static double groupRadialSpan(ArcRibbonBlockEntity entity) {
        ArcRibbonBlockEntity.Prism marker = groupMarker(entity);
        if (marker == null || marker.xyz().length < 6) return Double.NaN;
        return Math.max(1.0, marker.xyz()[5]);
    }

    public static net.minecraft.util.math.Direction groupInnerFace(ArcRibbonBlockEntity entity) {
        ArcRibbonBlockEntity.Prism marker = groupMarker(entity);
        if (marker == null || marker.xyz().length < 7) return null;
        int ordinal = Math.round(marker.xyz()[6]) - 1;
        var values = net.minecraft.util.math.Direction.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    private static ArcRibbonBlockEntity.Prism groupMarker(ArcRibbonBlockEntity entity) {
        if (entity == null) return null;
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) if (isGroupMarker(prism)) return prism;
        return null;
    }

    public static long groupId(ArcRibbonBlockEntity entity) {
        if (entity == null) return 0L;
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            if (!isGroupMarker(prism)) continue;
            float[] xyz = prism.xyz();
            if (xyz == null || xyz.length < 4) return 0L;
            long a = ((long) Math.round(xyz[0])) & 0xffffL;
            long b = ((long) Math.round(xyz[1])) & 0xffffL;
            long c = ((long) Math.round(xyz[2])) & 0xffffL;
            long d = ((long) Math.round(xyz[3])) & 0xffffL;
            return (a << 48) | (b << 32) | (c << 16) | d;
        }
        return 0L;
    }
}
