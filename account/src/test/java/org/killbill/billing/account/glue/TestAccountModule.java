/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014 Groupon, Inc
 * Copyright 2014 The Billing Project, LLC
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

package org.killbill.billing.account.glue;

import org.killbill.billing.mock.glue.MockSubscriptionModule;
import org.killbill.billing.mock.glue.MockTenantModule;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.billing.util.glue.AuditModule;
import org.killbill.billing.util.glue.CacheModule;
import org.killbill.billing.util.glue.CallContextModule;
import org.killbill.billing.util.glue.ConfigModule;
import org.killbill.billing.util.glue.CustomFieldModule;
import org.killbill.billing.util.glue.EventModule;
import org.killbill.billing.util.glue.TagStoreModule;
import org.killbill.commons.metrics.api.MetricRegistry;
import org.killbill.commons.metrics.impl.NoOpMetricRegistry;

public class TestAccountModule extends DefaultAccountModule {

    public TestAccountModule(final KillbillConfigSource configSource) {
        super(configSource);
    }

    @Override
    protected void configure() {
        super.configure();

        install(new AuditModule(configSource));
        install(new CacheModule(configSource));
        install(new ConfigModule(configSource));
        install(new EventModule(configSource));
        install(new CallContextModule(configSource));
        install(new CustomFieldModule(configSource));
        install(new MockTenantModule(configSource));
        // Needed for Audit
        install(new MockSubscriptionModule(configSource));
        install(new TagStoreModule(configSource));

        bind(MetricRegistry.class).to(NoOpMetricRegistry.class).asEagerSingleton();
    }
}
