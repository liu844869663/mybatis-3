/**
 *    Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.executor.statement;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
public abstract class BaseStatementHandler implements StatementHandler {

	protected final Configuration configuration;
	protected final ObjectFactory objectFactory;
	protected final TypeHandlerRegistry typeHandlerRegistry;
	protected final ResultSetHandler resultSetHandler;
	protected final ParameterHandler parameterHandler;

	protected final Executor executor;
	protected final MappedStatement mappedStatement;
	protected final RowBounds rowBounds;

	protected BoundSql boundSql;

	protected BaseStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameterObject,
			RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
		// 获得MappedStatement中的Configuration对象
		this.configuration = mappedStatement.getConfiguration();
		this.executor = executor;
		this.mappedStatement = mappedStatement;
		this.rowBounds = rowBounds;

		// 获得 TypeHandlerRegistry 和 ObjectFactory 对象
		this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
		this.objectFactory = configuration.getObjectFactory();

		// <1> 如果 boundSql 为空，一般是写类操作，例如：insert、update、delete ，则先获得自增主键，然后再创建 BoundSql 对象
		if (boundSql == null) { // issue #435, get the key before calculating the statement
			// <1.1> 获得自增主键
			generateKeys(parameterObject);
			// <1.2> 创建 BoundSql 对象
			boundSql = mappedStatement.getBoundSql(parameterObject);
		}

		this.boundSql = boundSql;

		// <2> 创建 ParameterHandler 对象，默认的 DefaultParameterHandler
		// PreparedStatementHandler实现的parameterize对参数预处理进行参数化时需要用到
		this.parameterHandler = configuration.newParameterHandler(mappedStatement, parameterObject, boundSql);
		// <3> 创建 ResultSetHandler 对象
		this.resultSetHandler = configuration.newResultSetHandler(executor, mappedStatement, rowBounds, parameterHandler, resultHandler, boundSql);
	}

	@Override
	public BoundSql getBoundSql() {
		return boundSql;
	}

	@Override
	public ParameterHandler getParameterHandler() {
		return parameterHandler;
	}

	@Override
	public Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException {
		ErrorContext.instance().sql(boundSql.getSql());
		Statement statement = null;
		try {
			// <1> 创建 Statement 对象
			statement = instantiateStatement(connection);
			// 设置超时时间
			setStatementTimeout(statement, transactionTimeout);
			// 设置 fetchSize
			setFetchSize(statement);
			return statement;
		} catch (SQLException e) {
			// 发生异常，进行关闭
			closeStatement(statement);
			throw e;
		} catch (Exception e) {
			// 发生异常，进行关闭
			closeStatement(statement);
			throw new ExecutorException("Error preparing statement.  Cause: " + e, e);
		}
	}

	protected abstract Statement instantiateStatement(Connection connection) throws SQLException;

	protected void setStatementTimeout(Statement stmt, Integer transactionTimeout) throws SQLException {
		Integer queryTimeout = null;
		// 获得 queryTimeout
		if (mappedStatement.getTimeout() != null) {
			queryTimeout = mappedStatement.getTimeout();
		} else if (configuration.getDefaultStatementTimeout() != null) {
			queryTimeout = configuration.getDefaultStatementTimeout();
		}
		// 设置查询超时时间
		if (queryTimeout != null) {
			stmt.setQueryTimeout(queryTimeout);
		}
		// 设置事务超时时间
		StatementUtil.applyTransactionTimeout(stmt, queryTimeout, transactionTimeout);
	}

	protected void setFetchSize(Statement stmt) throws SQLException {
		// 获得 fetchSize 。非空，则进行设置
		Integer fetchSize = mappedStatement.getFetchSize();
		if (fetchSize != null) {
			stmt.setFetchSize(fetchSize);
			return;
		}
		// 获得 defaultFetchSize 。非空，则进行设置
		Integer defaultFetchSize = configuration.getDefaultFetchSize();
		if (defaultFetchSize != null) {
			stmt.setFetchSize(defaultFetchSize);
		}
	}

	protected void closeStatement(Statement statement) {
		try {
			if (statement != null) {
				statement.close();
			}
		} catch (SQLException e) {
			// ignore
		}
	}

	protected void generateKeys(Object parameter) {
		// 获得 KeyGenerator 对象
		KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
		ErrorContext.instance().store();
		// 前置处理，创建自增编号到 parameter 中
		keyGenerator.processBefore(executor, mappedStatement, null, parameter);
		ErrorContext.instance().recall();
	}

}
