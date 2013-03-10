/**
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant;

import io.informant.api.PluginServices;
import io.informant.config.ConfigModule;
import io.informant.core.CoreModule;
import io.informant.local.store.DataSourceModule;
import io.informant.local.store.StorageModule;
import io.informant.local.ui.LocalUiModule;
import io.informant.marker.OnlyUsedByTests;
import io.informant.marker.ThreadSafe;

import java.lang.instrument.ClassFileTransformer;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.ReadOnly;

import com.google.common.annotations.VisibleForTesting;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@VisibleForTesting
@ThreadSafe
public class InformantModule {

    private static final Logger logger = LoggerFactory.getLogger(InformantModule.class);

    private final ConfigModule configModule;
    private final DataSourceModule dataSourceModule;
    private final StorageModule storageModule;
    private final CoreModule coreModule;
    private final LocalUiModule uiModule;

    InformantModule(@ReadOnly Map<String, String> properties) throws Exception {
        configModule = new ConfigModule(properties);
        dataSourceModule = new DataSourceModule(configModule, properties);
        storageModule = new StorageModule(configModule, dataSourceModule);
        coreModule = new CoreModule(configModule, storageModule.getSnapshotSink());
        uiModule = new LocalUiModule(configModule, dataSourceModule, storageModule, coreModule,
                properties);
    }

    ClassFileTransformer createWeavingClassFileTransformer() {
        return coreModule.createWeavingClassFileTransformer();
    }

    PluginServices getPluginServices(String pluginId) {
        return coreModule.getPluginServices(pluginId);
    }

    @OnlyUsedByTests
    public CoreModule getCoreModule() {
        return coreModule;
    }

    @OnlyUsedByTests
    public ConfigModule getConfigModule() {
        return configModule;
    }

    @OnlyUsedByTests
    public DataSourceModule getDataSourceModule() {
        return dataSourceModule;
    }

    @OnlyUsedByTests
    public StorageModule getStorageModule() {
        return storageModule;
    }

    @OnlyUsedByTests
    public LocalUiModule getUiModule() {
        return uiModule;
    }

    @OnlyUsedByTests
    public void close() {
        logger.debug("shutdown()");
        uiModule.close();
        coreModule.close();
        storageModule.close();
        dataSourceModule.close();
    }
}