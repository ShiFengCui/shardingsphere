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
 */

package org.apache.shardingsphere.dbdiscovery.mgr;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.shardingsphere.elasticjob.lite.api.bootstrap.impl.ScheduleJobBootstrap;
import org.apache.shardingsphere.elasticjob.reg.base.CoordinatorRegistryCenter;
import org.apache.shardingsphere.elasticjob.reg.zookeeper.ZookeeperConfiguration;
import org.apache.shardingsphere.elasticjob.reg.zookeeper.ZookeeperRegistryCenter;
import org.apache.shardingsphere.infra.exception.ShardingSphereException;
import org.apache.zookeeper.CreateMode;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;

public final class MGRDatabaseDiscoveryTypeTest {
    
    private static final String PLUGIN_STATUS = "SELECT * FROM information_schema.PLUGINS WHERE PLUGIN_NAME='group_replication'";
    
    private static final String MEMBER_COUNT = "SELECT count(*) FROM performance_schema.replication_group_members";
    
    private static final String GROUP_NAME = "SELECT * FROM performance_schema.global_variables WHERE VARIABLE_NAME='group_replication_group_name'";
    
    private static final String SINGLE_PRIMARY = "SELECT * FROM performance_schema.global_variables WHERE VARIABLE_NAME='group_replication_single_primary_mode'";
    
    private final MGRDatabaseDiscoveryType mgrHaType = new MGRDatabaseDiscoveryType();
    
    private static TestingServer server;
    
    private static CuratorFramework client;
    
    @BeforeClass
    public static void before() throws Exception {
        server = new TestingServer(2181, true);
        server.start();
        client = CuratorFrameworkFactory.newClient("127.0.0.1",
                new ExponentialBackoffRetry(1000, 3));
        client.start();
    }
    
    @AfterClass
    public static void after() throws Exception {
        server.stop();
        client.close();
    }
    
    @Test
    public void checkHAConfig() {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        try {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(PLUGIN_STATUS)).thenReturn(resultSet);
            when(statement.executeQuery(MEMBER_COUNT)).thenReturn(resultSet);
            when(statement.executeQuery(GROUP_NAME)).thenReturn(resultSet);
            when(statement.executeQuery(SINGLE_PRIMARY)).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true, false, true, false, true, false, true, false);
            when(resultSet.getString("PLUGIN_STATUS")).thenReturn("ACTIVE");
            when(resultSet.getInt(1)).thenReturn(3);
            when(resultSet.getString("VARIABLE_VALUE")).thenReturn("group_name", "ON");
        } catch (final SQLException ex) {
            throw new ShardingSphereException(ex);
        }
        Map<String, DataSource> dataSourceMap = mock(HashMap.class);
        when(dataSourceMap.get(null)).thenReturn(dataSource);
        try {
            mgrHaType.getProps().setProperty("groupName", "group_name");
            mgrHaType.checkDatabaseDiscoveryConfiguration("discovery_db", dataSourceMap);
        } catch (final SQLException ex) {
            throw new ShardingSphereException(ex);
        }
    }
    
    @Test
    public void updatePrimaryDataSource() {
        List<DataSource> dataSources = new LinkedList<>();
        List<Connection> connections = new LinkedList<>();
        List<Statement> statements = new LinkedList<>();
        List<ResultSet> resultSets = new LinkedList<>();
        List<DatabaseMetaData> databaseMetaData = new LinkedList<>();
        for (int i = 0; i < 3; i++) {
            dataSources.add(mock(DataSource.class));
            connections.add(mock(Connection.class));
            statements.add(mock(Statement.class));
            resultSets.add(mock(ResultSet.class));
            databaseMetaData.add(mock(DatabaseMetaData.class));
        }
        String sql = "SELECT MEMBER_HOST, MEMBER_PORT FROM performance_schema.replication_group_members WHERE MEMBER_ID = "
                + "(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'group_replication_primary_member')";
        try {
            for (int i = 0; i < 3; i++) {
                when(dataSources.get(i).getConnection()).thenReturn(connections.get(i));
                when(connections.get(i).createStatement()).thenReturn(statements.get(i));
                when(statements.get(i).executeQuery(sql)).thenReturn(resultSets.get(i));
                when(resultSets.get(i).next()).thenReturn(true, false);
                when(resultSets.get(i).getString("MEMBER_HOST")).thenReturn("127.0.0.1");
                when(resultSets.get(i).getString("MEMBER_PORT")).thenReturn(Integer.toString(3306 + i));
                when(connections.get(i).getMetaData()).thenReturn(databaseMetaData.get(i));
                when(databaseMetaData.get(i).getURL()).thenReturn("jdbc:mysql://127.0.0.1:" + (3306 + i) + "/ds_0?serverTimezone=UTC&useSSL=false");
            }
        } catch (final SQLException ex) {
            throw new ShardingSphereException(ex);
        }
        Map<String, DataSource> dataSourceMap = new HashMap<>(3, 1);
        for (int i = 0; i < 3; i++) {
            dataSourceMap.put(String.format("ds_%s", i), dataSources.get(i));
        }
        mgrHaType.getProps().setProperty("groupName", "group_name");
        mgrHaType.updatePrimaryDataSource("discovery_db", dataSourceMap, Collections.emptySet(), "group_name");
        assertThat(mgrHaType.getPrimaryDataSource(), is("ds_2"));
    }
    
    @Test
    public void startPeriodicalUpdate() throws NoSuchFieldException, IllegalAccessException {
        Properties props = mock(Properties.class);
        when(props.getProperty("zkServerLists")).thenReturn("127.0.0.1:2181");
        when(props.getProperty("keepAliveCron")).thenReturn("0/5 * * * * ?");
        Field propsFiled = MGRDatabaseDiscoveryType.class.getDeclaredField("props");
        propsFiled.setAccessible(true);
        propsFiled.set(mgrHaType, props);
        HashMap<String, ScheduleJobBootstrap> hashMap = spy(HashMap.class);
        Field SCHEDULE_JOB_BOOTSTRAP_MAP_Filed = MGRDatabaseDiscoveryType.class.getDeclaredField("SCHEDULE_JOB_BOOTSTRAP_MAP");
        SCHEDULE_JOB_BOOTSTRAP_MAP_Filed.setAccessible(true);
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(SCHEDULE_JOB_BOOTSTRAP_MAP_Filed, SCHEDULE_JOB_BOOTSTRAP_MAP_Filed.getModifiers() & ~Modifier.FINAL);
        SCHEDULE_JOB_BOOTSTRAP_MAP_Filed.set(mgrHaType, hashMap);
        Map<String, DataSource> originalDataSourceMap = new HashMap<>(3, 1);
        mgrHaType.startPeriodicalUpdate("discovery_db", originalDataSourceMap, null, "group_name");
        verify(hashMap, times(2)).get("group_name");
        Assert.assertEquals(hashMap.get("group_name").getClass(), ScheduleJobBootstrap.class);
        hashMap.get("group_name").shutdown();
    }
    
    @Test
    public void updateProperties() throws Exception {
        Properties props = mock(Properties.class);
        when(props.getProperty("zkServerLists")).thenReturn("127.0.0.1:2181");
        when(props.getProperty("keepAliveCron")).thenReturn("0/5 * * * * ?");
        ZookeeperConfiguration zkConfig = new ZookeeperConfiguration(props.getProperty("zkServerLists"), "");
        CoordinatorRegistryCenter coordinatorRegistryCenter = new ZookeeperRegistryCenter(zkConfig);
        coordinatorRegistryCenter.init();
        Field propsFiled = MGRDatabaseDiscoveryType.class.getDeclaredField("coordinatorRegistryCenter");
        propsFiled.setAccessible(true);
        propsFiled.set(mgrHaType, coordinatorRegistryCenter);
        ( (CuratorFramework)coordinatorRegistryCenter.getRawClient()).create().withMode(CreateMode.PERSISTENT).forPath("/MGR-group_name", "123".getBytes("utf-8"));
        ( (CuratorFramework)coordinatorRegistryCenter.getRawClient()).create().withMode(CreateMode.PERSISTENT).forPath("/MGR-group_name/config", "123".getBytes("utf-8"));
        mgrHaType.updateProperties("group_name", props);
        Assert.assertNotEquals(coordinatorRegistryCenter.get("/mgr-elasticjob/MGR-group_name/config"),"123");
        Assert.assertEquals(coordinatorRegistryCenter.get("/MGR-group_name/config"), "cron: 0/5 * * * * ?\n" +
                "disabled: false\n" +
                "failover: false\n" +
                "jobName: MGR-group_name\n" +
                "maxTimeDiffSeconds: -1\n" +
                "misfire: false\n" +
                "monitorExecution: false\n" +
                "overwrite: false\n" +
                "reconcileIntervalMinutes: 0\n" +
                "shardingTotalCount: 1\n" +
                "staticSharding: false\n");
    }
}
