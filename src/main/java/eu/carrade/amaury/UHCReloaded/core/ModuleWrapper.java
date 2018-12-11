/*
 * Copyright or © or Copr. Amaury Carrade (2014 - 2016)
 *
 * http://amaury.carrade.eu
 *
 * This software is governed by the CeCILL-B license under French law and
 * abiding by the rules of distribution of free software.  You can  use,
 * modify and/ or redistribute the software under the terms of the CeCILL-B
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability.
 *
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or
 * data to be ensured and,  more generally, to use and operate it in the
 * same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL-B license and that you accept its terms.
 */
package eu.carrade.amaury.UHCReloaded.core;

import com.google.common.base.CaseFormat;
import eu.carrade.amaury.UHCReloaded.core.events.ModuleLoadedEvent;
import eu.carrade.amaury.UHCReloaded.core.events.ModuleUnloadedEvent;
import fr.zcraft.zlib.components.configuration.ConfigurationInstance;
import fr.zcraft.zlib.core.ZLib;
import fr.zcraft.zlib.tools.PluginLogger;
import fr.zcraft.zlib.tools.reflection.Reflection;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;


public class ModuleWrapper
{
    private final String name;
    private final String description;
    private final ModuleInfo.ModuleLoadTime when;

    private final Class<? extends UHModule> moduleClass;

    private final Class<? extends ConfigurationInstance> moduleConfiguration;
    private final String settingsFileName;
    private String[] dependencies;

    private final boolean internal;
    private final boolean canBeDisabled;

    /**
     * TODO reimplement properly activated modules
     */
    @Deprecated
    private boolean enabledAtStartup;

    private UHModule instance = null;

    public ModuleWrapper(final Class<? extends UHModule> moduleClass)
    {
        this.name = computeModuleName(moduleClass);
        this.moduleClass = moduleClass;

        final ModuleInfo info = moduleClass.getAnnotation(ModuleInfo.class);

        if (info == null)
        {
            description = "";
            internal = false;
            canBeDisabled = true;
            when = ModuleInfo.ModuleLoadTime.POST_WORLD;
            moduleConfiguration = null;
            settingsFileName = null;
            dependencies = new String[] {};
        }
        else
        {
            description = info.description();
            internal = info.internal();
            canBeDisabled = info.can_be_disabled();
            when = info.when();
            moduleConfiguration = info.settings().equals(ConfigurationInstance.class) ? null : info.settings();
            settingsFileName = info.settings_filename().isEmpty() ? null : info.settings_filename();
            dependencies = info.depends();
        }

        loadConfiguration();
    }

    /**
     * Enables this module.
     */
    public void enable()
    {
        // Check dependencies

        for (String dependency : dependencies)
        {
            final Plugin plugin = Bukkit.getPluginManager().getPlugin(dependency);
            if (plugin == null)
            {
                if (dependencies.length >= 2)
                {
                    PluginLogger.warning("Cannot enable module {0}: missing dependency {1} (depends on {2}).", name, dependency, String.join(", ", dependencies));
                }
                else
                {
                    PluginLogger.warning("Cannot enable module {0}: missing dependency {1}.", name, dependency);
                }
            }
            else if (!plugin.isEnabled())
            {
                // Ensures every dependency is available when a module is loaded.
                Bukkit.getPluginManager().enablePlugin(plugin);
            }
        }

        instance = ZLib.loadComponent(moduleClass);

        Bukkit.getPluginManager().callEvent(new ModuleLoadedEvent(this));
    }

    /**
     * Disable this module.
     */
    public void disable()
    {
        if (instance == null) return;

        instance.setEnabled(false);
        ZLib.unregisterEvents(instance);

        Bukkit.getPluginManager().callEvent(new ModuleUnloadedEvent(instance));

        instance = null;
    }

    /**
     * If this module was not yet loaded (e.g. if we're pre-game and the module loads
     * when the game starts), sets the module to be loaded (or not) when the time comes.
     *
     * @param enabledAtStartup {@code true} to register this module to be enabled at the right time.
     */
    public void setEnabledAtStartup(boolean enabledAtStartup)
    {
        if (instance != null) return;

        this.enabledAtStartup = enabledAtStartup;
    }

    /**
     * @return A name for this module. Either the provided name using {@link ModuleInfo} or a name derived from the class name.
     */
    public String getName()
    {
        return name;
    }

    /**
     * @return A description for the module, or {@code null} if none provided.
     */
    public String getDescription()
    {
        return description != null && !description.isEmpty() ? description : null;
    }

    /**
     * @return A list of external plugins this module depends on.
     */
    public String[] getDependencies()
    {
        return dependencies;
    }

    /**
     * @return When this module should be loaded.
     */
    public ModuleInfo.ModuleLoadTime getWhen()
    {
        return when;
    }

    /**
     * @return This module's base class.
     */
    public Class<? extends UHModule> getModuleClass()
    {
        return moduleClass;
    }

    /**
     * @return A {@link File} representing the configuration file on the server's filesystem.
     */
    private File getConfigurationFile()
    {
        final String settingsFileName;

        if (this.settingsFileName != null)
        {
            settingsFileName = this.settingsFileName + ".yml";
        }
        else
        {
            settingsFileName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, moduleClass.getSimpleName()) + ".yml";
        }

        return new File(ZLib.getPlugin().getDataFolder(), "modules" + File.separator + settingsFileName);
    }

    /**
     * Loads the configuration from its file and initialize the class.
     */
    private void loadConfiguration()
    {
        if (moduleConfiguration != null )
        {
            final File settingsFile = getConfigurationFile();
            try
            {
                if (!settingsFile.exists())
                {
                    try
                    {
                        settingsFile.getParentFile().mkdirs();
                        settingsFile.createNewFile();
                    }
                    catch (IOException e)
                    {
                        PluginLogger.error("Cannot create and populate {0}'s module configuration file - using default values.", e, getName());
                    }
                }

                final ConfigurationInstance settings = Reflection.instantiate(moduleConfiguration, settingsFile);
                settings.setEnabled(true);
                settings.save();
            }
            catch (NoSuchMethodException | InstantiationException | InvocationTargetException | IllegalAccessException e)
            {
                PluginLogger.info("Cannot initialize the configuration for the module {0} ({1})!", e, getName(), moduleClass.getName());
            }
        }
    }

    /**
     * @return {@code true} if this module is internal.
     */
    public boolean isInternal()
    {
        return internal;
    }

    /**
     * @return {@code true} if this module can be disabled at runtime.
     */
    public boolean canBeDisabled()
    {
        return canBeDisabled;
    }

    /**
     * @return {@code true} if this module, according to the configuration file, should be loaded at startup.
     */
    public boolean isEnabledAtStartup()
    {
        return enabledAtStartup;
    }

    /**
     * @return {@code true} if the module was loaded and enabled.
     */
    public boolean isEnabled()
    {
        return instance != null && instance.isEnabled();
    }

    /**
     * @return This module's instance.
     */
    public UHModule get()
    {
        return instance;
    }

    static String computeModuleName(Class<? extends UHModule> moduleClass)
    {
        final ModuleInfo info = moduleClass.getAnnotation(ModuleInfo.class);

        if (info == null || info.name().isEmpty())
            return StringUtils.capitalize(String.join(" ", StringUtils.splitByCharacterTypeCamelCase(moduleClass.getSimpleName())));

        else return info.name();
    }
}