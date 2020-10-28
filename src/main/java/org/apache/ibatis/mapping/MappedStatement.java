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
package org.apache.ibatis.mapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public final class MappedStatement {

  /**
   * XML 资源路径
   */
	private String resource;
	private Configuration configuration;
  /**
   * Statement 的Id：`${namespace}.${id}`
   */
	private String id;
	private Integer fetchSize;
	private Integer timeout;
  /**
   * Statement 的类型：STATEMENT, PREPARED, CALLABLE，默认 PREPARED
   */
	private StatementType statementType;
	private ResultSetType resultSetType;
  /**
   * SQL 相关信息
   */
	private SqlSource sqlSource;
  /**
   * 缓存对象
   */
	private Cache cache;
	private ParameterMap parameterMap;
	private List<ResultMap> resultMaps;
  /**
   * 是否清空缓存
   */
	private boolean flushCacheRequired;
  /**
   * 是否使用缓存
   */
	private boolean useCache;
	private boolean resultOrdered;
  /**
   * SQL 语句类型
   */
	private SqlCommandType sqlCommandType;
  /**
   * key 的生成器
   */
	private KeyGenerator keyGenerator;
  /**
   * key 的生成器的 Java 属性
   */
	private String[] keyProperties;
  /**
   * key 的生成器的 column 列名
   */
	private String[] keyColumns;
  /**
   * 是否有内嵌的 ResultMap
   */
	private boolean hasNestedResultMaps;
	private String databaseId;
	private Log statementLog;
  /**
   * 语言驱动，默认为XMLLanguageDriver
   */
	private LanguageDriver lang;
	private String[] resultSets;

	MappedStatement() {
		// constructor disabled
	}

	public static class Builder {
		private MappedStatement mappedStatement = new MappedStatement();

		public Builder(Configuration configuration, String id, SqlSource sqlSource, SqlCommandType sqlCommandType) {
			mappedStatement.configuration = configuration;
			mappedStatement.id = id;
			mappedStatement.sqlSource = sqlSource;
			mappedStatement.statementType = StatementType.PREPARED;
			mappedStatement.resultSetType = ResultSetType.DEFAULT;
			mappedStatement.parameterMap = new ParameterMap.Builder(configuration, "defaultParameterMap", null, new ArrayList<>()).build();
			mappedStatement.resultMaps = new ArrayList<>();
			mappedStatement.sqlCommandType = sqlCommandType;
			mappedStatement.keyGenerator = configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType)
        ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
			String logId = id;
			if (configuration.getLogPrefix() != null) {
				logId = configuration.getLogPrefix() + id;
			}
			mappedStatement.statementLog = LogFactory.getLog(logId);
			mappedStatement.lang = configuration.getDefaultScriptingLanguageInstance();
		}

		public Builder resource(String resource) {
			mappedStatement.resource = resource;
			return this;
		}

		public String id() {
			return mappedStatement.id;
		}

		public Builder parameterMap(ParameterMap parameterMap) {
			mappedStatement.parameterMap = parameterMap;
			return this;
		}

		public Builder resultMaps(List<ResultMap> resultMaps) {
			mappedStatement.resultMaps = resultMaps;
			for (ResultMap resultMap : resultMaps) {
				mappedStatement.hasNestedResultMaps = mappedStatement.hasNestedResultMaps
						|| resultMap.hasNestedResultMaps();
			}
			return this;
		}

		public Builder fetchSize(Integer fetchSize) {
			mappedStatement.fetchSize = fetchSize;
			return this;
		}

		public Builder timeout(Integer timeout) {
			mappedStatement.timeout = timeout;
			return this;
		}

		public Builder statementType(StatementType statementType) {
			mappedStatement.statementType = statementType;
			return this;
		}

		public Builder resultSetType(ResultSetType resultSetType) {
			mappedStatement.resultSetType = resultSetType == null ? ResultSetType.DEFAULT : resultSetType;
			return this;
		}

		public Builder cache(Cache cache) {
			mappedStatement.cache = cache;
			return this;
		}

		public Builder flushCacheRequired(boolean flushCacheRequired) {
			mappedStatement.flushCacheRequired = flushCacheRequired;
			return this;
		}

		public Builder useCache(boolean useCache) {
			mappedStatement.useCache = useCache;
			return this;
		}

		public Builder resultOrdered(boolean resultOrdered) {
			mappedStatement.resultOrdered = resultOrdered;
			return this;
		}

		public Builder keyGenerator(KeyGenerator keyGenerator) {
			mappedStatement.keyGenerator = keyGenerator;
			return this;
		}

		public Builder keyProperty(String keyProperty) {
			mappedStatement.keyProperties = delimitedStringToArray(keyProperty);
			return this;
		}

		public Builder keyColumn(String keyColumn) {
			mappedStatement.keyColumns = delimitedStringToArray(keyColumn);
			return this;
		}

		public Builder databaseId(String databaseId) {
			mappedStatement.databaseId = databaseId;
			return this;
		}

		public Builder lang(LanguageDriver driver) {
			mappedStatement.lang = driver;
			return this;
		}

		public Builder resultSets(String resultSet) {
			mappedStatement.resultSets = delimitedStringToArray(resultSet);
			return this;
		}

		/**
		 * @deprecated Use {@link #resultSets}
		 */
		@Deprecated
		public Builder resulSets(String resultSet) {
			mappedStatement.resultSets = delimitedStringToArray(resultSet);
			return this;
		}

		public MappedStatement build() {
			assert mappedStatement.configuration != null;
			assert mappedStatement.id != null;
			assert mappedStatement.sqlSource != null;
			assert mappedStatement.lang != null;
			mappedStatement.resultMaps = Collections.unmodifiableList(mappedStatement.resultMaps);
			return mappedStatement;
		}
	}

	public KeyGenerator getKeyGenerator() {
		return keyGenerator;
	}

	public SqlCommandType getSqlCommandType() {
		return sqlCommandType;
	}

	public String getResource() {
		return resource;
	}

	public Configuration getConfiguration() {
		return configuration;
	}

	public String getId() {
		return id;
	}

	public boolean hasNestedResultMaps() {
		return hasNestedResultMaps;
	}

	public Integer getFetchSize() {
		return fetchSize;
	}

	public Integer getTimeout() {
		return timeout;
	}

	public StatementType getStatementType() {
		return statementType;
	}

	public ResultSetType getResultSetType() {
		return resultSetType;
	}

	public SqlSource getSqlSource() {
		return sqlSource;
	}

	public ParameterMap getParameterMap() {
		return parameterMap;
	}

	public List<ResultMap> getResultMaps() {
		return resultMaps;
	}

	public Cache getCache() {
		return cache;
	}

	public boolean isFlushCacheRequired() {
		return flushCacheRequired;
	}

	public boolean isUseCache() {
		return useCache;
	}

	public boolean isResultOrdered() {
		return resultOrdered;
	}

	public String getDatabaseId() {
		return databaseId;
	}

	public String[] getKeyProperties() {
		return keyProperties;
	}

	public String[] getKeyColumns() {
		return keyColumns;
	}

	public Log getStatementLog() {
		return statementLog;
	}

	public LanguageDriver getLang() {
		return lang;
	}

	public String[] getResultSets() {
		return resultSets;
	}

	/**
	 * @deprecated Use {@link #getResultSets()}
	 */
	@Deprecated
	public String[] getResulSets() {
		return resultSets;
	}

	public BoundSql getBoundSql(Object parameterObject) {
		// 获得 BoundSql 对象
		BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
		// 忽略，因为 <parameterMap /> 已经废弃，参见
		// http://www.mybatis.org/mybatis-3/zh/sqlmap-xml.html 文档
		List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
		if (parameterMappings == null || parameterMappings.isEmpty()) {
			boundSql = new BoundSql(configuration, boundSql.getSql(), parameterMap.getParameterMappings(),
					parameterObject);
		}

		// check for nested result maps in parameter mappings (issue #30)
		// 判断传入的参数中，是否有内嵌的结果 ResultMap 。如果有，则修改 hasNestedResultMaps 为 true
		// 存储过程相关，暂时无视
		for (ParameterMapping pm : boundSql.getParameterMappings()) {
			String rmId = pm.getResultMapId();
			if (rmId != null) {
				ResultMap rm = configuration.getResultMap(rmId);
				if (rm != null) {
					hasNestedResultMaps |= rm.hasNestedResultMaps();
				}
			}
		}

		return boundSql;
	}

	private static String[] delimitedStringToArray(String in) {
		if (in == null || in.trim().length() == 0) {
			return null;
		} else {
			return in.split(",");
		}
	}

}
