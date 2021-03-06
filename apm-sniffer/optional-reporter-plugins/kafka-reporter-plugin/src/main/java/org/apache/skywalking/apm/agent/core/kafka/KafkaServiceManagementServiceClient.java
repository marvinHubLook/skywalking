/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.agent.core.kafka;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.utils.Bytes;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.DefaultNamedThreadFactory;
import org.apache.skywalking.apm.agent.core.boot.OverrideImplementor;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.os.OSUtil;
import org.apache.skywalking.apm.agent.core.remote.ServiceManagementClient;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.management.v3.InstancePingPkg;
import org.apache.skywalking.apm.network.management.v3.InstanceProperties;
import org.apache.skywalking.apm.util.RunnableWithExceptionProtection;
import org.apache.skywalking.apm.util.StringUtil;

/**
 * A service management data(Instance registering properties and Instance pinging) reporter.
 */
@OverrideImplementor(ServiceManagementClient.class)
public class KafkaServiceManagementServiceClient implements BootService, Runnable {
    private static final ILog logger = LogManager.getLogger(KafkaServiceManagementServiceClient.class);

    private static List<KeyStringValuePair> SERVICE_INSTANCE_PROPERTIES;

    private static final String TOPIC_KEY_REGISTER = "register-";

    private ScheduledFuture<?> heartbeatFuture;
    private KafkaProducer<String, Bytes> producer;

    private String topic;

    @Override
    public void prepare() {
        topic = KafkaReporterPluginConfig.Plugin.Kafka.TOPIC_MANAGEMENT;

        SERVICE_INSTANCE_PROPERTIES = new ArrayList<>();
        for (String key : Config.Agent.INSTANCE_PROPERTIES.keySet()) {
            SERVICE_INSTANCE_PROPERTIES.add(KeyStringValuePair.newBuilder()
                                                              .setKey(key)
                                                              .setValue(Config.Agent.INSTANCE_PROPERTIES.get(key))
                                                              .build());
        }

        Config.Agent.INSTANCE_NAME = StringUtil.isEmpty(Config.Agent.INSTANCE_NAME)
            ? UUID.randomUUID().toString().replaceAll("-", "") + "@" + OSUtil.getIPV4()
            : Config.Agent.INSTANCE_NAME;
    }

    @Override
    public void boot() {
        producer = ServiceManager.INSTANCE.findService(KafkaProducerManager.class).getProducer();

        heartbeatFuture = Executors.newSingleThreadScheduledExecutor(
            new DefaultNamedThreadFactory("ServiceManagementClientKafkaProducer")
        ).scheduleAtFixedRate(new RunnableWithExceptionProtection(
            this,
            t -> logger.error("unexpected exception.", t)
        ), 0, Config.Collector.HEARTBEAT_PERIOD, TimeUnit.SECONDS);

        InstanceProperties instance = InstanceProperties.newBuilder()
                                                        .setService(Config.Agent.SERVICE_NAME)
                                                        .setServiceInstance(Config.Agent.INSTANCE_NAME)
                                                        .addAllProperties(OSUtil.buildOSInfo(
                                                            Config.OsInfo.IPV4_LIST_SIZE))
                                                        .addAllProperties(SERVICE_INSTANCE_PROPERTIES)
                                                        .build();
        producer.send(new ProducerRecord<>(topic, TOPIC_KEY_REGISTER + instance.getServiceInstance(), Bytes.wrap(instance.toByteArray())));
        producer.flush();
    }

    @Override
    public void run() {
        InstancePingPkg ping = InstancePingPkg.newBuilder()
                                              .setService(Config.Agent.SERVICE_NAME)
                                              .setServiceInstance(Config.Agent.INSTANCE_NAME)
                                              .build();
        if (logger.isDebugEnable()) {
            logger.debug("Heartbeat reporting, instance: {}", ping.getServiceInstance());
        }
        producer.send(new ProducerRecord<>(ping.getServiceInstance(), Bytes.wrap(ping.toByteArray())));
    }

    @Override
    public void onComplete() {

    }

    @Override
    public void shutdown() {
        heartbeatFuture.cancel(true);
    }
}
