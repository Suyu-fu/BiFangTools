package dev.waythread;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

record RouteTarget(ResourceKey<Level> dimension, Vec3 position) {
    boolean isWithin(ResourceKey<Level> currentDimension, Vec3 currentPosition, double rangeSquared) {
        return dimension.equals(currentDimension) && currentPosition != null
                && position.distanceToSqr(currentPosition) <= rangeSquared;
    }
}
