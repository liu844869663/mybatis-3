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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;

/**
 * 类型与别名的注册表
 * 
 * @author Clinton Begin
 */
public class TypeAliasRegistry {

	/**
	 * 类型与别名的映射。
	 */
	private final Map<String, Class<?>> typeAliases = new HashMap<>();

	/**
	 * 初始化默认的类型与别名
	 *
	 * 另外，在 {@link org.apache.ibatis.session.Configuration} 构造方法中，也有默认的注册
	 */
	public TypeAliasRegistry() {
		registerAlias("string", String.class);

		registerAlias("byte", Byte.class);
		registerAlias("long", Long.class);
		registerAlias("short", Short.class);
		registerAlias("int", Integer.class);
		registerAlias("integer", Integer.class);
		registerAlias("double", Double.class);
		registerAlias("float", Float.class);
		registerAlias("boolean", Boolean.class);

		registerAlias("byte[]", Byte[].class);
		registerAlias("long[]", Long[].class);
		registerAlias("short[]", Short[].class);
		registerAlias("int[]", Integer[].class);
		registerAlias("integer[]", Integer[].class);
		registerAlias("double[]", Double[].class);
		registerAlias("float[]", Float[].class);
		registerAlias("boolean[]", Boolean[].class);

		registerAlias("_byte", byte.class);
		registerAlias("_long", long.class);
		registerAlias("_short", short.class);
		registerAlias("_int", int.class);
		registerAlias("_integer", int.class);
		registerAlias("_double", double.class);
		registerAlias("_float", float.class);
		registerAlias("_boolean", boolean.class);

		registerAlias("_byte[]", byte[].class);
		registerAlias("_long[]", long[].class);
		registerAlias("_short[]", short[].class);
		registerAlias("_int[]", int[].class);
		registerAlias("_integer[]", int[].class);
		registerAlias("_double[]", double[].class);
		registerAlias("_float[]", float[].class);
		registerAlias("_boolean[]", boolean[].class);

		registerAlias("date", Date.class);
		registerAlias("decimal", BigDecimal.class);
		registerAlias("bigdecimal", BigDecimal.class);
		registerAlias("biginteger", BigInteger.class);
		registerAlias("object", Object.class);

		registerAlias("date[]", Date[].class);
		registerAlias("decimal[]", BigDecimal[].class);
		registerAlias("bigdecimal[]", BigDecimal[].class);
		registerAlias("biginteger[]", BigInteger[].class);
		registerAlias("object[]", Object[].class);

		registerAlias("map", Map.class);
		registerAlias("hashmap", HashMap.class);
		registerAlias("list", List.class);
		registerAlias("arraylist", ArrayList.class);
		registerAlias("collection", Collection.class);
		registerAlias("iterator", Iterator.class);

		registerAlias("ResultSet", ResultSet.class);
	}

	@SuppressWarnings("unchecked")
	// throws class cast exception as well if types cannot be assigned
	public <T> Class<T> resolveAlias(String string) {
		// 获得别名对应的类型
		try {
			if (string == null) {
				return null;
			}
			// issue #748
			// <1> 转换成小写
			String key = string.toLowerCase(Locale.ENGLISH);
			Class<T> value;
			// <2.1> 首先，从 TYPE_ALIASES 中获取
			if (typeAliases.containsKey(key)) {
				value = (Class<T>) typeAliases.get(key);
			} else { // <2.2> 其次，直接获得对应类
				value = (Class<T>) Resources.classForName(string);
			}
			return value;
		} catch (ClassNotFoundException e) {
			throw new TypeException("Could not resolve type alias '" + string + "'.  Cause: " + e, e);
		}
	}

	/**
	 * 注册指定包下的别名与类的映射
	 *
	 * @param packageName 指定包
	 */
	public void registerAliases(String packageName) {
		registerAliases(packageName, Object.class);
	}

	/**
	 * 注册指定包下的别名与类的映射。另外，要求类必须是 {@param superType} 类型（包括子类）。
	 *
	 * @param packageName 指定包
	 * @param superType 指定父类
	 */
	public void registerAliases(String packageName, Class<?> superType) {
		// 获得指定包下的类门
		ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
		resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
		Set<Class<? extends Class<?>>> typeSet = resolverUtil.getClasses();
		// 遍历，逐个注册类型与别名的注册表
		for (Class<?> type : typeSet) {
			// Ignore inner classes and interfaces (including package-info.java)
			// Skip also inner classes. See issue #6
			if (!type.isAnonymousClass() // 排除匿名类
					&& !type.isInterface() // 排除接口
					&& !type.isMemberClass()) { // 排除内部类
				registerAlias(type);
			}
		}
	}

	public void registerAlias(Class<?> type) {
		// <1> 默认为，简单类名
		String alias = type.getSimpleName();
		// <2> 如果有注解，使用注册上的名字
		Alias aliasAnnotation = type.getAnnotation(Alias.class);
		if (aliasAnnotation != null) {
			alias = aliasAnnotation.value();
		}
		// <3> 注册类型与别名的注册表
		registerAlias(alias, type);
	}

	public void registerAlias(String alias, Class<?> value) {
		if (alias == null) {
			throw new TypeException("The parameter alias cannot be null");
		}
		// issue #748
		// <1> 转换成小写
		// 将别名转换成**小写**。这样的话，无论我们在 Mapper XML 中，写 `String` 还是 `string` 甚至是 `STRING` ，都是对应的 String 类型
		String key = alias.toLowerCase(Locale.ENGLISH);
		// <2> 冲突，抛出 TypeException 异常
		// 如果已经注册，并且类型不一致，说明有冲突，抛出 TypeException 异常
		if (typeAliases.containsKey(key) && typeAliases.get(key) != null && !typeAliases.get(key).equals(value)) {
			throw new TypeException("The alias '" + alias + "' is already mapped to the value '"
					+ typeAliases.get(key).getName() + "'.");
		}
		typeAliases.put(key, value);
	}

	public void registerAlias(String alias, String value) {
		try {
			registerAlias(alias, Resources.classForName(value));
		} catch (ClassNotFoundException e) {
			throw new TypeException("Error registering type alias " + alias + " for " + value + ". Cause: " + e, e);
		}
	}

	/**
	 * @since 3.2.2
	 */
	public Map<String, Class<?>> getTypeAliases() {
		return Collections.unmodifiableMap(typeAliases);
	}

}
