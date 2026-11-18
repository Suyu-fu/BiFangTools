package dev.waythread;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.ValueSpec;

final class TrailConfigurationScreen extends ConfigurationScreen.ConfigurationSectionScreen {
    static final int UNLIMITED_POSITION = WaythreadConfig.MAX_TRAIL_PARTICLE_LIMIT + 1;

    private final PageEntries pageEntries;
    private final Map<String, AbstractWidget> controls = new HashMap<>();
    private Page page = Page.ROUTE;
    private TabNavigationBar tabs;
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget) {
        @Override
        public void setCurrentTab(Tab tab, boolean playSound) {
            super.setCurrentTab(tab, playSound);
            Page selected = (Page) tab;
            if (page != selected) {
                page = selected;
                if (list != null) {
                    list.setFocused(null);
                    rebuild();
                    list.setScrollAmount(0);
                }
            }
        }
    };

    TrailConfigurationScreen(Screen parent, ModConfig.Type type, ModConfig config, Component title) {
        this(parent, type, config, title, new PageEntries());
    }

    private TrailConfigurationScreen(Screen parent, ModConfig.Type type, ModConfig config, Component title, PageEntries pageEntries) {
        super(parent, type, config, title, pageEntries);
        this.pageEntries = pageEntries;
    }

    @Override
    protected void addTitle() {
        tabs = TabNavigationBar.builder(tabManager, width).addTabs(Page.values()).build();
        addRenderableWidget(tabs);
        tabs.selectTab(page.ordinal(), false);
    }

    @Override
    protected void repositionElements() {
        if (tabs != null) {
            tabs.setWidth(width);
            tabs.arrangeElements();
            layout.setHeaderHeight(tabs.getRectangle().bottom() + 4);
        }
        super.repositionElements();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return (tabs != null && tabs.keyPressed(keyCode)) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected ConfigurationScreen.ConfigurationSectionScreen rebuild() {
        pageEntries.options.clear();
        controls.clear();
        super.rebuild();
        updateDependentControls();
        return this;
    }

    @Override
    protected Collection<? extends Element> createSyntheticValues() {
        List<Element> rows = new ArrayList<>();
        for (Group group : page.groups) {
            if (group.headingKey != null) {
                Component heading = Component.translatable(group.headingKey).withStyle(ChatFormatting.BOLD);
                rows.add(new Element(heading, heading,
                        new StringWidget(Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, Component.empty(), font), false));
            }
            for (ConfigValue<?> value : group.settings) {
                String key = value.getPath().getFirst();
                addControl(rows, key, pageEntries.options.remove(key));
            }
        }
        if (page == Page.ROUTE) {
            pageEntries.options.forEach((key, element) -> {
                if (!Page.hasSetting(key)) {
                    addControl(rows, key, element);
                }
            });
        }
        return rows;
    }

    private void addControl(List<Element> rows, String key, Element element) {
        if (element != null) {
            AbstractWidget widget = element.getWidget(options);
            controls.put(key, widget);
            rows.add(new Element(element.name(), element.tooltip(), widget, element.undoable()));
        }
    }

    @Override
    protected void onChanged(String key) {
        super.onChanged(key);
        updateDependentControls();
    }

    private void updateDependentControls() {
        setEnabled(WaythreadConfig.TRAIL_PARTICLE_SPACING,
                () -> WaythreadConfig.DISPLAY_MODE.getRaw() == TrailParticles.DisplayMode.LINE);
        setEnabled(WaythreadConfig.ANCHOR_COLOR, () -> !WaythreadConfig.ANCHOR_FOLLOWS_TRAIL_COLOR.getRaw());
        setEnabled(WaythreadConfig.ANCHOR_SEE_THROUGH_DISTANCE, WaythreadConfig.ANCHOR_SEE_THROUGH::getRaw);
        setEnabled(WaythreadConfig.RETURN_PROGRESS_FONT_SIZE, WaythreadConfig.SHOW_RETURN_PERCENTAGE::getRaw);
    }

    private void setEnabled(ConfigValue<?> setting, BooleanSupplier enabled) {
        AbstractWidget widget = controls.get(setting.getPath().getFirst());
        if (widget == null) {
            return;
        }
        widget.active = enabled.getAsBoolean();
        if (widget instanceof EditBox editBox) {
            editBox.setEditable(widget.active);
        }
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

    private static final class PageEntries implements Filter {
        private final Map<String, Element> options = new LinkedHashMap<>();

        @Override
        public Element filterEntry(Context context, String key, Element original) {
            if (original != null) {
                options.put(key, original);
            }
            return null;
        }
    }

    record Group(String headingKey, List<ConfigValue<?>> settings) {
    }

    enum Page implements Tab {
        ROUTE("waythread.configuration.page.route", new Group(null, List.of(
                WaythreadConfig.DISPLAY_MODE, WaythreadConfig.LOAD_DISTANCE, WaythreadConfig.TRAIL_PARTICLE_SPACING,
                WaythreadConfig.TRAIL_PARTICLE_LIMIT, WaythreadConfig.PARTICLE_STYLE, WaythreadConfig.TRAIL_COLOR))),
        ANCHOR("waythread.configuration.page.anchor", new Group(null, List.of(
                WaythreadConfig.ANCHOR_APPEARANCE, WaythreadConfig.ANCHOR_FOLLOWS_TRAIL_COLOR, WaythreadConfig.ANCHOR_COLOR,
                WaythreadConfig.ANCHOR_SEE_THROUGH, WaythreadConfig.ANCHOR_SEE_THROUGH_DISTANCE))),
        RECORD_RETURN("waythread.configuration.page.recordReturn",
                new Group("waythread.configuration.group.recording", List.of(WaythreadConfig.RECORD_POINT_SPACING,
                        WaythreadConfig.MAX_RECORDED_POINTS, WaythreadConfig.SHOW_RECORDING_DIRECTION)),
                new Group("waythread.configuration.group.returning", List.of(WaythreadConfig.RETURN_ERASE_DISTANCE,
                        WaythreadConfig.SHOW_RETURN_PROGRESS_BAR, WaythreadConfig.SHOW_RETURN_PERCENTAGE,
                        WaythreadConfig.RETURN_PROGRESS_FONT_SIZE,
                        WaythreadConfig.SHOW_ARRIVAL_MESSAGE, WaythreadConfig.CLEAR_ON_ARRIVAL)));

        private final String nameKey;
        final List<Group> groups;

        Page(String nameKey, Group... groups) {
            this.nameKey = nameKey;
            this.groups = List.of(groups);
        }

        static boolean hasSetting(String key) {
            for (Page page : values()) {
                for (Group group : page.groups) {
                    for (ConfigValue<?> value : group.settings) {
                        if (value.getPath().getFirst().equals(key)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public Component getTabTitle() {
            return Component.translatable(nameKey);
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> visitor) {
        }

        @Override
        public void doLayout(ScreenRectangle area) {
        }
    }
}
