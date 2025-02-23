/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
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
package org.jboss.as.test.integration.domain.suites;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CHILD_TYPE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_CHILDREN_NAMES_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.as.jmx.model.ModelControllerMBeanHelper;
import org.jboss.as.network.NetworkUtils;
import org.jboss.as.test.integration.domain.management.util.DomainLifecycleUtil;
import org.jboss.as.test.integration.domain.management.util.DomainTestSupport;
import org.jboss.as.test.integration.domain.management.util.WildFlyManagedConfiguration;
import org.jboss.dmr.ModelNode;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xnio.IoUtils;

/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
public class JMXHostSubsystemTestCase {

    private static final String RESOLVED_DOMAIN = "jboss.as";

    static final ObjectName RESOLVED_MODEL_FILTER = createObjectName(RESOLVED_DOMAIN  + ":*");
    static final ObjectName RESOLVED_ROOT_MODEL_NAME = ModelControllerMBeanHelper.createRootObjectName(RESOLVED_DOMAIN);


    private static DomainTestSupport testSupport;
    private static DomainLifecycleUtil domainMasterLifecycleUtil;
    private static DomainLifecycleUtil domainSlaveLifecycleUtil;
    private DomainClient masterClient;
    private DomainClient slaveClient;
    JMXConnector masterConnector;
    MBeanServerConnection masterConnection;
    JMXConnector slaveConnector;
    MBeanServerConnection slaveConnection;


    @BeforeClass
    public static void setupDomain() throws Exception {
        testSupport = DomainTestSuite.createSupport(JMXHostSubsystemTestCase.class.getSimpleName());
        domainMasterLifecycleUtil = testSupport.getDomainMasterLifecycleUtil();
        domainSlaveLifecycleUtil = testSupport.getDomainSlaveLifecycleUtil();
    }

    @AfterClass
    public static void tearDownDomain() throws Exception {
        testSupport = null;
        domainMasterLifecycleUtil = null;
        domainSlaveLifecycleUtil = null;
        DomainTestSuite.stopSupport();
    }


    @Before
    public void initialize() throws Exception {
        masterClient = domainMasterLifecycleUtil.getDomainClient();
        masterConnector = setupAndGetConnector(domainMasterLifecycleUtil);
        masterConnection = masterConnector.getMBeanServerConnection();
        slaveClient = domainSlaveLifecycleUtil.getDomainClient();
        slaveConnector = setupAndGetConnector(domainSlaveLifecycleUtil);
        slaveConnection = slaveConnector.getMBeanServerConnection();
    }

    @After
    public void closeConnection() throws Exception {
        IoUtils.safeClose(masterConnector);
        IoUtils.safeClose(slaveConnector);
    }

    /**
     * Test that all the MBean infos can be read properly
     */
    @Test
    public void testAllMBeanInfosMaster() throws Exception {
        testAllMBeanInfos(masterConnection);
    }

    /**
     * Test that all the MBean infos can be read properly
     */
    @Test
    public void testAllMBeanInfosSlave() throws Exception {
        testAllMBeanInfos(slaveConnection);
    }

    private void testAllMBeanInfos(MBeanServerConnection connection) throws Exception {
        Set<ObjectName> names = connection.queryNames(RESOLVED_MODEL_FILTER, null);
        Map<ObjectName, Exception> failedInfos = new HashMap<ObjectName, Exception>();

        for (ObjectName name : names) {
            try {
                Assert.assertNotNull(connection.getMBeanInfo(name));
            } catch (Exception e) {
                System.out.println("Error getting info for " + name);
                failedInfos.put(name, e);
            }
        }
        Assert.assertTrue(failedInfos.toString(), failedInfos.isEmpty());
    }

    @Test
    public void testSystemPropertiesMaster() throws Exception {
        //testDomainModelSystemProperties(masterClient, true, masterConnection);
        //For now disable writes on the master while we decide if it is a good idea or not
        testDomainModelSystemProperties(masterClient, false, masterConnection);
    }

    @Test
    public void testSystemPropertiesSlave() throws Exception {
        testDomainModelSystemProperties(slaveClient, false, slaveConnection);
    }

    private void testDomainModelSystemProperties(DomainClient client, boolean master, MBeanServerConnection connection) throws Exception {
        String[] initialNames = getSystemPropertyNames(client);

        ObjectName testName = new ObjectName(RESOLVED_DOMAIN + ":system-property=mbeantest");
        assertNoMBean(testName, connection);

        MBeanInfo info = connection.getMBeanInfo(RESOLVED_ROOT_MODEL_NAME);
        MBeanOperationInfo opInfo = null;
        for (MBeanOperationInfo op : info.getOperations()) {
            if (op.getName().equals("addSystemProperty")) {
                Assert.assertNull(opInfo); //Simple check to guard against the op being overloaded
                opInfo = op;

            }
        }
        Assert.assertEquals(master, opInfo != null);

        try {
            connection.invoke(RESOLVED_ROOT_MODEL_NAME, "addSystemProperty", new Object[] {"mbeantest", false, "800"}, new String[] {String.class.getName(), Boolean.class.getName(), String.class.getName()});
            Assert.assertTrue(master);//The invoke should not work if it is a slave since the domain model is only writable from the slave
        } catch (Exception e) {
            if (master) {
                //There should be no exception executing the invoke from a master HC
                throw e;
            }
            //Expected for a slave; we can't do any more
            return;
        }
        try {
            String[] newNames = getSystemPropertyNames(client);
            Assert.assertEquals(initialNames.length + 1, newNames.length);
            boolean found = false;
            for (String s : newNames) {
                if (s.equals("mbeantest")) {
                    found = true;
                    break;
                }
            }
            Assert.assertTrue(found);
            Assert.assertNotNull(connection.getMBeanInfo(new ObjectName(RESOLVED_DOMAIN + ":system-property=mbeantest")));
        } finally {
            connection.invoke(new ObjectName(RESOLVED_DOMAIN + ":system-property=mbeantest"), "remove", new Object[0], new String[0]);
        }

        assertNoMBean(testName, connection);

        Assert.assertEquals(initialNames.length, getSystemPropertyNames(client).length);
    }

    @Test
    public void testNoSlaveMBeansVisibleFromMaster() throws Exception {
        testNoMBeansVisible(masterConnection, true);
    }

    @Test
    public void testNoMasterMBeansVisibleFromSlave() throws Exception {
        testNoMBeansVisible(slaveConnection, false);
    }

    private void testNoMBeansVisible(MBeanServerConnection connection, boolean master) throws Exception {
        String pattern = "jboss.as:host=%s,extension=org.jboss.as.jmx";
        ObjectName mine = createObjectName(String.format(pattern, master ? "primary" : "secondary"));
        ObjectName other = createObjectName(String.format(pattern, master ? "secondary" : "primary"));
        assertNoMBean(other, connection);
        Assert.assertNotNull(connection.getMBeanInfo(mine));
    }

    private void assertNoMBean(ObjectName name, MBeanServerConnection connection) throws Exception {
        try {
            connection.getMBeanInfo(name);
            Assert.fail("Should not have found mbean with nane " + name);
        } catch (InstanceNotFoundException expected) {
        }
    }

    private String[] getSystemPropertyNames(DomainClient client) throws Exception {
        ModelNode op = new ModelNode();
        op.get(OP).set(READ_CHILDREN_NAMES_OPERATION);
        op.get(OP_ADDR).setEmptyList();
        op.get(CHILD_TYPE).set("system-property");

        ModelNode result = client.execute(op);
        Assert.assertEquals(SUCCESS, result.get(OUTCOME).asString());
        List<ModelNode> propertyNames = result.get(RESULT).asList();
        String[] names = new String[propertyNames.size()];
        int i = 0;
        for (ModelNode node : propertyNames) {
            names[i++] = node.asString();
        }
        return names;
    }


    private static ObjectName createObjectName(String name) {
        try {
            return ObjectName.getInstance(name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JMXConnector setupAndGetConnector(DomainLifecycleUtil util) throws Exception {
        WildFlyManagedConfiguration config = util.getConfiguration();
        // Make sure that we can connect to the MBean server
        String urlString = System
                .getProperty("jmx.service.url", "service:jmx:remoting-jmx://" + NetworkUtils.formatPossibleIpv6Address(config.getHostControllerManagementAddress()) + ":" + config.getHostControllerManagementPort());
        JMXServiceURL serviceURL = new JMXServiceURL(urlString);
        return JMXConnectorFactory.connect(serviceURL, null);
    }
}
