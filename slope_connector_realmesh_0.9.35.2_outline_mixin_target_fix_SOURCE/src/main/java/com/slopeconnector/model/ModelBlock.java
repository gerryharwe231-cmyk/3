package com.slopeconnector.model;

import net.minecraft.block.Block;

/**
 * Public pure-white template block.
 *
 * <p>This is deliberately a plain {@link Block}, not BlockWithEntity. Dense template fields must be
 * as cheap as ordinary opaque cubes. Only the two endpoints that actually belong to an arc are
 * converted to the internal {@link ModelEndpointBlock}.</p>
 */
public final class ModelBlock extends Block {
    public ModelBlock(Settings settings) {
        super(settings);
    }
}
