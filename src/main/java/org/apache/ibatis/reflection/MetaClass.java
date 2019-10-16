/**
 *    Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * @author Clinton Begin
 */
public class MetaClass {

	private final ReflectorFactory reflectorFactory;
	private final Reflector reflector;

	private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
		this.reflectorFactory = reflectorFactory;
		this.reflector = reflectorFactory.findForClass(type);
	}

	public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
		return new MetaClass(type, reflectorFactory);
	}

	public MetaClass metaClassForProperty(String name) {
		// 获得属性的类对象
		Class<?> propType = reflector.getGetterType(name);
		// 生产该属性类对象对应的MetaClass元数据对象
		return MetaClass.forClass(propType, reflectorFactory);
	}

	public String findProperty(String name) {
		// <3> 构建属性
		StringBuilder prop = buildProperty(name, new StringBuilder());
		return prop.length() > 0 ? prop.toString() : null;
	}

	public String findProperty(String name, boolean useCamelCaseMapping) {
		// <1> 下划线转驼峰
		if (useCamelCaseMapping) {
			name = name.replace("_", "");
		}
		// <2> 获得属性
		return findProperty(name);
	}

	public String[] getGetterNames() {
		return reflector.getGetablePropertyNames();
	}

	public String[] getSetterNames() {
		return reflector.getSetablePropertyNames();
	}

	public Class<?> getSetterType(String name) {
		PropertyTokenizer prop = new PropertyTokenizer(name);
		if (prop.hasNext()) {
			MetaClass metaProp = metaClassForProperty(prop.getName());
			return metaProp.getSetterType(prop.getChildren());
		} else {
			return reflector.getSetterType(prop.getName());
		}
	}

	public Class<?> getGetterType(String name) {
		// 创建 PropertyTokenizer 对象，对 name 进行分词
		PropertyTokenizer prop = new PropertyTokenizer(name);
		// 有子表达式
		if (prop.hasNext()) {
			// 创建 MetaClass 对象
			MetaClass metaProp = metaClassForProperty(prop);
			// 递归判断子表达式 children ，获得返回值的类型
			return metaProp.getGetterType(prop.getChildren());
		}
		// issue #506. Resolve the type inside a Collection Object
		// 直接获得返回值的类型
		return getGetterType(prop);
	}

	private MetaClass metaClassForProperty(PropertyTokenizer prop) {
		// 【调用】获得 getter 方法返回的类型
		Class<?> propType = getGetterType(prop);
		// 创建 MetaClass 对象
		return MetaClass.forClass(propType, reflectorFactory);
	}

	private Class<?> getGetterType(PropertyTokenizer prop) {
		// 获得返回类型
		Class<?> type = reflector.getGetterType(prop.getName());
		// 如果获取数组的某个位置的元素，则获取其泛型。例如说：list[0].field ，那么就会解析 list 是什么类型，这样才好通过该类型，继续获得 field
		if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
			// 【调用】获得返回的类型
			Type returnType = getGenericGetterType(prop.getName());
			// 如果是泛型，进行解析真正的类型
			if (returnType instanceof ParameterizedType) {
				Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
				// 为什么这里判断大小为 1 呢，因为 Collection 是 Collection<T> ，至多一个
				if (actualTypeArguments != null && actualTypeArguments.length == 1) {
					returnType = actualTypeArguments[0];
					if (returnType instanceof Class) {
						type = (Class<?>) returnType;
					} else if (returnType instanceof ParameterizedType) {
						type = (Class<?>) ((ParameterizedType) returnType).getRawType();
					}
				}
			}
		}
		return type;
	}

	private Type getGenericGetterType(String propertyName) {
		try {
			// 获得 Invoker 对象
			Invoker invoker = reflector.getGetInvoker(propertyName);
			// 如果 MethodInvoker 对象，则说明是 getter 方法，解析方法返回类型
			if (invoker instanceof MethodInvoker) {
				Field _method = MethodInvoker.class.getDeclaredField("method");
				_method.setAccessible(true);
				Method method = (Method) _method.get(invoker);
				return TypeParameterResolver.resolveReturnType(method, reflector.getType());
			// 如果 GetFieldInvoker 对象，则说明是 field ，直接访问
			} else if (invoker instanceof GetFieldInvoker) {
				Field _field = GetFieldInvoker.class.getDeclaredField("field");
				_field.setAccessible(true);
				Field field = (Field) _field.get(invoker);
				return TypeParameterResolver.resolveFieldType(field, reflector.getType());
			}
		} catch (NoSuchFieldException | IllegalAccessException ignored) {
		}
		return null;
	}

	public boolean hasSetter(String name) {
		// 创建 PropertyTokenizer 对象，对 name 进行分词
		PropertyTokenizer prop = new PropertyTokenizer(name);
		// 有子表达式
		if (prop.hasNext()) {
			// 判断是否有该属性的 getter 方法
			if (reflector.hasSetter(prop.getName())) {
				// <1> 创建 MetaClass 对象
				MetaClass metaProp = metaClassForProperty(prop.getName());
				// 递归判断子表达式 children ，是否有 getter 方法
				return metaProp.hasSetter(prop.getChildren());
			} else {
				return false;
			}
		} else { // 无子表达式
			// 判断是否有该属性的 getter 方法
			return reflector.hasSetter(prop.getName());
		}
	}

	public boolean hasGetter(String name) {
		PropertyTokenizer prop = new PropertyTokenizer(name);
		if (prop.hasNext()) {
			if (reflector.hasGetter(prop.getName())) {
				MetaClass metaProp = metaClassForProperty(prop);
				return metaProp.hasGetter(prop.getChildren());
			} else {
				return false;
			}
		} else {
			return reflector.hasGetter(prop.getName());
		}
	}

	public Invoker getGetInvoker(String name) {
		return reflector.getGetInvoker(name);
	}

	public Invoker getSetInvoker(String name) {
		return reflector.getSetInvoker(name);
	}

	private StringBuilder buildProperty(String name, StringBuilder builder) {
		// 创建 PropertyTokenizer 对象，对 name 进行分词
		PropertyTokenizer prop = new PropertyTokenizer(name);
		// 有子表达式
		if (prop.hasNext()) {
			// <4> 获得属性名，并添加到 builder 中
			String propertyName = reflector.findPropertyName(prop.getName());
			if (propertyName != null) {
				// 拼接属性到 builder 中
				builder.append(propertyName);
				builder.append(".");
				// 创建 MetaClass 对象
				MetaClass metaProp = metaClassForProperty(propertyName);
				// 递归解析子表达式 children ，并将结果添加到 builder 中
				metaProp.buildProperty(prop.getChildren(), builder);
			}
		} else { // 无子表达式
			// <4> 获得属性名，并添加到 builder 中
			String propertyName = reflector.findPropertyName(name);
			if (propertyName != null) {
				builder.append(propertyName);
			}
		}
		return builder;
	}

	public boolean hasDefaultConstructor() {
		return reflector.hasDefaultConstructor();
	}

}
