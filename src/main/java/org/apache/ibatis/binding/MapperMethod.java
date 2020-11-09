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
package org.apache.ibatis.binding;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 * @author Kazuki Shimizu
 */
public class MapperMethod {

  /**
   * 该方法对应的 SQL 的唯一编号与类型
   */
	private final SqlCommand command;
  /**
   * 该方法的签名，包含入参和出参的相关信息
   */
	private final MethodSignature method;

	public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
		this.command = new SqlCommand(config, mapperInterface, method);
		this.method = new MethodSignature(config, mapperInterface, method);
	}

	public Object execute(SqlSession sqlSession, Object[] args) {
		// 根据 SqlCommand 的 Type 判断应该如何执行 SQL 语句
		Object result;
		switch (command.getType()) {
		case INSERT: {
			// <1> 获取参数值与参数名的映射
			Object param = method.convertArgsToSqlCommandParam(args);
      // <2> 然后通过 SqlSession 进行数据库更新操作，并将受影响行数转换为结果
			result = rowCountResult(sqlSession.insert(command.getName(), param));
			break;
		}
		case UPDATE: {
			Object param = method.convertArgsToSqlCommandParam(args);
			result = rowCountResult(sqlSession.update(command.getName(), param));
			break;
		}
		case DELETE: {
			Object param = method.convertArgsToSqlCommandParam(args);
			result = rowCountResult(sqlSession.delete(command.getName(), param));
			break;
		}
		case SELECT:
			if (method.returnsVoid() && method.hasResultHandler()) { // 无返回，且入参中有 ResultHandler 结果处理器
				executeWithResultHandler(sqlSession, args);
				result = null;
			} else if (method.returnsMany()) {
        // 执行查询，返回列表
				result = executeForMany(sqlSession, args);
			} else if (method.returnsMap()) {
        // 执行查询，返回 Map
				result = executeForMap(sqlSession, args);
			} else if (method.returnsCursor()) {
        // 执行查询，返回 Cursor
				result = executeForCursor(sqlSession, args);
			} else { // 执行查询，返回单个对象
				// 获取参数名称与入参的映射
				Object param = method.convertArgsToSqlCommandParam(args);
				// 执行查询，返回单条数据
				result = sqlSession.selectOne(command.getName(), param);
				if (method.returnsOptional() && (result == null || !method.getReturnType().equals(result.getClass()))) {
					result = Optional.ofNullable(result);
				}
			}
			break;
		case FLUSH:
			result = sqlSession.flushStatements();
			break;
		default:
			throw new BindingException("Unknown execution method for: " + command.getName());
		}
		// 返回结果为 null ，并且返回类型为原始类型（基本类型），则抛出 BindingException 异常
		if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
			throw new BindingException("Mapper method '" + command.getName()
					+ " attempted to return null from a method with a primitive return type (" + method.getReturnType()
					+ ").");
		}
		return result;
	}

  /**
   * 将受影响行数转换为结果
   *
   * @param rowCount 受影响行数
   * @return 返回结果
   */
	private Object rowCountResult(int rowCount) {
		final Object result;
		if (method.returnsVoid()) {
			result = null;
		} else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
			result = rowCount;
		} else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
			result = (long) rowCount;
		} else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
			result = rowCount > 0;
		} else {
			throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: "
					+ method.getReturnType());
		}
		return result;
	}

	private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
		// <1> 获得 MappedStatement 对象
		MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
		/*
		 * <2> 配置校验
		 * 因为入参定义了 ResultHandler 结果处理器，所以如果不是存储过程，且没有配置返回结果的 Java Type，则会抛出异常
		 */
		if (!StatementType.CALLABLE.equals(ms.getStatementType())
				&& void.class.equals(ms.getResultMaps().get(0).getType())) {
			throw new BindingException(
					"method " + command.getName() + " needs either a @ResultMap annotation, a @ResultType annotation,"
							+ " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
		}
		// <3> 获取参数名称与入参的映射
		Object param = method.convertArgsToSqlCommandParam(args);
		// <4> 执行数据库查询操作
		if (method.hasRowBounds()) { // <4.1> 入参定义了 RowBounds 分页对象
		  // <4.1.1> 获取入参定义了 RowBounds 分页对象
			RowBounds rowBounds = method.extractRowBounds(args);
			// <4.1.2> 执行查询
			sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
		} else {
		  // <4.2> 执行查询
			sqlSession.select(command.getName(), param, method.extractResultHandler(args));
		}
	}

	private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
		List<E> result;
		// 获取参数名称与入参的映射
		Object param = method.convertArgsToSqlCommandParam(args);
		// 执行数据库查询操作
		if (method.hasRowBounds()) {
			RowBounds rowBounds = method.extractRowBounds(args);
			// 执行查询，返回 List 集合
			result = sqlSession.selectList(command.getName(), param, rowBounds);
		} else {
		  // 执行查询，返回 List 集合
			result = sqlSession.selectList(command.getName(), param);
		}
		// issue #510 Collections & arrays support
		// 封装 Array 或 Collection 结果
		if (!method.getReturnType().isAssignableFrom(result.getClass())) { // 如果不是 List 集合类型
			if (method.getReturnType().isArray()) {
			  // 将 List 转换成 Array 数组类型的结果
				return convertToArray(result);
			} else {
			  // 转换成其他 Collection 集合类型的结果
				return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
			}
		}
		// 直接返回 List 集合类型的结果
		return result;
	}

	private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
		Cursor<T> result;
		Object param = method.convertArgsToSqlCommandParam(args);
		if (method.hasRowBounds()) {
			RowBounds rowBounds = method.extractRowBounds(args);
			result = sqlSession.selectCursor(command.getName(), param, rowBounds);
		} else {
			result = sqlSession.selectCursor(command.getName(), param);
		}
		return result;
	}

	private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
		Object collection = config.getObjectFactory().create(method.getReturnType());
		MetaObject metaObject = config.newMetaObject(collection);
		metaObject.addAll(list);
		return collection;
	}

	@SuppressWarnings("unchecked")
	private <E> Object convertToArray(List<E> list) {
		Class<?> arrayComponentType = method.getReturnType().getComponentType();
		Object array = Array.newInstance(arrayComponentType, list.size());
		if (arrayComponentType.isPrimitive()) {
			for (int i = 0; i < list.size(); i++) {
				Array.set(array, i, list.get(i));
			}
			return array;
		} else {
			return list.toArray((E[]) array);
		}
	}

	private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
		Map<K, V> result;
		// 获取参数名称与入参的映射
		Object param = method.convertArgsToSqlCommandParam(args);
		// 执行 SELECT 操作
		if (method.hasRowBounds()) {
			RowBounds rowBounds = method.extractRowBounds(args);
      // 执行查询，返回 Map 集合
			result = sqlSession.selectMap(command.getName(), param, method.getMapKey(), rowBounds);
		} else {
      // 执行查询，返回 Map 集合
			result = sqlSession.selectMap(command.getName(), param, method.getMapKey());
		}
		return result;
	}

	public static class ParamMap<V> extends HashMap<String, V> {

		private static final long serialVersionUID = -2212268410512043556L;

		@Override
		public V get(Object key) {
			if (!super.containsKey(key)) {
				throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
			}
			return super.get(key);
		}

	}

	public static class SqlCommand {

		/**
		 * SQL的唯一编号：namespace+id（Mapper接口名称+'.'+方法名称），{# MappedStatement#id}
		 */
		private final String name;
		/**
		 * SQL 命令类型 {# MappedStatement#sqlCommandType}
		 */
		private final SqlCommandType type;

		public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
			final String methodName = method.getName();
			final Class<?> declaringClass = method.getDeclaringClass();
			// 获取该方法对应的 MappedStatement
			MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass, configuration);
			if (ms == null) {
			  // 如果有 @Flush 注解，则标记为 FLUSH 类型
				if (method.getAnnotation(Flush.class) != null) {
					name = null;
					type = SqlCommandType.FLUSH;
				} else {
					throw new BindingException(
							"Invalid bound statement (not found): " + mapperInterface.getName() + "." + methodName);
				}
			} else {
				name = ms.getId();
				type = ms.getSqlCommandType();
				if (type == SqlCommandType.UNKNOWN) {
					throw new BindingException("Unknown execution method for: " + name);
				}
			}
		}

		public String getName() {
			return name;
		}

		public SqlCommandType getType() {
			return type;
		}

		private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
				Class<?> declaringClass, Configuration configuration) {
			// 生成 MappedStatement 唯一编号：接口名称+'.'+方法名称
			String statementId = mapperInterface.getName() + "." + methodName;
			// 在全局对象 Configuration 中获取对应的 MappedStatement
			if (configuration.hasStatement(statementId)) {
				return configuration.getMappedStatement(statementId);
			} else if (mapperInterface.equals(declaringClass)) {
				// 如果方法就是定义在该接口中，又没找到则直接返回 null
				return null;
			}
			// 遍历父接口，获取对应的 MappedStatement
			for (Class<?> superInterface : mapperInterface.getInterfaces()) {
				if (declaringClass.isAssignableFrom(superInterface)) {
					MappedStatement ms = resolveMappedStatement(superInterface, methodName, declaringClass, configuration);
					if (ms != null) {
						return ms;
					}
				}
			}
			return null;
		}
	}

	public static class MethodSignature {

		/**
		 * 返回数据是否包含多个
		 */
		private final boolean returnsMany;
		/**
		 * 返回类型是否为Map的子类，并且该方法上面使用了 @MapKey 注解
		 */
		private final boolean returnsMap;
		/**
		 * 返回类型是否为 void
		 */
		private final boolean returnsVoid;
		/**
		 * 返回类型是否为 Cursor
		 */
		private final boolean returnsCursor;
		/**
		 * 返回类型是否为 Optional
		 */
		private final boolean returnsOptional;
		/**
		 * 返回类型
		 */
		private final Class<?> returnType;
		/**
		 * 方法上 @MapKey 注解定义的值
		 */
		private final String mapKey;
		/**
		 * 用来标记该方法参数列表中 ResultHandler 类型参数得位置
		 */
		private final Integer resultHandlerIndex;
		/**
		 * 用来标记该方法参数列表中 RowBounds 类型参数得位置
		 */
		private final Integer rowBoundsIndex;
		/**
		 * ParamNameResolver 对象，主要用于解析 @Param 注解定义的参数，参数值与参数得映射等
		 */
		private final ParamNameResolver paramNameResolver;

		public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
			// 获取该方法的返回类型
			Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
			if (resolvedReturnType instanceof Class<?>) {
				this.returnType = (Class<?>) resolvedReturnType;
			} else if (resolvedReturnType instanceof ParameterizedType) { // 泛型类型
				// 获取该参数化类型的实际类型
				this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
			} else {
				this.returnType = method.getReturnType();
			}
			// 是否为无返回结果
			this.returnsVoid = void.class.equals(this.returnType);
			// 返回类型是否为集合或者数组类型
			this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
			// 返回类型是否为游标类型
			this.returnsCursor = Cursor.class.equals(this.returnType);
			// 返回结果是否则 Optional 类型
			this.returnsOptional = Optional.class.equals(this.returnType);
			// 解析方法上面的 @MapKey 注解
			this.mapKey = getMapKey(method);
			this.returnsMap = this.mapKey != null;
			// 方法参数类型为 RowBounds 的位置
			this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
      // 方法参数类型为 ResultHandler 的位置
			this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
			/*
			 * 解析该方法参数名称生成参数位置与参数名称的映射
			 * @Param 注解则取其值作为参数名称，否则取其真实的参数名称，在没有则为参数位置
			 */
			this.paramNameResolver = new ParamNameResolver(configuration, method);
		}

    /**
     * 根据入参返回参数名称与入参的映射
     *
     * @param args 入参
     * @return 参数名称与入参的映射
     */
		public Object convertArgsToSqlCommandParam(Object[] args) {
			return paramNameResolver.getNamedParams(args);
		}

		public boolean hasRowBounds() {
			return rowBoundsIndex != null;
		}

		public RowBounds extractRowBounds(Object[] args) {
			return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
		}

		public boolean hasResultHandler() {
			return resultHandlerIndex != null;
		}

		public ResultHandler extractResultHandler(Object[] args) {
			return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
		}

		public String getMapKey() {
			return mapKey;
		}

		public Class<?> getReturnType() {
			return returnType;
		}

		public boolean returnsMany() {
			return returnsMany;
		}

		public boolean returnsMap() {
			return returnsMap;
		}

		public boolean returnsVoid() {
			return returnsVoid;
		}

		public boolean returnsCursor() {
			return returnsCursor;
		}

		/**
		 * return whether return type is {@code java.util.Optional}.
		 *
		 * @return return {@code true}, if return type is {@code java.util.Optional}
		 * @since 3.5.0
		 */
		public boolean returnsOptional() {
			return returnsOptional;
		}

		private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
			Integer index = null;
			final Class<?>[] argTypes = method.getParameterTypes();
			for (int i = 0; i < argTypes.length; i++) {
				if (paramType.isAssignableFrom(argTypes[i])) {
					if (index == null) {
						index = i;
					} else {
						throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
					}
				}
			}
			return index;
		}

		private String getMapKey(Method method) {
			String mapKey = null;
			if (Map.class.isAssignableFrom(method.getReturnType())) {
				final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
				if (mapKeyAnnotation != null) {
					mapKey = mapKeyAnnotation.value();
				}
			}
			return mapKey;
		}
	}

}
