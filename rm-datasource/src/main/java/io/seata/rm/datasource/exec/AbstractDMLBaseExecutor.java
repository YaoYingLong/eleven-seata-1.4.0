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
package io.seata.rm.datasource.exec;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.seata.common.exception.NotSupportYetException;
import io.seata.common.exception.ShouldNeverHappenException;
import io.seata.common.util.CollectionUtils;
import io.seata.rm.datasource.AbstractConnectionProxy;
import io.seata.rm.datasource.ConnectionContext;
import io.seata.rm.datasource.ConnectionProxy;
import io.seata.rm.datasource.StatementProxy;
import io.seata.rm.datasource.sql.struct.TableRecords;
import io.seata.sqlparser.SQLRecognizer;
import io.seata.sqlparser.util.JdbcConstants;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The type Abstract dml base executor.
 *
 * @param <T> the type parameter
 * @param <S> the type parameter
 * @author sharajava
 */
public abstract class AbstractDMLBaseExecutor<T, S extends Statement> extends BaseTransactionalExecutor<T, S> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractDMLBaseExecutor.class);

    protected static final String WHERE = " WHERE ";


    /**
     * Instantiates a new Abstract dml base executor.
     *
     * @param statementProxy    the statement proxy
     * @param statementCallback the statement callback
     * @param sqlRecognizer     the sql recognizer
     */
    public AbstractDMLBaseExecutor(StatementProxy<S> statementProxy, StatementCallback<T, S> statementCallback,
                                   SQLRecognizer sqlRecognizer) {
        super(statementProxy, statementCallback, sqlRecognizer);
    }

    /**
     * Instantiates a new Base transactional executor.
     *
     * @param statementProxy    the statement proxy
     * @param statementCallback the statement callback
     * @param sqlRecognizers    the multi sql recognizer
     */
    public AbstractDMLBaseExecutor(StatementProxy<S> statementProxy, StatementCallback<T, S> statementCallback,
                                   List<SQLRecognizer> sqlRecognizers) {
        super(statementProxy, statementCallback, sqlRecognizers);
    }

    @Override
    public T doExecute(Object... args) throws Throwable { // 这里将事务的自动提交设置为手动提交
        AbstractConnectionProxy connectionProxy = statementProxy.getConnectionProxy(); // 获取到代理的Connection
        if (connectionProxy.getAutoCommit()) { // 第一次默认为true
            return executeAutoCommitTrue(args); // 本地事务提交逻辑，这里会将事务设置为手动提交
        } else {
            return executeAutoCommitFalse(args); // 设置本地事务提交逻辑为手动提交
        }
    }

    /**
     * Execute auto commit false t.
     *
     * @param args the args
     * @return the t
     * @throws Exception the exception
     */
    protected T executeAutoCommitFalse(Object[] args) throws Exception {
        if (!JdbcConstants.MYSQL.equalsIgnoreCase(getDbType()) && isMultiPk()) {
            throw new NotSupportYetException("multi pk only support mysql!");
        }
        TableRecords beforeImage = beforeImage(); // 生成前置镜像
        T result = statementCallback.execute(statementProxy.getTargetStatement(), args); // 执行业务SQL
        TableRecords afterImage = afterImage(beforeImage); // BaseInsertExecutor生成后置镜像，为了查后置镜像事务隔离级别为读未提交
        prepareUndoLog(beforeImage, afterImage); // 通过前置镜像和后置镜像生成undo log回滚日志
        return result;
    }

    private boolean isMultiPk() {
        if (null != sqlRecognizer) {
            return getTableMeta().getPrimaryKeyOnlyName().size() > 1;
        }
        if (CollectionUtils.isNotEmpty(sqlRecognizers)) {
            List<SQLRecognizer> distinctSQLRecognizer = sqlRecognizers.stream().filter(
                distinctByKey(t -> t.getTableName())).collect(Collectors.toList());
            for (SQLRecognizer sqlRecognizer : distinctSQLRecognizer) {
                if (getTableMeta(sqlRecognizer.getTableName()).getPrimaryKeyOnlyName().size() > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> map = new HashMap<>();
        return t -> map.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }


    /**
     * Execute auto commit true t.
     *
     * @param args the args
     * @return the t
     * @throws Throwable the throwable
     */
    protected T executeAutoCommitTrue(Object[] args) throws Throwable {
        ConnectionProxy connectionProxy = statementProxy.getConnectionProxy();
        try {
            connectionProxy.setAutoCommit(false); // 设置本地事务为手动提交
            return new LockRetryPolicy(connectionProxy).execute(() -> {
                T result = executeAutoCommitFalse(args);
                connectionProxy.commit();
                return result;
            });
        } catch (Exception e) {
            // when exception occur in finally,this exception will lost, so just print it here
            LOGGER.error("execute executeAutoCommitTrue error:{}", e.getMessage(), e);
            if (!LockRetryPolicy.isLockRetryPolicyBranchRollbackOnConflict()) {
                connectionProxy.getTargetConnection().rollback(); // 回滚本地事务
            }
            throw e;
        } finally {
            connectionProxy.getContext().reset();
            connectionProxy.setAutoCommit(true);
        }
    }

    /**
     * Before image table records.
     *
     * @return the table records
     * @throws SQLException the sql exception
     */
    protected abstract TableRecords beforeImage() throws SQLException;

    /**
     * After image table records.
     *
     * @param beforeImage the before image
     * @return the table records
     * @throws SQLException the sql exception
     */
    protected abstract TableRecords afterImage(TableRecords beforeImage) throws SQLException;

    private static class LockRetryPolicy extends ConnectionProxy.LockRetryPolicy {
        private final ConnectionProxy connection;

        LockRetryPolicy(final ConnectionProxy connection) {
            this.connection = connection;
        }

        @Override
        public <T> T execute(Callable<T> callable) throws Exception {
            if (LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT) { // 默认为true
                return doRetryOnLockConflict(callable);
            } else {
                return callable.call();
            }
        }

        @Override
        protected void onException(Exception e) throws Exception {
            ConnectionContext context = connection.getContext();
            //UndoItems can't use the Set collection class to prevent ABA
            context.getUndoItems().clear();
            context.getLockKeysBuffer().clear();
            connection.getTargetConnection().rollback();
        }

        public static boolean isLockRetryPolicyBranchRollbackOnConflict() {
            return LOCK_RETRY_POLICY_BRANCH_ROLLBACK_ON_CONFLICT;
        }
    }

    protected void assertContainsPKColumnName(List<String> updateColumns) {
        for (String columnName : updateColumns) {
            String standardColumnName = getStandardPkColumnName(columnName);
            if (StringUtils.isNotEmpty(standardColumnName)) {
                throw new ShouldNeverHappenException("Sorry, update pk value is not supported!");
            }
        }
    }
}
