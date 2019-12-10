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
package org.apache.ibatis.executor.loader;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.ResultExtractor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;

/**
 * @author Clinton Begin
 */
public class ResultLoader {

	protected final Configuration configuration;
	protected final Executor executor;
	protected final MappedStatement mappedStatement;
	/**
	 * 查询的参数对象
	 */
	protected final Object parameterObject;
	/**
	 * 结果的类型
	 */
	protected final Class<?> targetType;
	protected final ObjectFactory objectFactory;
	protected final CacheKey cacheKey;
	protected final BoundSql boundSql;
	/**
	 * ResultExtractor 对象
	 */
	protected final ResultExtractor resultExtractor;
	/**
	 * 创建 ResultLoader 对象时，所在的线程
	 */
	protected final long creatorThreadId;

	/**
	 * 是否已经加载
	 */
	protected boolean loaded;
	/**
	 * 查询的结果对象
	 */
	protected Object resultObject;

	public ResultLoader(Configuration config, Executor executor, MappedStatement mappedStatement,
			Object parameterObject, Class<?> targetType, CacheKey cacheKey, BoundSql boundSql) {
		this.configuration = config;
		this.executor = executor;
		this.mappedStatement = mappedStatement;
		this.parameterObject = parameterObject;
		this.targetType = targetType;
		this.objectFactory = configuration.getObjectFactory();
		this.cacheKey = cacheKey;
		this.boundSql = boundSql;
		// 初始化 resultExtractor
		this.resultExtractor = new ResultExtractor(configuration, objectFactory);
		// 初始化 creatorThreadId
		this.creatorThreadId = Thread.currentThread().getId();
	}

	public Object loadResult() throws SQLException {
		// <1> 查询结果
		List<Object> list = selectList();
		// <2> 提取结果
		resultObject = resultExtractor.extractObjectFromList(list, targetType);
		// <3> 返回结果
		return resultObject;
	}

	private <E> List<E> selectList() throws SQLException {
		// <1> 获得 Executor 对象
		Executor localExecutor = executor;
		if (Thread.currentThread().getId() != this.creatorThreadId || localExecutor.isClosed()) {
			localExecutor = newExecutor();
		}
		try {
			// <2> 执行查询
			return localExecutor.query(mappedStatement, parameterObject, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER,
					cacheKey, boundSql);
		} finally {
			// <3> 关闭 Executor 对象
			if (localExecutor != executor) {
				localExecutor.close(false);
			}
		}
	}

	/**
	 * 如果当前线程不是创建线程，则调用 #newExecutor() 方法，创建 Executor 对象，因为 Executor 是非线程安全的
	 * 
	 * @return Executor
	 */
	private Executor newExecutor() {
		// 校验 environment
		final Environment environment = configuration.getEnvironment();
		if (environment == null) {
			throw new ExecutorException("ResultLoader could not load lazily.  Environment was not configured.");
		}
		// 校验 DataSource
		final DataSource ds = environment.getDataSource();
		if (ds == null) {
			throw new ExecutorException("ResultLoader could not load lazily.  DataSource was not configured.");
		}
		// 创建 Transaction 对象
		final TransactionFactory transactionFactory = environment.getTransactionFactory();
		final Transaction tx = transactionFactory.newTransaction(ds, null, false);
		// 创建 Executor 对象
		return configuration.newExecutor(tx, ExecutorType.SIMPLE);
	}

	public boolean wasNull() {
		return resultObject == null;
	}

}
