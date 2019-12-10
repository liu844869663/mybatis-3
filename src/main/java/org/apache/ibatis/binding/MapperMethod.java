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

	private final SqlCommand command;
	private final MethodSignature method;

	public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
		this.command = new SqlCommand(config, mapperInterface, method);
		this.method = new MethodSignature(config, mapperInterface, method);
	}

	public Object execute(SqlSession sqlSession, Object[] args) {
		// 根据SqlCommand的Type判断应该如何执行SQL语句
		Object result;
		switch (command.getType()) {
		case INSERT: {
			// <1> 获取参数值与参数名的映射
			// <2> 然后通过SqlSession进行相关操作，
			// <3> SqlSession调用不同的Executor执行器执行SQL语句
			// <4> Executor执行器通过MappedStatement生成对应的StatementHandler，StatementHandler生成对应的Statement对象，再执行相应的SQL并对返回结果进行处理
			Object param = method.convertArgsToSqlCommandParam(args);
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
			// <2.1> 无返回，并且有 ResultHandler 方法参数，则将查询的结果，提交给 ResultHandler 进行处理
			if (method.returnsVoid() && method.hasResultHandler()) {
				executeWithResultHandler(sqlSession, args);
				result = null;
			} else if (method.returnsMany()) { // <2.2> 执行查询，返回列表
				result = executeForMany(sqlSession, args);
			} else if (method.returnsMap()) { // <2.3> 执行查询，返回 Map
				result = executeForMap(sqlSession, args);
			} else if (method.returnsCursor()) { // <2.4> 执行查询，返回 Cursor
				result = executeForCursor(sqlSession, args);
			} else { // <2.5> 执行查询，返回单个对象
				// 转换参数
				Object param = method.convertArgsToSqlCommandParam(args);
				// 查询单条
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
		// 返回结果为 null ，并且返回类型为基本类型，则抛出 BindingException 异常
		if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
			throw new BindingException("Mapper method '" + command.getName()
					+ " attempted to return null from a method with a primitive return type (" + method.getReturnType()
					+ ").");
		}
		return result;
	}

	private Object rowCountResult(int rowCount) {
		final Object result;
		if (method.returnsVoid()) { // Void 情况，不用返回
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
		// 获得 MappedStatement 对象
		MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
		 // 校验存储过程的情况。不符合，抛出 BindingException 异常
		if (!StatementType.CALLABLE.equals(ms.getStatementType())
				&& void.class.equals(ms.getResultMaps().get(0).getType())) {
			throw new BindingException(
					"method " + command.getName() + " needs either a @ResultMap annotation, a @ResultType annotation,"
							+ " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
		}
		// 转换参数
		Object param = method.convertArgsToSqlCommandParam(args);
		// 执行 SELECT 操作
		if (method.hasRowBounds()) {
			RowBounds rowBounds = method.extractRowBounds(args);
			sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
		} else {
			sqlSession.select(command.getName(), param, method.extractResultHandler(args));
		}
	}

	private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
		List<E> result;
		// 转换参数
		Object param = method.convertArgsToSqlCommandParam(args);
		// 执行 SELECT 操作
		if (method.hasRowBounds()) {
			RowBounds rowBounds = method.extractRowBounds(args);
			result = sqlSession.selectList(command.getName(), param, rowBounds);
		} else {
			result = sqlSession.selectList(command.getName(), param);
		}
		// issue #510 Collections & arrays support
		// 封装 Array 或 Collection 结果
		if (!method.getReturnType().isAssignableFrom(result.getClass())) {
			if (method.getReturnType().isArray()) {
				return convertToArray(result);
			} else {
				return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
			}
		}
		// 直接返回的结果
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
		// 转换参数
		Object param = method.convertArgsToSqlCommandParam(args);
		// 执行 SELECT 操作
		if (method.hasRowBounds()) {
			RowBounds rowBounds = method.extractRowBounds(args);
			result = sqlSession.selectMap(command.getName(), param, method.getMapKey(), rowBounds);
		} else {
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
		 * MappedStatement的ID
		 */
		private final String name;
		/**
		 * MappedStatement的SqlCommandType
		 */
		private final SqlCommandType type;

		public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
			final String methodName = method.getName();
			final Class<?> declaringClass = method.getDeclaringClass();
			// 获取该方法对应的MappedStatement
			MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass, configuration);
			if (ms == null) {
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
			// 获取由名称+'.'+方法名称组成的statementId
			String statementId = mapperInterface.getName() + "." + methodName;
			// 在全局对象Configuration中根据statementId获取对应的MappedStatement
			if (configuration.hasStatement(statementId)) {
				return configuration.getMappedStatement(statementId);
			} else if (mapperInterface.equals(declaringClass)) {
				// 如果方法就是定义在该mapperInterface接口中
				return null;
			}
			// 获取该接口的父类
			for (Class<?> superInterface : mapperInterface.getInterfaces()) {
				if (declaringClass.isAssignableFrom(superInterface)) {
					// 如果该方法定义在接口中父类中，则通过接口的父类名称获取MappedStatement
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
		 * 用来标记该方法参数列表中ResultHandler类型参数得位置
		 */
		private final Integer resultHandlerIndex;
		/**
		 * 用来标记该方法参数列表中RowBounds类型参数得位置
		 */
		private final Integer rowBoundsIndex;
		/**
		 * ParamNameResolver对象，主要用于解析 @Param 注解定义的参数，参数值与参数得映射等
		 */
		private final ParamNameResolver paramNameResolver;

		public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
			// 获取该方法的返回类型
			Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
			if (resolvedReturnType instanceof Class<?>) { // Class<?> 类型
				this.returnType = (Class<?>) resolvedReturnType;
			} else if (resolvedReturnType instanceof ParameterizedType) { // 泛型类型
				// 获取该参数化类型的实际类型
				this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
			} else {
				this.returnType = method.getReturnType();
			}
			this.returnsVoid = void.class.equals(this.returnType);
			this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
			this.returnsCursor = Cursor.class.equals(this.returnType);
			this.returnsOptional = Optional.class.equals(this.returnType);
			this.mapKey = getMapKey(method);
			this.returnsMap = this.mapKey != null;
			this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
			this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
			this.paramNameResolver = new ParamNameResolver(configuration, method);
		}

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
