/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.integration.domain.slavereconnect;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.BLOCKING;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CHILD_TYPE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PORT_OFFSET;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_ATTRIBUTE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_CHILDREN_NAMES_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_CONFIG;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.START;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STOP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.util.List;

import javax.security.auth.callback.CallbackHandler;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.test.integration.domain.management.util.Authentication;
import org.jboss.as.test.integration.domain.management.util.DomainLifecycleUtil;
import org.jboss.as.test.integration.domain.management.util.DomainTestSupport;
import org.jboss.as.test.integration.domain.management.util.DomainTestUtils;
import org.jboss.as.test.integration.domain.management.util.WildFlyManagedConfiguration;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.dmr.ModelNode;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Wrapper for several tests which require a slave to master reconnect, so that we need as few reconnects as possible.
 * It makes some assumptions about the HC/server process states,
 * so don't run this within a suite.
 *
 * @author Kabir Khan
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SlaveReconnectTestCase {

    static final PathAddress SLAVE_ADDR = PathAddress.pathAddress(HOST, "secondary");

    private static DomainTestSupport testSupport;
    private static DomainLifecycleUtil domainMasterLifecycleUtil;
    private static DomainLifecycleUtil domainSlaveLifecycleUtil;

    private static final int ADJUSTED_SECOND = TimeoutUtil.adjust(1000);
    private static final String RIGHT_PASSWORD = DomainLifecycleUtil.SLAVE_HOST_PASSWORD;


    @BeforeClass
    public static void setupDomain() throws Exception {
        testSupport = DomainTestSupport.create(
                DomainTestSupport.Configuration.create(SlaveReconnectTestCase.class.getSimpleName(),
                        "domain-configs/domain-standard.xml", "host-configs/host-primary.xml", "host-configs/host-secondary.xml"));

        WildFlyManagedConfiguration masterConfig = testSupport.getDomainMasterConfiguration();
        CallbackHandler callbackHandler = Authentication.getCallbackHandler("secondary", RIGHT_PASSWORD, "ManagementRealm");
        masterConfig.setCallbackHandler(callbackHandler);

        WildFlyManagedConfiguration slaveConfig = testSupport.getDomainSlaveConfiguration();
        slaveConfig.setCallbackHandler(callbackHandler);

        testSupport.start();

        domainMasterLifecycleUtil = testSupport.getDomainMasterLifecycleUtil();
        domainSlaveLifecycleUtil = testSupport.getDomainSlaveLifecycleUtil();
    }

    @AfterClass
    public static void tearDownDomain() throws Exception {
        testSupport.close();
        testSupport = null;
        domainMasterLifecycleUtil = null;
        domainSlaveLifecycleUtil = null;
    }

    @Test
    public void test01_OrderedExtensionsAndDeployments() throws Exception {
        testReconnect(new ReconnectTestScenario[]{
                new UnaffectedScenario(650),
                new OrderedChildResourceScenario(),
                new DeploymentScenario(750)
        });
    }

    @Test
    @Ignore("[WFCORE-5549] Unable to remove JBOSS_LOCAL_USER with Elytron configuration.")
    public void test02_RBAC_user_and_model_out_of_sync() throws Exception {
        testReconnect(new ReconnectTestScenario[]{
                new RBACModelOutOfSyncScenario()
        });
    }

    @Test
    public void test03_DeploymentOverlays() throws Exception {
        //Since deployment-overlays affect all servers (https://issues.jboss.org/browse/WFCORE-710), this needs to
        //be tested separately, and to come last since the server state gets affected
        testReconnect(new ReconnectTestScenario[]{
                new UnaffectedScenario(650),
                new DeploymentOverlayScenario(750)
        });
    }

    private void testReconnect(ReconnectTestScenario[] scenarios) throws Exception {
        Throwable t = null;
        int initialisedScenarios = -1;
        try {
            DomainClient masterClient = domainMasterLifecycleUtil.getDomainClient();
            DomainClient slaveClient = domainSlaveLifecycleUtil.getDomainClient();
            for (int i = 0; i < scenarios.length; i++) {
                initialisedScenarios = i;
                scenarios[i].setUpDomain(testSupport, masterClient, slaveClient);
            }
            //server could have been reloaded in the setUpDomain, get the new clients
            masterClient = domainMasterLifecycleUtil.getDomainClient();
            slaveClient = domainSlaveLifecycleUtil.getDomainClient();

            for (ReconnectTestScenario scenario : scenarios) {
                scenario.testOnInitialStartup(masterClient, slaveClient);
            }

            //Restart the DC as admin-only
            domainMasterLifecycleUtil.reloadAdminOnly("primary");
            masterClient = domainMasterLifecycleUtil.createDomainClient();

            for (ReconnectTestScenario scenario : scenarios) {
                scenario.testWhileMasterInAdminOnly(masterClient, slaveClient);
            }

            //Restart the DC as normal
            domainMasterLifecycleUtil.reload("primary", null, false);
            masterClient = domainMasterLifecycleUtil.createDomainClient();

            //Wait for the slave to reconnect, look for the slave in the list of hosts
            long end = System.currentTimeMillis() + 20 * ADJUSTED_SECOND;
            boolean slaveReconnected = false;
            do {
                Thread.sleep(1 * ADJUSTED_SECOND);
                slaveReconnected = checkSlaveReconnected(masterClient);
            } while (!slaveReconnected && System.currentTimeMillis() < end);

            //Wait for master servers to come up
            end = System.currentTimeMillis() + 60 * ADJUSTED_SECOND;
            boolean serversUp = false;
            do {
                Thread.sleep(1 * ADJUSTED_SECOND);
                serversUp = checkHostServersStarted(masterClient, "primary");
            } while (!serversUp && System.currentTimeMillis() < end);

            for (ReconnectTestScenario scenario : scenarios) {
                scenario.testAfterReconnect(masterClient, slaveClient);
            }
        } catch (Throwable thrown) {
            t = thrown;
            if (thrown instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        finally {
            for (int i = initialisedScenarios; i >=0 ; i--) {
                try {
                    scenarios[i].tearDownDomain(
                            testSupport, domainMasterLifecycleUtil.getDomainClient(), domainSlaveLifecycleUtil.getDomainClient());
                } catch (Throwable thrown) {
                    if (t == null) {
                        t = thrown;
                    } else {
                        System.out.println("Caught second failure during cleanup following initial '" + t.toString() + "' failure. Second failure:");
                        thrown.printStackTrace(System.out);
                    }
                    if (thrown instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (t != null) {
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw (Error) t;
        }
    }

    private boolean checkSlaveReconnected(DomainClient masterClient) throws Exception {
        ModelNode op = Util.createEmptyOperation(READ_CHILDREN_NAMES_OPERATION, PathAddress.EMPTY_ADDRESS);
        op.get(CHILD_TYPE).set(HOST);
        try {
            ModelNode ret = DomainTestUtils.executeForResult(op, masterClient);
            List<ModelNode> list = ret.asList();
            if (list.size() == 2) {
                for (ModelNode entry : list) {
                    if ("secondary".equals(entry.asString())){
                        return true;
                    }
                }
            }
        } catch (Exception e) {
        }
        return false;
    }

    private boolean checkHostServersStarted(DomainClient masterClient, String host) {
        try {
            ModelNode op = Util.createEmptyOperation(READ_CHILDREN_NAMES_OPERATION, PathAddress.pathAddress(HOST, host));
            op.get(CHILD_TYPE).set(SERVER);
            ModelNode ret = DomainTestUtils.executeForResult(op, masterClient);
            List<ModelNode> list = ret.asList();
            for (ModelNode entry : list) {
                String server = entry.asString();
                op = Util.createEmptyOperation(READ_ATTRIBUTE_OPERATION, PathAddress.pathAddress(HOST, host).append(SERVER, server));
                op.get(NAME).set("server-state");
                ModelNode state = DomainTestUtils.executeForResult(op, masterClient);
                return "running".equals(state.asString());
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static void cloneProfile(DomainClient masterClient, String source, String target) throws Exception {
        ModelNode clone = Util.createEmptyOperation("clone", PathAddress.pathAddress(PROFILE, source));
        clone.get("to-profile").set(target);
        DomainTestUtils.executeForResult(clone, masterClient);
    }

    static void createServerGroup(DomainClient masterClient, String name, String profile) throws Exception {
        ModelNode add = Util.createAddOperation(PathAddress.pathAddress(SERVER_GROUP, name));
        add.get(PROFILE).set(profile);
        add.get(SOCKET_BINDING_GROUP).set("standard-sockets");
        DomainTestUtils.executeForResult(add, masterClient);
    }

    static void createServer(DomainClient slaveClient, String name, String serverGroup, int portOffset) throws Exception {
        ModelNode add = Util.createAddOperation(SLAVE_ADDR.append(SERVER_CONFIG, name));
        add.get(GROUP).set(serverGroup);
        add.get(PORT_OFFSET).set(portOffset);
        DomainTestUtils.executeForResult(add, slaveClient);
        DomainTestUtils.executeForResult(Util.createAddOperation(SLAVE_ADDR.append(SERVER_CONFIG, name).append("jvm", "default")), slaveClient);
    }

    static void startServer(DomainClient slaveClient, String name) throws Exception {
        ModelNode start = Util.createEmptyOperation(START, SLAVE_ADDR.append(SERVER_CONFIG, name));
        start.get(BLOCKING).set(true);
        DomainTestUtils.executeForResult(start, slaveClient);
    }

    static void stopServer(DomainClient slaveClient, String name) throws Exception {
        PathAddress serverAddr = SLAVE_ADDR.append(SERVER_CONFIG, name);
        ModelNode stop = Util.createEmptyOperation(STOP, serverAddr);
        stop.get(BLOCKING).set(true);
        DomainTestUtils.executeForResult(stop, slaveClient);
    }

    static void removeProfile(DomainClient masterClient, String name) throws Exception {
        PathAddress profileAddr = PathAddress.pathAddress(PROFILE, name);
        DomainTestUtils.executeForResult(Util.createRemoveOperation(profileAddr.append(SUBSYSTEM, "logging")), masterClient);
        DomainTestUtils.executeForResult(
                Util.createRemoveOperation(profileAddr), masterClient);
    }
}
