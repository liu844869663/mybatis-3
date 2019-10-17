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
package org.apache.ibatis.type;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.chrono.JapaneseDate;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public final class TypeHandlerRegistry {

	/**
	 * JDBC Type 和 {@link TypeHandler} 的映射
	 *
	 * {@link #register(JdbcType, TypeHandler)}
	 */
	private final Map<JdbcType, TypeHandler<?>> jdbcTypeHandlerMap = new EnumMap<>(JdbcType.class);
	
	/**
	 * {@link TypeHandler} 的映射
	 *
	 * KEY1：Java Type
	 * VALUE1：{@link jdbcTypeHandlerMap} 对象，例如Date对应多种TypeHandler，所以采用Map
	 * KEY2：JDBC Type
	 * VALUE2：{@link TypeHandler} 对象
	 */
	private final Map<Type, Map<JdbcType, TypeHandler<?>>> typeHandlerMap = new ConcurrentHashMap<>();
	
	/**
	 * {@link UnknownTypeHandler} 对象
	 */
	private final TypeHandler<Object> unknownTypeHandler = new UnknownTypeHandler(this);
	
	/**
	 * 所有 TypeHandler 的“集合”
	 *
	 * KEY：{@link TypeHandler#getClass()}
	 * VALUE：{@link TypeHandler} 对象
	 */
	private final Map<Class<?>, TypeHandler<?>> allTypeHandlersMap = new HashMap<>();
	
	/**
	 * 空 TypeHandler 集合的标识，即使 {@link #TYPE_HANDLER_MAP} 中，某个 KEY1 对应的 Map<JdbcType, TypeHandler<?>> 为空。
	 *
	 * @see #getJdbcHandlerMap(Type)
	 */
	private static final Map<JdbcType, TypeHandler<?>> NULL_TYPE_HANDLER_MAP = Collections.emptyMap();
	
	/**
	 * 默认的枚举类型的 TypeHandler 对象
	 */
	private Class<? extends TypeHandler> defaultEnumTypeHandler = EnumTypeHandler.class;

	/**
	 * 无参构造方法，将Java Type与TypeHandler、JDBC Type与TypeHandler之间的对应添加至本地
	 */
	public TypeHandlerRegistry() {
		register(Boolean.class, new BooleanTypeHandler());
		register(boolean.class, new BooleanTypeHandler());
		register(JdbcType.BOOLEAN, new BooleanTypeHandler());
		register(JdbcType.BIT, new BooleanTypeHandler());

		register(Byte.class, new ByteTypeHandler());
		register(byte.class, new ByteTypeHandler());
		register(JdbcType.TINYINT, new ByteTypeHandler());

		register(Short.class, new ShortTypeHandler());
		register(short.class, new ShortTypeHandler());
		register(JdbcType.SMALLINT, new ShortTypeHandler());

		register(Integer.class, new IntegerTypeHandler());
		register(int.class, new IntegerTypeHandler());
		register(JdbcType.INTEGER, new IntegerTypeHandler());

		register(Long.class, new LongTypeHandler());
		register(long.class, new LongTypeHandler());

		register(Float.class, new FloatTypeHandler());
		register(float.class, new FloatTypeHandler());
		register(JdbcType.FLOAT, new FloatTypeHandler());

		register(Double.class, new DoubleTypeHandler());
		register(double.class, new DoubleTypeHandler());
		register(JdbcType.DOUBLE, new DoubleTypeHandler());

		register(Reader.class, new ClobReaderTypeHandler());
		register(String.class, new StringTypeHandler());
		register(String.class, JdbcType.CHAR, new StringTypeHandler());
		register(String.class, JdbcType.CLOB, new ClobTypeHandler());
		register(String.class, JdbcType.VARCHAR, new StringTypeHandler());
		register(String.class, JdbcType.LONGVARCHAR, new StringTypeHandler());
		register(String.class, JdbcType.NVARCHAR, new NStringTypeHandler());
		register(String.class, JdbcType.NCHAR, new NStringTypeHandler());
		register(String.class, JdbcType.NCLOB, new NClobTypeHandler());
		register(JdbcType.CHAR, new StringTypeHandler());
		register(JdbcType.VARCHAR, new StringTypeHandler());
		register(JdbcType.CLOB, new ClobTypeHandler());
		register(JdbcType.LONGVARCHAR, new StringTypeHandler());
		register(JdbcType.NVARCHAR, new NStringTypeHandler());
		register(JdbcType.NCHAR, new NStringTypeHandler());
		register(JdbcType.NCLOB, new NClobTypeHandler());

		register(Object.class, JdbcType.ARRAY, new ArrayTypeHandler());
		register(JdbcType.ARRAY, new ArrayTypeHandler());

		register(BigInteger.class, new BigIntegerTypeHandler());
		register(JdbcType.BIGINT, new LongTypeHandler());

		register(BigDecimal.class, new BigDecimalTypeHandler());
		register(JdbcType.REAL, new BigDecimalTypeHandler());
		register(JdbcType.DECIMAL, new BigDecimalTypeHandler());
		register(JdbcType.NUMERIC, new BigDecimalTypeHandler());

		register(InputStream.class, new BlobInputStreamTypeHandler());
		register(Byte[].class, new ByteObjectArrayTypeHandler());
		register(Byte[].class, JdbcType.BLOB, new BlobByteObjectArrayTypeHandler());
		register(Byte[].class, JdbcType.LONGVARBINARY, new BlobByteObjectArrayTypeHandler());
		register(byte[].class, new ByteArrayTypeHandler());
		register(byte[].class, JdbcType.BLOB, new BlobTypeHandler());
		register(byte[].class, JdbcType.LONGVARBINARY, new BlobTypeHandler());
		register(JdbcType.LONGVARBINARY, new BlobTypeHandler());
		register(JdbcType.BLOB, new BlobTypeHandler());

		register(Object.class, unknownTypeHandler);
		register(Object.class, JdbcType.OTHER, unknownTypeHandler);
		register(JdbcType.OTHER, unknownTypeHandler);

		register(Date.class, new DateTypeHandler());
		register(Date.class, JdbcType.DATE, new DateOnlyTypeHandler());
		register(Date.class, JdbcType.TIME, new TimeOnlyTypeHandler());
		register(JdbcType.TIMESTAMP, new DateTypeHandler());
		register(JdbcType.DATE, new DateOnlyTypeHandler());
		register(JdbcType.TIME, new TimeOnlyTypeHandler());

		register(java.sql.Date.class, new SqlDateTypeHandler());
		register(java.sql.Time.class, new SqlTimeTypeHandler());
		register(java.sql.Timestamp.class, new SqlTimestampTypeHandler());

		register(String.class, JdbcType.SQLXML, new SqlxmlTypeHandler());

		register(Instant.class, new InstantTypeHandler());
		register(LocalDateTime.class, new LocalDateTimeTypeHandler());
		register(LocalDate.class, new LocalDateTypeHandler());
		register(LocalTime.class, new LocalTimeTypeHandler());
		register(OffsetDateTime.class, new OffsetDateTimeTypeHandler());
		register(OffsetTime.class, new OffsetTimeTypeHandler());
		register(ZonedDateTime.class, new ZonedDateTimeTypeHandler());
		register(Month.class, new MonthTypeHandler());
		register(Year.class, new YearTypeHandler());
		register(YearMonth.class, new YearMonthTypeHandler());
		register(JapaneseDate.class, new JapaneseDateTypeHandler());

		// issue #273
		register(Character.class, new CharacterTypeHandler());
		register(char.class, new CharacterTypeHandler());
	}

	/**
	 * Set a default {@link TypeHandler} class for {@link Enum}. A default
	 * {@link TypeHandler} is {@link org.apache.ibatis.type.EnumTypeHandler}.
	 * 
	 * @param typeHandler a type handler class for {@link Enum}
	 * @since 3.4.5
	 */
	public void setDefaultEnumTypeHandler(Class<? extends TypeHandler> typeHandler) {
		this.defaultEnumTypeHandler = typeHandler;
	}

	public boolean hasTypeHandler(Class<?> javaType) {
		return hasTypeHandler(javaType, null);
	}

	public boolean hasTypeHandler(TypeReference<?> javaTypeReference) {
		return hasTypeHandler(javaTypeReference, null);
	}

	public boolean hasTypeHandler(Class<?> javaType, JdbcType jdbcType) {
		return javaType != null && getTypeHandler((Type) javaType, jdbcType) != null;
	}

	public boolean hasTypeHandler(TypeReference<?> javaTypeReference, JdbcType jdbcType) {
		return javaTypeReference != null && getTypeHandler(javaTypeReference, jdbcType) != null;
	}

	public TypeHandler<?> getMappingTypeHandler(Class<? extends TypeHandler<?>> handlerType) {
		return allTypeHandlersMap.get(handlerType);
	}

	public <T> TypeHandler<T> getTypeHandler(Class<T> type) {
		return getTypeHandler((Type) type, null);
	}

	public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference) {
		return getTypeHandler(javaTypeReference, null);
	}

	public TypeHandler<?> getTypeHandler(JdbcType jdbcType) {
		return jdbcTypeHandlerMap.get(jdbcType);
	}

	public <T> TypeHandler<T> getTypeHandler(Class<T> type, JdbcType jdbcType) {
		return getTypeHandler((Type) type, jdbcType);
	}

	public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference, JdbcType jdbcType) {
		return getTypeHandler(javaTypeReference.getRawType(), jdbcType);
	}

	/**
	 * 根据JAVA Type和JDBC Type获取一个TypeHandler
	 * 如果JDBC Type为null，则从jdbcHandlerMap中获取第一个TypeHandler
	 */
	@SuppressWarnings("unchecked")
	private <T> TypeHandler<T> getTypeHandler(Type type, JdbcType jdbcType) {
		// 忽略 ParamMap 的情况
		if (ParamMap.class.equals(type)) {
			return null;
		}
		// <1> 获得 Java Type 对应的 TypeHandler 集合
		Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = getJdbcHandlerMap(type);
		TypeHandler<?> handler = null;
		if (jdbcHandlerMap != null) {
			// <2.1> 优先，使用 jdbcType 获取对应的 TypeHandler
			handler = jdbcHandlerMap.get(jdbcType);
			// <2.2> 其次，使用 null 获取对应的 TypeHandler ，可以认为是默认的 TypeHandler
			if (handler == null) {
				handler = jdbcHandlerMap.get(null);
			}
			// <2.3> 最差，从 TypeHandler 集合中选择一个唯一的 TypeHandler
			if (handler == null) {
				// #591
				handler = pickSoleHandler(jdbcHandlerMap);
			}
		}
		// type drives generics here
		return (TypeHandler<T>) handler;
	}

	private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMap(Type type) {
		// <1.1> 获得 Java Type 对应的 TypeHandler 集合
		Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = typeHandlerMap.get(type);
		// <1.2> 如果为 NULL_TYPE_HANDLER_MAP ，意味着为空，直接返回
		if (NULL_TYPE_HANDLER_MAP.equals(jdbcHandlerMap)) {
			return null;
		}
		// <1.3> 如果找不到
		if (jdbcHandlerMap == null && type instanceof Class) {
			Class<?> clazz = (Class<?>) type;
			if (Enum.class.isAssignableFrom(clazz)) { // 枚举类型
				// 获得父类对应的 TypeHandler 集合
				Class<?> enumClass = clazz.isAnonymousClass() ? clazz.getSuperclass() : clazz;
				jdbcHandlerMap = getJdbcHandlerMapForEnumInterfaces(enumClass, enumClass);
				if (jdbcHandlerMap == null) { // 如果找不到
					// 注册 defaultEnumTypeHandler ，并使用它
					register(enumClass, getInstance(enumClass, defaultEnumTypeHandler));
					// 返回结果
					return typeHandlerMap.get(enumClass);
				}
			} else { // 非枚举类型
				// 获得父类对应的 TypeHandler 集合
				jdbcHandlerMap = getJdbcHandlerMapForSuperclass(clazz);
			}
		}
		// <1.4> 如果结果为空，设置为 NULL_TYPE_HANDLER_MAP ，提升查找速度，避免二次查找
		typeHandlerMap.put(type, jdbcHandlerMap == null ? NULL_TYPE_HANDLER_MAP : jdbcHandlerMap);
		return jdbcHandlerMap;
	}

	private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForEnumInterfaces(Class<?> clazz, Class<?> enumClazz) {
		// 遍历枚举类的所有接口
		for (Class<?> iface : clazz.getInterfaces()) {
			// 获得该接口对应的 jdbcHandlerMap 集合
			Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = typeHandlerMap.get(iface);
			// 为空，递归 getJdbcHandlerMapForEnumInterfaces 方法，继续从父类对应的 TypeHandler 集合
			if (jdbcHandlerMap == null) {
				jdbcHandlerMap = getJdbcHandlerMapForEnumInterfaces(iface, enumClazz);
			}
			// 如果找到，则从 jdbcHandlerMap 初始化中 newMap 中，并进行返回
			if (jdbcHandlerMap != null) {
				// Found a type handler regsiterd to a super interface
				HashMap<JdbcType, TypeHandler<?>> newMap = new HashMap<>();
				for (Entry<JdbcType, TypeHandler<?>> entry : jdbcHandlerMap.entrySet()) {
					// Create a type handler instance with enum type as a constructor arg
					newMap.put(entry.getKey(), getInstance(enumClazz, entry.getValue().getClass()));
				}
				return newMap;
			}
		}
		return null;
	}

	private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForSuperclass(Class<?> clazz) {
		// 获得父类
		Class<?> superclass = clazz.getSuperclass();
		// 不存在非 Object 的父类，返回 null
		if (superclass == null || Object.class.equals(superclass)) {
			return null;
		}
		// 获得父类对应的 TypeHandler 集合
		Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = typeHandlerMap.get(superclass);
		// 找到，则直接返回
		if (jdbcHandlerMap != null) {
			return jdbcHandlerMap;
		} else {
			// 找不到，则递归 getJdbcHandlerMapForSuperclass 方法，继续获得父类对应的 TypeHandler 集合
			return getJdbcHandlerMapForSuperclass(superclass);
		}
	}

	private TypeHandler<?> pickSoleHandler(Map<JdbcType, TypeHandler<?>> jdbcHandlerMap) {
		// 实际上就是从中选择第一个
		TypeHandler<?> soleHandler = null;
		for (TypeHandler<?> handler : jdbcHandlerMap.values()) {
			// 选择一个
			if (soleHandler == null) {
				soleHandler = handler;
			} else if (!handler.getClass().equals(soleHandler.getClass())) { // 如果还有，并且不同类，那么不好选择，所以返回 null
				// More than one type handlers registered.
				return null;
			}
		}
		return soleHandler;
	}

	public TypeHandler<Object> getUnknownTypeHandler() {
		return unknownTypeHandler;
	}

	public void register(JdbcType jdbcType, TypeHandler<?> handler) {
		jdbcTypeHandlerMap.put(jdbcType, handler);
	}

	//
	// REGISTER INSTANCE
	//

	// Only handler

	@SuppressWarnings("unchecked")
	public <T> void register(TypeHandler<T> typeHandler) {
		boolean mappedTypeFound = false;
		MappedTypes mappedTypes = typeHandler.getClass().getAnnotation(MappedTypes.class);
		if (mappedTypes != null) {
			for (Class<?> handledType : mappedTypes.value()) {
				register(handledType, typeHandler);
				mappedTypeFound = true;
			}
		}
		// @since 3.1.0 - try to auto-discover the mapped type
		if (!mappedTypeFound && typeHandler instanceof TypeReference) {
			try {
				TypeReference<T> typeReference = (TypeReference<T>) typeHandler;
				register(typeReference.getRawType(), typeHandler);
				mappedTypeFound = true;
			} catch (Throwable t) {
				// maybe users define the TypeReference with a different type and are not
				// assignable, so just ignore it
			}
		}
		if (!mappedTypeFound) {
			register((Class<T>) null, typeHandler);
		}
	}

	// java type + handler

	public <T> void register(Class<T> javaType, TypeHandler<? extends T> typeHandler) {
		register((Type) javaType, typeHandler);
	}

	private <T> void register(Type javaType, TypeHandler<? extends T> typeHandler) {
		// 获得 MappedJdbcTypes 注解
		MappedJdbcTypes mappedJdbcTypes = typeHandler.getClass().getAnnotation(MappedJdbcTypes.class);
		if (mappedJdbcTypes != null) {
			// 遍历 MappedJdbcTypes 注册的 JDBC Type 进行注册
			for (JdbcType handledJdbcType : mappedJdbcTypes.value()) {
				register(javaType, handledJdbcType, typeHandler);
			}
			if (mappedJdbcTypes.includeNullJdbcType()) {
				register(javaType, null, typeHandler);
			}
		} else {
			register(javaType, null, typeHandler);
		}
	}

	public <T> void register(TypeReference<T> javaTypeReference, TypeHandler<? extends T> handler) {
		register(javaTypeReference.getRawType(), handler);
	}

	// java type + jdbc type + handler

	public <T> void register(Class<T> type, JdbcType jdbcType, TypeHandler<? extends T> handler) {
		register((Type) type, jdbcType, handler);
	}

	private void register(Type javaType, JdbcType jdbcType, TypeHandler<?> handler) {
		// <1> 添加 handler 到 TYPE_HANDLER_MAP 中
		if (javaType != null) {
			// 获得 Java Type 对应的 map
			Map<JdbcType, TypeHandler<?>> map = typeHandlerMap.get(javaType);
			if (map == null || map == NULL_TYPE_HANDLER_MAP) { // 如果不存在，则进行创建
				map = new HashMap<>();
				typeHandlerMap.put(javaType, map); // 添加到 handler 中 map 中
			}
			map.put(jdbcType, handler);
		}
		// <2> 添加 handler 到 ALL_TYPE_HANDLERS_MAP 中
		allTypeHandlersMap.put(handler.getClass(), handler);
	}

	//
	// REGISTER CLASS
	//

	// Only handler type

	public void register(Class<?> typeHandlerClass) {
		boolean mappedTypeFound = false;
		// <3> 获得 @MappedTypes 注解
		MappedTypes mappedTypes = typeHandlerClass.getAnnotation(MappedTypes.class);
		if (mappedTypes != null) {
			// 遍历注解的 Java Type 数组，逐个进行注册
			for (Class<?> javaTypeClass : mappedTypes.value()) {
				register(javaTypeClass, typeHandlerClass);
				mappedTypeFound = true;
			}
		}
		 // <4> 未使用 @MappedTypes 注解，则直接注册
		if (!mappedTypeFound) {
			register(getInstance(null, typeHandlerClass)); // 创建 TypeHandler 对象
		}
	}

	// java type + handler type

	public void register(String javaTypeClassName, String typeHandlerClassName) throws ClassNotFoundException {
		register(Resources.classForName(javaTypeClassName), Resources.classForName(typeHandlerClassName));
	}

	public void register(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
		register(javaTypeClass, getInstance(javaTypeClass, typeHandlerClass));  // 创建 TypeHandler 对象
	}

	// java type + jdbc type + handler type

	public void register(Class<?> javaTypeClass, JdbcType jdbcType, Class<?> typeHandlerClass) {
		register(javaTypeClass, jdbcType, getInstance(javaTypeClass, typeHandlerClass));
	}

	// Construct a handler (used also from Builders)

	@SuppressWarnings("unchecked")
	public <T> TypeHandler<T> getInstance(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
		if (javaTypeClass != null) {
			try {
				Constructor<?> c = typeHandlerClass.getConstructor(Class.class);
				return (TypeHandler<T>) c.newInstance(javaTypeClass);
			} catch (NoSuchMethodException ignored) {
				// ignored
			} catch (Exception e) {
				throw new TypeException("Failed invoking constructor for handler " + typeHandlerClass, e);
			}
		}
		try {
			Constructor<?> c = typeHandlerClass.getConstructor();
			return (TypeHandler<T>) c.newInstance();
		} catch (Exception e) {
			throw new TypeException("Unable to find a usable constructor for " + typeHandlerClass, e);
		}
	}

	// scan

	public void register(String packageName) {
		// 扫描指定包下的所有 TypeHandler 类
		ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
		resolverUtil.find(new ResolverUtil.IsA(TypeHandler.class), packageName);
		Set<Class<? extends Class<?>>> handlerSet = resolverUtil.getClasses();
		// 遍历 TypeHandler 数组，发起注册
		for (Class<?> type : handlerSet) {
			// Ignore inner classes and interfaces (including package-info.java) and
			// abstract classes
			if (!type.isAnonymousClass() && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
				register(type);
			}
		}
	}

	// get information

	/**
	 * @since 3.2.2
	 */
	public Collection<TypeHandler<?>> getTypeHandlers() {
		return Collections.unmodifiableCollection(allTypeHandlersMap.values());
	}

}
