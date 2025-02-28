/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
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

package org.jboss.as.test.integration.mgmt.access;

import org.jboss.as.test.integration.management.rbac.RbacUtil;
import org.wildfly.test.jmx.JMXServiceDeploymentSetupTask;
import org.junit.runner.RunWith;
import org.wildfly.core.testrunner.ServerSetup;
import org.wildfly.core.testrunner.WildFlyRunner;

/**
 * @author Ladislav Thon <lthon@redhat.com>
 */
@RunWith(WildFlyRunner.class)
@ServerSetup({StandardExtensionSetupTask.class, StandardUsersSetupTask.class, JMXServiceDeploymentSetupTask.class})
public class JmxSensitiveTestCase extends AbstractJmxNonCoreMBeansSensitivityTestCase {
    @Override
    protected boolean isReadAllowed(String userName) {
        return RbacUtil.ADMINISTRATOR_USER.equals(userName)
                || RbacUtil.AUDITOR_USER.equals(userName)
                || RbacUtil.SUPERUSER_USER.equals(userName);
    }

    @Override
    protected boolean isWriteAllowed(String userName) {
        return RbacUtil.ADMINISTRATOR_USER.equals(userName)
                || RbacUtil.SUPERUSER_USER.equals(userName);
    }
}
