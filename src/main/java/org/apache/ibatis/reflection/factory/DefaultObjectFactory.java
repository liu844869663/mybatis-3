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
package org.apache.ibatis.reflection.factory;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.ibatis.reflection.ReflectionException;
import org.apache.ibatis.reflection.Reflector;

/**
 * @author Clinton Begin
 */
public class DefaultObjectFactory implements ObjectFactory, Serializable {

	private static final long serialVersionUID = -8855120656740914948L;

	@Override
	public <T> T create(Class<T> type) {
		return create(type, null, null);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
		// 获取需要创建的类
		Class<?> classToCreate = resolveInterface(type);
		// 创建指定类的对象
		return (T) instantiateClass(classToCreate, constructorArgTypes, constructorArgs);
	}

	private <T> T instantiateClass(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
		try {
			Constructor<T> constructor;
			if (constructorArgTypes == null || constructorArgs == null) {
				// 使用默认的构造器
				constructor = type.getDeclaredConstructor();
				try {
					// 返回实例
					return constructor.newInstance();
				} catch (IllegalAccessException e) {
					if (Reflector.canControlMemberAccessible()) {
						constructor.setAccessible(true);
						return constructor.newInstance();
					} else {
						throw e;
					}
				}
			}
			// 通过参数类型列表获取构造器
			constructor = type.getDeclaredConstructor(constructorArgTypes.toArray(new Class[constructorArgTypes.size()]));
			try {
				// 返回实例
				return constructor.newInstance(constructorArgs.toArray(new Object[constructorArgs.size()]));
			} catch (IllegalAccessException e) {
				if (Reflector.canControlMemberAccessible()) {
					constructor.setAccessible(true);
					return constructor.newInstance(constructorArgs.toArray(new Object[constructorArgs.size()]));
				} else {
					throw e;
				}
			}
		} catch (Exception e) {
			String argTypes = Optional.ofNullable(constructorArgTypes) // 把指定的值封装为Optional对象，如果指定的值为null，则创建一个空的Optional对象
									.orElseGet(Collections::emptyList).stream() // 如果创建的Optional中有值存在，则返回此值，否则返回一个由Supplier接口生成的值(空集合)
									.map(Class::getSimpleName) // 如果创建的Optional中的值存在，对该值执行提供的Function函数调用，获取每个参数类型的名称
									.collect(Collectors.joining(",")); // 对该集合进行操作并返回字符串
			String argValues = Optional.ofNullable(constructorArgs)
									.orElseGet(Collections::emptyList).stream()
									.map(String::valueOf)
									.collect(Collectors.joining(","));
			throw new ReflectionException("Error instantiating " + type
					+ " with invalid types (" + argTypes + ")"
					+ " or values (" + argValues + ")."
					+ " Cause: " + e, e);
		}
	}

	protected Class<?> resolveInterface(Class<?> type) {
		Class<?> classToCreate;
		if (type == List.class || type == Collection.class || type == Iterable.class) {
			classToCreate = ArrayList.class;
		} else if (type == Map.class) {
			classToCreate = HashMap.class;
		} else if (type == SortedSet.class) { // issue #510 Collections Support
			classToCreate = TreeSet.class;
		} else if (type == Set.class) {
			classToCreate = HashSet.class;
		} else {
			classToCreate = type;
		}
		return classToCreate;
	}

	@Override
	public <T> boolean isCollection(Class<T> type) {
		return Collection.class.isAssignableFrom(type);
	}

}
