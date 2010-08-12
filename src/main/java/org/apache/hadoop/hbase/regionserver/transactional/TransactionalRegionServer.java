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
package org.apache.hadoop.hbase.regionserver.transactional;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.Leases;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.RemoteExceptionHandler;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.ipc.HBaseRPCProtocolVersion;
import org.apache.hadoop.hbase.ipc.TransactionalRegionInterface;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.regionserver.wal.HLogSplitter;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hdfs.server.namenode.LeaseExpiredException;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.util.Progressable;

/**
 * RegionServer with support for transactions. Transactional logic is at the region level, so we mostly just delegate to
 * the appropriate TransactionalRegion.
 */
public class TransactionalRegionServer extends HRegionServer implements TransactionalRegionInterface {

    private static final String LEASE_TIME = "hbase.transaction.leasetime";
    private static final int DEFAULT_LEASE_TIME = 60 * 1000;
    private static final int LEASE_CHECK_FREQUENCY = 1000;

    static final Log LOG = LogFactory.getLog(TransactionalRegionServer.class);
    private final Leases transactionLeases;
    private final CleanOldTransactionsChore cleanOldTransactionsThread;

    private THLog trxHLog;

    /**
     * @param conf
     * @throws IOException
     */
    public TransactionalRegionServer(final Configuration conf) throws IOException {
        super(conf);
        cleanOldTransactionsThread = new CleanOldTransactionsChore(this, super.stopRequested);
        transactionLeases = new Leases(conf.getInt(LEASE_TIME, DEFAULT_LEASE_TIME), LEASE_CHECK_FREQUENCY);
        LOG.info("Transaction lease time: " + conf.getInt(LEASE_TIME, DEFAULT_LEASE_TIME));
    }

    protected THLog getTransactionLog() {
        return trxHLog;
    }

    @Override
    public long getProtocolVersion(final String protocol, final long clientVersion) throws IOException {
        if (protocol.equals(TransactionalRegionInterface.class.getName())) {
            return HBaseRPCProtocolVersion.versionID;
        }
        return super.getProtocolVersion(protocol, clientVersion);
    }

    @Override
    protected void init(final MapWritable c) throws IOException {
        super.init(c);
        initializeTHLog();
        String n = Thread.currentThread().getName();
        UncaughtExceptionHandler handler = new UncaughtExceptionHandler() {

            public void uncaughtException(final Thread t, final Throwable e) {
                abort("Set stop flag in " + t.getName(), e);
                LOG.fatal("Set stop flag in " + t.getName(), e);
            }
        };
        Threads.setDaemonThreadRunning(this.cleanOldTransactionsThread, n + ".oldTransactionCleaner", handler);
        Threads.setDaemonThreadRunning(this.transactionLeases, "Transactional leases");

    }

    private void initializeTHLog() throws IOException {
        // We keep in the same directory as the core HLog.
        Path oldLogDir = new Path(getRootDir(), HLogSplitter.RECOVERED_EDITS);
        Path logdir = new Path(getRootDir(), HLog.getHLogDirectoryName(this.serverInfo));

        trxHLog = new THLog(getFileSystem(), logdir, oldLogDir, conf, null);
    }

    @Override
    protected HRegion instantiateRegion(final HRegionInfo regionInfo, final HLog log) throws IOException {
        HRegion r = new TransactionalRegion(HTableDescriptor.getTableDir(super.getRootDir(), regionInfo.getTableDesc()
                .getName()), super.hlog, this.trxHLog, super.getFileSystem(), super.conf, regionInfo, super
                .getFlushRequester(), this.getTransactionalLeases());
        r.initialize(new Progressable() {

            public void progress() {
                addProcessingMessage(regionInfo);
            }
        });
        return r;
    }

    protected TransactionalRegion getTransactionalRegion(final byte[] regionName) throws NotServingRegionException {
        return (TransactionalRegion) super.getRegion(regionName);
    }

    protected Leases getTransactionalLeases() {
        return this.transactionLeases;
    }

    /**
     * We want to delay the close region for a bit if we have commit pending transactions.
     */
    @Override
    protected void closeRegion(final HRegionInfo hri, final boolean reportWhenCompleted) throws IOException {
        getTransactionalRegion(hri.getRegionName()).prepareToClose();
        super.closeRegion(hri, reportWhenCompleted);
    }

    /**
     * Make sure transaction log gets closed on abort.
     */
    @Override
    protected void cleanupOnAbort() throws IOException {
        super.cleanupOnAbort();
        if (null != trxHLog) {
            trxHLog.close();
        }
    }

    /**
     * Make sure transaction log gets closed on shutdown. Currently the HRegion implementation will close it by wiping
     * the whole log dir.
     */
    @Override
    protected void cleanupOnShutdown() throws IOException {
        super.cleanupOnShutdown();
        try {
            trxHLog.close();
        } catch (IOException e) {
            Throwable t = RemoteExceptionHandler.checkThrowable(e);
            if (!(t instanceof LeaseExpiredException)) {
                throw e;
            }
            LOG.info("Transaction log deleted along with HRegion log directory.");
        }
    }

    /**
     * {@inheritDoc}
     */
    public void abortTransaction(final byte[] regionName, final long transactionId) throws IOException {
        checkOpen();
        super.getRequestCount().incrementAndGet();
        try {
            getTransactionalRegion(regionName).abort(transactionId);
        } catch (NotServingRegionException e) {
            LOG.info("Got not serving region durring abort. Ignoring.");
        } catch (IOException e) {
            checkFileSystem();
            throw e;
        }
    }

    public void commit(final byte[] regionName, final long transactionId) throws IOException {
        checkOpen();
        super.getRequestCount().incrementAndGet();
        try {
            getTransactionalRegion(regionName).commit(transactionId);
        } catch (IOException e) {
            checkFileSystem();
            throw e;
        }
    }

    public int commitRequest(final byte[] regionName, final long transactionId) throws IOException {
        checkOpen();
        super.getRequestCount().incrementAndGet();
        try {
            return getTransactionalRegion(regionName).commitRequest(transactionId);
        } catch (IOException e) {
            checkFileSystem();
            throw e;
        }
    }

    public boolean commitIfPossible(final byte[] regionName, final long transactionId) throws IOException {
        checkOpen();
        super.getRequestCount().incrementAndGet();
        try {
            return getTransactionalRegion(regionName).commitIfPossible(transactionId);
        } catch (IOException e) {
            checkFileSystem();
            throw e;
        }
    }

    public long openScanner(final long transactionId, final byte[] regionName, final Scan scan) throws IOException {
        checkOpen();
        NullPointerException npe = null;
        if (regionName == null) {
            npe = new NullPointerException("regionName is null");
        } else if (scan == null) {
            npe = new NullPointerException("scan is null");
        }
        if (npe != null) {
            throw new IOException("Invalid arguments to openScanner", npe);
        }
        super.getRequestCount().incrementAndGet();
        try {
            TransactionalRegion r = getTransactionalRegion(regionName);
            InternalScanner s = r.getScanner(transactionId, scan);
            long scannerId = addScanner(s);
            return scannerId;
        } catch (IOException e) {
            LOG.error("Error opening scanner (fsOk: " + this.fsOk + ")", RemoteExceptionHandler.checkIOException(e));
            checkFileSystem();
            throw e;
        }
    }

    public void beginTransaction(final long transactionId, final byte[] regionName) throws IOException {
        getTransactionalRegion(regionName).beginTransaction(transactionId);
    }

    public void delete(final long transactionId, final byte[] regionName, final Delete delete) throws IOException {
        getTransactionalRegion(regionName).delete(transactionId, delete);
    }

    public Result get(final long transactionId, final byte[] regionName, final Get get) throws IOException {
        return getTransactionalRegion(regionName).get(transactionId, get);
    }

    public void put(final long transactionId, final byte[] regionName, final Put put) throws IOException {
        getTransactionalRegion(regionName).put(transactionId, put);

    }

    public int put(final long transactionId, final byte[] regionName, final Put[] puts) throws IOException {
        getTransactionalRegion(regionName).put(transactionId, puts);
        return puts.length; // ??
    }
}
