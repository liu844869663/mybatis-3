/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.executor;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

	private static final Log log = LogFactory.getLog(BaseExecutor.class);

	/**
	 * 事务对象
	 */
	protected Transaction transaction;
	/**
	 * 包装的 Executor 对象
	 */
	protected Executor wrapper;
	/**
	 * DeferredLoad（延迟加载）队列
	 */
	protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
	/**
	 * 本地缓存，即一级缓存，内部就是一个 HashMap 对象
	 */
	protected PerpetualCache localCache;
	/**
	 * 本地输出类型参数的缓存，和存储过程有关
	 */
	protected PerpetualCache localOutputParameterCache;
  /**
   * 全局配置
   */
	protected Configuration configuration;
	/**
	 * 记录当前会话正在查询的数量
	 */
	protected int queryStack;
	/**
	 * 是否关闭
	 */
	private boolean closed;

	protected BaseExecutor(Configuration configuration, Transaction transaction) {
		this.transaction = transaction;
		this.deferredLoads = new ConcurrentLinkedQueue<>();
		this.localCache = new PerpetualCache("LocalCache");
		this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
		this.closed = false;
		this.configuration = configuration;
		this.wrapper = this;
	}

	@Override
	public Transaction getTransaction() {
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		return transaction;
	}

	@Override
	public void close(boolean forceRollback) {
		try {
			try {
				rollback(forceRollback);
			} finally {
				if (transaction != null) {
					transaction.close();
				}
			}
		} catch (SQLException e) {
			// Ignore. There's nothing that can be done at this point.
			log.warn("Unexpected exception on closing transaction.  Cause: " + e);
		} finally {
			transaction = null;
			deferredLoads = null;
			localCache = null;
			localOutputParameterCache = null;
			closed = true;
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public int update(MappedStatement ms, Object parameter) throws SQLException {
		ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
		// <1> 已经关闭，则抛出 ExecutorException 异常
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		// <2> 清空本地缓存
		clearLocalCache();
		// <3> 执行写操作
		return doUpdate(ms, parameter);
	}

	@Override
	public List<BatchResult> flushStatements() throws SQLException {
		return flushStatements(false);
	}

	public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		return doFlushStatements(isRollBack);
	}

	@Override
	public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler)
			throws SQLException {
		// <1> 获得 BoundSql 对象
		BoundSql boundSql = ms.getBoundSql(parameter);
		// <2> 创建 CacheKey 对象
		CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
		// <3> 查询
		return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler,
			CacheKey key, BoundSql boundSql) throws SQLException {
		ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
		// <1> 已经关闭，则抛出 ExecutorException 异常
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		// <2> 清空本地缓存，如果 queryStack 为零，并且要求清空本地缓存（配置了 flushCache = true）
		if (queryStack == 0 && ms.isFlushCacheRequired()) {
			clearLocalCache();
		}
		List<E> list;
		try {
			// <3> queryStack + 1
			queryStack++;
			// <4> 从一级缓存中，获取查询结果
			list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
			if (list != null) { // <4.1> 获取到，则进行处理
				// 处理存储过程的情况，将需要作为出参（`OUT`）的参数设置到本次查询的入参的属性中
				handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
			} else { // <4.2> 获得不到，则从数据库中查询
				list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
			}
		} finally {
			// <5> queryStack - 1
			queryStack--;
		}
		if (queryStack == 0) { // <6> 如果当前会话的所有查询执行完了
			// <6.1> 执行延迟加载
			for (DeferredLoad deferredLoad : deferredLoads) {
				deferredLoad.load();
			}
			// issue #601
			// <6.2> 清空 deferredLoads
			deferredLoads.clear();
			// <6.3> 如果缓存级别是 LocalCacheScope.STATEMENT ，则进行清理
			if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
				// issue #482
				clearLocalCache();
			}
		}
		// <7> 返回查询结果
		return list;
	}

	@Override
	public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
		// <1> 获得 BoundSql 对象
		BoundSql boundSql = ms.getBoundSql(parameter);
		// 执行查询
		return doQueryCursor(ms, parameter, rowBounds, boundSql);
	}

	@Override
	public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key,
			Class<?> targetType) {
		// 如果执行器已关闭，抛出 ExecutorException 异常
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		// 创建 DeferredLoad 对象
		DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
		// 如果可加载，则执行加载
		if (deferredLoad.canLoad()) {
			deferredLoad.load();
		} else { // 如果不可加载，则添加到 deferredLoads 中
			deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
		}
	}

	@Override
	public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		// <1> 创建 CacheKey 对象
		CacheKey cacheKey = new CacheKey();
		// <2> 设置 id、offset、limit、sql 到 CacheKey 对象中
		cacheKey.update(ms.getId());
		cacheKey.update(rowBounds.getOffset());
		cacheKey.update(rowBounds.getLimit());
		cacheKey.update(boundSql.getSql());
		// <3> 设置 ParameterMapping 数组的元素对应的每个 value 到 CacheKey 对象中
		List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
		TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
		// mimic DefaultParameterHandler logic
		for (ParameterMapping parameterMapping : parameterMappings) {
			if (parameterMapping.getMode() != ParameterMode.OUT) { // 该参数需要作为入参
				Object value;
				String propertyName = parameterMapping.getProperty();
        /*
         * 获取该属性值
         */
				if (boundSql.hasAdditionalParameter(propertyName)) {
				  // 从附加参数中获取
					value = boundSql.getAdditionalParameter(propertyName);
				} else if (parameterObject == null) {
				  // 入参对象为空则直接返回 null
					value = null;
				} else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
				  // 入参有对应的类型处理器则直接返回该参数
					value = parameterObject;
				} else {
				  // 从入参对象中获取该属性的值
					MetaObject metaObject = configuration.newMetaObject(parameterObject);
					value = metaObject.getValue(propertyName);
				}
				cacheKey.update(value);
			}
		}
		// <4> 设置 Environment.id 到 CacheKey 对象中
		if (configuration.getEnvironment() != null) {
			// issue #176
			cacheKey.update(configuration.getEnvironment().getId());
		}
		return cacheKey;
	}

	@Override
	public boolean isCached(MappedStatement ms, CacheKey key) {
		return localCache.getObject(key) != null;
	}

	@Override
	public void commit(boolean required) throws SQLException {
		if (closed) {
			throw new ExecutorException("Cannot commit, transaction is already closed");
		}
		clearLocalCache();
		flushStatements();
		if (required) {
			transaction.commit();
		}
	}

	@Override
	public void rollback(boolean required) throws SQLException {
		if (!closed) {
			try {
				clearLocalCache();
				flushStatements(true);
			} finally {
				if (required) {
					transaction.rollback();
				}
			}
		}
	}

	@Override
	public void clearLocalCache() {
		if (!closed) {
			localCache.clear();
			localOutputParameterCache.clear();
		}
	}

	protected abstract int doUpdate(MappedStatement ms, Object parameter) throws SQLException;

	protected abstract List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException;

	protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds,
			ResultHandler resultHandler, BoundSql boundSql) throws SQLException;

	protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds,
			BoundSql boundSql) throws SQLException;

	protected void closeStatement(Statement statement) {
		if (statement != null) {
			try {
				statement.close();
			} catch (SQLException e) {
				// ignore
			}
		}
	}

	/**
	 * Apply a transaction timeout.
	 *
	 * @param statement a current statement
	 * @throws SQLException if a database access error occurs, this method is called
	 *                      on a closed <code>Statement</code>
	 * @since 3.4.0
	 * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
	 */
	protected void applyTransactionTimeout(Statement statement) throws SQLException {
		StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
	}

	private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter,
			BoundSql boundSql) {
		if (ms.getStatementType() == StatementType.CALLABLE) { // 存储过程
		  // 从本地缓存中获取对应的参数（执行查询前保存的）
			final Object cachedParameter = localOutputParameterCache.getObject(key);
			if (cachedParameter != null && parameter != null) {
				final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
				final MetaObject metaParameter = configuration.newMetaObject(parameter);
				for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
					if (parameterMapping.getMode() != ParameterMode.IN) {
						final String parameterName = parameterMapping.getProperty();
						final Object cachedValue = metaCachedParameter.getValue(parameterName);
						metaParameter.setValue(parameterName, cachedValue);
					}
				}
			}
		}
	}

	private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds,
			ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
		List<E> list;
		// <1> 在缓存中，添加正在执行符的占位对象，因为正在执行的查询不允许提前加载需要延迟加载的属性，可见 DeferredLoad#canLoad() 方法
		localCache.putObject(key, EXECUTION_PLACEHOLDER);
		try {
			// <2> 执行读操作
			list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
		} finally {
			// <3> 从缓存中，移除占位对象
			localCache.removeObject(key);
		}
		// <4> 添加到缓存中
		localCache.putObject(key, list);
		// <5> 如果是存储过程，则将入参信息保存保存，跟一级缓存处理存储过程相关
		if (ms.getStatementType() == StatementType.CALLABLE) {
			localOutputParameterCache.putObject(key, parameter);
		}
    // <6> 返回查询结果
		return list;
	}

	protected Connection getConnection(Log statementLog) throws SQLException {
		Connection connection = transaction.getConnection();
		if (statementLog.isDebugEnabled()) { // 如果 debug 日志级别，则创建 ConnectionLogger 对象，进行动态代理
			return ConnectionLogger.newInstance(connection, statementLog, queryStack);
		} else {
			return connection;
		}
	}

	@Override
	public void setExecutorWrapper(Executor wrapper) {
		this.wrapper = wrapper;
	}

	private static class DeferredLoad {

		private final MetaObject resultObject;
		private final String property;
		private final Class<?> targetType;
		private final CacheKey key;
		private final PerpetualCache localCache;
		private final ObjectFactory objectFactory;
		private final ResultExtractor resultExtractor;

		// issue #781
		public DeferredLoad(MetaObject resultObject, String property, CacheKey key, PerpetualCache localCache,
				Configuration configuration, Class<?> targetType) {
			this.resultObject = resultObject;
			this.property = property;
			this.key = key;
			this.localCache = localCache;
			this.objectFactory = configuration.getObjectFactory();
			this.resultExtractor = new ResultExtractor(configuration, objectFactory);
			this.targetType = targetType;
		}

		public boolean canLoad() {
			return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
		}

		public void load() {
      // 从缓存 localCache 中获取
			// we suppose we get back a List
      @SuppressWarnings("unchecked")
			List<Object> list = (List<Object>) localCache.getObject(key);
			// 解析结果
			Object value = resultExtractor.extractObjectFromList(list, targetType);
			// 设置到 resultObject 中
			resultObject.setValue(property, value);
		}

	}

}
