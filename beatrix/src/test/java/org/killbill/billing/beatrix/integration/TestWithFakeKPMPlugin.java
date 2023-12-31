/*
 * Copyright 2014-2018 Groupon, Inc
 * Copyright 2014-2018 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.beatrix.integration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.notification.plugin.api.BroadcastMetadata;
import org.killbill.billing.notification.plugin.api.ExtBusEvent;
import org.killbill.billing.notification.plugin.api.ExtBusEventType;
import org.killbill.billing.osgi.BundleRegistry;
import org.killbill.billing.osgi.BundleWithConfig;
import org.killbill.billing.osgi.FileInstall;
import org.killbill.billing.osgi.api.PluginInfo;
import org.killbill.billing.osgi.api.PluginStateChange;
import org.killbill.billing.osgi.api.PluginsInfoApi;
import org.killbill.billing.osgi.api.config.PluginConfig;
import org.killbill.billing.osgi.api.config.PluginLanguage;
import org.killbill.billing.osgi.api.config.PluginType;
import org.killbill.billing.osgi.config.OSGIConfig;
import org.killbill.billing.osgi.pluginconf.PluginConfigException;
import org.killbill.billing.osgi.pluginconf.PluginFinder;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.commons.utils.collect.Iterables;
import org.killbill.billing.util.jackson.ObjectMapper;
import org.killbill.billing.util.nodes.NodeCommand;
import org.killbill.billing.util.nodes.NodeCommandMetadata;
import org.killbill.billing.util.nodes.NodeInfo;
import org.killbill.billing.util.nodes.NodeInfoMapper;
import org.killbill.billing.util.nodes.PluginNodeCommandMetadata;
import org.killbill.billing.util.nodes.SystemNodeCommandType;
import org.killbill.commons.eventbus.Subscribe;
import org.mockito.Mockito;
import org.osgi.framework.Bundle;
import org.osgi.framework.launch.Framework;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.joda.JodaModule;

import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Stage;
import com.google.inject.util.Modules;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.withSettings;

public class TestWithFakeKPMPlugin extends TestIntegrationBase {

    private static final String NEW_PLUGIN_NAME = "Foo";
    private static final String NEW_PLUGIN_VERSION = "2.5.7";

    @Inject
    private PluginsInfoApi pluginsInfoApi;

    @Inject
    private PluginFinder pluginFinder;

    @Override
    protected KillbillConfigSource getConfigSource(final Map<String, String> extraProperties) {
        final Map<String, String> allExtraProperties = new HashMap<String, String>(extraProperties);
        allExtraProperties.putAll(DEFAULT_BEATRIX_PROPERTIES);
        allExtraProperties.put("org.killbill.billing.util.broadcast.rate", "100ms");
        return getConfigSource(null, allExtraProperties);
    }

    public class FakeKPMPlugin {

        private final NodeInfoMapper nodeInfoMapper;
        private final ObjectMapper objectMapper;

        FakeKPMPlugin() {
            this.nodeInfoMapper = new NodeInfoMapper();
            this.objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JodaModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        @Subscribe
        public void handleExternalEvents(final ExtBusEvent extBusEvent) {
            if (extBusEvent.getEventType().equals(ExtBusEventType.BROADCAST_SERVICE)) {
                final String metadata = extBusEvent.getMetaData();
                try {
                    final BroadcastMetadata broadcastMetadata = objectMapper.readValue(metadata, BroadcastMetadata.class);

                    final PluginNodeCommandMetadata nodeCommandMetadata = (PluginNodeCommandMetadata) nodeInfoMapper.deserializeNodeCommand(broadcastMetadata.getEventJson(), broadcastMetadata.getCommandType());
                    ((FakePluginFinder) pluginFinder).addPlugin(createPluginConfig(nodeCommandMetadata));

                    pluginsInfoApi.notifyOfStateChanged(PluginStateChange.NEW_VERSION, nodeCommandMetadata.getPluginKey(), nodeCommandMetadata.getPluginName(), nodeCommandMetadata.getPluginVersion(), PluginLanguage.JAVA);

                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private PluginConfig createPluginConfig(final PluginNodeCommandMetadata nodeCommandMetadata) {
        return new PluginConfig() {
            @Override
            public int compareTo(final PluginConfig o) {
                return 0;
            }

            @Override
            public String getPluginKey() {
                return nodeCommandMetadata.getPluginKey();
            }

            @Override
            public String getPluginName() {
                return nodeCommandMetadata.getPluginName();
            }

            @Override
            public PluginType getPluginType() {
                return PluginType.NOTIFICATION;
            }

            @Override
            public String getVersion() {
                return nodeCommandMetadata.getPluginVersion();
            }

            @Override
            public String getPluginVersionnedName() {
                return getPluginName() + "-" + getVersion();
            }

            @Override
            public File getPluginVersionRoot() {
                return null;
            }

            @Override
            public PluginLanguage getPluginLanguage() {
                return PluginLanguage.JAVA;
            }

            @Override
            public boolean isSelectedForStart() {
                return true;
            }

            @Override
            public boolean isDisabled() {
                return false;
            }
        };
    }

    // We override the  BundleRegistry to bypass the bundle installation and yet return our new bundle as being installed.
    private static class FakePluginFinder extends PluginFinder {

        @Inject
        public FakePluginFinder(final OSGIConfig osgiConfig) {
            super(osgiConfig);
        }

        public void reloadPlugins() throws PluginConfigException, IOException {
        }

        public void addPlugin(final PluginConfig newPlugin) {
            final Map<String, LinkedList<PluginConfig>> allPluginField = getAllPluginField();

            allPluginField.clear();
            if (allPluginField.get(newPlugin.getPluginName()) == null) {
                allPluginField.put(newPlugin.getPluginName(), new LinkedList<PluginConfig>());
            }
            allPluginField.get(newPlugin.getPluginName()).add(newPlugin);
        }

        private Map<String, LinkedList<PluginConfig>> getAllPluginField() {
            try {
                final Field f = PluginFinder.class.getDeclaredField("allPlugins");
                f.setAccessible(true);
                return (Map<String, LinkedList<PluginConfig>>) f.get(this);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException("Failed to retrieve private field allPlugins from PluginFinder class ", e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to retrieve private field allPlugins from PluginFinder class ", e);
            }
        }
    }

    private static class FakeBundleRegistry extends BundleRegistry {

        private final List<BundleWithMetadata> bundles;

        @Inject
        public FakeBundleRegistry() {
            super(Mockito.mock(FileInstall.class, withSettings().defaultAnswer(RETURNS_MOCKS)));
            bundles = new ArrayList<BundleWithMetadata>();
        }

        @Override
        public void installBundles(final Framework framework) {
            super.installBundles(framework);

            final Bundle bundle = Mockito.mock(Bundle.class);
            Mockito.when(bundle.getSymbolicName()).thenReturn(NEW_PLUGIN_NAME);

            final BundleWithConfig config = new BundleWithConfig(bundle, new PluginConfig() {
                @Override
                public int compareTo(final PluginConfig o) {
                    return 0;
                }

                @Override
                public String getPluginKey() {
                    return null;
                }

                @Override
                public String getPluginName() {
                    return NEW_PLUGIN_NAME;
                }

                @Override
                public PluginType getPluginType() {
                    return PluginType.NOTIFICATION;
                }

                @Override
                public String getVersion() {
                    return NEW_PLUGIN_VERSION;
                }

                @Override
                public String getPluginVersionnedName() {
                    return null;
                }

                @Override
                public File getPluginVersionRoot() {
                    return null;
                }

                @Override
                public PluginLanguage getPluginLanguage() {
                    return PluginLanguage.JAVA;
                }

                @Override
                public boolean isSelectedForStart() {
                    return true;
                }

                @Override
                public boolean isDisabled() {
                    return false;
                }
            });
            bundles.add(new BundleWithMetadata(config));
        }

        public BundleWithMetadata getBundle(final String pluginName) {
            return bundles.stream()
                    .filter(input -> pluginName.equals(input.getPluginName()))
                    .findFirst().orElse(null);
        }
    }

    public static class OverrideModuleForOSGI implements Module {

        @Override
        public void configure(final Binder binder) {
            binder.bind(BundleRegistry.class).to(FakeBundleRegistry.class).asEagerSingleton();
            binder.bind(PluginFinder.class).to(FakePluginFinder.class).asEagerSingleton();
        }
    }

    @BeforeClass(groups = "slow")
    public void beforeClass() throws Exception {
        if (hasFailed()) {
            return;
        }

        final Injector g = Guice.createInjector(Stage.PRODUCTION, Modules.override(new BeatrixIntegrationModule(configSource, clock)).with(new OverrideModuleForOSGI()));
        g.injectMembers(this);
    }

    @BeforeMethod(groups = "slow")
    public void beforeMethod() throws Exception {
        if (hasFailed()) {
            return;
        }

        log.debug("RESET TEST FRAMEWORK");

        cleanupAllTables();

        clock.resetDeltaFromReality();
        busHandler.reset();

        lifecycle.fireStartupSequencePriorEventRegistration();
        busService.getBus().register(busHandler);
        externalBus.register(new FakeKPMPlugin());

        lifecycle.fireStartupSequencePostEventRegistration();
    }

    @Test(groups = "slow")
    public void testPluginInstallMechanism() throws Exception {

        final NodeCommand nodeCommand = new NodeCommand() {
            @Override
            public boolean isSystemCommandType() {
                return true;
            }

            @Override
            public String getNodeCommandType() {
                return SystemNodeCommandType.INSTALL_PLUGIN.name();
            }

            @Override
            public NodeCommandMetadata getNodeCommandMetadata() {
                return new PluginNodeCommandMetadata(NEW_PLUGIN_NAME, NEW_PLUGIN_NAME, NEW_PLUGIN_VERSION, Collections.emptyList());
            }
        };
        busHandler.pushExpectedEvent(NextEvent.BROADCAST_SERVICE);
        nodesApi.triggerNodeCommand(nodeCommand, false);
        assertListenerStatus();

        // Exit condition is based on the new config being updated on disk
        await().atMost(3, SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                final Iterable<NodeInfo> rawNodeInfos = nodesApi.getNodesInfo();
                final List<NodeInfo> nodeInfos = Iterables.toUnmodifiableList(rawNodeInfos);
                Assert.assertEquals(nodeInfos.size(), 1);

                final NodeInfo nodeInfo = nodeInfos.get(0);
                final Iterable<PluginInfo> rawPluginInfos = nodeInfo.getPluginInfo();
                final List<PluginInfo> pluginsInfo = Iterables.toUnmodifiableList(rawPluginInfos);

                if (pluginsInfo.size() == 1) {
                    final PluginInfo pluginInfo = pluginsInfo.get(0);
                    Assert.assertEquals(pluginInfo.getPluginName(), NEW_PLUGIN_NAME);
                    Assert.assertEquals(pluginInfo.getVersion(), NEW_PLUGIN_VERSION);
                }
                return pluginsInfo.size() == 1;
            }
        });
    }

}
