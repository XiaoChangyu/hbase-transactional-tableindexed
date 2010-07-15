/**
 * Copyright 2009 The Apache Software Foundation Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and limitations under the
 * License.
 */
package org.apache.hadoop.hbase.regionserver.tableindexed;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.ipc.HBaseRPCProtocolVersion;
import org.apache.hadoop.hbase.ipc.IndexedRegionInterface;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.transactional.TransactionalRegionServer;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.util.Progressable;

/**
 * RegionServer which maintains secondary indexes.
 **/
public class IndexedRegionServer extends TransactionalRegionServer implements IndexedRegionInterface {

    public IndexedRegionServer(final Configuration conf) throws IOException {
        super(conf);
    }

    @Override
    public long getProtocolVersion(final String protocol, final long clientVersion) throws IOException {
        if (protocol.equals(IndexedRegionInterface.class.getName())) {
            return HBaseRPCProtocolVersion.versionID;
        }
        return super.getProtocolVersion(protocol, clientVersion);
    }

    @Override
    protected HRegion instantiateRegion(final HRegionInfo regionInfo, final HLog hlog) throws IOException {
        HRegion r = new IndexedRegion(HTableDescriptor.getTableDir(super.getRootDir(), regionInfo.getTableDesc()
                .getName()), super.hlog, super.getTransactionLog(), super.getFileSystem(), super.conf, regionInfo, super
                .getFlushRequester(), super.getTransactionalLeases());
        r.initialize(new Progressable() {

            public void progress() {
                addProcessingMessage(regionInfo);
            }
        });
        return r;
    }

}
