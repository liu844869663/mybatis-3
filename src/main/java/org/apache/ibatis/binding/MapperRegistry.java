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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/**
 * Mapper注册中心，主要实现Mapper接口与MapperProxyFactory之间映射的管理
 * 通过MapperProxyFactory创建MapperProxy代理对象
 * 以通过动态代理的方式执行相应的方法
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperRegistry {

	/**
	 * MyBatis Configuration 对象
	 */
	private final Configuration config;
	/**
	 * MapperProxyFactory 的映射
	 *
	 * KEY：Mapper 接口
	 */
	private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<>();

	public MapperRegistry(Configuration config) {
		this.config = config;
	}

	@SuppressWarnings("unchecked")
	public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
		// <1> 获得 MapperProxyFactory 对象
		final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);
		// 不存在，则抛出 BindingException 异常
		if (mapperProxyFactory == null) {
			throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
		}
		try {
			// 创建 Mapper Proxy 对象
			return mapperProxyFactory.newInstance(sqlSession);
		} catch (Exception e) {
			throw new BindingException("Error getting mapper instance. Cause: " + e, e);
		}
	}

	public <T> boolean hasMapper(Class<T> type) {
		return knownMappers.containsKey(type);
	}

	public <T> void addMapper(Class<T> type) {
		// <1> 判断，必须是接口。
		if (type.isInterface()) {
			// <2> 已经添加过，则抛出 BindingException 异常
			if (hasMapper(type)) {
				throw new BindingException("Type " + type + " is already known to the MapperRegistry.");
			}
			boolean loadCompleted = false;
			try {
				// <3> 将Mapper接口对应的代理工厂添加到 knownMappers 中
				knownMappers.put(type, new MapperProxyFactory<>(type));
				// It's important that the type is added before the parser is run
				// otherwise the binding may automatically be attempted by the mapper parser.
				// If the type is already known, it won't try.
				// <4> 解析 Mapper 的注解配置
				MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);
				// 解析 Mapper 接口上面的注解和 Mapper 接口对应的 XML 文件
				parser.parse();
				// <5> 标记加载完成
				loadCompleted = true;
			} finally {
				// <6> 若加载未完成，从 knownMappers 中移除
				if (!loadCompleted) {
					knownMappers.remove(type);
				}
			}
		}
	}

	/**
	 * @since 3.2.2
	 */
	public Collection<Class<?>> getMappers() {
		return Collections.unmodifiableCollection(knownMappers.keySet());
	}

	/**
	 * 用于扫描指定包中的Mapper接口，并与XML文件进行绑定
	 * @since 3.2.2
	 */
	public void addMappers(String packageName, Class<?> superType) {
		// <1> 扫描指定包下的指定类
		ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
		resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
		Set<Class<? extends Class<?>>> mapperSet = resolverUtil.getClasses();
		// <2> 遍历，添加到 knownMappers 中
		for (Class<?> mapperClass : mapperSet) {
			addMapper(mapperClass);
		}
	}

	/**
	 * @since 3.2.2
	 */
	public void addMappers(String packageName) {
		addMappers(packageName, Object.class);
	}

}
