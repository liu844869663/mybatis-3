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
package org.apache.ibatis.executor.loader.javassist;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.Proxy;
import javassist.util.proxy.ProxyFactory;

import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.AbstractEnhancedDeserializationProxy;
import org.apache.ibatis.executor.loader.AbstractSerialStateHolder;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.loader.WriteReplaceInterface;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyCopier;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.apache.ibatis.session.Configuration;

/**
 * @author Eduardo Macarron
 */
public class JavassistProxyFactory implements org.apache.ibatis.executor.loader.ProxyFactory {

	private static final String FINALIZE_METHOD = "finalize";
	private static final String WRITE_REPLACE_METHOD = "writeReplace";

	public JavassistProxyFactory() {
		try {
			// 加载 javassist.util.proxy.ProxyFactory 类
			Resources.classForName("javassist.util.proxy.ProxyFactory");
		} catch (Throwable e) {
			throw new IllegalStateException(
					"Cannot enable lazy loading because Javassist is not available. Add Javassist to your classpath.",
					e);
		}
	}

	@Override
	public Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration,
			ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
		return EnhancedResultObjectProxyImpl.createProxy(target, lazyLoader, configuration, objectFactory,
				constructorArgTypes, constructorArgs);
	}

	// 方法，创建支持反序列化的代理对象
	public Object createDeserializationProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties,
			ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
		return EnhancedDeserializationProxyImpl.createProxy(target, unloadedProperties, objectFactory,
				constructorArgTypes, constructorArgs);
	}

	static Object crateProxy(Class<?> type, MethodHandler callback, List<Class<?>> constructorArgTypes,
			List<Object> constructorArgs) {

		// 创建 javassist ProxyFactory 对象
		ProxyFactory enhancer = new ProxyFactory();
		// 设置父类
		enhancer.setSuperclass(type);

		// 根据情况，设置接口为 WriteReplaceInterface 。和序列化相关，可以无视
		try {
			type.getDeclaredMethod(WRITE_REPLACE_METHOD); // 如果已经存在 writeReplace 方法，则不用设置接口为 WriteReplaceInterface
			// ObjectOutputStream will call writeReplace of objects returned by writeReplace
			if (LogHolder.log.isDebugEnabled()) {
				LogHolder.log.debug(
						WRITE_REPLACE_METHOD + " method was found on bean " + type + ", make sure it returns this");
			}
		} catch (NoSuchMethodException e) {
			enhancer.setInterfaces(new Class[] { WriteReplaceInterface.class });
		} catch (SecurityException e) {
			// nothing to do here
		}

		// 创建代理对象
		Object enhanced;
		Class<?>[] typesArray = constructorArgTypes.toArray(new Class[constructorArgTypes.size()]);
		Object[] valuesArray = constructorArgs.toArray(new Object[constructorArgs.size()]);
		try {
			enhanced = enhancer.create(typesArray, valuesArray);
		} catch (Exception e) {
			throw new ExecutorException("Error creating lazy proxy.  Cause: " + e, e);
		}
		// <x> 设置代理对象的执行器
		((Proxy) enhanced).setHandler(callback);
		return enhanced;
	}

	/**
	 * 方法处理器的实现类
	 */
	private static class EnhancedResultObjectProxyImpl implements MethodHandler {

		private final Class<?> type;
		private final ResultLoaderMap lazyLoader;
		private final boolean aggressive;
		private final Set<String> lazyLoadTriggerMethods;
		private final ObjectFactory objectFactory;
		private final List<Class<?>> constructorArgTypes;
		private final List<Object> constructorArgs;

		private EnhancedResultObjectProxyImpl(Class<?> type, ResultLoaderMap lazyLoader, Configuration configuration,
				ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
			this.type = type;
			this.lazyLoader = lazyLoader;
			this.aggressive = configuration.isAggressiveLazyLoading();
			this.lazyLoadTriggerMethods = configuration.getLazyLoadTriggerMethods();
			this.objectFactory = objectFactory;
			this.constructorArgTypes = constructorArgTypes;
			this.constructorArgs = constructorArgs;
		}

		public static Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration,
				ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
			final Class<?> type = target.getClass();
			EnhancedResultObjectProxyImpl callback = new EnhancedResultObjectProxyImpl(type, lazyLoader, configuration,
					objectFactory, constructorArgTypes, constructorArgs);
			Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
			PropertyCopier.copyBeanProperties(type, target, enhanced);
			return enhanced;
		}

		@Override
		public Object invoke(Object enhanced, Method method, Method methodProxy, Object[] args) throws Throwable {
			final String methodName = method.getName();
			try {
				synchronized (lazyLoader) {
					// 忽略 WRITE_REPLACE_METHOD ，和序列化相关
					if (WRITE_REPLACE_METHOD.equals(methodName)) {
						Object original;
						if (constructorArgTypes.isEmpty()) {
							original = objectFactory.create(type);
						} else {
							original = objectFactory.create(type, constructorArgTypes, constructorArgs);
						}
						PropertyCopier.copyBeanProperties(type, enhanced, original);
						if (lazyLoader.size() > 0) {
							return new JavassistSerialStateHolder(original, lazyLoader.getProperties(), objectFactory,
									constructorArgTypes, constructorArgs);
						} else {
							return original;
						}
					} else {
						if (lazyLoader.size() > 0 && !FINALIZE_METHOD.equals(methodName)) {
							// <1.1> 加载所有延迟加载的属性
							if (aggressive || lazyLoadTriggerMethods.contains(methodName)) {
								lazyLoader.loadAll();
							} else if (PropertyNamer.isSetter(methodName)) { // <1.2> 如果调用了 set 方法，则不在使用延迟加载
								final String property = PropertyNamer.methodToProperty(methodName);
								lazyLoader.remove(property);
							} else if (PropertyNamer.isGetter(methodName)) { // <1.3> 如果调用了 get 方法，则执行延迟加载
								final String property = PropertyNamer.methodToProperty(methodName);
								if (lazyLoader.hasLoader(property)) {
									lazyLoader.load(property);
								}
							}
						}
					}
				}
				// <2> 继续执行原方法
				return methodProxy.invoke(enhanced, args);
			} catch (Throwable t) {
				throw ExceptionUtil.unwrapThrowable(t);
			}
		}
	}

	private static class EnhancedDeserializationProxyImpl extends AbstractEnhancedDeserializationProxy
			implements MethodHandler {

		private EnhancedDeserializationProxyImpl(Class<?> type,
				Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
				List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
			super(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
		}

		public static Object createProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties,
				ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
			final Class<?> type = target.getClass();
			EnhancedDeserializationProxyImpl callback = new EnhancedDeserializationProxyImpl(type, unloadedProperties,
					objectFactory, constructorArgTypes, constructorArgs);
			Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
			PropertyCopier.copyBeanProperties(type, target, enhanced);
			return enhanced;
		}

		@Override
		public Object invoke(Object enhanced, Method method, Method methodProxy, Object[] args) throws Throwable {
			final Object o = super.invoke(enhanced, method, args);
			return o instanceof AbstractSerialStateHolder ? o : methodProxy.invoke(o, args);
		}

		@Override
		protected AbstractSerialStateHolder newSerialStateHolder(Object userBean,
				Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
				List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
			return new JavassistSerialStateHolder(userBean, unloadedProperties, objectFactory, constructorArgTypes,
					constructorArgs);
		}
	}

	private static class LogHolder {
		private static final Log log = LogFactory.getLog(JavassistProxyFactory.class);
	}

}
