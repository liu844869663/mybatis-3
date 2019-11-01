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
package org.apache.ibatis.executor.resultset;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * @author Iwao AVE!
 */
public class ResultSetWrapper {

	/**
	 * 底层封装的ResultSet对象
	 */
	private final ResultSet resultSet;
	/**
	 * TypeHandlerRegistry注册中心
	 */
	private final TypeHandlerRegistry typeHandlerRegistry;
	/**
	 * ResultSet中每列的列名
	 */
	private final List<String> columnNames = new ArrayList<>();
	/**
	 * ResultSet中每列对应的Java类型
	 */
	private final List<String> classNames = new ArrayList<>();
	/**
	 * ResultSet中每列对应的JDBC Type类型
	 */
	private final List<JdbcType> jdbcTypes = new ArrayList<>();
	/**
	 * 记录每列对应的TypeHandler对象，Key是列名，Value是TypeHandler集合
	 */
	private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<>();
	/**
	 * 记录了被映射的列名，其中Key是ResultMap对象的Id，Value是该ResultMap对象映射的列名集合
	 */
	private final Map<String, List<String>> mappedColumnNamesMap = new HashMap<>();
	/**
	 * 记录了未映射的列名，其中Key是ResultMap对象的Id，Value是该ResultMap对象未被映射的列名集合
	 */
	private final Map<String, List<String>> unMappedColumnNamesMap = new HashMap<>();

	public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
		super();
		this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
		this.resultSet = rs;
		final ResultSetMetaData metaData = rs.getMetaData(); // 获取ResultSet的元信息
		final int columnCount = metaData.getColumnCount();
		for (int i = 1; i <= columnCount; i++) {
			// 获得列名或是通过"AS"关键字指定的列名
			columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
			// 该列对应的JdbcType类型
			jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i)));
			// 该列对应的Java类型
			classNames.add(metaData.getColumnClassName(i));
		}
	}

	public ResultSet getResultSet() {
		return resultSet;
	}

	public List<String> getColumnNames() {
		return this.columnNames;
	}

	public List<String> getClassNames() {
		return Collections.unmodifiableList(classNames);
	}

	public List<JdbcType> getJdbcTypes() {
		return jdbcTypes;
	}

	public JdbcType getJdbcType(String columnName) {
		for (int i = 0; i < columnNames.size(); i++) {
			if (columnNames.get(i).equalsIgnoreCase(columnName)) {
				return jdbcTypes.get(i);
			}
		}
		return null;
	}

	/**
	 * Gets the type handler to use when reading the result set. Tries to get from
	 * the TypeHandlerRegistry by searching for the property type. If not found it
	 * gets the column JDBC type and tries to get a handler for it.
	 *
	 * @param propertyType
	 * @param columnName
	 * @return
	 */
	public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
		TypeHandler<?> handler = null;
		// 获取列名对应的类型处理器
		Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
		if (columnHandlers == null) {
			columnHandlers = new HashMap<>();
			typeHandlerMap.put(columnName, columnHandlers);
		} else {
			handler = columnHandlers.get(propertyType);
		}
		if (handler == null) { // 如果没有对应的TypeHandler
			// 获取JdbcType类型重新创建一个TypeHandler
			JdbcType jdbcType = getJdbcType(columnName);
			handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
			// Replicate logic of UnknownTypeHandler#resolveTypeHandler
			// See issue #59 comment 10
			if (handler == null || handler instanceof UnknownTypeHandler) {
				final int index = columnNames.indexOf(columnName);
				final Class<?> javaType = resolveClass(classNames.get(index));
				if (javaType != null && jdbcType != null) {
					handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
				} else if (javaType != null) {
					handler = typeHandlerRegistry.getTypeHandler(javaType);
				} else if (jdbcType != null) {
					handler = typeHandlerRegistry.getTypeHandler(jdbcType);
				}
			}
			if (handler == null || handler instanceof UnknownTypeHandler) {
				// 上述还获取失败则设置为默认的ObjectTypeHandler
				handler = new ObjectTypeHandler();
			}
			columnHandlers.put(propertyType, handler);
		}
		return handler;
	}

	private Class<?> resolveClass(String className) {
		try {
			// #699 className could be null
			if (className != null) {
				return Resources.classForName(className);
			}
		} catch (ClassNotFoundException e) {
			// ignore
		}
		return null;
	}

	// 将被映射的列名或者未被映射的列名分别添加到对应的集合中去
	private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
		List<String> mappedColumnNames = new ArrayList<>();
		List<String> unmappedColumnNames = new ArrayList<>();
		final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
		// 添加前缀
		final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix);
		for (String columnName : columnNames) {
			final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
			if (mappedColumns.contains(upperColumnName)) {
				mappedColumnNames.add(upperColumnName);
			} else {
				unmappedColumnNames.add(columnName);
			}
		}
		// Key为resultMap的Id+':'+columnPrefix
		mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
		unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
	}

	public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
		// 在mappedColumnNamesMap集合中查找被映射的列名
		List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
		if (mappedColumnNames == null) { // 为空则将ResultMap的列名添加至mappedColumnNamesMap中再获取
			loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
			mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
		}
		return mappedColumnNames;
	}

	public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
		List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
		if (unMappedColumnNames == null) {
			loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
			unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
		}
		return unMappedColumnNames;
	}

	private String getMapKey(ResultMap resultMap, String columnPrefix) {
		return resultMap.getId() + ":" + columnPrefix;
	}

	/**
	 * @param columnNames 列名集合
	 * @param prefix      前缀
	 * @description 为每个列名添加前缀
	 * @return 添加前缀后的列名集合
	 */
	private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
		if (columnNames == null || columnNames.isEmpty() || prefix == null || prefix.length() == 0) {
			return columnNames;
		}
		final Set<String> prefixed = new HashSet<>();
		for (String columnName : columnNames) {
			prefixed.add(prefix + columnName);
		}
		return prefixed;
	}

}
