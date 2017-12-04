/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.fetchmail;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.ConfigurationNode;
import org.apache.james.dnsservice.api.DNSService;
import org.apache.james.domainlist.api.DomainList;
import org.apache.james.lifecycle.api.Configurable;
import org.apache.james.lifecycle.api.LogEnabled;
import org.apache.james.queue.api.MailQueue;
import org.apache.james.queue.api.MailQueueFactory;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.util.concurrent.JMXEnabledScheduledThreadPoolExecutor;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

/**
 * A class to instantiate and schedule a set of mail fetching tasks
 */
public class FetchScheduler implements FetchSchedulerMBean, LogEnabled, Configurable {

    public static final String DYNAMIC_FETCHMAIL = "dynamic-fetchmail.xml";
    public static final String FETCH_DEFINITION = "fetchDefinition";
    private static final String MAILS = "mails";
    /**
     * Configuration object for this service
     */
    private HierarchicalConfiguration conf;

    /**
     * Whether this service is enabled.
     */
    private volatile boolean enabled = false;

    private final List<ScheduledFuture<?>> schedulers = new ArrayList<ScheduledFuture<?>>();

    private final Map<String, Integer> emailToScheduler = new HashMap<String, Integer>();

    private DNSService dns;

    private UsersRepository urepos;

    private Logger logger;

    private MailQueueFactory queueFactory;

    private DomainList domainList;

    private ScheduledExecutorService scheduler;

    private MailQueue queue;

    private File dynamicFetchXmlFile;

    private XMLConfiguration dynamicFetchMails;

    private Map<String, HierarchicalConfiguration> providerDefinitions;

    @Inject
    public void setMailQueueFactory(MailQueueFactory queueFactory) {
        this.queueFactory = queueFactory;
    }

    @Inject
    public void setDNSService(DNSService dns) {
        this.dns = dns;
    }

    @Inject
    public void setUsersRepository(UsersRepository urepos) {
        this.urepos = urepos;
    }

    @Inject
    public void setDomainList(DomainList domainList) {
        this.domainList = domainList;
    }

    /**
     * @see org.apache.james.lifecycle.api.LogEnabled#setLog(org.slf4j.Logger)
     */
    public final void setLog(Logger logger) {
        this.logger = logger;
    }

    /**
     * @see org.apache.james.lifecycle.api.Configurable#configure(org.apache.commons.configuration.HierarchicalConfiguration)
     */
    public final void configure(HierarchicalConfiguration config) throws ConfigurationException {
        this.conf = config;
    }

    @PostConstruct
    public void init() throws Exception {
        enabled = conf.getBoolean("[@enabled]", false);
        if (enabled) {
            int numThreads = conf.getInt("threads", 5);
            String jmxName = conf.getString("jmxName", "fetchmail");
            String jmxPath = "org.apache.james:type=component,name=" + jmxName + ",sub-type=threadpool";



            /*
      The scheduler service that is used to trigger fetch tasks.
     */
            scheduler = new JMXEnabledScheduledThreadPoolExecutor(numThreads, jmxPath, "scheduler");
            queue = queueFactory.getQueue(MailQueueFactory.SPOOL);
            List<HierarchicalConfiguration> fetchConfs = conf.configurationsAt("fetch");
            for (HierarchicalConfiguration fetchConf : fetchConfs) {
                // read configuration
                Long interval = fetchConf.getLong("interval");

                FetchMail fetcher = new FetchMail();

                fetcher.setLog(logger);
                fetcher.setDNSService(dns);
                fetcher.setUsersRepository(urepos);
                fetcher.setMailQueue(queue);
                fetcher.setDomainList(domainList);

                fetcher.configure(fetchConf);
                // initialize scheduling
                schedulers.add(scheduler.scheduleWithFixedDelay(fetcher, 0, interval, TimeUnit.MILLISECONDS));
            }

            initProviders();

            dynamicFetchXmlFile = new File(".." + File.separator + "conf" + File.separator + DYNAMIC_FETCHMAIL);
            dynamicFetchMails = new XMLConfiguration(dynamicFetchXmlFile);
            try {
                List<HierarchicalConfiguration> dynamicFetchConfs = dynamicFetchMails.configurationAt(MAILS).configurationsAt(FETCH_DEFINITION);
                int i = 0;
                for (HierarchicalConfiguration fetchConf : dynamicFetchConfs) {
                    // read configuration
                    Long interval = fetchConf.getLong("interval");

                    FetchMail fetcher = new FetchMail();

                    fetcher.setLog(logger);
                    fetcher.setDNSService(dns);
                    fetcher.setUsersRepository(urepos);
                    fetcher.setMailQueue(queue);
                    fetcher.setDomainList(domainList);

                    fetcher.configure(fetchConf);
                    // initialize scheduling
                    schedulers.add(scheduler.scheduleWithFixedDelay(fetcher, 0, interval, TimeUnit.MILLISECONDS));
                    emailToScheduler.put(fetcher.getStaticAccounts().get(0).getUser(), i);
                    i++;
                }
            } catch (IllegalArgumentException e) {
                logger.error("Error during initializing dynamic fetchmails entries", e);
            }

            if (logger.isInfoEnabled())
                logger.info("FetchMail Started");
        } else {
            if (logger.isInfoEnabled())
                logger.info("FetchMail Disabled");
        }
    }

    private void initProviders() {
        final List<HierarchicalConfiguration> configurations = conf.configurationsAt(FETCH_DEFINITION);

        providerDefinitions = new HashMap<String, HierarchicalConfiguration>(configurations.size());

        for (HierarchicalConfiguration configuration : configurations) {
            final List<ConfigurationNode> names = configuration.getRootNode().getAttributes("name");
            for (ConfigurationNode configurationNode : names) {
                providerDefinitions.put(configurationNode.getValue().toString(), configuration);
            }
        }
    }

    @PreDestroy
    public void dispose() throws IOException {
        if (enabled) {
            logger.info("FetchMail dispose...");
            for (ScheduledFuture<?> scheduler1 : schedulers) {
                scheduler1.cancel(false);
            }
            saveDynamicFetchMails();
            logger.info("FetchMail ...dispose end");
        }
    }

    private void saveDynamicFetchMails() throws IOException {
        try {
            dynamicFetchMails.save(dynamicFetchXmlFile);
        } catch (ConfigurationException e) {
            logger.error("", e);
        }
    }

    /**
     * Describes whether this service is enabled by configuration.
     *
     * @return is the service enabled.
     */
    public final boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean addFetchMailConfig(final String provider, String email, String password) throws ConfigurationException {
        if (enabled && providerDefinitions.containsKey(provider)) {
            final HierarchicalConfiguration configuration = (HierarchicalConfiguration) providerDefinitions.get(provider).clone();
            FetchMail fetcher = new FetchMail();

            fetcher.setDNSService(dns);
            fetcher.setUsersRepository(urepos);
            fetcher.setMailQueue(queue);
            fetcher.setDomainList(domainList);
            fetcher.configure(configuration);

            final List<Account> staticAccounts = fetcher.getStaticAccounts();
            final Account account = staticAccounts.get(0);
            account.setUser(email);
            account.setPassword(password);

            schedulers.add(scheduler.scheduleWithFixedDelay(fetcher, 0, configuration.getLong("interval"), TimeUnit.MILLISECONDS));
            emailToScheduler.put(email, schedulers.size() - 1);
            final ConfigurationNode rootNode = configuration.getRootNode();
            configuration.getRootNode().getAttributes("name").get(0).setValue(email);
            final ConfigurationNode accountNodes = rootNode.getChildren("accounts").get(0);
            accountNodes.getChild(0).getAttributes("user").get(0).setValue(email);
            accountNodes.getChild(0).getAttributes("password").get(0).setValue(password);
            dynamicFetchMails.addNodes(MAILS, Collections.singleton(rootNode));
            return true;
        }
        return false;
    }

    @Override
    public boolean deleteFetchMailConfig(String email) throws ConfigurationException {
        return deleteFetchMail(email);
    }

    private boolean deleteFetchMail(String email) {
        if (enabled) {
            final List<HierarchicalConfiguration> dynamicFetchMailsConfigs = dynamicFetchMails.configurationAt(MAILS).configurationsAt(FETCH_DEFINITION);
            final int indexToDelete = getIndexFromConfig(dynamicFetchMailsConfigs, email);
            if (indexToDelete != -1) {
                dynamicFetchMails.clearTree(MAILS + "." + FETCH_DEFINITION + "(" + indexToDelete + ")");
                final Integer schedulerIndex = emailToScheduler.get(email);
                schedulers.get(schedulerIndex).cancel(false);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean updateFetchMailConfig(String provider, String email, String password) throws ConfigurationException {
        if(deleteFetchMail(email)){
            return addFetchMailConfig(provider, email, password);
        }
        return false;
    }

    private int getIndexFromConfig(List<HierarchicalConfiguration> dynamicFetchMailsConfigs, String email) {
        for (int i = 0; i < dynamicFetchMailsConfigs.size(); i++) {
            final HierarchicalConfiguration config = dynamicFetchMailsConfigs.get(i);
            final ConfigurationNode rootNode = config.getRootNode();
            if (email.equals(rootNode.getAttributes("name").get(0).getValue())) {
                final ConfigurationNode accountNodes = rootNode.getChildren("accounts").get(0);
                final Object userObj = accountNodes.getChild(0).getAttributes("user").get(0).getValue();
                if (email.equals(userObj)) {
                    return i;
                }
            }
        }
        return -1;
    }

}
