/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
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
package org.jboss.as.test.integration.domain.autoignore;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.BLOCKING;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CHILD_TYPE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DOMAIN_CONTROLLER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST_STATE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.IGNORE_UNUSED_CONFIG;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_CHILDREN_NAMES_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RELOAD_REQUIRED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOTE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESTART;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_CONFIG;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SYSTEM_PROPERTY;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.VALUE;
import static org.jboss.as.test.integration.domain.management.util.DomainTestSupport.validateFailedResponse;
import static org.jboss.as.test.integration.domain.management.util.DomainTestSupport.validateResponse;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.test.integration.domain.management.util.DomainLifecycleUtil;
import org.jboss.as.test.integration.domain.management.util.DomainTestSupport;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Tests ignore-unused-configuration=true.
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AutoIgnoredResourcesDomainTestCase {

    private static DomainTestSupport testSupport;
    private static DomainLifecycleUtil domainMasterLifecycleUtil;
    private static DomainLifecycleUtil domainSlaveLifecycleUtil;

    private static final ModelNode ROOT_ADDRESS = new ModelNode().setEmptyList();
    private static final ModelNode MASTER_ROOT_ADDRESS = new ModelNode().add(HOST, "primary");
    private static final ModelNode SLAVE_ROOT_ADDRESS = new ModelNode().add(HOST, "secondary");

    static {
        ROOT_ADDRESS.protect();
        MASTER_ROOT_ADDRESS.protect();
        SLAVE_ROOT_ADDRESS.protect();
    }

    private static final String EXTENSION_JMX = "org.jboss.as.jmx";
    private static final String EXTENSION_LOGGING = "org.jboss.as.logging";
    private static final String EXTENSION_REMOTING = "org.jboss.as.remoting";
    private static final String EXTENSION_IO = "org.wildfly.extension.io";
    private static final String EXTENSION_RC = "org.wildfly.extension.request-controller";

    private static final String ROOT_PROFILE1 = "root-profile1";
    private static final String ROOT_PROFILE2 = "root-profile2";
    private static final String PROFILE1 = "profile1";
    private static final String PROFILE2 = "profile2";
    private static final String PROFILE3 = "profile3";

    private static final String ROOT_SOCKETS1 = "root-sockets1";
    private static final String ROOT_SOCKETS2 = "root-sockets2";
    private static final String SOCKETS1 = "sockets1";
    private static final String SOCKETS2 = "sockets2";
    private static final String SOCKETS3 = "sockets3";
    private static final String SOCKETSA = "socketsA";

    private static final String GROUP1 = "group1";
    private static final String GROUP2 = "group2";

    private static final String SERVER1 = "server1";

    @BeforeClass
    public static void setupDomain() throws Exception {
        setupDomain(false, false);
    }

    public static void setupDomain(boolean slaveIsBackupDC, boolean slaveIsCachedDc) throws Exception {
        //Make all the configs read-only so we can stop and start when we like to reset
        DomainTestSupport.Configuration config = DomainTestSupport.Configuration.create(AutoIgnoredResourcesDomainTestCase.class.getSimpleName(),
                "domain-configs/domain-auto-ignore.xml", "host-configs/host-auto-ignore-primary.xml", "host-configs/host-auto-ignore-secondary.xml",
                true, true, true);
        if (slaveIsBackupDC)
            config.getSlaveConfiguration().setBackupDC(true);
        if (slaveIsCachedDc)
            config.getSlaveConfiguration().setCachedDC(true);
        testSupport = DomainTestSupport.create(config);
        // Start!
        testSupport.start();
        domainMasterLifecycleUtil = testSupport.getDomainMasterLifecycleUtil();
        domainSlaveLifecycleUtil = testSupport.getDomainSlaveLifecycleUtil();
    }

    @AfterClass
    public static void tearDownDomain() throws Exception {
        testSupport.close();
        domainMasterLifecycleUtil = null;
        domainSlaveLifecycleUtil = null;
        testSupport = null;
    }

    private DomainClient masterClient;
    private DomainClient slaveClient;

    @Before
    public void setup() throws Exception {
        masterClient = domainMasterLifecycleUtil.getDomainClient();
        slaveClient = domainSlaveLifecycleUtil.getDomainClient();
    }

    /////////////////////////////////////////////////////////////////
    // These tests check that a simple operation on the slave server
    // config pulls down the missing data from the DC

    @Test
    public void test00_CheckInitialBootExclusions() throws Exception {
        checkSlaveProfiles(PROFILE1, ROOT_PROFILE1);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP1);
        checkSlaveSocketBindingGroups(SOCKETS1, ROOT_SOCKETS1);
        checkSystemProperties(0);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }

    @Test
    public void test01_ChangeSlaveServerConfigSocketBindingGroupOverridePullsDownDataFromDc() throws Exception {
        validateResponse(slaveClient.execute(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), SOCKET_BINDING_GROUP, SOCKETSA)), false);

        checkSlaveProfiles(PROFILE1, ROOT_PROFILE1);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP1);
        checkSlaveSocketBindingGroups(SOCKETS1, ROOT_SOCKETS1, SOCKETSA);
        checkSystemProperties(0);
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveServerStatus(SERVER1));

        restartSlaveServer(SERVER1);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }

    @Test
    public void test02_ChangeSlaveServerConfigGroupPullsDownDataFromDc() throws Exception {
        validateResponse(slaveClient.execute(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), GROUP, GROUP2)), false);

        checkSlaveProfiles(PROFILE2, ROOT_PROFILE2);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP2);
        checkSlaveSocketBindingGroups(SOCKETSA, SOCKETS2, ROOT_SOCKETS2);
        checkSystemProperties(0);
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveServerStatus(SERVER1));

        restartSlaveServer(SERVER1);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }

    @Test
    public void test03_AddServerGroupAndServerConfigPullsDownDataFromDc() throws Exception {
        ModelNode addGroupOp = Util.createAddOperation(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "testgroup")));
        addGroupOp.get(PROFILE).set(PROFILE3);
        addGroupOp.get(SOCKET_BINDING_GROUP).set(SOCKETS3);
        validateResponse(masterClient.execute(addGroupOp), false);

        //New data should not be pushed yet since nothing on the slave uses it
        checkSlaveProfiles(PROFILE2, ROOT_PROFILE2);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP2);
        checkSlaveSocketBindingGroups(SOCKETSA, SOCKETS2, ROOT_SOCKETS2);
        checkSystemProperties(0);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));

        ModelNode addConfigOp = Util.createAddOperation(PathAddress.pathAddress(getSlaveServerConfigAddress("testserver")));
        addConfigOp.get(GROUP).set("testgroup");
        validateResponse(slaveClient.execute(addConfigOp), false);

        //Now that we have a group using the new data it should be pulled down
        checkSlaveProfiles(PROFILE2, PROFILE3, ROOT_PROFILE2);
        checkSlaveExtensions(EXTENSION_LOGGING, EXTENSION_JMX);
        checkSlaveServerGroups(GROUP2, "testgroup");
        checkSlaveSocketBindingGroups(SOCKETSA, SOCKETS2, SOCKETS3, ROOT_SOCKETS2);
        checkSystemProperties(0);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }

    @Test
    public void test04_RestartDomainAndReloadReadOnlyConfig() throws Exception {
        //Clean up after ourselves for the next round of tests /////////////
        restartDomainAndReloadReadOnlyConfig();
    }

    /////////////////////////////////////////////////////////////////
    // These tests use a composite to obtain the DC lock, and check
    // that an operation on the slave server config pulls down the
    // missing data from the DC

    @Test
    public void test10_ChangeSlaveServerConfigSocketBindingGroupOverridePullsDownDataFromDcWithDcLockTaken() throws Exception {
        validateResponse(slaveClient.execute(createDcLockTakenComposite(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), SOCKET_BINDING_GROUP, SOCKETSA))), false);

        checkSlaveProfiles(PROFILE1, ROOT_PROFILE1);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP1);
        checkSlaveSocketBindingGroups(SOCKETS1, ROOT_SOCKETS1, SOCKETSA);
        checkSystemProperties(1); //Composite added a property
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveServerStatus(SERVER1));

        restartSlaveServer(SERVER1);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }

    @Test
    public void test11_ChangeSlaveServerConfigGroupPullsDownDataFromDcWithDcLockTaken() throws Exception {
        validateResponse(slaveClient.execute(createDcLockTakenComposite(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), GROUP, GROUP2))), false);

        checkSlaveProfiles(PROFILE2, ROOT_PROFILE2);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP2);
        checkSlaveSocketBindingGroups(SOCKETSA, SOCKETS2, ROOT_SOCKETS2);
        checkSystemProperties(2); //Composite added a property
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveServerStatus(SERVER1));

        restartSlaveServer(SERVER1);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }

    @Test
    public void test12_AddServerGroupAndServerConfigPullsDownDataFromDcWithDcLockTaken() throws Exception {
        ModelNode addGroupOp = Util.createAddOperation(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "testgroup")));
        addGroupOp.get(PROFILE).set(PROFILE3);
        addGroupOp.get(SOCKET_BINDING_GROUP).set(SOCKETS3);
        validateResponse(masterClient.execute(createDcLockTakenComposite(addGroupOp)), false);

        //New data should not be pushed yet since nothing on the slave uses it
        checkSlaveProfiles(PROFILE2, ROOT_PROFILE2);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP2);
        checkSlaveSocketBindingGroups(SOCKETSA, SOCKETS2, ROOT_SOCKETS2);
        checkSystemProperties(3); //Composite added a property
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));

        ModelNode addConfigOp = Util.createAddOperation(PathAddress.pathAddress(getSlaveServerConfigAddress("testserver")));
        addConfigOp.get(GROUP).set("testgroup");
        validateResponse(slaveClient.execute(createDcLockTakenComposite(addConfigOp)), false);

        //Now that we have a group using the new data it should be pulled down
        checkSlaveProfiles(PROFILE2, ROOT_PROFILE2, PROFILE3);
        checkSlaveExtensions(EXTENSION_LOGGING, EXTENSION_JMX);
        checkSlaveServerGroups(GROUP2, "testgroup");
        checkSlaveSocketBindingGroups(SOCKETSA, SOCKETS2, SOCKETS3, ROOT_SOCKETS2);
        checkSystemProperties(4); //Composite added a property
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }


    @Test
    public void test13_RestartDomainAndReloadReadOnlyConfig() throws Exception {
        //Clean up after ourselves for the next round of tests /////////////
        restartDomainAndReloadReadOnlyConfig();
    }


    /////////////////////////////////////////////////////////////////
    // These tests use a composite to obtain the DC lock, and check
    // that an operation on the slave server config pulls down the
    // missing data from the DC
    // The first time this is attempted the operation will roll back
    // The second time it should succeed


    @Test
    public void test20_ChangeSlaveServerConfigSocketBindingGroupOverridePullsDownDataFromDcWithDcLockTakenAndRollback() throws Exception {
        validateFailedResponse(slaveClient.execute(createDcLockTakenCompositeWithRollback(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), SOCKET_BINDING_GROUP, SOCKETSA))));

        checkSlaveProfiles(PROFILE1, ROOT_PROFILE1);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP1);
        checkSlaveSocketBindingGroups(SOCKETS1, ROOT_SOCKETS1);
        checkSystemProperties(0);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));

        validateResponse(slaveClient.execute(createDcLockTakenComposite(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), SOCKET_BINDING_GROUP, SOCKETSA))), false);
        checkSlaveProfiles(PROFILE1, ROOT_PROFILE1);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP1);
        checkSlaveSocketBindingGroups(SOCKETS1, SOCKETSA, ROOT_SOCKETS1);
        checkSystemProperties(1); //Composite added a property
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveServerStatus(SERVER1));

        restartSlaveServer(SERVER1);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }

    @Test
    public void test21_ChangeSlaveServerConfigGroupPullsDownDataFromDcWithDcLockTakenAndRollback() throws Exception {
        validateFailedResponse(slaveClient.execute(createDcLockTakenCompositeWithRollback(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), GROUP, GROUP2))));

        checkSlaveProfiles(PROFILE1, ROOT_PROFILE1);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP1);
        checkSlaveSocketBindingGroups(SOCKETS1, SOCKETSA, ROOT_SOCKETS1);
        checkSystemProperties(1);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));

        validateResponse(slaveClient.execute(createDcLockTakenComposite(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), GROUP, GROUP2))), false);

        checkSlaveProfiles(PROFILE2, ROOT_PROFILE2);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP2);
        checkSlaveSocketBindingGroups(SOCKETSA, SOCKETS2, ROOT_SOCKETS2);
        checkSystemProperties(2); //Composite added a property
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveServerStatus(SERVER1));

        restartSlaveServer(SERVER1);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }

    @Test
    public void test22_AddServerGroupAndServerConfigPullsDownDataFromDcWithDcLockTakenAndRollback() throws Exception {
        ModelNode addGroupOp = Util.createAddOperation(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "testgroup")));
        addGroupOp.get(PROFILE).set(PROFILE3);
        addGroupOp.get(SOCKET_BINDING_GROUP).set(SOCKETS3);
        validateResponse(masterClient.execute(createDcLockTakenComposite(addGroupOp)), false);

        //New data should not be pushed yet since nothing on the slave uses it
        checkSlaveProfiles(PROFILE2, ROOT_PROFILE2);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP2);
        checkSlaveSocketBindingGroups(SOCKETSA, SOCKETS2, ROOT_SOCKETS2);
        checkSystemProperties(3); //Composite added a property
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));

        ModelNode addConfigOp = Util.createAddOperation(PathAddress.pathAddress(getSlaveServerConfigAddress("testserver")));
        addConfigOp.get(GROUP).set("testgroup");
        validateFailedResponse(slaveClient.execute(createDcLockTakenCompositeWithRollback(addConfigOp)));
        checkSlaveProfiles(PROFILE2, ROOT_PROFILE2);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP2);
        checkSlaveSocketBindingGroups(SOCKETSA, SOCKETS2, ROOT_SOCKETS2);
        checkSystemProperties(3);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));

        //Now that we have a group using the new data it should be pulled down
        validateResponse(slaveClient.execute(createDcLockTakenComposite(addConfigOp)), false);
        checkSlaveProfiles(PROFILE2, ROOT_PROFILE2, PROFILE3);
        checkSlaveExtensions(EXTENSION_LOGGING, EXTENSION_JMX);
        checkSlaveServerGroups(GROUP2, "testgroup");
        checkSlaveSocketBindingGroups(SOCKETSA, SOCKETS2, SOCKETS3, ROOT_SOCKETS2);
        checkSystemProperties(4); //Composite added a property
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }

    @Test
    public void test23_RestartDomainAndReloadReadOnlyConfig() throws Exception {
        //Clean up after ourselves for the next round of tests /////////////
        restartDomainAndReloadReadOnlyConfig();
    }

    /////////////////////////////////////////////////////////////////
    // These tests test that changing a server group on the DC
    // piggybacks missing data to the slave

    @Test
    public void test30_ChangeServerGroupSocketBindingGroupGetsPushedToSlave() throws Exception {
        ModelNode op = Util.getWriteAttributeOperation(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, GROUP1)).toModelNode(), SOCKET_BINDING_GROUP, SOCKETS2);
        validateResponse(masterClient.execute(op));

        checkSlaveProfiles(PROFILE1, ROOT_PROFILE1);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP1);
        checkSlaveSocketBindingGroups(SOCKETS2, ROOT_SOCKETS2);
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveServerStatus(SERVER1));

        restartSlaveServer(SERVER1);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }


    @Test
    public void test31_ChangeServerGroupProfileGetsPushedToSlave() throws Exception {
        ModelNode op = Util.getWriteAttributeOperation(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, GROUP1)).toModelNode(), PROFILE, PROFILE2);
        validateResponse(masterClient.execute(op));

        checkSlaveProfiles(PROFILE2, ROOT_PROFILE2);
        checkSlaveExtensions(EXTENSION_LOGGING);
        checkSlaveServerGroups(GROUP1);
        checkSlaveSocketBindingGroups(SOCKETS2, ROOT_SOCKETS2);
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveServerStatus(SERVER1));

        restartSlaveServer(SERVER1);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }

    /////////////////////////////////////////////////////////////////
    // Test deployments to a server group get picked up by a server
    // switching to it
    @Test
    public void test40_ChangeServerGroupProfileAndGetDeployment() throws Exception {

        JavaArchive deployment = ShrinkWrap.create(JavaArchive.class);
        deployment.addClasses(TestClass.class, TestClassMBean.class);

        File testMarker = new File("target" + File.separator + "testmarker");
        if (testMarker.exists()) {
            testMarker.delete();
        }
        String serviceXml = "<server xmlns=\"urn:jboss:service:7.0\"" +
                            "   xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" +
                            "   xsi:schemaLocation=\"urn:jboss:service:7.0 jboss-service_7_0.xsd\">" +
                            "   <mbean name=\"jboss:name=test,type=testclassfilemarker\" code=\"org.jboss.as.test.integration.autoignore.TestClass\">" +
                            "       <attribute name=\"path\">" + testMarker.getAbsolutePath() + "</attribute>" +
                            "    </mbean>" +
                            "</server>";
        deployment.addAsManifestResource(new StringAsset(serviceXml), "jboss-service.xml");

        InputStream in = deployment.as(ZipExporter.class).exportAsInputStream();
        masterClient.getDeploymentManager().execute(masterClient.getDeploymentManager().newDeploymentPlan().add("sardeployment.sar", in).deploy("sardeployment.sar").toServerGroup(GROUP2).build());

        ModelNode op = Util.getWriteAttributeOperation(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, GROUP2)).toModelNode(), PROFILE, PROFILE3);
        validateResponse(masterClient.execute(op));

        op = Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), GROUP, GROUP2);
        validateResponse(slaveClient.execute(op));

        checkSlaveProfiles(PROFILE3);
        checkSlaveExtensions(EXTENSION_LOGGING, EXTENSION_JMX);
        checkSlaveServerGroups(GROUP2);
        checkSlaveSocketBindingGroups(SOCKETS2, ROOT_SOCKETS2);
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveServerStatus(SERVER1));

        Assert.assertFalse(testMarker.exists());

        restartSlaveServer(SERVER1);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));

        //The mbean should have created this file
        // Assert.assertTrue(testMarker.exists());
    }

    @Test
    public void test50_RestartDomainAndReloadReadOnlyConfig() throws Exception {
        //Clean up after ourselves for the next round of tests /////////////
        restartDomainAndReloadReadOnlyConfig();
    }

    @Test
    public void test51_testCompositeOperation() throws Exception {

        final ModelNode composite = new ModelNode();
        composite.get(OP).set(COMPOSITE);
        composite.get(OP_ADDR).setEmptyList();

        final ModelNode steps = composite.get(STEPS);

        ModelNode addGroupOp = Util.createAddOperation(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "testgroup")));
        addGroupOp.get(PROFILE).set(PROFILE3);
        addGroupOp.get(SOCKET_BINDING_GROUP).set(SOCKETS3);

        steps.add(addGroupOp);
        steps.add(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), SOCKET_BINDING_GROUP, SOCKETS3));
        steps.add(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), GROUP, GROUP2));
        steps.add(Util.getWriteAttributeOperation(getSlaveServerConfigAddress(SERVER1), GROUP, "testgroup"));


        validateResponse(masterClient.execute(composite));

        checkSlaveProfiles(PROFILE3);
        checkSlaveExtensions(EXTENSION_LOGGING, EXTENSION_JMX);
        checkSlaveServerGroups("testgroup");
        checkSlaveSocketBindingGroups(SOCKETS3);

    }

    /////////////////////////////////////////////////////////////////
    // These tests check how ignoring unused resources works in conjunction with --backup when
    // ignore-unused-configuration is undefined/true/false

    @Test
    public void test60_RestartDomainAndReloadReadOnlyConfig() throws Exception {
        //Clean up after ourselves for the next round of tests /////////////
        restartDomainAndReloadReadOnlyConfig();
    }

    /////////////////////////////////////////////////////////////////
    // ignore-unused-configuration is undefined and --backup not set
    // the behavior is as if the ignore-unused-configuration attribute had a value of 'true'
    @Test
    public void test61_IgnoreUnusedConfigurationAttrUndefined() throws Exception {
        undefineIgnoreUnsusedConfiguration();
        test00_CheckInitialBootExclusions();
    }

    @Test
    public void test62_RestartDomainAndReloadReadOnlyConfig() throws Exception {
        //Clean up after ourselves for the next round of tests /////////////
        // start with --backup
        restartDomainAndReloadReadOnlyConfig(true, false);
    }

    /////////////////////////////////////////////////////////////////
    // ignore-unused-configuration=true and --backup set
    // the behavior is as if the ignore-unused-configuration attribute had a value of 'true'
    @Test
    public void test63_IgnoreUnusedConfigurationAttrTrueBackup() throws Exception {
        test00_CheckInitialBootExclusions();
    }

    /////////////////////////////////////////////////////////////////
    // ignore-unused-configuration is undefined and --backup set
    // the behavior is as if the ignore-unused-configuration attribute had a value of 'false'
    @Test
    public void test64_IgnoreUnusedConfigurationAttrUndefinedBackup() throws Exception {
        undefineIgnoreUnsusedConfiguration();
        checkFullConfiguration();
    }

    /////////////////////////////////////////////////////////////////
    // ignore-unused-configuration=false and --backup set
    @Test
    public void test65_IgnoreUnusedConfigurationAttrFalseBackup() throws Exception {
        setIgnoreUnusedConfiguration(false);
        checkFullConfiguration();
    }

    @Test
    public void test66_RestartDomainAndReloadReadOnlyConfig() throws Exception {
        //Clean up after ourselves for the next round of tests /////////////
        restartDomainAndReloadReadOnlyConfig();
    }

    /////////////////////////////////////////////////////////////////
    // ignore-unused-configuration=false and --backup not set
    @Test
    public void test67_IgnoreUnusedConfigurationAttrFalse() throws Exception {
        setIgnoreUnusedConfiguration(false);
        checkFullConfiguration();
    }

    @Test
    public void test68_RestartDomainAndReloadReadOnlyConfig() throws Exception {
        //Clean up after ourselves for the next round of tests /////////////
        // start with --cached-dc and reseting ignore-unused-configuration=true
        restartDomainAndReloadReadOnlyConfig(false, true);
    }

    /////////////////////////////////////////////////////////////////
    // ignore-unused-configuration=true and --cached-dc set
    // the behavior is as if the ignore-unused-configuration attribute had a value of 'true'
    @Test
    public void test69_IgnoreUnusedConfigurationAttrTrueCachedDc() throws Exception {
        test00_CheckInitialBootExclusions();
    }

    /////////////////////////////////////////////////////////////////
    // ignore-unused-configuration is undefined and --cached-dc set
    // the behavior is as if the ignore-unused-configuration attribute had a value of 'true'
    // When ignore-unused-configuration is unset and --backup is provided, then ignore-unused-configuration
    // behaves as if it is set to false, and nothing is ignored. This behavior does not happen for --cached-dc,
    // so providing both of them does still make some sense.
    @Test
    public void test70_IgnoreUnusedConfigurationAttrUndefinedCachedDc() throws Exception {
        undefineIgnoreUnsusedConfiguration();
        test00_CheckInitialBootExclusions();
    }

    /////////////////////////////////////////////////////////////////
    // ignore-unused-configuration=false and --cached-dc set
    @Test
    public void test71_IgnoreUnusedConfigurationAttrFalseCachedDc() throws Exception {
        setIgnoreUnusedConfiguration(false);
        checkFullConfiguration();
    }



    /////////////////////////////////////////////////////////////////
    // Private stuff

    private void checkFullConfiguration() throws Exception {
        checkSlaveProfiles(ROOT_PROFILE1, ROOT_PROFILE2, PROFILE1, PROFILE2, PROFILE3);
        checkSlaveExtensions(EXTENSION_JMX, EXTENSION_LOGGING, EXTENSION_REMOTING, EXTENSION_IO, EXTENSION_RC);
        checkSlaveServerGroups(GROUP1, GROUP2);
        checkSlaveSocketBindingGroups(ROOT_SOCKETS1, ROOT_SOCKETS2, SOCKETS1, SOCKETS2, SOCKETS3, SOCKETSA);
        checkSystemProperties(0);
        Assert.assertEquals("running", getSlaveServerStatus(SERVER1));
    }


    private ModelNode createDcLockTakenComposite(ModelNode op) {
        ModelNode composite = new ModelNode();
        composite.get(OP).set(COMPOSITE);
        composite.get(OP_ADDR).setEmptyList();

        ModelNode addProperty = Util.createAddOperation(PathAddress.pathAddress(PathElement.pathElement(SYSTEM_PROPERTY, String.valueOf(System.currentTimeMillis()))));
        addProperty.get(VALUE).set("xxx");
        composite.get(STEPS).add(addProperty);
        composite.get(STEPS).add(op);
        return composite;
    }

    private ModelNode createDcLockTakenCompositeWithRollback(ModelNode op) {
        ModelNode composite = createDcLockTakenComposite(op);

        ModelNode rollback = Util.getWriteAttributeOperation(SLAVE_ROOT_ADDRESS.clone().add(SYSTEM_PROPERTY, "rollback-does-not-exist" + String.valueOf(System.currentTimeMillis())), VALUE, "xxx");
        composite.get(STEPS).add(rollback);
        return composite;
    }

    private void checkSystemProperties(int size) throws Exception {
        Assert.assertEquals(size, getChildrenOfTypeOnSlave(SYSTEM_PROPERTY).asList().size());
    }

    private void checkSlaveProfiles(String... profiles) throws Exception {
        checkEqualContents(getChildrenOfTypeOnSlave(PROFILE).asList(), profiles);
    }


    private void checkSlaveExtensions(String... extensions) throws Exception {
        if (true) {
            return; // Automatically ignoring extensions is disabled atm
        }
        checkEqualContents(getChildrenOfTypeOnSlave(EXTENSION).asList(), extensions);
    }

    private void checkSlaveServerGroups(String... groups) throws Exception {
        checkEqualContents(getChildrenOfTypeOnSlave(SERVER_GROUP).asList(), groups);
    }

    private void checkSlaveSocketBindingGroups(String... groups) throws Exception {
        checkEqualContents(getChildrenOfTypeOnSlave(SOCKET_BINDING_GROUP).asList(), groups);
    }

    private void undefineIgnoreUnsusedConfiguration() throws Exception {
        // undefine ignore-unused-configuration
        ModelNode slaveModel = validateResponse(masterClient.execute(Operations.createReadAttributeOperation(SLAVE_ROOT_ADDRESS, DOMAIN_CONTROLLER)), true);
        slaveModel.get(REMOTE).remove(IGNORE_UNUSED_CONFIG);
        ModelNode op = Operations.createWriteAttributeOperation(SLAVE_ROOT_ADDRESS, DOMAIN_CONTROLLER, slaveModel);
        validateResponse(masterClient.execute(op));

        // reload slave
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveHostStatus());
        reloadSlaveHost();

        // verify that ignore-unused-configuration is undefined
        op = Operations.createReadAttributeOperation(SLAVE_ROOT_ADDRESS, DOMAIN_CONTROLLER);
        Assert.assertFalse(validateResponse(masterClient.execute(op), true).get(REMOTE).hasDefined(IGNORE_UNUSED_CONFIG));
    }

    private void setIgnoreUnusedConfiguration(boolean ignoreUnusedConfiguration) throws Exception {
        ModelNode slaveModel = validateResponse(masterClient.execute(Operations.createReadAttributeOperation(SLAVE_ROOT_ADDRESS, DOMAIN_CONTROLLER)), true);
        slaveModel.get(REMOTE).get(IGNORE_UNUSED_CONFIG).set(ignoreUnusedConfiguration);
        ModelNode op = Operations.createWriteAttributeOperation(SLAVE_ROOT_ADDRESS, DOMAIN_CONTROLLER, slaveModel);
        validateResponse(masterClient.execute(op));

        // reload slave
        Assert.assertEquals(RELOAD_REQUIRED, getSlaveHostStatus());
        reloadSlaveHost();

        // verify value of ignore-unused-configuration
        op = Operations.createReadAttributeOperation(SLAVE_ROOT_ADDRESS, DOMAIN_CONTROLLER);
        Assert.assertEquals(ignoreUnusedConfiguration, validateResponse(masterClient.execute(op), true).get(REMOTE).get(IGNORE_UNUSED_CONFIG).asBoolean());
    }

    private ModelNode getChildrenOfTypeOnSlave(String type) throws Exception {
        ModelNode op = Util.createOperation(READ_CHILDREN_NAMES_OPERATION, PathAddress.EMPTY_ADDRESS);
        op.get(CHILD_TYPE).set(type);
        ModelNode result = slaveClient.execute(op);
        return validateResponse(result);
    }

    private String getSlaveServerStatus(String serverName) throws Exception {
        ModelNode op = Util.getReadAttributeOperation(PathAddress.pathAddress(getSlaveRunningServerAddress(serverName)), "server-state");
        ModelNode result = slaveClient.execute(op);
        return validateResponse(result).asString();
    }

    private ModelNode getSlaveServerConfigAddress(String serverName) {
        return SLAVE_ROOT_ADDRESS.clone().add(SERVER_CONFIG, serverName);
    }

    private ModelNode getSlaveRunningServerAddress(String serverName) {
        return SLAVE_ROOT_ADDRESS.clone().add(SERVER, serverName);
    }

    private void checkEqualContents(List<ModelNode> values, String... expected) {
        HashSet<String> actualSet = new HashSet<String>();
        for (ModelNode value : values) {
            actualSet.add(value.asString());
        }
        HashSet<String> expectedSet = new HashSet<String>(Arrays.asList(expected));
        Assert.assertEquals("Expected " + expectedSet + "; was " + actualSet, expectedSet, actualSet);
    }

    private void restartSlaveServer(String serverName) throws Exception {
        ModelNode op = Util.createOperation(RESTART, PathAddress.pathAddress(getSlaveServerConfigAddress(serverName)));
        op.get(BLOCKING).set(true);
        Assert.assertEquals("STARTED", validateResponse(slaveClient.execute(op), true).asString());
    }

    private String getSlaveHostStatus() throws Exception {
        ModelNode op = Util.getReadAttributeOperation(PathAddress.pathAddress(SLAVE_ROOT_ADDRESS), HOST_STATE);
        ModelNode result = slaveClient.execute(op);
        return validateResponse(result).asString();
    }

    private void reloadSlaveHost() throws Exception {
        domainSlaveLifecycleUtil.executeAwaitConnectionClosed(Operations.createOperation("reload", SLAVE_ROOT_ADDRESS));
        domainSlaveLifecycleUtil.connect();
        domainSlaveLifecycleUtil.awaitServers(System.currentTimeMillis());
    }

    private void restartDomainAndReloadReadOnlyConfig() throws Exception {
        restartDomainAndReloadReadOnlyConfig(false, false);
    }

    private void restartDomainAndReloadReadOnlyConfig(boolean slaveIsBackupDC, boolean slaveIsCachedDC) throws Exception {
        DomainTestSupport.stopHosts(TimeoutUtil.adjust(30000), domainSlaveLifecycleUtil, domainMasterLifecycleUtil);
        testSupport.close();

        //Totally reinitialize the domain client
        setupDomain(slaveIsBackupDC, slaveIsCachedDC);
        setup();
        //Check we're back to where we were
        test00_CheckInitialBootExclusions();
    }
}
