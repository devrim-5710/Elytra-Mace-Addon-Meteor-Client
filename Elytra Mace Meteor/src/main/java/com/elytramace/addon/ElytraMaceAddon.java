package com.elytramace.addon;

import com.elytramace.addon.commands.CommandExample;
import com.elytramace.addon.hud.HudExample;
import com.elytramace.addon.modules.ElytraMace;
import com.elytramace.addon.modules.ModuleExample;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class ElytraMaceAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Elytra Mace");
    public static final HudGroup HUD_GROUP = new HudGroup("Elytra Mace");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Elytra Mace addon");

        // Modules
        Modules.get().add(new ElytraMace());
        Modules.get().add(new ModuleExample());

        // Commands
        Commands.add(new CommandExample());

        // HUD
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.elytramace.addon";
    }
}
