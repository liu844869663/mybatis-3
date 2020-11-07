/**
 *    Copyright 2009-2018 the original author or authors.
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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 * @description 每次开始读或写操作，优先从缓存中获取对应的 Statement 对象。如果不存在，才进行创建。执行完成后，不关闭该 Statement 对象。
 */
public class ReuseExecutor extends BaseExecutor {

	/**
	 * Statement 的缓存
	 *
	 * KEY ：SQL
	 */
	private final Map<String, Statement> statementMap = new HashMap<>();

	public ReuseExecutor(Configuration configuration, Transaction transaction) {
		super(configuration, transaction);
	}

	@Override
	public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
		Configuration configuration = ms.getConfiguration();
    // 创建 StatementHandler 对象
		StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
    // 初始化 Statement 对象
		Statement stmt = prepareStatement(handler, ms.getStatementLog());
    // 通过 StatementHandler 执行写操作
		return handler.update(stmt);
	}

	@Override
	public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler,
			BoundSql boundSql) throws SQLException {
		Configuration configuration = ms.getConfiguration();
    // 创建 StatementHandler 对象
		StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
    // 初始化 Statement 对象
		Statement stmt = prepareStatement(handler, ms.getStatementLog());
    // 通过 StatementHandler 执行写操作
		return handler.query(stmt, resultHandler);
	}

	@Override
	protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
			throws SQLException {
		Configuration configuration = ms.getConfiguration();
		StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
		Statement stmt = prepareStatement(handler, ms.getStatementLog());
		return handler.queryCursor(stmt);
	}

	@Override
	public List<BatchResult> doFlushStatements(boolean isRollback) {
		for (Statement stmt : statementMap.values()) {
		  // 关闭 Statement 对象
			closeStatement(stmt);
		}
		statementMap.clear();
		return Collections.emptyList();
	}

	private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
		Statement stmt;
		BoundSql boundSql = handler.getBoundSql();
		String sql = boundSql.getSql();
    /*
     * 根据需要执行的 SQL 语句判断 是否已有对应的 Statement 并且连接未关闭
     */
		if (hasStatementFor(sql)) {
			// 从缓存中获得 Statement 对象
			stmt = getStatement(sql);
			// 重新设置事务超时时间
			applyTransactionTimeout(stmt);
		} else {
			// 获得 Connection 对象
			Connection connection = getConnection(statementLog);
      // 初始化 Statement 对象
			stmt = handler.prepare(connection, transaction.getTimeout());
			// 将 Statement 添加到缓存中，key 值为 当前执行的 SQL 语句
			putStatement(sql, stmt);
		}
		// 往 Statement 中设置 SQL 语句上的参数，例如 PrepareStatement 的 ? 占位符
		handler.parameterize(stmt);
		return stmt;
	}

	private boolean hasStatementFor(String sql) {
		try {
			// 如果缓存中存在该 SQL 对应的 Statement 并且连接未关闭
			return statementMap.keySet().contains(sql) && !statementMap.get(sql).getConnection().isClosed();
		} catch (SQLException e) {
			return false;
		}
	}

	private Statement getStatement(String s) {
		return statementMap.get(s);
	}

	private void putStatement(String sql, Statement stmt) {
		statementMap.put(sql, stmt);
	}

}
