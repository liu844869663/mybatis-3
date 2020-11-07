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

  /**
   * 全局配置
   */
	protected final Configuration configuration;
  /**
   * 实例工厂
   */
	protected final ObjectFactory objectFactory;
  /**
   * 类型处理器注册表
   */
	protected final TypeHandlerRegistry typeHandlerRegistry;
  /**
   * 执行结果处理器
   */
	protected final ResultSetHandler resultSetHandler;
  /**
   * 参数处理器，默认 DefaultParameterHandler
   */
	protected final ParameterHandler parameterHandler;
  /**
   * 执行器
   */
	protected final Executor executor;
  /**
   * SQL 相关信息
   */
	protected final MappedStatement mappedStatement;
  /**
   * 分页条件
   */
	protected final RowBounds rowBounds;
  /**
   * SQL 语句
   */
	protected BoundSql boundSql;

	protected BaseStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameterObject,
			RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
		this.configuration = mappedStatement.getConfiguration();
		this.executor = executor;
		this.mappedStatement = mappedStatement;
		this.rowBounds = rowBounds;
		this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
		this.objectFactory = configuration.getObjectFactory();

		// <1> 如果 boundSql 为空，更新数据库的操作这里传入的对象会为 null
		if (boundSql == null) { // issue #435, get the key before calculating the statement
			// <1.1> 生成 key，定义了 <selectKey /> 且配置了 order="BEFORE"，则在 SQL 执行之前执行
			generateKeys(parameterObject);
			// <1.2> 创建 BoundSql 对象
			boundSql = mappedStatement.getBoundSql(parameterObject);
		}

		this.boundSql = boundSql;

		// <2> 创建 ParameterHandler 对象，默认为 DefaultParameterHandler
		// PreparedStatementHandler 实现的 parameterize 方法中需要对参数进行预处理，进行参数化时需要用到
		this.parameterHandler = configuration.newParameterHandler(mappedStatement, parameterObject, boundSql);
		// <3> 创建 DefaultResultSetHandler 对象
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
			// <2> 设置执行和事务的超时时间
			setStatementTimeout(statement, transactionTimeout);
			// <3> 设置 fetchSize，为驱动的结果集获取数量（fetchSize）设置一个建议值
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
		// 设置执行的超时时间
		if (queryTimeout != null) {
			stmt.setQueryTimeout(queryTimeout);
		}
		// 设置事务超时时间
		StatementUtil.applyTransactionTimeout(stmt, queryTimeout, transactionTimeout);
	}

	protected void setFetchSize(Statement stmt) throws SQLException {
		// 获得 fetchSize 配置
		Integer fetchSize = mappedStatement.getFetchSize();
		if (fetchSize != null) {
			stmt.setFetchSize(fetchSize);
			return;
		}
		// 获得 fetchSize 的默认配置
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
		/*
		 * 获得 KeyGenerator 对象
		 * 1. 配置了 <selectKey /> 则会生成 SelectKeyGenerator 对象
		 * 2. 配置了 useGeneratedKeys="true" 则会生成 Jdbc3KeyGenerator 对象
		 * 否则为 NoKeyGenerator 对象
		 */
		KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
		ErrorContext.instance().store();
		// 前置处理，创建自增编号到 parameter 中
		keyGenerator.processBefore(executor, mappedStatement, null, parameter);
		ErrorContext.instance().recall();
	}

}
