package dev.waythread;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.TranslatableEnum;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

final class AnchorRenderer {
    private static final int SLICES = 16;
    private static final int SPHERE_RINGS = 8;
    private static final double MAX_DISPLAY_DISTANCE = 48.0;
    private static final float OUTLINE_SCALE = 1.16F;
    private static final int RELAY_RING_SLICES = 24;
    private static final List<Vertex> ORB = buildOrb();
    private static final List<Vertex> CUBE = buildCube();
    private static final List<Vertex> RELAY_RING = buildRelayRing();

    private AnchorRenderer() {
    }

    static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(BodyRenderTypes.NORMAL_PIPELINE);
        event.registerPipeline(BodyRenderTypes.SEE_THROUGH_PIPELINE);
    }

    static void submit(SubmitCustomGeometryEvent event, Marker marker) {
        RenderType type = marker.seeThrough ? BodyRenderTypes.SEE_THROUGH : BodyRenderTypes.NORMAL;
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), type,
                (pose, buffer) -> renderMarker(buffer, new Matrix4f(pose.pose()), marker));
    }

    private static void renderMarker(VertexConsumer buffer, Matrix4f transform, Marker marker) {
        Vec3 center = marker.cameraRelativePosition;
        Appearance appearance = marker.appearance;
        int rgb = marker.rgb;
        transform.translate((float) center.x, (float) center.y, (float) center.z);
        Matrix4f ringTransform = marker.ringRotation == null ? null : new Matrix4f(transform).rotate(marker.ringRotation);
        if (appearance == Appearance.CUBE) {
            transform.rotateY(marker.time * 0.037F).rotateX(0.26F).scale(0.18F);
        } else {
            transform.scale(ringTransform != null ? 0.135F : 0.22F);
        }
        List<Vertex> vertices = vertices(appearance);
        int lightFrame = ARGB.srgbLerp(0.65F, 0xFF000000 | rgb, 0xFFFFFFFF);
        drawOutline(buffer, transform, vertices, appearance == Appearance.CUBE ? lightFrame : outlineColor(rgb));
        for (Vertex vertex : vertices) {
            buffer.addVertex(transform, vertex.x, vertex.y, vertex.z).setColor(tint(rgb, vertex.light));
        }
        if (ringTransform != null) {
            for (Vertex vertex : RELAY_RING) {
                buffer.addVertex(ringTransform, vertex.x, vertex.y, vertex.z).setColor(lightFrame);
            }
        }
    }

    private static void drawOutline(VertexConsumer buffer, Matrix4f transform, List<Vertex> vertices, int color) {
        Matrix4f outline = new Matrix4f(transform).scale(OUTLINE_SCALE);
        for (int i = 0; i < vertices.size(); i += 3) {
            // Reversed winding shows only the back faces of the expanded mesh around the body.
            outlineVertex(buffer, outline, vertices.get(i), color);
            outlineVertex(buffer, outline, vertices.get(i + 2), color);
            outlineVertex(buffer, outline, vertices.get(i + 1), color);
        }
    }

    private static void outlineVertex(VertexConsumer buffer, Matrix4f transform, Vertex vertex, int color) {
        buffer.addVertex(transform, vertex.x, vertex.y, vertex.z).setColor(color);
    }

    static int outlineColor(int rgb) {
        int red = (rgb >> 16) & 255;
        int green = (rgb >> 8) & 255;
        int blue = rgb & 255;
        boolean lightFill = 54 * red + 183 * green + 19 * blue >= 96 * 256;
        return 0xFF000000 | (outlineChannel(red, lightFill) << 16)
                | (outlineChannel(green, lightFill) << 8) | outlineChannel(blue, lightFill);
    }

    private static int outlineChannel(int channel, boolean lightFill) {
        return lightFill ? channel / 5 : (channel + 4 * 255) / 5;
    }

    static Vec3 floatingCenter(Vec3 anchor, float time) {
        return anchor.add(0, 0.72F + 0.08F * Mth.sin(time * 0.16F), 0);
    }

    static Vec3 seeThroughPosition(Vec3 center, Vec3 camera) {
        double distanceSquared = center.distanceToSqr(camera);
        if (distanceSquared <= MAX_DISPLAY_DISTANCE * MAX_DISPLAY_DISTANCE) {
            return center;
        }
        // Keep the direction while preventing the marker from shrinking away or reaching the far plane.
        return camera.lerp(center, MAX_DISPLAY_DISTANCE / Math.sqrt(distanceSquared));
    }

    private static List<Vertex> vertices(Appearance appearance) {
        return appearance == Appearance.CUBE ? CUBE : ORB;
    }

    private static int tint(int rgb, float light) {
        int red = shade((rgb >> 16) & 255, light);
        int green = shade((rgb >> 8) & 255, light);
        int blue = shade(rgb & 255, light);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int shade(int channel, float light) {
        return Math.round(Mth.lerp(light, channel * 0.42F, Mth.lerp(0.38F, channel, 255)));
    }

    private static List<Vertex> buildOrb() {
        List<Vertex> vertices = new ArrayList<>();
        for (int ring = 0; ring < SPHERE_RINGS; ring++) {
            for (int slice = 0; slice < SLICES; slice++) {
                Vertex topLeft = sphereVertex(ring, slice);
                Vertex topRight = sphereVertex(ring, slice + 1);
                Vertex bottomLeft = sphereVertex(ring + 1, slice);
                Vertex bottomRight = sphereVertex(ring + 1, slice + 1);
                if (ring > 0) {
                    vertices.add(topLeft);
                    vertices.add(topRight);
                    vertices.add(bottomRight);
                }
                if (ring + 1 < SPHERE_RINGS) {
                    vertices.add(topLeft);
                    vertices.add(bottomRight);
                    vertices.add(bottomLeft);
                }
            }
        }
        return List.copyOf(vertices);
    }

    private static Vertex sphereVertex(int ring, int slice) {
        double latitude = ring * Math.PI / SPHERE_RINGS;
        double longitude = slice * Math.TAU / SLICES;
        float x = (float) (Math.sin(latitude) * Math.cos(longitude));
        float y = (float) Math.cos(latitude);
        float z = (float) (Math.sin(latitude) * Math.sin(longitude));
        float light = 0.35F + 0.65F * Math.max(0.0F, -0.35F * x + 0.78F * y + 0.52F * z);
        return new Vertex(x, y, z, light);
    }

    private static List<Vertex> buildRelayRing() {
        List<Vertex> vertices = new ArrayList<>(RELAY_RING_SLICES * 12);
        for (int slice = 0; slice < RELAY_RING_SLICES; slice++) {
            Vertex inner = ringVertex(slice, 0.184F);
            Vertex outer = ringVertex(slice, 0.213F);
            Vertex nextInner = ringVertex(slice + 1, 0.184F);
            Vertex nextOuter = ringVertex(slice + 1, 0.213F);
            addRingTriangle(vertices, outer, nextOuter, inner);
            addRingTriangle(vertices, inner, nextOuter, nextInner);
        }
        return List.copyOf(vertices);
    }

    private static Vertex ringVertex(int slice, float radius) {
        double angle = slice * Math.TAU / RELAY_RING_SLICES;
        return new Vertex((float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius, 0, 1);
    }

    private static void addRingTriangle(List<Vertex> vertices, Vertex a, Vertex b, Vertex c) {
        vertices.addAll(List.of(a, b, c, a, c, b));
    }

    private static List<Vertex> buildCube() {
        List<Vertex> vertices = new ArrayList<>(36);
        addFace(vertices, 4, 5, 6, 7, 0.76F);
        addFace(vertices, 1, 0, 3, 2, 0.52F);
        addFace(vertices, 0, 4, 7, 3, 0.64F);
        addFace(vertices, 5, 1, 2, 6, 0.44F);
        addFace(vertices, 3, 7, 6, 2, 1.0F);
        addFace(vertices, 0, 1, 5, 4, 0.35F);
        return List.copyOf(vertices);
    }

    private static void addFace(List<Vertex> vertices, int a, int b, int c, int d, float light) {
        Vertex first = cubeVertex(a, light);
        Vertex third = cubeVertex(c, light);
        vertices.add(first);
        vertices.add(cubeVertex(b, light));
        vertices.add(third);
        vertices.add(first);
        vertices.add(third);
        vertices.add(cubeVertex(d, light));
    }

    private static Vertex cubeVertex(int index, float light) {
        int corner = index & 3;
        return new Vertex(corner == 1 || corner == 2 ? 1 : -1, corner >= 2 ? 1 : -1, index >= 4 ? 1 : -1, light);
    }

    private record Vertex(float x, float y, float z, float light) {
    }

    record Marker(Vec3 cameraRelativePosition, Appearance appearance, boolean seeThrough, int rgb, float time,
                  Quaternionf ringRotation) {
    }

    private static final class BodyRenderTypes {
        private static final RenderPipeline NORMAL_PIPELINE = pipeline("waythread_anchor", CompareOp.LESS_THAN_OR_EQUAL);
        private static final RenderPipeline SEE_THROUGH_PIPELINE = pipeline("waythread_anchor_see_through", CompareOp.ALWAYS_PASS);
        private static final RenderType NORMAL = RenderType.create("waythread_anchor",
                RenderSetup.builder(NORMAL_PIPELINE).bufferSize(16_384).createRenderSetup());
        private static final RenderType SEE_THROUGH = RenderType.create("waythread_anchor_see_through",
                RenderSetup.builder(SEE_THROUGH_PIPELINE).bufferSize(16_384).createRenderSetup());

        private static RenderPipeline pipeline(String name, CompareOp depthTest) {
            return RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(Waythread.MOD_ID, "pipeline/" + name))
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
                    .withDepthStencilState(new DepthStencilState(depthTest, false))
                    .build();
        }
    }

    enum Appearance implements TranslatableEnum {
        PARTICLES, ORB, CUBE;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("waythread.configuration.anchorAppearance." + name().toLowerCase(Locale.ROOT));
        }
    }
}
