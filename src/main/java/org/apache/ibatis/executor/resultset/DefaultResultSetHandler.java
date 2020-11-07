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

  /**
   * 延迟加载默认对象
   */
	private static final Object DEFERRED = new Object();
  /**
   * 执行器
   */
	private final Executor executor;
  /**
   * 全局配置对象
   */
	private final Configuration configuration;
  /**
   * 本次查询操作对应的 MappedStatement 对象
   */
	private final MappedStatement mappedStatement;
  /**
   * 分页对象
   */
	private final RowBounds rowBounds;
  /**
   * 参数处理器，默认为 DefaultParameterHandler
   */
	private final ParameterHandler parameterHandler;

  /**
   * 结果处理器，默认为DefaultResultHandler
   */
	private final ResultHandler<?> resultHandler;
  /**
   * SQL 相关信息
   */
	private final BoundSql boundSql;
  /**
   * 类型处理器注册表
   */
	private final TypeHandlerRegistry typeHandlerRegistry;
  /**
   * 对象实例工厂
   */
	private final ObjectFactory objectFactory;
  /**
   * Reflector 工厂
   */
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

	// temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
	private boolean useConstructorMappings;

	private static class PendingRelation {
		public MetaObject metaObject;
		public ResultMapping propertyMapping;
	}

  /**
   * 未被映射的字段
   */
	private static class UnMappedColumnAutoMapping {
    /**
     * 列名
     */
		private final String column;
    /**
     * 属性名称
     */
		private final String property;
    /**
     * 类型处理器
     */
		private final TypeHandler<?> typeHandler;
    /**
     * 是否为原始类型（基本类型）
     */
		private final boolean primitive;

		public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
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

		/*
		 * <1> 用于保存映射结果集得到的结果队形
		 * 多 ResultSet 的结果集合，每个 ResultSet 对应一个 Object 对象，而实际上，每个 Object 是 List<Object> 对象
		 */
		final List<Object> multipleResults = new ArrayList<>();

		int resultSetCount = 0;
		// <2> 获取 ResultSet 对象，并封装成 ResultSetWrapper
		ResultSetWrapper rsw = getFirstResultSet(stmt);

    /*
     * <3> 获得当前 MappedStatement 对象中的 ResultMap 集合，XML 映射文件中 <resultMap /> 标签生成的
     * 或者 配置 "resultType" 属性也会生成对应的 ResultMap 对象
     * 在 <select /> 标签配置 ResultMap 属性时，可以以逗号分隔配置多个，如果返回多个 ResultSet 则会一一映射，通常配置一个
     */
		List<ResultMap> resultMaps = mappedStatement.getResultMaps();
		int resultMapCount = resultMaps.size();
    // <4> 如果有返回结果，但是没有 ResultMap 接收对象则抛出异常
		validateResultMapsCount(rsw, resultMapCount);
		while (rsw != null && resultMapCount > resultSetCount) {
			ResultMap resultMap = resultMaps.get(resultSetCount);
      /*
       * <5> 完成结果集的映射，全部转换的 Java 对象
       * 保存至 multipleResults 集合中，或者 this.resultHandler 中
       */
			handleResultSet(rsw, resultMap, multipleResults, null);
      // 获取下一个结果集
			rsw = getNextResultSet(stmt);
      // 清空 nestedResultObjects 集合
			cleanUpAfterHandlingResultSet();
      // 递增 resultSetCount 结果集数量
			resultSetCount++;
		}

    // <6> 获取 resultSets 多结果集属性的配置，存储过程中使用，暂时忽略
		String[] resultSets = mappedStatement.getResultSets();
		if (resultSets != null) {
			while (rsw != null && resultSetCount < resultSets.length) {
				// 根据 resultSet 的名称，获取未处理的 ResultMapping
				ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
				if (parentMapping != null) {
					String nestedResultMapId = parentMapping.getNestedResultMapId();
					// 未处理的 ResultMap 对象
					ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
					// 完成结果集的映射，全部转换的 Java 对象
					handleResultSet(rsw, resultMap, null, parentMapping);
				}
        // 获取下一个结果集
				rsw = getNextResultSet(stmt);
				cleanUpAfterHandlingResultSet();
				resultSetCount++;
			}
		}

		// <7> 如果是 multipleResults 单元素，则取首元素返回
		return collapseSingleResultList(multipleResults);
	}

	@Override
	public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
		ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

    // 获得首个 ResultSet 结果集，并封装成 ResultSetWrapper 对象
		ResultSetWrapper rsw = getFirstResultSet(stmt);

    // 游标方式的查询，只允许一个 ResultSet 对象，所以只允许配置一个 ResultMap
		List<ResultMap> resultMaps = mappedStatement.getResultMaps();

		int resultMapCount = resultMaps.size();
		validateResultMapsCount(rsw, resultMapCount);
		if (resultMapCount != 1) {
			throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
		}

    // 获得 ResultMap 对象，后创建 DefaultCursor 对象
		ResultMap resultMap = resultMaps.get(0);
		return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
	}

	private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
		ResultSet rs = stmt.getResultSet();
		while (rs == null) {
			// move forward to get the first resultset in case the driver doesn't return the resultset as the first result (HSQLDB 2.1)
			if (stmt.getMoreResults()) { // 检测是否还有待处理的 ResultSet 对象
				rs = stmt.getResultSet();
			} else {
				if (stmt.getUpdateCount() == -1) {
					// no more results. Must be no resultset
					break;
				}
			}
		}
		// 封装成 ResultSetWrapper 对象
		return rs != null ? new ResultSetWrapper(rs, configuration) : null;
	}

	private ResultSetWrapper getNextResultSet(Statement stmt) {
		// Making this method tolerant of bad JDBC drivers
		try {
			// 检测 JDBC 是否支持多结果集
			if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
				// Crazy Standard JDBC way of determining if there are more results
				// 检测是否还有待处理的结果集，若存在，则封装成 ResultSetWrapper 对象并返回
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
	private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
		try {
			if (parentMapping != null) {
				// <1> 暂时忽略，因为只有存储过程的情况时 parentMapping 为非空
				handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
			} else {
				if (resultHandler == null) { // <2>
					// <2.1> 创建 DefaultResultHandler 默认结果处理器
					DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
          // <2.2> 处理结果集，进行一系列的处理，完成映射，将结果保存至 DefaultResultHandler 中
					handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
					// <2.3> 将结果集合添加至 multipleResults 中
					multipleResults.add(defaultResultHandler.getResultList());
				} else { // 用户自定义了 resultHandler，则结果都会保存在其中
          // <3> 处理结果集，进行一系列的处理，完成映射，将结果保存至 DefaultResultHandler 中
					handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
				}
			}
		} finally {
			// issue #228 (close resultsets)
      // <4> 关闭结果集
			closeResultSet(rsw.getResultSet());
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
    /*
     * <1> ResultMap 存在内嵌的 ResultMap
     * 例如 <resultMap /> 标签中 <association /> 或者 <collection /> 都会创建对应的 ResultMap 对象
     * 该对象的 id 会设置到 ResultMapping 的 nestedResultMapId 属性中，这就属于内嵌的 ResultMap
     */
		if (resultMap.hasNestedResultMaps()) { // 存在
		  // <1.1> 如果不允许在嵌套语句中使用分页，则对 rowBounds 进行校验，设置了 limit 或者 offset 则抛出异常，默认允许
			ensureNoRowBounds();
			// <1.2> 校验要不要使用自定义的 ResultHandler，针对内嵌的 ResultMap
			checkResultHandler();
      // <1.3> 处理结果集，进行映射，生成返回结果，保存至 resultHandler 或者设置到 parentMapping 的对应属性中
      // 这里会处理内嵌的 ResultMap
			handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
		} else {
		  // <2> 处理结果集，进行映射，生成返回结果，保存至 resultHandler 或者设置到 parentMapping 的对应属性中
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

  /**
   * 4.2 处理结果集（不含嵌套映射）
   */
  private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap,
                                                 ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // 默认的上下文对象，临时保存每一行的结果且记录返回结果数量
    DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    ResultSet resultSet = rsw.getResultSet();
    // <1> 根据 RowBounds 中的 offset 跳到到指定的记录
    skipRows(resultSet, rowBounds);
    // <2> 检测已经处理的行数是否已经达到上限（RowBounds.limit）以及 ResultSet 中是否还有可处理的记录
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      /*
       * <3> 获取最终的 ResultMap
       * 因为 ResultMap 可能使用到了 <discriminator /> 标签，需要根据不同的值映射不同的 ResultMap
       * 如果存在 Discriminator 鉴别器，则根据当前记录选择对应的 ResultMap，会一直嵌套处理
       */
      ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      // <4> 从结果集中获取到返回结果对象，进行映射，比较复杂，关键方法！！！
      Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
      // <5> 将返回结果对象保存至 resultHandler，或者设置到父对象 parentMapping 的对应属性中
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
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
	 * 5.处理结果集
	 */
	private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext,
			Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
		if (parentMapping != null) {
			// 嵌套查询或嵌套映射，将返回结果设置到父对象的对应属性中
			linkToParents(rs, parentMapping, rowValue);
		} else {
			// 普通映射，将结果对象保存到 ResultHandler 中
			callResultHandler(resultHandler, resultContext, rowValue);
		}
	}

	/**
	 * 5.1.将结果对象添加至 ResultHandler
	 */
	@SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object> */)
	private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
    /*
     * 递增返回结果数量 resultCount
     * 保存返回结果 resultObject
     */
		resultContext.nextResultObject(rowValue);
		// 将返回结果保存至 ResultHandler 中
		((ResultHandler<Object>) resultHandler).handleResult(resultContext);
	}

	private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
		/*
		 * 1. 检测 DefaultResultContext.stopped 字段是否停止
		 * 2. 检测映射行数是否达到 RowBounds.limit 限制
		 */
		return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
	}

	private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
		/*
		 * 根据 ResultSet 的类型来决定如何调到指定位置的记录
		 */
		if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) { // 光标不是只能向前移动
			if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
        // 直接定位到 offset 指定的记录
				rs.absolute(rowBounds.getOffset());
			}
		} else {
			// 通过多次调用其 next() 方法移动到 offset 的记录
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
	 * 4.2.2 处理结果集
	 */
	private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
		// <1> 保存延迟加载的集合
		final ResultLoaderMap lazyLoader = new ResultLoaderMap();
		// <2> 创建返回结果的实例对象（如果存在嵌套子查询且是延迟加载则为其创建代理对象，后续的延迟加载保存至 lazyLoader 中即可）
		Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);

		/*
		 * <3> 如果上面创建的返回结果的实例对象不为 null，并且没有对应的 TypeHandler 类型处理器，则需要对它进行赋值
		 * 例如我们返回结果为 java.lang.String 就不用了，因为上面已经处理且赋值了
		 */
		if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
			// <3.1> 将返回结果的实例对象封装成 MetaObject，便于操作
			final MetaObject metaObject = configuration.newMetaObject(rowValue);
      // <3.2> 标记是否成功映射了任意一个属性，useConstructorMappings 表示是否在构造方法中使用了参数映射
			boolean foundValues = this.useConstructorMappings;
			// <3.3> 检测是否需要自动映射
			if (shouldApplyAutomaticMappings(resultMap, false)) {
				/*
				 * <3.4> 从结果集中将未被映射的列值设置到返回结果 metaObject 中
				 * 返回是否映射成功，设置了1个或以上的属性值
				 */
				foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
			}
			/*
			 * <3.5> 从结果集中将 ResultMap 中需要映射的列值设置到返回结果 metaObject 中
			 * 返回是否映射成功，设置了1个或以上的属性值
			 */
			foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
			foundValues = lazyLoader.size() > 0 || foundValues;
			/*
			 * <3.6> 如果没有成功映射任意一个属性，则根据 returnInstanceForEmptyRow 全局配置（默认为false）返回空对象还是 null
			 */
			rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
		}
		// <4> 返回该结果对象
		return rowValue;
	}

	private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
    /*
     * 获取<resultMap />中的 autoMapping 配置
     * 如果不为空则返回该值，是否自定映射
     */
		if (resultMap.getAutoMapping() != null) {
			return resultMap.getAutoMapping();
		} else {
			/*
			 * 全局配置 AutoMappingBehavior 默认为 PARTIAL
			 * 如果是嵌套，这里默认就返回 false
			 */
			if (isNested) { // 嵌套映射
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
	 * 4.2.2.3 将明确被映射的字段设置到返回结果中
	 */
	private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,
			ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
		// <1> 获取 ResultMap 中明确需要进行映射的列名集合
		final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
		// 标记是否找到1个以上的属性值，延迟加载也算
		boolean foundValues = false;
		// <2> 获取 ResultMap 中所有的 ResultMapping 对象
		final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
		for (ResultMapping propertyMapping : propertyMappings) {
		  // 获取字段名
			String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
			if (propertyMapping.getNestedResultMapId() != null) {
				// the user added a column attribute to a nested result map, ignore it
				column = null;
			}
			/*
			 * <3> 从结果集获取属性值设置到返回结果中，处理以下三种场景：
			 * 1. 配置的 column 属性为"{prop1:col1,prop2:col2}"这种形式，
			 * 一般就是嵌套子查询，表示将col1和col2的列值设置到嵌套子查询的入参对象的prop1和prop2属性中
			 * 2. 基本类型的属性映射
			 * 3. 多结果集的场景处理，该属性来自另一个结果集
			 *
			 * 对于没有配置 column 属性不会处理
			 */
			if (propertyMapping.isCompositeResult() // 场景1
					|| (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) // 场景2
					|| propertyMapping.getResultSet() != null) { // 场景3
				// <4> 完成映射，从结果集中获取到对应的属性值
				Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
				// issue #541 make property optional
				final String property = propertyMapping.getProperty();
				if (property == null) {
					// <4.1> 没有配置对应的 Java 属性则跳过
				  continue;
				} else if (value == DEFERRED) {
          // <4.2> 如果是占位符，则跳过
					foundValues = true;
					continue;
				}
				if (value != null) {
					foundValues = true;
				}
				if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
					// gcode issue #377, call setter on nulls (value is not 'found')
          // <4.3> 将属性值设置到返回结果中
					metaObject.setValue(property, value); // 设置属性值
				}
			}
		}
		return foundValues;
	}

	private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping,
			ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
		if (propertyMapping.getNestedQueryId() != null) { // 嵌套子查询
			// <1> 执行嵌套子查询，返回查询结果，如果需要延迟记载则返回的是 DEFERRED
			return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
		} else if (propertyMapping.getResultSet() != null) { // 多结果集，存储过程相关，暂时忽略
			// <2> 多结果集处理，延迟加载，返回占位符
			addPendingChildRelation(rs, metaResultObject, propertyMapping);
			return DEFERRED;
		} else { // 结果映射
			// 获取 TypeHandler 类型处理器
			final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
			final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
			// <3> 通过 TypeHandler 类型处理器从结果集中获取该列对应的属性值
			return typeHandler.getResult(rs, column);
		}
	}

	private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap,
			MetaObject metaObject, String columnPrefix) throws SQLException {
	  // <1> ResultMap 中需要 "自动映射" 的列会缓存起来，这是对应的缓存 key
		final String mapKey = resultMap.getId() + ":" + columnPrefix;
		// <2> 先从缓存中获取
		List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
		if (autoMapping == null) {
			autoMapping = new ArrayList<>();
			// <3> 获取未映射的的列名集合，也就是数据库返回的列名在 ResultMap 中没有配置，例如我们配置的是 resultType 属性就全部没有配置
			final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
			for (String columnName : unmappedColumnNames) {
				String propertyName = columnName;
				/*
				 * <4> 如果配置了前缀，则将列名中的前缀去掉作为属性名
				 */
				if (columnPrefix != null && !columnPrefix.isEmpty()) {
					// When columnPrefix is specified, ignore columns without the prefix.
					if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
						// 如果列名以前缀开头则将前缀去除
						propertyName = columnName.substring(columnPrefix.length());
					} else {
						continue;
					}
				}
        /**
         * <5> 根据列名从入参对象中获取对应的属性名称，不管大小写都可以找到
         * {@link org.apache.ibatis.reflection.Reflector#caseInsensitivePropertyMap)
         */
				final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
				// <6> 开始创建 UnMappedColumnAutoMapping 对象
				if (property != null && metaObject.hasSetter(property)) {
					if (resultMap.getMappedProperties().contains(property)) {
            // 如果该属性配置了映射关系则跳过
						continue;
					}
					// <6.1> 获取属性名称的 Class 对象
					final Class<?> propertyType = metaObject.getSetterType(property);
					if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
						final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
						// <6.2.1> 创建该属性的 UnMappedColumnAutoMapping 对象，设置列名、属性名、类型处理器、是否为原始类型
						autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
					} else {
            // <6.2.2> 执行发现自动映射目标为未知列（或未知属性类型）的行为，默认为 NONE，不做任何行为
						configuration.getAutoMappingUnknownColumnBehavior().doAction(mappedStatement, columnName, property, propertyType);
					}
				} else {
          // 执行发现自动映射目标为未知列（或未知属性类型）的行为，默认为 NONE，不做任何行为
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
    // <1> 将这些未被映射的字段创建对应的 UnMappedColumnAutoMapping 对象
		List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
    // 标记是否找到1个以上的属性值，延迟加载也算
		boolean foundValues = false;
		if (!autoMapping.isEmpty()) {
		  // <2> 遍历未被映射的字段数组，将这些属性设置到返回结果对象中
			for (UnMappedColumnAutoMapping mapping : autoMapping) {
				// <2.1> 通过 TypeHandler 获取未被映射的字段的值
				final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
				if (value != null) {
					foundValues = true;
				}
        /*
         * <2.2> 如果属性值不为空，或者配置了值为 null 也往返回结果设置该属性值（不能是基本类型）
         */
				if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
					// gcode issue #377, call setter on nulls (value is not 'found')
          // 往返回结果设置属性值
					metaObject.setValue(mapping.property, value);
				}
			}
		}
		return foundValues;
	}

	// MULTIPLE RESULT SETS
	private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
		CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
		// 获取父对象的依赖关系
		List<PendingRelation> parents = pendingRelations.get(parentKey);
		if (parents != null) {
			for (PendingRelation parent : parents) {
				if (parent != null && rowValue != null) {
				  // 为父对象的该属性进行赋值
					linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
				}
			}
		}
	}

	private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping)
			throws SQLException {
		CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
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

	private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
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
	 * 4.2.2.1 创建返回结果的实例对象（如果存在嵌套子查询且是延迟加载则为其创建代理对象）
	 */
	private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader,
			String columnPrefix) throws SQLException {
	  // 标记构造方法中是否使用了参数映射
		this.useConstructorMappings = false; // reset previous mapping result
		// <1> 记录构造方法的入参类型
		final List<Class<?>> constructorArgTypes = new ArrayList<>();
		// <2> 记录构造方法的参数值
		final List<Object> constructorArgs = new ArrayList<>();
		// <3> 创建返回结果的实例对象，该步骤的核心！！！
		Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
    /*
     * <4> 如果返回结果的实例对象不为空，且返回结果没有对应的 TypeHandler 类型处理器
     * 则遍历所有的映射列，如果存在嵌套子查询并且要求延迟加载，那么为该返回结果的实例对象创建一个动态代理对象（Javassist）
     * 这样一来可以后续将需要延迟加载的属性放入 `lazyLoader` 中即可
     *
     * 为该对象创建对应的代理对象，其中通过 ResultLoaderMap 对延迟加载的方法进行了增强
     * 调用 getter 方法时执行查询并从 ResultLoaderMap 中删除，直接调用 setter 方法也会从中删除
     */
		if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
			final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
			for (ResultMapping propertyMapping : propertyMappings) {
				// issue gcode #109 && issue #149
				if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
					resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration,
							objectFactory, constructorArgTypes, constructorArgs);
					break;
				}
			}
		}
		// <5> 记录是否使用有参构造方法创建的该返回结果实例对象
		this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
		return resultObject;
	}

	private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes,
			List<Object> constructorArgs, String columnPrefix) throws SQLException {
		// 获取 Java Type
		final Class<?> resultType = resultMap.getType();
		// 创建对应的 MetaClass 对象，便于操作
		final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
		// 获取 <constructor /> 标签下构造函数的入参信息，可以通过这些入参确认一个构造函数
		final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();

		/*
		 * 创建结果对象，分为下面4种场景：
		 * 1. 结果集只有一列，且存在对应的 TypeHandler 类型处理器，例如返回 java.lang.String
		 * 2. <resultMap /> 标签下配置的 <constructor /> 标签下的构造函数参数信息不为空
		 * 3. 返回类型为接口，或者有默认的构造方法
		 * 4. 找到合适的构造方法
		 */
		if (hasTypeHandlerForResultObject(rsw, resultType)) { // 场景1
			// 将该列转换成对应 Java Type 的值，然后返回
			return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
		} else if (!constructorMappings.isEmpty()) { // 场景2
			// 根据 <constructor /> 标签下的构造方法入参配置，尝试从结果集中获取入参值，并创建返回结果的实例对象
			return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
		} else if (resultType.isInterface() || metaType.hasDefaultConstructor()) { // 场景3
		  // 使用默认无参构造方法创建返回结果的实例对象
			return objectFactory.create(resultType);
		} else if (shouldApplyAutomaticMappings(resultMap, false)) { // 场景4
			// 找到合适的构造方法并创建返回结果对象
			return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs);
		}
		throw new ExecutorException("Do not know how to create an instance of " + resultType);
	}

	Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType,
			List<ResultMapping> constructorMappings, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
			String columnPrefix) {
		// 标记是否找到配置的构造函数的所有入参
		boolean foundValues = false;
		for (ResultMapping constructorMapping : constructorMappings) {
			// 获取参数的 Java Type
			final Class<?> parameterType = constructorMapping.getJavaType();
			// 获取参数对应的 column 列名
			final String column = constructorMapping.getColumn();
			final Object value;
			try {
        /*
         * 获取该属性值，可能存在以下几种场景：
         * 1. 存在嵌套查询
         * 2. 存在嵌套 ResultMap
         * 3. 直接获取值
         */
				if (constructorMapping.getNestedQueryId() != null) { // 场景1
				  // 通过嵌套查询获取到该属性值
					value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
				} else if (constructorMapping.getNestedResultMapId() != null) { // 场景2
				  // 获取到嵌套的 ResultMap 对象
					final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
					// 从结果集中获取到嵌套 ResultMap 对应的值
					value = getRowValue(rsw, resultMap, getColumnPrefix(columnPrefix, constructorMapping));
				} else { // 场景3
					final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
					// 通过 TypeHandler 从结果集中获取该列的值
					value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
				}
			} catch (ResultMapException | SQLException e) {
				throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
			}
			constructorArgTypes.add(parameterType);
			constructorArgs.add(value);
			foundValues = value != null || foundValues;
		}
		// 如果构造函数的入参全部找到，则创建返回结果的实例对象
		return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
	}

	private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType,
			List<Class<?>> constructorArgTypes, List<Object> constructorArgs) throws SQLException {
		// 获取所有的构造函数
		final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
		// 找到添加了 @AutomapConstructor 注解的构造方法
		final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
		if (defaultConstructor != null) {
			// 使用这个构造方法创建返回结果的实例对象
			return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, defaultConstructor);
		} else {
			for (Constructor<?> constructor : constructors) { // 遍历所有的构造方法
				// 如果构造方法的入参与结果集中列的个数相同，并且入参的 Java Type 和列的 Jdbc Type 有类型处理器
				if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
					// 使用这个构造方法创建返回结果的实例对象
					return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, constructor);
				}
			}
		}
		throw new ExecutorException(
				"No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
	}

	private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes,
			List<Object> constructorArgs, Constructor<?> constructor) throws SQLException {
	  // 标记是否找到构造方法的所有入参
		boolean foundValues = false;
		for (int i = 0; i < constructor.getParameterTypes().length; i++) {
			// 参数类型
			Class<?> parameterType = constructor.getParameterTypes()[i];
			// 参数列名
			String columnName = rsw.getColumnNames().get(i);
			TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
			// 通过 TypeHandler 从结果集中获取该列的值
			Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
			constructorArgTypes.add(parameterType);
			constructorArgs.add(value);
			foundValues = value != null || foundValues;
		}
		// 如果构造函数的入参全部找到，则创建返回结果的实例对象
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
		// 获取 Java Type
		final Class<?> resultType = resultMap.getType();
		final String columnName;
    /*
     * 获取列名
     */
		if (!resultMap.getResultMappings().isEmpty()) { // 配置了 <resultMap />
		  // 获取 <resultMap /> 标签下的配置信息
			final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
			// 因为只有一个参数，则直接取第一个
			final ResultMapping mapping = resultMappingList.get(0);
			// 从配置中获取 column 属性
			columnName = prependPrefix(mapping.getColumn(), columnPrefix);
		} else {
		  // 从结果集中获取列名
			columnName = rsw.getColumnNames().get(0);
		}
		// 通过 Java Type 和列名获取对应的 TypeHandler
		final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
		// 通过 TypeHandler 将返回结果转换成对应 Java Type 的值
		return typeHandler.getResult(rsw.getResultSet(), columnName);
	}

	//
	// NESTED QUERY
	//
	private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix)
			throws SQLException {
		// <1> 获得嵌套查询关联的 id
		final String nestedQueryId = constructorMapping.getNestedQueryId();
		// <2> 获取嵌套查询对应的 MappedStatement 对象
		final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
		// <3> 获取嵌套查询的参数类型
		final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
		// <4> 获取嵌套查询的参数对象，已完成初始化
		final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
		Object value = null;
		// <5> 执行查询
		if (nestedQueryParameterObject != null) {
			// <5.1> 获取嵌套查询中的 SQL 对象
			final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
			// <5.2> 获取CacheKey对象
			final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
			final Class<?> targetType = constructorMapping.getJavaType();
			// <5.3> 创建 ResultLoader 对象
			final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
			// <5.4> 加载结果
			value = resultLoader.loadResult();
		}
		return value;
	}

  /*
   * 获得嵌套子查询的值，如果需要延迟记载则返回 DEFERRED
   */
	private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping,
			ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
		// <1> 获取嵌套子查询关联的ID
		final String nestedQueryId = propertyMapping.getNestedQueryId();
		// 获得属性名
		final String property = propertyMapping.getProperty();
		// 获得嵌套子查询的 MappedStatement 对象
		final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
		// 获得嵌套子查询的参数类型
		final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
		// <2> 准备好嵌套子查询的入参
		final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping,
				nestedQueryParameterType, columnPrefix);
		Object value = null;
		if (nestedQueryParameterObject != null) {
			// <3> 获得嵌套子查询的 BoundSql 对象
			final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
			// <4> 获得嵌套子查询本次查询的 CacheKey 对象
			final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      // 嵌套子查询的返回 Java Type
			final Class<?> targetType = propertyMapping.getJavaType();
			// <5> 检查缓存中已存在
			if (executor.isCached(nestedQuery, key)) {
				// <5.1> 创建 DeferredLoad 对象，并通过该 DeferredLoad 对象从缓存中加载结果对象
				executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
				// <5.2> 返回已定义
				value = DEFERRED;
			} else { // <6> 缓存中不存在
				// <6.1> 创建 ResultLoader 对象
				final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery,
						nestedQueryParameterObject, targetType, key, nestedBoundSql);
				if (propertyMapping.isLazy()) { // <6.2> 如果要求延迟加载，则延迟加载
					// <6.2.1> 如果该属性配置了延迟加载，则将其添加到 `ResultLoader.loaderMap` 中，等待真正使用时再执行嵌套查询并得到结果对象
					lazyLoader.addLoader(property, metaResultObject, resultLoader);
					// <6.2.2> 返回延迟加载占位符
					value = DEFERRED;
				} else { // <6.3> 如果不要求延迟加载，则直接执行加载对应的值
					value = resultLoader.loadResult();
				}
			}
		}
		return value;
	}

	private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
			String columnPrefix) throws SQLException {
		if (resultMapping.isCompositeResult()) { // 嵌套子查询是否有多个属性映射
		  // 从结果集中获取多个属性值设置到入参对象中
			return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
		} else {
		  // 从结果集中直接获取嵌套查询的入参
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
		// 根据类型处理器从结果集中获取该列的值，作为嵌套查询的入参
		return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
	}

	private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
			String columnPrefix) throws SQLException {
	  // 创建一个嵌套子查询的入参的实例对象
		final Object parameterObject = instantiateParameterObject(parameterType);
		final MetaObject metaObject = configuration.newMetaObject(parameterObject);
		// 标记是否找到一个或以上的属性值
		boolean foundValues = false;
		for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
		  // 获取嵌套子查询的入参该属性的 Java Type
			final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
			final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
			//通过 TypeHandler 根据该属性的 column 列名从该结果集中获取值
			final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
			// issue #353 & #560 do not execute nested query if key is null
			if (propValue != null) {
			  // 设置属性值到入参对象中
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
	 * 4.2.1 如果存在<discriminator />鉴别器，则进行处理，选择对应的 ResultMap，会一直嵌套处理
	 */
	public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix)
			throws SQLException {
		// 记录已经处理过的 ResultMap 的 id
		Set<String> pastDiscriminators = new HashSet<>();
		// <1> 获取 ResultMap 中的 Discriminator 鉴别器，<discriminator />标签会被解析成该对象
		Discriminator discriminator = resultMap.getDiscriminator();
		while (discriminator != null) {
			// <2> 获取当前记录中该列的值，通过类型处理器转换成了对应的类型
			final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
			// <3> 鉴别器根据该值获取到对应的 ResultMap 的 id
			final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
			if (configuration.hasResultMap(discriminatedMapId)) {
				// <3.1> 获取到对应的 ResultMap
				resultMap = configuration.getResultMap(discriminatedMapId);
				// <3.2> 记录上一次的鉴别器
				Discriminator lastDiscriminator = discriminator;
				// <3.3> 获取到对应 ResultMap 内的鉴别器，可能鉴别器里面还有鉴别器
				discriminator = resultMap.getDiscriminator();
				// <3.4> 检测是否出现循环嵌套了
				if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
					break;
				}
			} else {
			  // <4> 鉴别结果没有对应的 ResultMap，则直接跳过
				break;
			}
		}
		// <5> 返回最终使用的 ResultMap 对象
		return resultMap;
	}

	private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix)
			throws SQLException {
		// 获取 <discriminator />标签对应的的 ResultMapping 对象
		final ResultMapping resultMapping = discriminator.getResultMapping();
		// 获取 TypeHandler 类型处理器
		final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
		// 通过 TypeHandler 从 ResultSet 中获取该列的值
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
	 * 4.2.1 处理结果集（含嵌套映射）
	 */
	private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap,
			ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // 默认的上下文对象，临时保存每一行的结果且记录返回结果数量
		final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
		ResultSet resultSet = rsw.getResultSet();
    // <1> 根据 RowBounds 中的 offset 跳到到指定的记录
		skipRows(resultSet, rowBounds);
		Object rowValue = previousRowValue;
    // <2> 检测已经处理的行数是否已经达到上限（RowBounds.limit）以及 ResultSet 中是否还有可处理的记录
		while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      /*
       * <3> 获取最终的 ResultMap
       * 因为 ResultMap 可能使用到了 <discriminator /> 标签，需要根据不同的值映射不同的 ResultMap
       * 如果存在 Discriminator 鉴别器，则根据当前记录选择对应的 ResultMap，会一直嵌套处理
       */
			final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
			// <4> 生成当前结果集该 ResultMap 对应的 CacheKey
			final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
			// 根据 CacheKey 尝试从 nestedResultObjects 集合中获取到是否有内嵌的对象
			Object partialObject = nestedResultObjects.get(rowKey);
			// issue #577 && #542
			if (mappedStatement.isResultOrdered()) { // 检测 resultOrdered 属性
				if (partialObject == null && rowValue != null) {
					nestedResultObjects.clear();
					storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
				}
				rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
			} else {
				// <5> 完成映射，获取返回结果对象，另外还会添加至 nestedResultObjects 集合
				rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
				if (partialObject == null) {
          // <6> 将返回结果对象保存至 resultHandler，或者设置到父对象 parentMapping 的对应属性中
					storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
				}
			}
		}
		// 对 resultOrdered 属性为 true 时的特殊处理，调用 storeObject() 方法保存结果对象
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
	private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
		final String resultMapId = resultMap.getId();
		Object rowValue = partialObject;
		if (rowValue != null) { // 该对象已经被其他列映射了
			final MetaObject metaObject = configuration.newMetaObject(rowValue);
			// 将该对象添加到 ancestorObjects 集合中
			putAncestor(rowValue, resultMapId);
			// 处理嵌套查询
			applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
			// 将上面的对象从 ancestorObjects 中移除
			ancestorObjects.remove(resultMapId);
		} else {
      // 保存延迟加载的集合
			final ResultLoaderMap lazyLoader = new ResultLoaderMap();
			// <1> 创建返回结果的实例对象（如果存在嵌套子查询且是延迟加载则为其创建代理对象）
			rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
      /*
       * <2> 对该实例对象进行赋值
       * 如果返回结果的实例对象不为空，且返回结果没有对应的 TypeHandler 类型处理器
       */
			if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
			  // <3> 为该返回结果创建 MetaObject 对象，便于操作
				final MetaObject metaObject = configuration.newMetaObject(rowValue);
        // 标记是否成功映射了任意一个属性，useConstructorMappings 表示是否在构造方法中使用了参数映射
				boolean foundValues = this.useConstructorMappings;
        // <4> 检测是否需要自动映射
				if (shouldApplyAutomaticMappings(resultMap, true)) {
          /*
           * <4.1> 从结果集中将未被映射的列值设置到返回结果 metaObject 中
           * 返回是否映射成功，设置了1个或以上的属性值
           */
					foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
				}
        /*
         * <5> 从结果集中将 ResultMap 中需要映射的列值设置到返回结果 metaObject 中
         * 返回是否映射成功，设置了1个或以上的属性值
         */
				foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
				// <6> 将当前原型结果对象添加到 ancestorObjects 集合中
				putAncestor(rowValue, resultMapId);
				// <7> 处理嵌套查询
				foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
				// <8> 移除第6步往 ancestorObjects 集合中添加的当前结果对象
				ancestorObjects.remove(resultMapId);
				foundValues = lazyLoader.size() > 0 || foundValues;
				/*
				 * <9> 如果没有成功映射任意一个属性，则根据 returnInstanceForEmptyRow 全局配置（默认为false）返回空对象还是 null
				 */
				rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
			}
			if (combinedKey != CacheKey.NULL_CACHE_KEY) {
				// 将该对象保存至 nestedResultObjects中，后面的对象也可以引用
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
		// 遍历该 ResultMap 所有的 ResultMapping 对象
		for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
		  // 获取其对应的内嵌的 ResultMapId
			final String nestedResultMapId = resultMapping.getNestedResultMapId();
			/*
			 * <1> 如果该属性就是内嵌的 ResultMap，则开始对他处理
			 */
			if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
				try {
				  // <2> 获取列前缀
					final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
					// <3> 获取内嵌的最终的 ResultMap，可能存在鉴别器，需要根据值进行判断再取到对应的 ResultMap
					final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
					// <3> 处理循环引用，比如两个 ResultMap 中的字段相互引用的情况
					if (resultMapping.getColumnPrefix() == null) {
						// try to fill circular reference only when columnPrefix is not specified for
						// the nested result map (issue #215)
            // 因为这里是对内嵌的 ResultMap 进行处理
            // 如果从上层对象集合中又获取到当前对象的值，则可能出现循环引用了，所以这里需要跳出循环
						Object ancestorObject = ancestorObjects.get(nestedResultMapId);
						if (ancestorObject != null) {
							if (newObject) {
                // 将 ancestorObject 设置到外层对象对应的属性中
								linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
							}
              // 出现了循环引用，则不用执行下面路径创建新对象，而是重用之前的对象
							continue;
						}
					}
					// <4> 为内嵌的 ResultMap 生成 CacheKey
					final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
					// 将内嵌的 CacheKey 和父级的 CacheKey 进行合并
					final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
					// <5> 从内嵌的集合中获取该结果
					Object rowValue = nestedResultObjects.get(combinedKey);
					// 标记该内嵌对象是否已经存在
					boolean knownValue = rowValue != null;

					// <4> 如果父对象该属性值为null且为集合类型，则需要为该属性设置一个空的集合对象
					instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
					// <5> 校验不能为 null 的列是否都有值
					if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
						// <6> 完成映射，获取内嵌对象
						rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            /*
             * 注意，`!knownValue`这个条件，当嵌套对象已经存在于 nestedResultObjects 集合中时，说明相关列已经映射成嵌套对象
             * 假设对象 A 中有 b1 和 b2 两个属性，它们指向了对象 B，且两个属性都是由同一个 ResultMap 进行映射的
             * 在对一行记录进行映射时，首先映射 b1 属性会生成 B 对象且成功赋值，而 b2 属性则为null
             */
						if (rowValue != null && !knownValue) {
							// <7> 将得到的嵌套对象的结果保存到上层对象的对应属性中
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

	private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
		// 获取不能为空的列名
	  Set<String> notNullColumns = resultMapping.getNotNullColumns();
		if (notNullColumns != null && !notNullColumns.isEmpty()) {
			ResultSet rs = rsw.getResultSet();
			for (String column : notNullColumns) {
				rs.getObject(prependPrefix(column, columnPrefix));
				if (!rs.wasNull()) { // 最后读的一列都有值
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
	  // 获取内嵌的 ResultMap
		ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
		return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
	}

	//
	// UNIQUE RESULT KEY
	//
	/**
	 * 4.2.1 处理结果集（含嵌套映射）生成对应的cacheKey
	 */
	private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
		final CacheKey cacheKey = new CacheKey();
		cacheKey.update(resultMap.getId());
		/*
		 * 获取用来组合去唯一标识当前 ResultMap 的 ResultMapping 集合（例如定义的 <id />，没有定义则是全部）
		 */
		List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
		if (resultMappings.isEmpty()) { // 没有唯一标识组合
			if (Map.class.isAssignableFrom(resultMap.getType())) {
				// Map 类型则，则从结果集获取的所有列名和值添加到 CacheKey 中
				createRowKeyForMap(rsw, cacheKey);
			} else {
				// 将未映射的列名与值添加到 CacheKey 中
				createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
			}
		} else {
      // 将上面获取到的唯一标识组合对应的列名和值添加到 CacheKey 中
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
		// 获取<resultMap />标签下的<idArg />和<id />子标签对应的 ResultMapping
		List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
		if (resultMappings.isEmpty()) {
			// 如果上面的集合为空，则获取<resultMap />标签下所有的子标签对应的 ResultMapping
			resultMappings = resultMap.getPropertyResultMappings();
		}
		return resultMappings;
	}

	private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey,
			List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
		for (ResultMapping resultMapping : resultMappings) {
			if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) { // 存在嵌套映射
				// Issue #392
        // 循环调用该方法进行处理
				final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
				createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey,
						nestedResultMap.getConstructorResultMappings(),
						prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
			} else if (resultMapping.getNestedQueryId() == null) { // 不是嵌套子查询
				// 获取列名
				final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
				final TypeHandler<?> th = resultMapping.getTypeHandler();
				// 获取返回结果需要映射的列名
				List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
				// Issue #114
				if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) { // 如果该列名被映射
				  // 通过 TypeHandler 从结果集中获取该列的值
					final Object value = th.getResult(rsw.getResultSet(), column);
					if (value != null || configuration.isReturnInstanceForEmptyRow()) {
						// 将列名和列值添加至 CacheKey 对象中
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
		// 获取未被映射的列名集合
		List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
		for (String column : unmappedColumnNames) {
			String property = column;
			// 去除前缀
			if (columnPrefix != null && !columnPrefix.isEmpty()) {
				// When columnPrefix is specified, ignore columns without the prefix.
				if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
					property = column.substring(columnPrefix.length());
				} else {
					continue;
				}
			}
			if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
			  // 获取到该列对应的值
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
		// 如果父对象该属性值为null且为集合类型，则需要为该属性设置一个空的集合对象
		final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
		// 根据属性是否为集合类型，调用MetaObject的相应方法，将嵌套对象记录到外层对象的相应属性中
		if (collectionProperty != null) {
			final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
			// 往集合对象中添加返回结果
			targetMetaObject.add(rowValue);
		} else {
		  // 设置父对象该属性值
			metaObject.setValue(resultMapping.getProperty(), rowValue);
		}
	}

	private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
		final String propertyName = resultMapping.getProperty();
		// 获取该属性的值
		Object propertyValue = metaObject.getValue(propertyName);
		/*
		 * 该未进行初始化，并且是集合类型，则创建对应的集合对象
		 */
		if (propertyValue == null) {
			Class<?> type = resultMapping.getJavaType();
			if (type == null) {
				type = metaObject.getSetterType(propertyName);
			}
			try {
			  /*
			   * 如果是集合类型则创建对应的实例，并设置到该对象中
			   */
				if (objectFactory.isCollection(type)) {
					propertyValue = objectFactory.create(type);
					metaObject.setValue(propertyName, propertyValue);
					return propertyValue;
				}
			} catch (Exception e) {
				throw new ExecutorException("Error instantiating collection property for result '"
						+ resultMapping.getProperty() + "'.  Cause: " + e, e);
			}
		} else if (objectFactory.isCollection(propertyValue.getClass())) {
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
