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

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Jeff Butler
 */
public class BatchExecutor extends BaseExecutor {

	public static final int BATCH_UPDATE_RETURN_VALUE = Integer.MIN_VALUE + 1002;

	/**
	 * Statement 数组
	 */
	private final List<Statement> statementList = new ArrayList<>();
	/**
	 * BatchResult 数组
	 *
	 * 每一个 BatchResult 元素，对应 {@link #statementList} 集合中的一个 Statement 元素
	 */
	private final List<BatchResult> batchResultList = new ArrayList<>();
	/**
	 * 上一次添加至批处理的 Statement 对象对应的SQL
	 */
	private String currentSql;
	/**
	 * 上一次添加至批处理的 Statement 对象对应的 MappedStatement 对象
	 */
	private MappedStatement currentStatement;

	public BatchExecutor(Configuration configuration, Transaction transaction) {
		super(configuration, transaction);
	}

	@Override
	public int doUpdate(MappedStatement ms, Object parameterObject) throws SQLException {
		final Configuration configuration = ms.getConfiguration();
		// <1> 创建 StatementHandler 对象
		final StatementHandler handler = configuration.newStatementHandler(this, ms, parameterObject, RowBounds.DEFAULT, null, null);
		final BoundSql boundSql = handler.getBoundSql();
		final String sql = boundSql.getSql();
		final Statement stmt;
		// <2> 如果和上一次添加至批处理 Statement 对象对应的 currentSql 和 currentStatement 都一致，则聚合到 BatchResult 中
		if (sql.equals(currentSql) && ms.equals(currentStatement)) {
			// <2.1> 获取上一次添加至批处理 Statement 对象
			int last = statementList.size() - 1;
			stmt = statementList.get(last);
			// <2.2> 重新设置事务超时时间
			applyTransactionTimeout(stmt);
			// <2.3> 往 Statement 中设置 SQL 语句上的参数，例如 PrepareStatement 的 ? 占位符
			handler.parameterize(stmt);// fix Issues 322
			// <2.4> 获取上一次添加至批处理 Statement 对应的 BatchResult 对象，将本次的入参添加到其中
			BatchResult batchResult = batchResultList.get(last);
			batchResult.addParameterObject(parameterObject);
		} else { // <3> 否则，创建 Statement 和 BatchResult 对象
		  // <3.1> 初始化 Statement 对象
			Connection connection = getConnection(ms.getStatementLog());
			stmt = handler.prepare(connection, transaction.getTimeout());
			handler.parameterize(stmt); // fix Issues 322
			// <3.2> 设置 currentSql 和 currentStatemen
			currentSql = sql;
			currentStatement = ms;
			// <3.3> 添加 Statement 到 statementList 中
			statementList.add(stmt);
			// <3.4> 创建 BatchResult 对象，并添加到 batchResultList 中
			batchResultList.add(new BatchResult(ms, sql, parameterObject));
		}
		// <4> 添加至批处理
		handler.batch(stmt);
		// <5> 返回 Integer.MIN_VALUE + 1002
		return BATCH_UPDATE_RETURN_VALUE;
	}

	@Override
	public <E> List<E> doQuery(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
			ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
		Statement stmt = null;
		try {
			// <1> 刷入批处理语句
			flushStatements();
			Configuration configuration = ms.getConfiguration();
			// 创建 StatementHandler 对象
			StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameterObject, rowBounds, resultHandler, boundSql);
			// 获得 Connection 对象
			Connection connection = getConnection(ms.getStatementLog());
			// 创建 Statement 或 PrepareStatement 对象
			stmt = handler.prepare(connection, transaction.getTimeout());
			// 设置 SQL 上的参数，例如 PrepareStatement 对象上的占位符
			handler.parameterize(stmt);
			// 执行 StatementHandler  ，进行读操作
			return handler.query(stmt, resultHandler);
		} finally {
			// 关闭 StatementHandler 对象
			closeStatement(stmt);
		}
	}

	@Override
	protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
			throws SQLException {
		flushStatements();
		Configuration configuration = ms.getConfiguration();
		StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
		Connection connection = getConnection(ms.getStatementLog());
		Statement stmt = handler.prepare(connection, transaction.getTimeout());
		handler.parameterize(stmt);
		Cursor<E> cursor = handler.queryCursor(stmt);
		stmt.closeOnCompletion();
		return cursor;
	}

	@Override
	public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
		try {
			List<BatchResult> results = new ArrayList<>();
			if (isRollback) { // <1> 如果 isRollback 为 true ，返回空数组
				return Collections.emptyList();
			}
			// <2> 遍历 statementList 和 batchResultList 数组，逐个提交批处理
			for (int i = 0, n = statementList.size(); i < n; i++) {
				// <2.1> 获得 Statement 和 BatchResult 对象
				Statement stmt = statementList.get(i);
				applyTransactionTimeout(stmt);
				BatchResult batchResult = batchResultList.get(i);
				try {
					// <2.2> 提交该 Statement 的批处理
					batchResult.setUpdateCounts(stmt.executeBatch());
					MappedStatement ms = batchResult.getMappedStatement();
					List<Object> parameterObjects = batchResult.getParameterObjects();
          /*
           * <2.3> 获得 KeyGenerator 对象
           * 1. 配置了 <selectKey /> 则会生成 SelectKeyGenerator 对象
           * 2. 配置了 useGeneratedKeys="true" 则会生成 Jdbc3KeyGenerator 对象
           * 否则为 NoKeyGenerator 对象
           */
					KeyGenerator keyGenerator = ms.getKeyGenerator();
					if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {
						Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;
						// <2.3.1> 批处理入参对象集合，设置自增键
						jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);
					} else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) { // issue #141
						for (Object parameter : parameterObjects) {
						  // <2.3.1> 一次处理每个入参对象，设置自增键
							keyGenerator.processAfter(this, ms, stmt, parameter);
						}
					}
					// Close statement to close cursor #1109
					// <2.4> 关闭 Statement 对象
					closeStatement(stmt);
				} catch (BatchUpdateException e) {
					// 如果发生异常，则抛出 BatchExecutorException 异常
					StringBuilder message = new StringBuilder();
					message.append(batchResult.getMappedStatement().getId())
							.append(" (batch index #")
							.append(i + 1)
							.append(")")
							.append(" failed.");
					if (i > 0) {
						message.append(" ")
								.append(i)
								.append(" prior sub executor(s) completed successfully, but will be rolled back.");
					}
					throw new BatchExecutorException(message.toString(), e, results, batchResult);
				}
				// <2.5> 添加到结果集
				results.add(batchResult);
			}
			return results;
		} finally {
			// <3.1> 关闭 Statement 们
			for (Statement stmt : statementList) {
				closeStatement(stmt);
			}
			// <3.2> 置空 currentSql、statementList、batchResultList 属性
			currentSql = null;
			statementList.clear();
			batchResultList.clear();
		}
	}

}
