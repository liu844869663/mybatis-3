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
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;

/**
 * @author Iwao AVE!
 */
public class TypeParameterResolver {

	/**
	 * 解析属性类型
	 * @return The field type as {@link Type}. If it has type parameters in the
	 *         declaration,<br>
	 *         they will be resolved to the actual runtime {@link Type}s.
	 */
	public static Type resolveFieldType(Field field, Type srcType) {
		// 属性类型
		Type fieldType = field.getGenericType();
		// 定义的类
		Class<?> declaringClass = field.getDeclaringClass();
		// 解析类型
		return resolveType(fieldType, srcType, declaringClass);
	}

	/**
	 * 解析方法返回类型
	 * @return The return type of the method as {@link Type}. If it has type
	 *         parameters in the declaration,<br>
	 *         they will be resolved to the actual runtime {@link Type}s.
	 */
	public static Type resolveReturnType(Method method, Type srcType) {
		// 方法返回类型
		Type returnType = method.getGenericReturnType();
		// 方法所在类
		Class<?> declaringClass = method.getDeclaringClass();
		// 解析类型
		return resolveType(returnType, srcType, declaringClass);
	}

	/**
	 * 解析方法参数的类型数组
	 * @return The parameter types of the method as an array of {@link Type}s. If
	 *         they have type parameters in the declaration,<br>
	 *         they will be resolved to the actual runtime {@link Type}s.
	 */
	public static Type[] resolveParamTypes(Method method, Type srcType) {
		// 方法参数类型列表
		Type[] paramTypes = method.getGenericParameterTypes();
		// 方法所在类
		Class<?> declaringClass = method.getDeclaringClass();
		Type[] result = new Type[paramTypes.length];
		for (int i = 0; i < paramTypes.length; i++) {
			result[i] = resolveType(paramTypes[i], srcType, declaringClass);
		}
		return result;
	}

	/**
	 * 解析类型
	 *
	 * @param type 类型
	 * @param srcType 来源类型
	 * @param declaringClass 定义的类
	 * @return 解析后的类型
	 */
	private static Type resolveType(Type type, Type srcType, Class<?> declaringClass) {
		if (type instanceof TypeVariable) { // 泛型变量
			return resolveTypeVar((TypeVariable<?>) type, srcType, declaringClass);
		} else if (type instanceof ParameterizedType) { // 参数类型
			return resolveParameterizedType((ParameterizedType) type, srcType, declaringClass);
		} else if (type instanceof GenericArrayType) { //泛型数组
			return resolveGenericArrayType((GenericArrayType) type, srcType, declaringClass);
		} else {
			return type;
		}
	}

	private static Type resolveGenericArrayType(GenericArrayType genericArrayType, Type srcType,
			Class<?> declaringClass) {
		// 【1】解析 componentType
		Type componentType = genericArrayType.getGenericComponentType();
		Type resolvedComponentType = null;
		if (componentType instanceof TypeVariable) {
			resolvedComponentType = resolveTypeVar((TypeVariable<?>) componentType, srcType, declaringClass);
		} else if (componentType instanceof GenericArrayType) {
			resolvedComponentType = resolveGenericArrayType((GenericArrayType) componentType, srcType, declaringClass);
		} else if (componentType instanceof ParameterizedType) {
			resolvedComponentType = resolveParameterizedType((ParameterizedType) componentType, srcType,
					declaringClass);
		}
		// 【2】创建 GenericArrayTypeImpl 对象
		if (resolvedComponentType instanceof Class) {
			return Array.newInstance((Class<?>) resolvedComponentType, 0).getClass();
		} else {
			return new GenericArrayTypeImpl(resolvedComponentType);
		}
	}

	/**
	 * 解析 ParameterizedType 类型
	 *
	 * @param parameterizedType ParameterizedType 类型
	 * @param srcType 来源类型
	 * @param declaringClass 定义的类
	 * @return 解析后的类型
	 */
	private static ParameterizedType resolveParameterizedType(ParameterizedType parameterizedType, Type srcType,
			Class<?> declaringClass) {
		Class<?> rawType = (Class<?>) parameterizedType.getRawType();
		// 【1】解析 <> 中实际类型
		Type[] typeArgs = parameterizedType.getActualTypeArguments();
		Type[] args = new Type[typeArgs.length];
		for (int i = 0; i < typeArgs.length; i++) {
			if (typeArgs[i] instanceof TypeVariable) {
				args[i] = resolveTypeVar((TypeVariable<?>) typeArgs[i], srcType, declaringClass);
			} else if (typeArgs[i] instanceof ParameterizedType) {
				args[i] = resolveParameterizedType((ParameterizedType) typeArgs[i], srcType, declaringClass);
			} else if (typeArgs[i] instanceof WildcardType) { // 通配符类型
				args[i] = resolveWildcardType((WildcardType) typeArgs[i], srcType, declaringClass);
			} else {
				args[i] = typeArgs[i];
			}
		}
    // 【2】创建 ParameterizedTypeImpl 对象
		return new ParameterizedTypeImpl(rawType, null, args);
	}

	private static Type resolveWildcardType(WildcardType wildcardType, Type srcType, Class<?> declaringClass) {
		// <1.1> 解析泛型表达式下界（下限 super）
		Type[] lowerBounds = resolveWildcardTypeBounds(wildcardType.getLowerBounds(), srcType, declaringClass);
		// <1.2> 解析泛型表达式上界（上限 extends）
		Type[] upperBounds = resolveWildcardTypeBounds(wildcardType.getUpperBounds(), srcType, declaringClass);
		// <2> 创建 WildcardTypeImpl 对象
		return new WildcardTypeImpl(lowerBounds, upperBounds);
	}

	private static Type[] resolveWildcardTypeBounds(Type[] bounds, Type srcType, Class<?> declaringClass) {
		Type[] result = new Type[bounds.length];
		for (int i = 0; i < bounds.length; i++) {
			if (bounds[i] instanceof TypeVariable) {
				result[i] = resolveTypeVar((TypeVariable<?>) bounds[i], srcType, declaringClass);
			} else if (bounds[i] instanceof ParameterizedType) {
				result[i] = resolveParameterizedType((ParameterizedType) bounds[i], srcType, declaringClass);
			} else if (bounds[i] instanceof WildcardType) {
				result[i] = resolveWildcardType((WildcardType) bounds[i], srcType, declaringClass);
			} else {
				result[i] = bounds[i];
			}
		}
		return result;
	}

	private static Type resolveTypeVar(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass) {
		// 例子
		// public class ClassA<K,V> { protected Map<K,V> map}
		// public class ClassSubA<T> extends ClassA<T,T>
		Type result;
		Class<?> clazz;
		if (srcType instanceof Class) {
			clazz = (Class<?>) srcType;
		} else if (srcType instanceof ParameterizedType) {
			// 本例中的SubA<Long>是ParameterizedType类型，clazz为SubA对应的class对象
			ParameterizedType parameterizedType = (ParameterizedType) srcType;
			clazz = (Class<?>) parameterizedType.getRawType();
		} else {
			throw new IllegalArgumentException(
					"The 2nd arg must be Class or ParameterizedType, but was: " + srcType.getClass());
		}
		// 因为SubA继承了A且map字段定义在A中，故这里的srcType与declaringClass并不相等
		// 如果map字段定义在SubA中，则可以直接结束对K的解析
		if (clazz == declaringClass) {
			Type[] bounds = typeVar.getBounds(); // 获取上界
			if (bounds.length > 0) {
				return bounds[0];
			}
			return Object.class;
		}

		// 获取声明的父类类型，即A<T,T>对应的ParameterizedType对象
		Type superclass = clazz.getGenericSuperclass();
		// 通过扫描父类进行后续解析，这是递归的入口
		result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superclass);
		if (result != null) {
			return result;
		}

		Type[] superInterfaces = clazz.getGenericInterfaces();
		for (Type superInterface : superInterfaces) {
			result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superInterface);
			if (result != null) {
				return result;
			}
		}
		return Object.class;
	}

	private static Type scanSuperTypes(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass, Class<?> clazz,
			Type superclass) {
		// superclass是A<T,T>对应的ParameterizedType对象，条件成立
		if (superclass instanceof ParameterizedType) {
			ParameterizedType parentAsType = (ParameterizedType) superclass;
			// 原始类型是A
			Class<?> parentAsClass = (Class<?>) parentAsType.getRawType();
			TypeVariable<?>[] parentTypeVars = parentAsClass.getTypeParameters();
			if (srcType instanceof ParameterizedType) {
				parentAsType = translateParentTypeVars((ParameterizedType) srcType, clazz, parentAsType);
			}
			if (declaringClass == parentAsClass) { //map定义在A中，条件成立
				for (int i = 0; i < parentTypeVars.length; i++) {
					if (typeVar == parentTypeVars[i]) {
						return parentAsType.getActualTypeArguments()[i];
					}
				}
			}
			if (declaringClass.isAssignableFrom(parentAsClass)) {
				return resolveTypeVar(typeVar, parentAsType, declaringClass);
			}
		} else if (superclass instanceof Class && declaringClass.isAssignableFrom((Class<?>) superclass)) {
			return resolveTypeVar(typeVar, superclass, declaringClass);
		}
		return null;
	}

	private static ParameterizedType translateParentTypeVars(ParameterizedType srcType, Class<?> srcClass,
			ParameterizedType parentType) {
		Type[] parentTypeArgs = parentType.getActualTypeArguments();
		Type[] srcTypeArgs = srcType.getActualTypeArguments();
		TypeVariable<?>[] srcTypeVars = srcClass.getTypeParameters();
		Type[] newParentArgs = new Type[parentTypeArgs.length];
		boolean noChange = true;
		for (int i = 0; i < parentTypeArgs.length; i++) {
			if (parentTypeArgs[i] instanceof TypeVariable) {
				for (int j = 0; j < srcTypeVars.length; j++) {
					if (srcTypeVars[j] == parentTypeArgs[i]) {
						noChange = false;
						newParentArgs[i] = srcTypeArgs[j];
					}
				}
			} else {
				newParentArgs[i] = parentTypeArgs[i];
			}
		}
		return noChange ? parentType
				: new ParameterizedTypeImpl((Class<?>) parentType.getRawType(), null, newParentArgs);
	}

	private TypeParameterResolver() {
		super();
	}

	/**
	 * ParameterizedType 实现类
	 *
	 * 参数化类型，即泛型。例如：List<T>、Map<K, V>等带有参数化的配置
	 */
	static class ParameterizedTypeImpl implements ParameterizedType {
		// 以 List<T> 举例子

	    /**
	     * <> 前面实际类型
	     *
	     * 例如：List
	     */
		private Class<?> rawType;
		/**
	     * 如果这个类型是某个属性所有，则获取这个所有者类型；否则，返回 null
	     */
		private Type ownerType;
		/**
	     * <> 中实际类型
	     *
	     * 例如：T
	     */
		private Type[] actualTypeArguments;

		public ParameterizedTypeImpl(Class<?> rawType, Type ownerType, Type[] actualTypeArguments) {
			super();
			this.rawType = rawType;
			this.ownerType = ownerType;
			this.actualTypeArguments = actualTypeArguments;
		}

		@Override
		public Type[] getActualTypeArguments() {
			return actualTypeArguments;
		}

		@Override
		public Type getOwnerType() {
			return ownerType;
		}

		@Override
		public Type getRawType() {
			return rawType;
		}

		@Override
		public String toString() {
			return "ParameterizedTypeImpl [rawType=" + rawType + ", ownerType=" + ownerType + ", actualTypeArguments="
					+ Arrays.toString(actualTypeArguments) + "]";
		}
	}

	/**
	 * WildcardType 实现类
	 *
	 * 泛型表达式（或者通配符表达式），即 ? extend Number、? super Integer 这样的表达式。
	 * WildcardType 虽然是 Type 的子接口，但却不是 Java 类型中的一种。
	 */
	static class WildcardTypeImpl implements WildcardType {
		/**
	     * 泛型表达式下界（下限 super）
	     */
		private Type[] lowerBounds;
		 /**
	     * 泛型表达式上界（上界 extends）
	     */
		private Type[] upperBounds;

		WildcardTypeImpl(Type[] lowerBounds, Type[] upperBounds) {
			super();
			this.lowerBounds = lowerBounds;
			this.upperBounds = upperBounds;
		}

		@Override
		public Type[] getLowerBounds() {
			return lowerBounds;
		}

		@Override
		public Type[] getUpperBounds() {
			return upperBounds;
		}
	}

	/**
	 * GenericArrayType 实现类
	 *
	 * 泛型数组类型，用来描述 ParameterizedType、TypeVariable 类型的数组；即 List<T>[]、T[] 等；
	 */
	static class GenericArrayTypeImpl implements GenericArrayType {
		/**
	     * 数组元素类型
	     */
		private Type genericComponentType;

		GenericArrayTypeImpl(Type genericComponentType) {
			super();
			this.genericComponentType = genericComponentType;
		}

		@Override
		public Type getGenericComponentType() {
			return genericComponentType;
		}
	}
}
