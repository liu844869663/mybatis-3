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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker;
import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

	/**
	 * 对应的类
	 */
	private final Class<?> type;
	/**
	 * 可读属性集合
	 */
	private final String[] readablePropertyNames;
	/**
	 * 可写属性集合
	 */
	private final String[] writablePropertyNames;
	/**
	 * 属性对应的 setter 方法的映射。
	 *
	 * key 为属性名称 
	 * value 为 Invoker 对象
	 */
	private final Map<String, Invoker> setMethods = new HashMap<>();
	/**
	 * 属性对应的 getter 方法的映射。
	 *
	 * key 为属性名称 value 为 Invoker 对象
	 */
	private final Map<String, Invoker> getMethods = new HashMap<>();
	/**
	 * 属性对应的 setting 方法的方法参数类型的映射。{@link #setMethods}
	 *
	 * key 为属性名称 
	 * value 为方法参数类型
	 */
	private final Map<String, Class<?>> setTypes = new HashMap<>();
	/**
	 * 属性对应的 getting 方法的返回值类型的映射。{@link #getMethods}
	 *
	 * key 为属性名称 
	 * value 为返回值的类型
	 */
	private final Map<String, Class<?>> getTypes = new HashMap<>();
	/**
	 * 默认构造方法
	 */
	private Constructor<?> defaultConstructor;

	/**
	 * 不区分大小写的属性集合
	 */
	private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

	public Reflector(Class<?> clazz) {
		// 设置对应的类
		type = clazz;
		// <1> 初始化 defaultConstructor
		addDefaultConstructor(clazz);
		// <2> // 初始化 getMethods 和 getTypes ，通过遍历 getting 方法
		addGetMethods(clazz);
		// <3> // 初始化 setMethods 和 setTypes ，通过遍历 setting 方法
		addSetMethods(clazz);
		// <4> // 初始化 getMethods + getTypes 和 setMethods + setTypes ，通过遍历 fields 属性
		addFields(clazz);
		// <5> 初始化 readablePropertyNames、writeablePropertyNames、caseInsensitivePropertyMap 属性
		readablePropertyNames = getMethods.keySet().toArray(new String[0]);
		writablePropertyNames = setMethods.keySet().toArray(new String[0]);
		for (String propName : readablePropertyNames) {
			caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
		}
		for (String propName : writablePropertyNames) {
			caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
		}
	}

	private void addDefaultConstructor(Class<?> clazz) {
		// 获得所有构造方法
		Constructor<?>[] constructors = clazz.getDeclaredConstructors();
		// 遍历所有构造方法，查找无参的构造方法
		Arrays.stream(constructors)
			.filter(constructor -> constructor.getParameterTypes().length == 0) // 进行过滤，无参
			.findAny() // 将过滤后的任意一个返回
			.ifPresent(constructor -> this.defaultConstructor = constructor); // 对过滤出来的数据进行操作
	}

	private void addGetMethods(Class<?> clazz) {
		// <1> 属性与其 getter 方法的映射
		Map<String, List<Method>> conflictingGetters = new HashMap<>();
		// <2> 获取所有的方法
		Method[] methods = getClassMethods(clazz);
		// <3> 遍历所有方法
		Arrays.stream(methods)
			// <3.1> 进行过滤，无参并且是getter方法
			.filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
			// <3.2> 对过滤出来的结果进行遍历并添加至 conflictingGetters 中
			.forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));
		// <4> 解决 getter 冲突方法
		resolveGetterConflicts(conflictingGetters);
	}

	private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
		// 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 getter 方法
		for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
			Method winner = null; // 最匹配的方法
			String propName = entry.getKey();
			boolean isAmbiguous = false;
			for (Method candidate : entry.getValue()) {
				// winner 为空，说明 candidate 为最匹配的方法
				if (winner == null) {
					winner = candidate;
					continue;
				}
				// <1> 基于返回类型比较
				Class<?> winnerType = winner.getReturnType();
				Class<?> candidateType = candidate.getReturnType();
				// 类型相同
				if (candidateType.equals(winnerType)) {
					// 返回值也相同，应该在 getClassMethods 方法中，已经合并，产生异常
					if (!boolean.class.equals(candidateType)) {
						isAmbiguous = true;
						break;
					// 选择 boolean 类型的 is 方法
					} else if (candidate.getName().startsWith("is")) {
						winner = candidate;
					}
					// 不符合选择子类
				} else if (candidateType.isAssignableFrom(winnerType)) {
					// OK getter type is descendant
				// 如果winnerType为candidateType的父类则选择candidateType子类
				} else if (winnerType.isAssignableFrom(candidateType)) {
					// 因为子类可以修改放大返回值。例如，父类的一个方法的返回值为 List ，子类对该方法的返回值可以覆写为 ArrayList
					winner = candidate;
				} else { // 同一个唯一签名对应多个方法，而这些方法的返回类型不同则产生异常
					isAmbiguous = true;
					break;
				}
			}
			// <2> 添加到 getMethods 和 getTypes 中
			addGetMethod(propName, winner, isAmbiguous);
		}
	}

	private void addGetMethod(String name, Method method, boolean isAmbiguous) {
		MethodInvoker invoker = isAmbiguous ? new AmbiguousMethodInvoker(method, MessageFormat.format(
				"Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
				name, method.getDeclaringClass().getName())) : new MethodInvoker(method);
		getMethods.put(name, invoker);
		Type returnType = TypeParameterResolver.resolveReturnType(method, type);
		getTypes.put(name, typeToClass(returnType));
	}

	private void addSetMethods(Class<?> clazz) {
		// 属性与其 setter 方法的映射
		Map<String, List<Method>> conflictingSetters = new HashMap<>();
		// 获得所有方法
		Method[] methods = getClassMethods(clazz);
		Arrays.stream(methods)
			.filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName())) // 进行过滤，只有一个参数且为setter方法
			.forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
		resolveSetterConflicts(conflictingSetters);
	}

	private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
		if (isValidPropertyName(name)) {
			List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
			list.add(method);
		}
	}

	private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
		for (String propName : conflictingSetters.keySet()) {
			List<Method> setters = conflictingSetters.get(propName);
			Class<?> getterType = getTypes.get(propName);
			boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
			boolean isSetterAmbiguous = false;
			Method match = null;
			// <1> 遍历属性对应的 setting 方法
			for (Method setter : setters) {
				if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
					// should be the best match
					match = setter;
					break;
				}
				if (!isSetterAmbiguous) { // 如果在多个方法的选择时不模棱两可
					// 选择一个更加匹配的
					match = pickBetterSetter(match, setter, propName);
					isSetterAmbiguous = match == null;
				}
			}
			if (match != null) {
				addSetMethod(propName, match);
			}
		}
	}

	private Method pickBetterSetter(Method setter1, Method setter2, String property) {
		// 同一个字段对应setter1和setter2方法，需要在其中挑选一个最优的
		if (setter1 == null) {
			return setter2;
		}
		Class<?> paramType1 = setter1.getParameterTypes()[0];
		Class<?> paramType2 = setter2.getParameterTypes()[0];
		if (paramType1.isAssignableFrom(paramType2)) { // paramType1为paramType2的父类
			return setter2;
		} else if (paramType2.isAssignableFrom(paramType1)) { // paramType1为paramType2的子类
			return setter1;
		}
		// 无法选择哪个setter方法则取第一个 setter1
		MethodInvoker invoker = new AmbiguousMethodInvoker(setter1, MessageFormat.format(
				"Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.",
				property, setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
		setMethods.put(property, invoker);
		Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
		setTypes.put(property, typeToClass(paramTypes[0]));
		return null;
	}

	private void addSetMethod(String name, Method method) {
		MethodInvoker invoker = new MethodInvoker(method);
		setMethods.put(name, invoker);
		Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
		setTypes.put(name, typeToClass(paramTypes[0]));
	}

	private Class<?> typeToClass(Type src) {
		Class<?> result = null;
		// 普通类型，直接使用类
		if (src instanceof Class) {
			result = (Class<?>) src;
		// 泛型类型，使用泛型
		} else if (src instanceof ParameterizedType) {
			result = (Class<?>) ((ParameterizedType) src).getRawType();
		// 泛型数组，获得具体类
		} else if (src instanceof GenericArrayType) {
			Type componentType = ((GenericArrayType) src).getGenericComponentType();
			if (componentType instanceof Class) { // 普通类型
				result = Array.newInstance((Class<?>) componentType, 0).getClass();
			} else {
				Class<?> componentClass = typeToClass(componentType); // 递归该方法，返回类
				result = Array.newInstance(componentClass, 0).getClass();
			}
		}
		if (result == null) {
			result = Object.class;
		}
		return result;
	}

	private void addFields(Class<?> clazz) {
		// 获得所有 field
		Field[] fields = clazz.getDeclaredFields();
		// 有些字段没有对应的setter或者getter方法，需要将其添加到对应的变量中并进行相应的封装
		for (Field field : fields) {
			// <1> 添加到 setMethods 和 setTypes 中
			if (!setMethods.containsKey(field.getName())) {
				// issue #379 - removed the check for final because JDK 1.5 allows
				// modification of final fields through reflection (JSR-133). (JGB)
				// pr #16 - final static can only be set by the classloader
				int modifiers = field.getModifiers();
				if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
					addSetField(field);
				}
			}
			// 添加到 getMethods 和 getTypes 中
			if (!getMethods.containsKey(field.getName())) {
				addGetField(field);
			}
		}
		// 递归，处理父类
		if (clazz.getSuperclass() != null) {
			addFields(clazz.getSuperclass());
		}
	}

	private void addSetField(Field field) {
		// 判断是合理的属性
		if (isValidPropertyName(field.getName())) {
			// 添加到 setMethods 中
			setMethods.put(field.getName(), new SetFieldInvoker(field));
			// 添加到 setTypes 中
			Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
			setTypes.put(field.getName(), typeToClass(fieldType));
		}
	}

	private void addGetField(Field field) {
		// 判断是合理的属性
		if (isValidPropertyName(field.getName())) {
			// 添加到 getMethods 中
			getMethods.put(field.getName(), new GetFieldInvoker(field));
			// 添加到 getMethods 中
			Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
			getTypes.put(field.getName(), typeToClass(fieldType));
		}
	}

	private boolean isValidPropertyName(String name) {
		return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
	}

	/**
	 * This method returns an array containing all methods declared in this class
	 * and any superclass. We use this method, instead of the simpler
	 * <code>Class.getMethods()</code>, because we want to look for private methods
	 * as well.
	 *
	 * @param clazz The class
	 * @return An array containing all methods in this class
	 */
	private Method[] getClassMethods(Class<?> clazz) {
		// 每个方法签名与该方法的映射
		Map<String, Method> uniqueMethods = new HashMap<>();
		// 循环类，类的父类，类的父类的父类，直到父类为 Object
		Class<?> currentClass = clazz;
		while (currentClass != null && currentClass != Object.class) {
			// <1> 记录当前类定义的方法
			addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

			// we also need to look for interface methods -
			// because the class may be abstract
			// <2> 记录接口中定义的方法
			Class<?>[] interfaces = currentClass.getInterfaces();
			for (Class<?> anInterface : interfaces) {
				addUniqueMethods(uniqueMethods, anInterface.getMethods());
			}
			// 获得父类
			currentClass = currentClass.getSuperclass();
		}

		Collection<Method> methods = uniqueMethods.values();

		return methods.toArray(new Method[0]);
	}

	private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
		for (Method currentMethod : methods) {
			// 判断是否为桥接方法，在jdk1.5后引入泛型，当一个类实现了某个泛型接口<T>时，如果指定了入参类型(String param)，
            // 编译器编译时会生成一个方法类型为(Object param)的方法，内部调用(String param)方法，该方法则为编译器生成的桥接方法
            // 例如public String getCar(T o)编译后会新生成public volatile String getCar(Object o);
			if (!currentMethod.isBridge()) { // 忽略桥接方法
				// 获取方法的唯一签名
				String signature = getSignature(currentMethod);
				// check to see if the method is already known
				// if it is known, then an extended class must have
				// overridden a method
				if (!uniqueMethods.containsKey(signature)) {
					// 将方法添加至 uniqueMethods 中
					uniqueMethods.put(signature, currentMethod);
				}
			}
		}
	}

	private String getSignature(Method method) {
		StringBuilder sb = new StringBuilder();
		// 返回类型
		Class<?> returnType = method.getReturnType();
		if (returnType != null) {
			sb.append(returnType.getName()).append('#');
		}
		// 方法名
		sb.append(method.getName());
		// 方法参数
		Class<?>[] parameters = method.getParameterTypes();
		for (int i = 0; i < parameters.length; i++) {
			sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
		}
		// 例如：java.lang.String#getSignature:java.lang.reflect.Method
		return sb.toString();
	}

	/**
	 * Checks whether can control member accessible.
	 *
	 * @return If can control member accessible, it return {@literal true}
	 * @since 3.5.0
	 */
	public static boolean canControlMemberAccessible() {
		try {
			SecurityManager securityManager = System.getSecurityManager();
			if (null != securityManager) {
				securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
			}
		} catch (SecurityException e) {
			return false;
		}
		return true;
	}

	/**
	 * Gets the name of the class the instance provides information for.
	 *
	 * @return The class name
	 */
	public Class<?> getType() {
		return type;
	}

	public Constructor<?> getDefaultConstructor() {
		if (defaultConstructor != null) {
			return defaultConstructor;
		} else {
			throw new ReflectionException("There is no default constructor for " + type);
		}
	}

	public boolean hasDefaultConstructor() {
		return defaultConstructor != null;
	}

	public Invoker getSetInvoker(String propertyName) {
		Invoker method = setMethods.get(propertyName);
		if (method == null) {
			throw new ReflectionException(
					"There is no setter for property named '" + propertyName + "' in '" + type + "'");
		}
		return method;
	}

	public Invoker getGetInvoker(String propertyName) {
		Invoker method = getMethods.get(propertyName);
		if (method == null) {
			throw new ReflectionException(
					"There is no getter for property named '" + propertyName + "' in '" + type + "'");
		}
		return method;
	}

	/**
	 * Gets the type for a property setter.
	 *
	 * @param propertyName - the name of the property
	 * @return The Class of the property setter
	 */
	public Class<?> getSetterType(String propertyName) {
		Class<?> clazz = setTypes.get(propertyName);
		if (clazz == null) {
			throw new ReflectionException(
					"There is no setter for property named '" + propertyName + "' in '" + type + "'");
		}
		return clazz;
	}

	/**
	 * Gets the type for a property getter.
	 *
	 * @param propertyName - the name of the property
	 * @return The Class of the property getter
	 */
	public Class<?> getGetterType(String propertyName) {
		Class<?> clazz = getTypes.get(propertyName);
		if (clazz == null) {
			throw new ReflectionException(
					"There is no getter for property named '" + propertyName + "' in '" + type + "'");
		}
		return clazz;
	}

	/**
	 * Gets an array of the readable properties for an object.
	 *
	 * @return The array
	 */
	public String[] getGetablePropertyNames() {
		return readablePropertyNames;
	}

	/**
	 * Gets an array of the writable properties for an object.
	 *
	 * @return The array
	 */
	public String[] getSetablePropertyNames() {
		return writablePropertyNames;
	}

	/**
	 * Check to see if a class has a writable property by name.
	 *
	 * @param propertyName - the name of the property to check
	 * @return True if the object has a writable property by the name
	 */
	public boolean hasSetter(String propertyName) {
		return setMethods.keySet().contains(propertyName);
	}

	/**
	 * Check to see if a class has a readable property by name.
	 *
	 * @param propertyName - the name of the property to check
	 * @return True if the object has a readable property by the name
	 */
	public boolean hasGetter(String propertyName) {
		return getMethods.keySet().contains(propertyName);
	}

	public String findPropertyName(String name) {
		return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
	}
}
