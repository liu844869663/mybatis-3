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

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

	private static final Object DEFERRED = new Object();

	private final Executor executor;
	private final Configuration configuration;
	private final MappedStatement mappedStatement;
	private final RowBounds rowBounds;
	private final ParameterHandler parameterHandler;
	private final ResultHandler<?> resultHandler;
	private final BoundSql boundSql;
	private final TypeHandlerRegistry typeHandlerRegistry;
	private final ObjectFactory objectFactory;
	private final ReflectorFactory reflectorFactory;

	// nested resultmaps
	private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
	private final Map<String, Object> ancestorObjects = new HashMap<>();
	private Object previousRowValue;

	// multiple resultsets
	private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
	private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

	// Cached Automappings
	private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

	// temporary marking flag that indicate using constructor mapping (use field to
	// reduce memory usage)
	private boolean useConstructorMappings;

	private static class PendingRelation {
		public MetaObject metaObject;
		public ResultMapping propertyMapping;
	}

	private static class UnMappedColumnAutoMapping {
		private final String column;
		private final String property;
		private final TypeHandler<?> typeHandler;
		private final boolean primitive;

		public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler,
				boolean primitive) {
			this.column = column;
			this.property = property;
			this.typeHandler = typeHandler;
			this.primitive = primitive;
		}
	}

	public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement,
			ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql, RowBounds rowBounds) {
		this.executor = executor;
		this.configuration = mappedStatement.getConfiguration();
		this.mappedStatement = mappedStatement;
		this.rowBounds = rowBounds;
		this.parameterHandler = parameterHandler;
		this.boundSql = boundSql;
		this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
		this.objectFactory = configuration.getObjectFactory();
		this.reflectorFactory = configuration.getReflectorFactory();
		this.resultHandler = resultHandler;
	}

	//
	// HANDLE OUTPUT PARAMETER
	//

	@Override
	public void handleOutputParameters(CallableStatement cs) throws SQLException {
		final Object parameterObject = parameterHandler.getParameterObject();
		final MetaObject metaParam = configuration.newMetaObject(parameterObject);
		final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
		for (int i = 0; i < parameterMappings.size(); i++) {
			final ParameterMapping parameterMapping = parameterMappings.get(i);
			if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
				if (ResultSet.class.equals(parameterMapping.getJavaType())) {
					handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
				} else {
					final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
					metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
				}
			}
		}
	}

	private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam)
			throws SQLException {
		if (rs == null) {
			return;
		}
		try {
			final String resultMapId = parameterMapping.getResultMapId();
			final ResultMap resultMap = configuration.getResultMap(resultMapId);
			final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
			if (this.resultHandler == null) {
				final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
				handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
				metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
			} else {
				handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
			}
		} finally {
			// issue #228 (close resultsets)
			closeResultSet(rs);
		}
	}

	/**
	 * 1.处理结果集
	 */
	@Override
	public List<Object> handleResultSets(Statement stmt) throws SQLException {
		ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

		// 该集合用于保存映射结果集得到的结果队形
		final List<Object> multipleResults = new ArrayList<>();

		int resultSetCount = 0;
		// 获取第一个ResultSet对象，并封装成ResultSetWrapper
		ResultSetWrapper rsw = getFirstResultSet(stmt);

		// 获取MappedStatement.resultMaps集合
		// 在MyBatis初始化时映射文件中的<resultMap>节点会被解析为ResultMap对象，并保存至MappedStatement.resultMaps集合中
		// 如果SQL节点能够产生多个ResultSet，那么我们可以在SQL节点(<select
		// />等)的resultMap属性中配置多个<resultMap>节点的ID
		// 它们之间通过","分隔，实现多个结果集的映射
		List<ResultMap> resultMaps = mappedStatement.getResultMaps();
		int resultMapCount = resultMaps.size();
		validateResultMapsCount(rsw, resultMapCount); // 进行校验如果有返回结果，但是没有ResultMap对应则抛出异常
		while (rsw != null && resultMapCount > resultSetCount) {
			ResultMap resultMap = resultMaps.get(resultSetCount); // 获取该结果集对应的ResultMap对象
			// 根据ResultMap中定义的映射规则对ResultSet进行映射（转换成Java类型），并将映射的结果添加到multipleResults中
			handleResultSet(rsw, resultMap, multipleResults, null);
			rsw = getNextResultSet(stmt); // 获取下一个结果集
			cleanUpAfterHandlingResultSet(); // 清空nestedResultObjects集合
			resultSetCount++; // 递增resultSetCount
		}

		// 获取MappedStatement.resultSets属性。
		// 该属性仅对多结果集的情况使用，该属性将列出语句执行后返回的结果集，并给每个结果集一个名称，名称是逗号分隔的
		// 这里会根据ResultSet的名称处理嵌套映射
		String[] resultSets = mappedStatement.getResultSets();
		if (resultSets != null) {
			while (rsw != null && resultSetCount < resultSets.length) {
				// 根据resultSet的名称，获取未处理的ResultMapping
				ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
				if (parentMapping != null) {
					String nestedResultMapId = parentMapping.getNestedResultMapId();
					ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
					// 根据ResultMap映射结果集
					handleResultSet(rsw, resultMap, null, parentMapping);
				}
				rsw = getNextResultSet(stmt); // 获取下一个结果集
				cleanUpAfterHandlingResultSet(); // 清空nestedResultObjects集合
				resultSetCount++; // 递增resultSetCount
			}
		}

		return collapseSingleResultList(multipleResults);
	}

	@Override
	public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
		ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

		ResultSetWrapper rsw = getFirstResultSet(stmt);

		List<ResultMap> resultMaps = mappedStatement.getResultMaps();

		int resultMapCount = resultMaps.size();
		validateResultMapsCount(rsw, resultMapCount);
		if (resultMapCount != 1) {
			throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
		}

		ResultMap resultMap = resultMaps.get(0);
		return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
	}

	private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
		ResultSet rs = stmt.getResultSet();
		while (rs == null) {
			// move forward to get the first resultset in case the driver
			// doesn't return the resultset as the first result (HSQLDB 2.1)
			if (stmt.getMoreResults()) { // 检测是否还有待处理的ResultSet对象
				rs = stmt.getResultSet();
			} else {
				if (stmt.getUpdateCount() == -1) {
					// no more results. Must be no resultset
					break;
				}
			}
		}
		// 封装成ResultSetWrapper
		return rs != null ? new ResultSetWrapper(rs, configuration) : null;
	}

	private ResultSetWrapper getNextResultSet(Statement stmt) {
		// Making this method tolerant of bad JDBC drivers
		try {
			// 检测JDBC是否支持多结果集
			if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
				// Crazy Standard JDBC way of determining if there are more results
				// 检测是否还有待处理的结果集，若存在，则封装成ResultSetWrapper对象并返回
				if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
					ResultSet rs = stmt.getResultSet();
					if (rs == null) {
						return getNextResultSet(stmt);
					} else {
						return new ResultSetWrapper(rs, configuration);
					}
				}
			}
		} catch (Exception e) {
			// Intentionally ignored.
		}
		return null;
	}

	private void closeResultSet(ResultSet rs) {
		try {
			if (rs != null) {
				rs.close();
			}
		} catch (SQLException e) {
			// ignore
		}
	}

	private void cleanUpAfterHandlingResultSet() {
		nestedResultObjects.clear();
	}

	private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
		if (rsw != null && resultMapCount < 1) {
			throw new ExecutorException(
					"A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
							+ "'.  It's likely that neither a Result Type nor a Result Map was specified.");
		}
	}

	/**
	 * 2.处理结果集
	 */
	private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults,
			ResultMapping parentMapping) throws SQLException {
		try {
			if (parentMapping != null) {
				// 处理多结果集中的嵌套查询（ResultMap中关联的其他的ResultMap）
				handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
			} else {
				if (resultHandler == null) {
					// 使用默认的DefaultResultHandler
					DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
					// 对ResultSet进行映射，并将映射得到的结果对象添加到defaultResultHandler对象中暂存
					handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
					multipleResults.add(defaultResultHandler.getResultList());
				} else {
					// 使用用户指定的ResultHandler
					handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
				}
			}
		} finally {
			// issue #228 (close resultsets)
			closeResultSet(rsw.getResultSet()); // 关闭结果集
		}
	}

	@SuppressWarnings("unchecked")
	private List<Object> collapseSingleResultList(List<Object> multipleResults) {
		return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
	}

	//
	// HANDLE ROWS FOR SIMPLE RESULTMAP
	//
	/**
	 * 3.处理结果集
	 */
	public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler,
			RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
		if (resultMap.hasNestedResultMaps()) { // 存在嵌套ResultMap的情况
			ensureNoRowBounds();
			checkResultHandler();
			handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
		} else { // 不含嵌套映射的简单映射查询
			handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
		}
	}

	private void ensureNoRowBounds() {
		if (configuration.isSafeRowBoundsEnabled() && rowBounds != null
				&& (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
			throw new ExecutorException(
					"Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
							+ "Use safeRowBoundsEnabled=false setting to bypass this check.");
		}
	}

	protected void checkResultHandler() {
		if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
			throw new ExecutorException(
					"Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
							+ "Use safeResultHandlerEnabled=false setting to bypass this check "
							+ "or ensure your statement returns ordered data and set resultOrdered=true on it.");
		}
	}

	/**
	 * 4.2处理结果集（不含嵌套映射）
	 */
	private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap,
			ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
		// 默认的上下文对象
		DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
		ResultSet resultSet = rsw.getResultSet();
		// <1> 根据RowBounds中的offset定位到指定的记录
		skipRows(resultSet, rowBounds);
		// <2> 检测已经处理的行数是否已经达到上限(RowBounds.limit)以及ResultSet中是否还有可处理的记录
		while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
			// <3> 根据该记录以及ResultMap.discriminator，决定映射使用的ResultMap
			// 因为ResultMap可能使用到了<discriminator />，需要根据不同的值映射不同的ResultMap
			ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
			// <4> 根据最终确定的ResultMap对ResultSet中该行记录进行映射，得到映射后的结果对象
			Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
			// <5> 将映射创建的结果对象添加到ResultHandler.resultList中保存
			storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
		}
	}

	/**
	 * 5.处理结果集
	 */
	private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext,
			Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
		if (parentMapping != null) {
			// 嵌套查询或嵌套映射，将结果对象保存到父对象对应的属性中
			linkToParents(rs, parentMapping, rowValue);
		} else {
			// 普通映射，将结果对象保存到ResultHandler中
			callResultHandler(resultHandler, resultContext, rowValue);
		}
	}

	/**
	 * 5.1.将结果对象添加至ResultHandler的resultList中
	 */
	@SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object> */)
	private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext,
			Object rowValue) {
		// 递增DefaultResultContext.resultCount，该值用于检测处理的记录行数是否已经达到上限
		// 在RowBounds.limit字段中记录了该上限，之后将结果对象保存到DefaultResultContext的resultObject字段中
		resultContext.nextResultObject(rowValue);
		// 将结果对象添加到ResultHandler.resultList中保存
		((ResultHandler<Object>) resultHandler).handleResult(resultContext);
	}

	private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
		// 一个检测DefaultResultContext.stopped字段，另一个是检测映射行数是否达到RowBounds.limit限制
		return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
	}

	private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
		// 根据ResultSet的类型进行定位
		if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
			if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
				rs.absolute(rowBounds.getOffset()); // 直接定位到offset指定的记录
			}
		} else {
			// 通过多次调用ResultSet.next()方法移动到指定的记录
			for (int i = 0; i < rowBounds.getOffset(); i++) {
				if (!rs.next()) {
					break;
				}
			}
		}
	}

	//
	// GET VALUE FROM ROW FOR SIMPLE RESULT MAP
	//
	/**
	 * 4.2.2处理结果集
	 */
	private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
		// 延迟加载...
		final ResultLoaderMap lazyLoader = new ResultLoaderMap();
		// <4.1> 创建该行记录映射之后得到的结果对象，该结果对象的类型由<resultMap>节点的type属性指定
		Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);

		// 接下来给这个结果对象赋值
		if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
			// 创建上述结果对象相应的MetaObject对象
			final MetaObject metaObject = configuration.newMetaObject(rowValue);
			// 成功映射任意属性，则foundValues为true；否则foundValues为false
			boolean foundValues = this.useConstructorMappings;
			// 检测是否需要自动映射
			if (shouldApplyAutomaticMappings(resultMap, false)) {
				// <4.2> 自动映射中ResultMap中未明确指定的列
				foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
			}
			// <4.3> 映射ResultMap中明确指定需要映射的列
			foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
			foundValues = lazyLoader.size() > 0 || foundValues;
			// <4.4>
			// 如果没有成功映射任何属性，则根据mybatis-config.xml中的<returnInstanceForEmptyRow>配置决定返回空结果对象还是null
			rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
		}
		return rowValue;
	}

	private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
		if (resultMap.getAutoMapping() != null) { // 获取ResultMap中的autoMapping属性值
			return resultMap.getAutoMapping(); // 返回是否自动映射
		} else {
			// 默认为PARTIAL
			if (isNested) { // 检测是否为嵌套查询或是嵌套映射
				return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
			} else {
				return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
			}
		}
	}

	//
	// PROPERTY MAPPINGS
	//
	/**
	 * 4.2.2.3 对明确被映射的字段进行映射
	 */
	private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,
			ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
		// 获取ResultMap中明确需要进行映射的列名集合
		final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
		boolean foundValues = false;
		// 获取ResultMap中所有的ResultMapping对象
		final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
		for (ResultMapping propertyMapping : propertyMappings) {
			// 处理前缀
			String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
			if (propertyMapping.getNestedResultMapId() != null) {
				// the user added a column attribute to a nested result map, ignore it
				column = null;
			}
			// 场景1：column是"{prop1:col1,prop2:col2}"这种形式，一般与嵌套查询配合使用，表示将col1和col2的列值传递给内层嵌套查询作为参数
			// 场景2：基本类型的属性映射
			// 场景3：多结果集的场景处理，该属性来自另一个结果集
			if (propertyMapping.isCompositeResult() // 场景1
					|| (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) // 场景2
					|| propertyMapping.getResultSet() != null) { // 场景3
				// 完成映射并得到属性值
				Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader,
						columnPrefix);
				// issue #541 make property optional
				final String property = propertyMapping.getProperty();
				if (property == null) {
					continue;
				} else if (value == DEFERRED) { // 如果是占位符
					foundValues = true;
					continue;
				}
				if (value != null) {
					foundValues = true;
				}
				if (value != null || (configuration.isCallSettersOnNulls()
						&& !metaObject.getSetterType(property).isPrimitive())) {
					// gcode issue #377, call setter on nulls (value is not 'found')
					metaObject.setValue(property, value); // 设置属性值
				}
			}
		}
		return foundValues;
	}

	private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping,
			ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
		if (propertyMapping.getNestedQueryId() != null) {
			// 嵌套查询
			return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
		} else if (propertyMapping.getResultSet() != null) {
			// 多结果集处理
			addPendingChildRelation(rs, metaResultObject, propertyMapping); // TODO is that OK?
			return DEFERRED; // 返回占位符
		} else {
			// 获取TypeHandler对象
			final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
			final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
			// 使用TypeHandler对象获取属性值
			return typeHandler.getResult(rs, column);
		}
	}

	private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap,
			MetaObject metaObject, String columnPrefix) throws SQLException {
		final String mapKey = resultMap.getId() + ":" + columnPrefix; // 自动映射的缓存Key
		List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
		if (autoMapping == null) { // 缓存未命中
			autoMapping = new ArrayList<>();
			// 从ResultSetWrapper中获取未映射的的列名集合
			final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
			for (String columnName : unmappedColumnNames) {
				String propertyName = columnName; // 生产的属性名称
				if (columnPrefix != null && !columnPrefix.isEmpty()) { // 如果有指定前缀
					// When columnPrefix is specified,
					// ignore columns without the prefix.
					if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
						// 如果列名以前缀开头则将前缀去除
						propertyName = columnName.substring(columnPrefix.length());
					} else {
						continue;
					}
				}
				// 在结果对象中查找指定的属性名
				final String property = metaObject.findProperty(propertyName,
						configuration.isMapUnderscoreToCamelCase());
				if (property != null && metaObject.hasSetter(property)) {
					if (resultMap.getMappedProperties().contains(property)) {
						continue;
					}
					// 获取属性名称的Class对象
					final Class<?> propertyType = metaObject.getSetterType(property);
					if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
						final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
						// 设置列名、属性名、类型处理器、是否私有化
						autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler,
								propertyType.isPrimitive()));
					} else {
						configuration.getAutoMappingUnknownColumnBehavior().doAction(mappedStatement, columnName,
								property, propertyType);
					}
				} else {
					configuration.getAutoMappingUnknownColumnBehavior().doAction(mappedStatement, columnName,
							(property != null) ? property : propertyName, null);
				}
			}
			autoMappingsCache.put(mapKey, autoMapping);
		}
		return autoMapping;
	}

	/**
	 * 4.2.2.2 对未被映射的字段进行映射
	 */
	private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,
			String columnPrefix) throws SQLException {
		// 获取ResultSet中存在，但ResultMap中没有明确映射的列所对应的UnMappedColumnAutoMapping集合
		// 如果ResultMap中设置的ResultType为java.util.Map的话，则全部列在这里获取
		List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
		boolean foundValues = false; // 用于边界检测
		if (!autoMapping.isEmpty()) {
			for (UnMappedColumnAutoMapping mapping : autoMapping) {
				// 使用typeHandler获取自动映射的值
				final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
				if (value != null) {
					foundValues = true;
				}
				// 如果属性值不为NULL则直接设置
				// 或者配置可设置NULL值并且该属性不私有化也可以设置为NULL
				if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
					// gcode issue #377, call setter on nulls (value is not 'found')
					// 将自动映射的属性值设置到结果对象中
					metaObject.setValue(mapping.property, value);
				}
			}
		}
		return foundValues;
	}

	// MULTIPLE RESULT SETS

	private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
		CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(),
				parentMapping.getForeignColumn());
		List<PendingRelation> parents = pendingRelations.get(parentKey);
		if (parents != null) {
			for (PendingRelation parent : parents) {
				if (parent != null && rowValue != null) {
					linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
				}
			}
		}
	}

	private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping)
			throws SQLException {
		CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(),
				parentMapping.getColumn());
		PendingRelation deferLoad = new PendingRelation();
		deferLoad.metaObject = metaResultObject;
		deferLoad.propertyMapping = parentMapping;
		List<PendingRelation> relations = pendingRelations.computeIfAbsent(cacheKey, k -> new ArrayList<>());
		// issue #255
		relations.add(deferLoad);
		ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
		if (previous == null) {
			nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
		} else {
			if (!previous.equals(parentMapping)) {
				throw new ExecutorException("Two different properties are mapped to the same resultSet");
			}
		}
	}

	private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names,
			String columns) throws SQLException {
		CacheKey cacheKey = new CacheKey();
		cacheKey.update(resultMapping);
		if (columns != null && names != null) {
			String[] columnsArray = columns.split(",");
			String[] namesArray = names.split(",");
			for (int i = 0; i < columnsArray.length; i++) {
				Object value = rs.getString(columnsArray[i]);
				if (value != null) {
					cacheKey.update(namesArray[i]);
					cacheKey.update(value);
				}
			}
		}
		return cacheKey;
	}

	//
	// INSTANTIATION & CONSTRUCTOR MAPPING
	//
	/**
	 * 4.2.2.1 创建结果集对应的对象（如果存在延迟加载则为其创建代理对象）
	 */
	private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader,
			String columnPrefix) throws SQLException {
		// 表示是否使用构造函数创建该结果对象
		this.useConstructorMappings = false; // reset previous mapping result
		// 记录构造函数的参数类型
		final List<Class<?>> constructorArgTypes = new ArrayList<>();
		// 记录构造函数的实参
		final List<Object> constructorArgs = new ArrayList<>();
		// 创建该行记录对应的结果对象，该方法是该步骤的核心
		Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
		// 如果包含嵌套查询且配置了延迟加载，则创建代理对象（具体可以查看ResultLoaderMap和JavassistProxyFactory）
		if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
			final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
			for (ResultMapping propertyMapping : propertyMappings) {
				// issue gcode #109 && issue #149
				// 如果存在延迟加载，则为其创建代理对象
				if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
					resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration,
							objectFactory, constructorArgTypes, constructorArgs);
					break;
				}
			}
		}
		// 记录是否使用构造函数创建该结果对象
		this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping
																								// result
		return resultObject;
	}

	private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes,
			List<Object> constructorArgs, String columnPrefix) throws SQLException {
		// 获取ResultMap中的type属性，也就是配置的Java类型
		final Class<?> resultType = resultMap.getType();
		// 创建给Java类型的MetaClass对象
		final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
		// 获取ResultMap中记录的<constructor>节点信息，如果该集合不为空，则通过该集合确认相应Java类中的唯一构造函数
		final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();

		// 创建结果对象分为下面4种场景
		if (hasTypeHandlerForResultObject(rsw, resultType)) { // 场景1：结果集只有一列，且存在TypeHandler对象可以将该列转换成resultType类型的值
			// 先查找相应的TypeHandler对象，再使用TypeHandler对象将该记录转换成Java类型的值
			return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
		} else if (!constructorMappings.isEmpty()) { // 场景2
			// 根据<constructor>构造函数信息，通过反射调用构造函数方法，获取对应的返回结果
			return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes,
					constructorArgs, columnPrefix);
		} else if (resultType.isInterface() || metaType.hasDefaultConstructor()) { // 场景3：使用默认的无参构造函数
			return objectFactory.create(resultType);
		} else if (shouldApplyAutomaticMappings(resultMap, false)) { // 场景4
			// 通过自动映射的方式查找合适的构造方法并创建返回结果对象
			return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs);
		}
		throw new ExecutorException("Do not know how to create an instance of " + resultType);
	}

	Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType,
			List<ResultMapping> constructorMappings, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
			String columnPrefix) {
		// 记录是否全部找到构造函数每个参数的Java类型的值
		boolean foundValues = false;
		for (ResultMapping constructorMapping : constructorMappings) {
			// 获取构造函数的参数类型
			final Class<?> parameterType = constructorMapping.getJavaType();
			// 获取参数列名
			final String column = constructorMapping.getColumn();
			final Object value;
			try {
				if (constructorMapping.getNestedQueryId() != null) { // 存在嵌套查询
					value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
				} else if (constructorMapping.getNestedResultMapId() != null) { // 存在嵌套映射
					final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
					value = getRowValue(rsw, resultMap, getColumnPrefix(columnPrefix, constructorMapping));
				} else {
					final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
					// 将返回结果中的值转换成Java类型的值
					value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
				}
			} catch (ResultMapException | SQLException e) {
				throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
			}
			constructorArgTypes.add(parameterType);
			constructorArgs.add(value);
			foundValues = value != null || foundValues;
		}
		// 如果全部找到构造函数中参数的值，则创建返回结果对象
		return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
	}

	private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType,
			List<Class<?>> constructorArgTypes, List<Object> constructorArgs) throws SQLException {
		// 获取所有的构造函数
		final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
		// 找到合适的构造函数
		final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
		if (defaultConstructor != null) {
			// 使用该合适的构造函数创建返回结果对象
			return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, defaultConstructor);
		} else { // 如果没有找到合适的构造函数
			for (Constructor<?> constructor : constructors) {
				// 遍历所有的构造函数，如果构造函数的参数个数与ResultSet的JdbcType相同并且都有类型处理器
				if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
					// 使用该构造函数创建返回结果对象
					return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, constructor);
				}
			}
		}
		throw new ExecutorException(
				"No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
	}

	private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes,
			List<Object> constructorArgs, Constructor<?> constructor) throws SQLException {
		boolean foundValues = false;
		for (int i = 0; i < constructor.getParameterTypes().length; i++) {
			// 参数类型
			Class<?> parameterType = constructor.getParameterTypes()[i];
			// 参数列名
			String columnName = rsw.getColumnNames().get(i);
			// 参数对应的TypeHandler
			TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
			// 转换成Java类型的参数
			Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
			constructorArgTypes.add(parameterType);
			constructorArgs.add(value);
			foundValues = value != null || foundValues;
		}
		// 创建返回结果的对象
		return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
	}

	private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
		if (constructors.length == 1) {
			return constructors[0];
		}

		for (final Constructor<?> constructor : constructors) {
			if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
				return constructor;
			}
		}
		return null;
	}

	private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor,
			final List<JdbcType> jdbcTypes) {
		final Class<?>[] parameterTypes = constructor.getParameterTypes();
		if (parameterTypes.length != jdbcTypes.size()) {
			return false;
		}
		for (int i = 0; i < parameterTypes.length; i++) {
			if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
				return false;
			}
		}
		return true;
	}

	private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix)
			throws SQLException {
		// 获取ResultMap的Type属性，即返回结果的Java类型
		final Class<?> resultType = resultMap.getType();
		final String columnName;
		// 获取列名
		if (!resultMap.getResultMappings().isEmpty()) {
			final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
			final ResultMapping mapping = resultMappingList.get(0);
			columnName = prependPrefix(mapping.getColumn(), columnPrefix);
		} else {
			columnName = rsw.getColumnNames().get(0);
		}
		// 获取对应的TypeHandler
		final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
		// 通过TypeHandler将返回结果转换成Java类型
		return typeHandler.getResult(rsw.getResultSet(), columnName);
	}

	//
	// NESTED QUERY
	//
	private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix)
			throws SQLException {
		// 获取嵌套查询关联的ID
		final String nestedQueryId = constructorMapping.getNestedQueryId();
		// 获取嵌套查询对应的MappedStatement对象
		final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
		// 获取嵌套查询的参数类型
		final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
		// 获取嵌套查询的参数对象
		final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping,
				nestedQueryParameterType, columnPrefix);
		Object value = null;
		if (nestedQueryParameterObject != null) {
			// 获取嵌套查询中的Sq对象
			final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
			// 获取CacheKey对象
			final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT,
					nestedBoundSql);
			final Class<?> targetType = constructorMapping.getJavaType();
			// 创建 ResultLoader 对象
			final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery,
					nestedQueryParameterObject, targetType, key, nestedBoundSql);
			// 加载结果
			value = resultLoader.loadResult();
		}
		return value;
	}

	// 获得嵌套查询的值
	private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping,
			ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
		// 获取嵌套查询关联的ID
		final String nestedQueryId = propertyMapping.getNestedQueryId();
		// 获得属性名
		final String property = propertyMapping.getProperty();
		// 获得内嵌查询的 MappedStatement 对象
		final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
		// 获得内嵌查询的参数类型
		final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
		// 获得内嵌查询的参数对象
		final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping,
				nestedQueryParameterType, columnPrefix);
		Object value = null;
		if (nestedQueryParameterObject != null) {
			// 获得 BoundSql 对象
			final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
			// 获得 CacheKey 对象
			final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT,
					nestedBoundSql);
			final Class<?> targetType = propertyMapping.getJavaType();
			// <y> 检查缓存中已存在
			if (executor.isCached(nestedQuery, key)) {
				// 创建 DeferredLoad 对象，并通过该 DeferredLoad 对象从缓存中加载结采对象
				executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
				// 返回已定义
				value = DEFERRED;
			} else { // 检查缓存中不存在
				// 创建 ResultLoader 对象
				final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery,
						nestedQueryParameterObject, targetType, key, nestedBoundSql);
				if (propertyMapping.isLazy()) { // <x> 如果要求延迟加载，则延迟加载
					// 如果该属性配置了延迟加载，则将其添加到 `ResultLoader.loaderMap` 中，等待真正使用时再执行嵌套查询并得到结果对象
					lazyLoader.addLoader(property, metaResultObject, resultLoader);
					// 返回已定义
					value = DEFERRED;
				} else { // 如果不要求延迟加载，则直接执行加载对应的值
					value = resultLoader.loadResult();
				}
			}
		}
		return value;
	}

	private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
			String columnPrefix) throws SQLException {
		if (resultMapping.isCompositeResult()) {
			return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
		} else {
			return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
		}
	}

	private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
			String columnPrefix) throws SQLException {
		final TypeHandler<?> typeHandler;
		if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
			typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
		} else {
			typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
		}
		return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
	}

	private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
			String columnPrefix) throws SQLException {
		final Object parameterObject = instantiateParameterObject(parameterType);
		final MetaObject metaObject = configuration.newMetaObject(parameterObject);
		boolean foundValues = false;
		for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
			final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
			final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
			final Object propValue = typeHandler.getResult(rs,
					prependPrefix(innerResultMapping.getColumn(), columnPrefix));
			// issue #353 & #560 do not execute nested query if key is null
			if (propValue != null) {
				metaObject.setValue(innerResultMapping.getProperty(), propValue);
				foundValues = true;
			}
		}
		return foundValues ? parameterObject : null;
	}

	private Object instantiateParameterObject(Class<?> parameterType) {
		if (parameterType == null) {
			return new HashMap<>();
		} else if (ParamMap.class.equals(parameterType)) {
			return new HashMap<>(); // issue #649
		} else {
			return objectFactory.create(parameterType);
		}
	}

	//
	// DISCRIMINATOR
	//
	/**
	 * 4.2.1 选择ResultSet对应的ResultMap
	 */
	public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix)
			throws SQLException {
		// 记录已经处理过的ResultMap的ID
		Set<String> pastDiscriminators = new HashSet<>();
		// 获取ResultMap中的Discriminator对象
		// <discriminator />节点生成对应的Discriminator对象，不会生成ResultMapping对象
		Discriminator discriminator = resultMap.getDiscriminator();
		while (discriminator != null) {
			// 获取记录中对应列的值，其中会使用到相应的TypeHandler对象将该列的值转换成Java类型
			final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
			// 获取该值对应的ResultMap的ID
			final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
			if (configuration.hasResultMap(discriminatedMapId)) {
				// 根据对应的ResultMap的ID获取ResultMap
				resultMap = configuration.getResultMap(discriminatedMapId);
				Discriminator lastDiscriminator = discriminator;
				discriminator = resultMap.getDiscriminator();
				// 检测是否出现环形引用
				if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
					break;
				}
			} else {
				break;
			}
		}
		// 返回最终使用的ResultMap对象
		return resultMap;
	}

	private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix)
			throws SQLException {
		// 获取Discriminator对象中的ResultMapping对象
		final ResultMapping resultMapping = discriminator.getResultMapping();
		// 获取ResultMapping对象的TypeHandler
		final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
		// 根据typeHandler将列名转换成Java值
		return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
	}

	private String prependPrefix(String columnName, String prefix) {
		if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
			return columnName;
		}
		return prefix + columnName;
	}

	//
	// HANDLE NESTED RESULT MAPS
	//
	/**
	 * 4.2.1处理结果集（含嵌套映射）
	 */
	private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap,
			ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
		final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
		ResultSet resultSet = rsw.getResultSet();
		skipRows(resultSet, rowBounds); // 定位到指定记录行
		Object rowValue = previousRowValue;
		while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
			// 获取到合适的ResultMap
			final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
			// 生成对应的CacheKey
			final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
			// 根据上面的CacheKey查询nestedResultObjects集合中对应的外层对象
			Object partialObject = nestedResultObjects.get(rowKey);
			// issue #577 && #542
			if (mappedStatement.isResultOrdered()) { // 检测resultOrdered属性
				if (partialObject == null && rowValue != null) { // 主结果发生变化
					nestedResultObjects.clear(); // 清空nestedResultObjects集合
					// 将主结果对象（也就是嵌套查询的外层结果对象）保存
					storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
				}
				// 完成该行记录的映射返回对象，其中还会将结果对象添加到nestedResultObjects集合中
				rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
			} else {
				// 完成该行记录的映射返回对象，其中还会将结果对象添加到nestedResultObjects集合中
				rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
				if (partialObject == null) {
					// 将主结果对象保存
					storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
				}
			}
		}
		// 对resultOrdered属性为true时的特殊处理，调用storeObject()方法保存结果对象
		if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
			storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
			previousRowValue = null;
		} else if (rowValue != null) {
			previousRowValue = rowValue;
		}
	}

	//
	// GET VALUE FROM ROW FOR NESTED RESULT MAP
	//

	private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix,
			Object partialObject) throws SQLException {
		final String resultMapId = resultMap.getId();
		Object rowValue = partialObject;
		if (rowValue != null) { // 外层对象已经存在了
			final MetaObject metaObject = configuration.newMetaObject(rowValue);
			// 将外层对象添加到ancestorObjects集合中
			putAncestor(rowValue, resultMapId);
			// 处理嵌套查询
			applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
			// 将外层对象从ancestorObjects中移除
			ancestorObjects.remove(resultMapId);
		} else {
			final ResultLoaderMap lazyLoader = new ResultLoaderMap(); // 延迟加载
			// 创建外层对象
			rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
			if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
				final MetaObject metaObject = configuration.newMetaObject(rowValue);
				// 更新foundValues，其含义与简单映射中同名变量相同：成功映射任意属性，则其为true，否则为false
				boolean foundValues = this.useConstructorMappings;
				if (shouldApplyAutomaticMappings(resultMap, true)) { // 自动映射
					foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
				}
				// 映射ResultMap中明确指定的字段
				foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix)
						|| foundValues;
				// 将外层对象添加到ancestorObjects集合中
				putAncestor(rowValue, resultMapId);
				// 处理嵌套查询
				foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true)
						|| foundValues;
				// 将外层对象从ancestorObjects中移除
				ancestorObjects.remove(resultMapId);
				foundValues = lazyLoader.size() > 0 || foundValues;
				rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
			}
			if (combinedKey != CacheKey.NULL_CACHE_KEY) {
				// 将外层对象保存至nestedResultObjects中，待映射后续记录时使用
				nestedResultObjects.put(combinedKey, rowValue);
			}
		}
		return rowValue;
	}

	private void putAncestor(Object resultObject, String resultMapId) {
		ancestorObjects.put(resultMapId, resultObject);
	}

	//
	// NESTED RESULT MAP (JOIN MAPPING)
	//

	private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,
			String parentPrefix, CacheKey parentRowKey, boolean newObject) {
		boolean foundValues = false;
		// 遍历所有的ResultMapping对象，处理其中的嵌套映射
		for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
			final String nestedResultMapId = resultMapping.getNestedResultMapId(); // 嵌套ID
			if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
				try {
					final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
					// <1> 确认嵌套映射中使用的ResultMap对象
					final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId,
							columnPrefix);
					// <2> 处理循环引用，比如两个ResultMap中的字段相互引用的情况
					if (resultMapping.getColumnPrefix() == null) {
						// try to fill circular reference only when columnPrefix is not specified for
						// the nested result map (issue #215)
						Object ancestorObject = ancestorObjects.get(nestedResultMapId);
						if (ancestorObject != null) { // 出现了相互引用
							if (newObject) {
								linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
							}
							continue; // 若是循环引用，则不用执行下面路径创建新对象，而是重用之前的对象
						}
					}
					// <3> 为嵌套对象创建CacheKey
					final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
					final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
					// 查询nestedResultObjects集合中是否有相同的Key的嵌套对象
					Object rowValue = nestedResultObjects.get(combinedKey);
					boolean knownValue = rowValue != null;

					// <4> 初始化外层对象中Collection类型的属性
					instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
					// <5> 根据notNullColumn属性检测结果集中的空值
					if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
						// <6> 完成嵌套映射，并生成嵌套对象
						rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
						// 注意，"!knownValue"这个条件，当嵌套对象已经存在于nestedResultObjects集合中时，说明相关列已经映射成嵌套对象。
						// 现假设对象A中有b1和b2两个属性都指向了对象B且两个属性都是由同一ResultMap进行映射的。
						// 在对一行记录进行映射时，首先映射b1属性会生成B对象且成功赋值，而b2属性则为null
						if (rowValue != null && !knownValue) {
							// <7> 将得到的嵌套对象rowValue保存到外层对象的相应属性中
							linkObjects(metaObject, resultMapping, rowValue);
							foundValues = true;
						}
					}
				} catch (SQLException e) {
					throw new ExecutorException("Error getting nested result map values for '"
							+ resultMapping.getProperty() + "'.  Cause: " + e, e);
				}
			}
		}
		return foundValues;
	}

	private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
		final StringBuilder columnPrefixBuilder = new StringBuilder();
		if (parentPrefix != null) {
			columnPrefixBuilder.append(parentPrefix);
		}
		if (resultMapping.getColumnPrefix() != null) {
			columnPrefixBuilder.append(resultMapping.getColumnPrefix());
		}
		return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
	}

	private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw)
			throws SQLException {
		Set<String> notNullColumns = resultMapping.getNotNullColumns();
		if (notNullColumns != null && !notNullColumns.isEmpty()) {
			ResultSet rs = rsw.getResultSet();
			for (String column : notNullColumns) {
				rs.getObject(prependPrefix(column, columnPrefix));
				if (!rs.wasNull()) {
					return true;
				}
			}
			return false;
		} else if (columnPrefix != null) {
			for (String columnName : rsw.getColumnNames()) {
				if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
					return true;
				}
			}
			return false;
		}
		return true;
	}

	private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix)
			throws SQLException {
		ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
		return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
	}

	//
	// UNIQUE RESULT KEY
	//
	/**
	 * 4.2.1处理结果集（含嵌套映射）生成对应的cacheKey
	 */
	private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
		final CacheKey cacheKey = new CacheKey();
		cacheKey.update(resultMap.getId()); // 将ResultMap的ID作为cacheKey的一部分
		// 查找ResultMapping集合
		List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
		if (resultMappings.isEmpty()) { // 没有找到任何的ResultMapping
			if (Map.class.isAssignableFrom(resultMap.getType())) {
				// 由结果集中所有的列名以及当前记录行中所有的列值一起构成cacheKey
				createRowKeyForMap(rsw, cacheKey);
			} else {
				// 有结果集中未映射的列名以及它们在当前记录行中对应列值一起构成cacheKey
				createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
			}
		} else {
			// 由resultMappings集合中的列名以及它们当前记录行中相应的值一起构成cacheKey
			createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
		}
		// 如果上面两种操作都没有找到任何列参与构成cacheKey，则返回NULL_CACHE_KEY对象
		if (cacheKey.getUpdateCount() < 2) {
			return CacheKey.NULL_CACHE_KEY;
		}
		return cacheKey;
	}

	private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
		if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
			CacheKey combinedKey;
			try {
				combinedKey = rowKey.clone();
			} catch (CloneNotSupportedException e) {
				throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
			}
			combinedKey.update(parentRowKey);
			return combinedKey;
		}
		return CacheKey.NULL_CACHE_KEY;
	}

	private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
		// 获取ResultMap中的<idArg />和<id />节点对应的ResultMapping对象
		List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
		if (resultMappings.isEmpty()) {
			// 获取ResultMap除了上面两个节点之外的ResultMapping对象
			resultMappings = resultMap.getPropertyResultMappings();
		}
		return resultMappings;
	}

	private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey,
			List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
		for (ResultMapping resultMapping : resultMappings) {
			if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) { // 存在嵌套映射
				// 循环调用该方法进行处理
				// Issue #392
				final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
				createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey,
						nestedResultMap.getConstructorResultMappings(),
						prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
			} else if (resultMapping.getNestedQueryId() == null) { // 不是嵌套查询（已应该忽略嵌套查询情况）
				// 获取列名
				final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
				final TypeHandler<?> th = resultMapping.getTypeHandler();
				// 获取映射的列名
				List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
				// Issue #114
				if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) { // 如果该列名被映射
					// 通过TypeHandler获取该列对应的列值
					final Object value = th.getResult(rsw.getResultSet(), column);
					if (value != null || configuration.isReturnInstanceForEmptyRow()) {
						// 将列名和列值添加至cacheKey对象中
						cacheKey.update(column);
						cacheKey.update(value);
					}
				}
			}
		}
	}

	private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey,
			String columnPrefix) throws SQLException {
		final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
		List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
		for (String column : unmappedColumnNames) {
			String property = column;
			if (columnPrefix != null && !columnPrefix.isEmpty()) {
				// When columnPrefix is specified, ignore columns without the prefix.
				if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
					property = column.substring(columnPrefix.length());
				} else {
					continue;
				}
			}
			if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
				String value = rsw.getResultSet().getString(column);
				if (value != null) {
					cacheKey.update(column);
					cacheKey.update(value);
				}
			}
		}
	}

	private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
		List<String> columnNames = rsw.getColumnNames();
		for (String columnName : columnNames) {
			final String value = rsw.getResultSet().getString(columnName);
			if (value != null) {
				cacheKey.update(columnName);
				cacheKey.update(value);
			}
		}
	}

	private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
		// 检测外层对象是否为Collection类型，如果是且为初始化，则初始化该集合属性并返回
		final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
		// 根据属性是否为集合类型，调用MetaObject的相应方法，将嵌套对象记录到外层对象的相应属性中
		if (collectionProperty != null) {
			final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
			targetMetaObject.add(rowValue);
		} else {
			metaObject.setValue(resultMapping.getProperty(), rowValue);
		}
	}

	private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
		final String propertyName = resultMapping.getProperty();
		Object propertyValue = metaObject.getValue(propertyName);
		if (propertyValue == null) { // 未初始化
			Class<?> type = resultMapping.getJavaType();
			if (type == null) {
				type = metaObject.getSetterType(propertyName);
			}
			try {
				if (objectFactory.isCollection(type)) { // 集合类型
					// 创建该类型的集合对象，并设置其值
					propertyValue = objectFactory.create(type);
					metaObject.setValue(propertyName, propertyValue);
					return propertyValue;
				}
			} catch (Exception e) {
				throw new ExecutorException("Error instantiating collection property for result '"
						+ resultMapping.getProperty() + "'.  Cause: " + e, e);
			}
		} else if (objectFactory.isCollection(propertyValue.getClass())) { // 已经初始化
			return propertyValue;
		}
		return null;
	}

	private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
		if (rsw.getColumnNames().size() == 1) {
			return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
		}
		return typeHandlerRegistry.hasTypeHandler(resultType);
	}

}
