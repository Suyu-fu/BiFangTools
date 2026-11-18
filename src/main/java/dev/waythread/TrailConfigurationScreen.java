package dev.waythread;

import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec.ValueSpec;

final class TrailConfigurationScreen extends ConfigurationScreen.ConfigurationSectionScreen {
    static final int UNLIMITED_POSITION = WaythreadConfig.MAX_TRAIL_PARTICLE_LIMIT + 1;

    TrailConfigurationScreen(Screen parent, ModConfig.Type type, ModConfig config, Component title) {
        super(parent, type, config, title);
    }

    @Override
    protected Element createIntegerValue(String key, ValueSpec spec, Supplier<Integer> source, Consumer<Integer> target) {
        if (!WaythreadConfig.TRAIL_PARTICLE_LIMIT_KEY.equals(key)) {
            return super.createIntegerValue(key, spec, source, target);
        }
        OptionInstance<Integer> slider = new OptionInstance<>(getTranslationKey(key), getTooltip(key, null),
                (caption, position) -> position == UNLIMITED_POSITION
                        ? Component.translatable("waythread.configuration.trailParticleLimit.unlimited")
                        : Component.literal(Integer.toString(position)),
                new OptionInstance.IntRange(1, UNLIMITED_POSITION), null, sliderPosition(source.get()), position -> {
                    int selected = particleLimit(position);
                    int previous = source.get();
                    if (selected != previous) {
                        Consumer<Integer> apply = value -> {
                            target.accept(value);
                            onChanged(key);
                        };
                        undoManager.add(apply, selected, apply, previous);
                    }
                });
        return new Element(getTranslationComponent(key), getTooltipComponent(key, null), slider);
    }

    static int sliderPosition(int particleLimit) {
        return particleLimit == 0 ? UNLIMITED_POSITION : particleLimit;
    }

    static int particleLimit(int position) {
        return position == UNLIMITED_POSITION ? 0 : position;
    }
}
