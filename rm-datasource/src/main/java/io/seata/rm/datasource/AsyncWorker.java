/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.rm.datasource;

import io.seata.common.exception.NotSupportYetException;
import io.seata.common.exception.ShouldNeverHappenException;
import io.seata.common.thread.NamedThreadFactory;
import io.seata.common.util.CollectionUtils;
import io.seata.config.ConfigurationFactory;
import io.seata.core.exception.TransactionException;
import io.seata.core.model.BranchStatus;
import io.seata.core.model.BranchType;
import io.seata.core.model.ResourceManagerInbound;
import io.seata.rm.DefaultResourceManager;
import io.seata.rm.datasource.undo.UndoLogManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static io.seata.common.DefaultValues.DEFAULT_CLIENT_ASYNC_COMMIT_BUFFER_LIMIT;
import static io.seata.core.constants.ConfigurationKeys.CLIENT_ASYNC_COMMIT_BUFFER_LIMIT;

/**
 * The type Async worker.
 *
 * @author sharajava
 */
public class AsyncWorker implements ResourceManagerInbound {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncWorker.class);

    private static final int DEFAULT_RESOURCE_SIZE = 16;

    private static final int UNDOLOG_DELETE_LIMIT_SIZE = 1000;


    private static class Phase2Context {

        /**
         * Instantiates a new Phase 2 context.
         *
         * @param branchType      the branchType
         * @param xid             the xid
         * @param branchId        the branch id
         * @param resourceId      the resource id
         * @param applicationData the application data
         */
        public Phase2Context(BranchType branchType, String xid, long branchId, String resourceId,
                             String applicationData) {
            this.xid = xid;
            this.branchId = branchId;
            this.resourceId = resourceId;
            this.applicationData = applicationData;
            this.branchType = branchType;
        }

        /**
         * The Xid.
         */
        String xid;
        /**
         * The Branch id.
         */
        long branchId;
        /**
         * The Resource id.
         */
        String resourceId;
        /**
         * The Application data.
         */
        String applicationData;

        /**
         * the branch Type
         */
        BranchType branchType;
    }

    private static int ASYNC_COMMIT_BUFFER_LIMIT = ConfigurationFactory.getInstance().getInt(
        CLIENT_ASYNC_COMMIT_BUFFER_LIMIT, DEFAULT_CLIENT_ASYNC_COMMIT_BUFFER_LIMIT);

    private static final BlockingQueue<Phase2Context> ASYNC_COMMIT_BUFFER = new LinkedBlockingQueue<>(ASYNC_COMMIT_BUFFER_LIMIT);

    @Override
    public BranchStatus branchCommit(BranchType branchType, String xid, long branchId, String resourceId, String applicationData) throws TransactionException {
        if (!ASYNC_COMMIT_BUFFER.offer(new Phase2Context(branchType, xid, branchId, resourceId, applicationData))) {
            LOGGER.warn("Async commit buffer is FULL. Rejected branch [{}/{}] will be handled by housekeeping later.", branchId, xid);
        }
        return BranchStatus.PhaseTwo_Committed;
    }

    /**
     * Init.
     */
    public synchronized void init() {
        LOGGER.info("Async Commit Buffer Limit: {}", ASYNC_COMMIT_BUFFER_LIMIT);
        ScheduledExecutorService timerExecutor = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("AsyncWorker", 1, true));
        timerExecutor.scheduleAtFixedRate(() -> {
            try {
                doBranchCommits();
            } catch (Throwable e) {
                LOGGER.info("Failed at async committing ... {}", e.getMessage());
            }
        }, 10, 1000 * 1, TimeUnit.MILLISECONDS); // 每1s执行一次
    }

    private void doBranchCommits() { // 因为都成功了，无需回滚成功的数据，只需要删除生成的操作日志就行，采用异步方式，提高效率
        if (ASYNC_COMMIT_BUFFER.isEmpty()) {
            return;
        }
        Map<String, List<Phase2Context>> mappedContexts = new HashMap<>(DEFAULT_RESOURCE_SIZE);
        List<Phase2Context> contextsGroupedByResourceId;
        while (!ASYNC_COMMIT_BUFFER.isEmpty()) {
            Phase2Context commitContext = ASYNC_COMMIT_BUFFER.poll();
            contextsGroupedByResourceId = CollectionUtils.computeIfAbsent(mappedContexts, commitContext.resourceId, key -> new ArrayList<>());
            contextsGroupedByResourceId.add(commitContext);
        }
        for (Map.Entry<String, List<Phase2Context>> entry : mappedContexts.entrySet()) {
            Connection conn = null;
            DataSourceProxy dataSourceProxy;
            try {
                try {
                    DataSourceManager resourceManager = (DataSourceManager) DefaultResourceManager.get().getResourceManager(BranchType.AT);
                    dataSourceProxy = resourceManager.get(entry.getKey());
                    if (dataSourceProxy == null) {
                        throw new ShouldNeverHappenException("Failed to find resource on " + entry.getKey());
                    }
                    conn = dataSourceProxy.getPlainConnection();
                } catch (SQLException sqle) {
                    LOGGER.warn("Failed to get connection for async committing on " + entry.getKey(), sqle);
                    continue;
                }
                contextsGroupedByResourceId = entry.getValue();
                Set<String> xids = new LinkedHashSet<>(UNDOLOG_DELETE_LIMIT_SIZE);
                Set<Long> branchIds = new LinkedHashSet<>(UNDOLOG_DELETE_LIMIT_SIZE);
                for (Phase2Context commitContext : contextsGroupedByResourceId) {
                    xids.add(commitContext.xid);
                    branchIds.add(commitContext.branchId);
                    int maxSize = Math.max(xids.size(), branchIds.size());
                    if (maxSize == UNDOLOG_DELETE_LIMIT_SIZE) {
                        try { // 删除undo_log记录
                            UndoLogManagerFactory.getUndoLogManager(dataSourceProxy.getDbType()).batchDeleteUndoLog(xids, branchIds, conn);
                        } catch (Exception ex) {
                            LOGGER.warn("Failed to batch delete undo log [" + branchIds + "/" + xids + "]", ex);
                        }
                        xids.clear();
                        branchIds.clear();
                    }
                }
                if (CollectionUtils.isEmpty(xids) || CollectionUtils.isEmpty(branchIds)) {
                    return;
                }
                try { // 删除undo_log记录
                    UndoLogManagerFactory.getUndoLogManager(dataSourceProxy.getDbType()).batchDeleteUndoLog(xids, branchIds, conn);
                } catch (Exception ex) {
                    LOGGER.warn("Failed to batch delete undo log [" + branchIds + "/" + xids + "]", ex);
                }
                if (!conn.getAutoCommit()) {
                    conn.commit();
                }
            } catch (Throwable e) {
                LOGGER.error(e.getMessage(), e);
                try {
                    if (conn != null) {
                        conn.rollback();
                    }
                } catch (SQLException rollbackEx) {
                    LOGGER.warn("Failed to rollback JDBC resource while deleting undo_log ", rollbackEx);
                }
            } finally {
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException closeEx) {
                        LOGGER.warn("Failed to close JDBC resource while deleting undo_log ", closeEx);
                    }
                }
            }
        }
    }

    @Override
    public BranchStatus branchRollback(BranchType branchType, String xid, long branchId, String resourceId, String applicationData) throws TransactionException {
        throw new NotSupportYetException();

    }
}
