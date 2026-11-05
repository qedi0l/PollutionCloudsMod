package com.qedi.pcloudsmod.server;

/**
 * A single pollution cloud to render on the client.
 *
 * <p>The cloud is rendered as an axis-aligned ellipsoid centered at
 * {@code (x, y, z)} with half-extents {@code (sizeX, sizeY, sizeZ)}.
 *
 * @param x      World X coordinate (block space, center of the cloud)
 * @param y      World Y coordinate (block space, center of the cloud)
 * @param z      World Z coordinate (block space, center of the cloud)
 * @param sizeX  Half-extent along X in blocks
 * @param sizeY  Half-extent along Y in blocks
 * @param sizeZ  Half-extent along Z in blocks
 * @param color  Blended ARGB color (0xAARRGGBB)
 */
public record CloudPoint(float x, float y, float z, float sizeX, float sizeY, float sizeZ, int color) {
}
