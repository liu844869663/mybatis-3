/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
 * java.sql.ResultSet 的包装类
 *
 * @author Iwao AVE!
 */
public class ResultSetWrapper {

  /**
   * ResultSet 对象
   */
  private final ResultSet resultSet;
  /**
   * 类型处理器注册表
   */
  private final TypeHandlerRegistry typeHandlerRegistry;
  /**
   * ResultSet 中每列的列名
   */
  private final List<String> columnNames = new ArrayList<>();
  /**
   * ResultSet 中每列对应的 Java Type
   */
  private final List<String> classNames = new ArrayList<>();
  /**
   * ResultSet 中每列对应的 Jdbc Type
   */
  private final List<JdbcType> jdbcTypes = new ArrayList<>();
  /**
   * 记录每列对应的 TypeHandler 对象
   * key：列名
   * value：TypeHandler 集合
   */
  private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<>();
  /**
   * 记录了被映射的列名
   * key：ResultMap 对象的 id {@link #getMapKey(ResultMap, String)}
   * value：ResultMap 对象映射的列名集合
   */
  private final Map<String, List<String>> mappedColumnNamesMap = new HashMap<>();
  /**
   * 记录了未映射的列名
   * key：ResultMap 对象的 id {@link #getMapKey(ResultMap, String)}
   * value：ResultMap 对象未被映射的列名集合
   */
  private final Map<String, List<String>> unMappedColumnNamesMap = new HashMap<>();

  public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
    super();
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.resultSet = rs;
    // 获取 ResultSet 的元信息
    final ResultSetMetaData metaData = rs.getMetaData();
    final int columnCount = metaData.getColumnCount();
    for (int i = 1; i <= columnCount; i++) {
      // 获得列名或者通过 AS 关键字指定列名的别名
      columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
      // 获得该列对应的 Jdbc Type
      jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i)));
      // 获得该列对应的 Java Type
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
   * @param propertyType Java 属性的类型
   * @param columnName   列名
   * @return TypeHandler 类型处理器
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
    if (handler == null) {
      // 获取该列对应的 Jdbc Type
      JdbcType jdbcType = getJdbcType(columnName);
      // 根据 Java Type 和 Jdbc Type 获取对应的 TypeHandler 类型处理器
      handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
      // Replicate logic of UnknownTypeHandler#resolveTypeHandler
      // See issue #59 comment 10
      if (handler == null || handler instanceof UnknownTypeHandler) {
        // 从 ResultSet 中获取该列对应的 Java Type 的 Class 对象
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
        // 最差的情况，设置为 ObjectTypeHandler
        handler = new ObjectTypeHandler();
      }
      // 将生成的 TypeHandler 存放在 typeHandlerMap 中
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

  private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    List<String> mappedColumnNames = new ArrayList<>();
    List<String> unmappedColumnNames = new ArrayList<>();
    // <1> 获取配置的列名的前缀，全部大写
    final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
    /*
     * <2> 获取 ResultMap 中配置的所有列名，并添加前缀
     * 如果在 <select /> 上面配置的是 resultType 属性，则返回的是空集合，因为它生成的 ResultMap 只有 Java Type 属性
     */
    final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix);
    /*
     * <3> 遍历数据库查询结果中所有的列名
     * 将所有列名分为两类：是否配置了映射
     */
    for (String columnName : columnNames) {
      final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
      if (mappedColumns.contains(upperColumnName)) {
        mappedColumnNames.add(upperColumnName);
      } else {
        unmappedColumnNames.add(columnName);
      }
    }
    // <4> 将上面两类的列名保存
    mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
    unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
  }

  public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    // 获得对应的 mapped 数组
    List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    if (mappedColumnNames == null) {
      // 初始化
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      // 重新获得对应的 mapped 数组
      mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    return mappedColumnNames;
  }

  public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    // 获得对应的 unMapped 数组
    List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    if (unMappedColumnNames == null) {
      // 初始化
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      // 重新获得对应的 unMapped 数组
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
   * @return 添加前缀后的列名集合
   * @description 为每个列名添加前缀
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
