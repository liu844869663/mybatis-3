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
package org.apache.ibatis.plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * @author Clinton Begin
 */
public class Plugin implements InvocationHandler {

	/**
	 * 目标对象
	 */
	private final Object target;
	/**
	 * 拦截器
	 */
	private final Interceptor interceptor;
	/**
	 * 拦截的方法映射
	 *
	 * KEY：类
	 * VALUE：方法集合
	 */
	private final Map<Class<?>, Set<Method>> signatureMap;

	private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
		this.target = target;
		this.interceptor = interceptor;
		this.signatureMap = signatureMap;
	}

	public static Object wrap(Object target, Interceptor interceptor) {
		// <1> 获得拦截器中需要拦截的类的方法集合
		Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
		// <2> 获得目标对象的 Class 对象（父类或者接口）
		Class<?> type = target.getClass();
		// <2> 获得目标对象所有需要被拦截的 Class 对象
		Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
		// <3> 若有接口，则为目标对象的创建一个动态代理对象（JDK 动态代理），代理类为 Plugin 对象
		if (interfaces.length > 0) {
			// 因为 Plugin 实现了 InvocationHandler 接口，所以可以作为 JDK 动态代理的调用处理器
			return Proxy.newProxyInstance(type.getClassLoader(), interfaces, new Plugin(target, interceptor, signatureMap));
		}
		// <4> 如果没有，则返回原始的目标对象
		return target;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		try {
			// 获得目标方法所在的类需要被拦截的方法
			Set<Method> methods = signatureMap.get(method.getDeclaringClass());
			if (methods != null && methods.contains(method)) { // 如果被拦截的方法包含当前方法
				// 使用插件拦截该方法进行处理
				return interceptor.intercept(new Invocation(target, method, args));
			}
			// 如果没有需要被拦截的方法，则调用原方法
			return method.invoke(target, args);
		} catch (Exception e) {
			throw ExceptionUtil.unwrapThrowable(e);
		}
	}

	private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
		// 获取 @Intercepts 注解
		Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
		// issue #251
		if (interceptsAnnotation == null) {
			throw new PluginException( "No @Intercepts annotation was found in interceptor " + interceptor.getClass().getName());
		}
		// 获取 @Intercepts 注解中的 @Signature 注解
		Signature[] sigs = interceptsAnnotation.value();
		Map<Class<?>, Set<Method>> signatureMap = new HashMap<>();
		for (Signature sig : sigs) {
		  // 为 @Signature 注解中定义类名创建一个方法数组
			Set<Method> methods = signatureMap.computeIfAbsent(sig.type(), k -> new HashSet<>());
			try {
			  // 获取 @Signature 注解中定义的方法对象
				Method method = sig.type().getMethod(sig.method(), sig.args());
				methods.add(method);
			} catch (NoSuchMethodException e) {
				throw new PluginException(
						"Could not find method on " + sig.type() + " named " + sig.method() + ". Cause: " + e, e);
			}
		}
		return signatureMap;
	}

	private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
		// 接口的集合
		Set<Class<?>> interfaces = new HashSet<>();
		// 循环递归 type 类，机器父类
		while (type != null) {
			// 遍历接口集合，若在 signatureMap 中，则添加到 interfaces 中
			for (Class<?> c : type.getInterfaces()) {
				if (signatureMap.containsKey(c)) {
					interfaces.add(c);
				}
			}
			// 获得父类
			type = type.getSuperclass();
		}
		// 创建接口的数组
		return interfaces.toArray(new Class<?>[interfaces.size()]);
	}

}
